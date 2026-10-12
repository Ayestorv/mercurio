/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.ormus.logos.devtools

import org.connectbot.transport.CommandOutput
import solutions.ormus.logos.herd.HerdrApi

/** Where a change sits: in the index, in the work tree only, or not tracked yet. */
enum class DiffArea { STAGED, UNSTAGED, UNTRACKED }

/**
 * One changed file in one [area]. [status] is git's letter (M, A, D, U, ?).
 * [added] and [removed] are line counts, null for a binary or untracked file.
 */
data class ChangedFile(
    val path: String,
    val area: DiffArea,
    val status: Char,
    val added: Int? = null,
    val removed: Int? = null,
    val binary: Boolean = false,
)

/** The work tree's changes, staged first, at the repository's top level [root]. */
data class DiffListing(val root: String, val files: List<ChangedFile>)

enum class DiffLineKind { META, HUNK, ADDED, REMOVED, CONTEXT }

data class DiffLine(val kind: DiffLineKind, val text: String)

/**
 * The diff viewer's git reads: `git status --porcelain=v1` and `git diff --numstat`
 * (staged and unstaged) for the list, then `git diff` for one file. Renames read as a
 * delete and an add (`--no-renames`), so every entry has one path.
 */
object GitDiff {
    /** Output caps in bytes: the file list, and one file's diff. */
    const val LIST_CAP = 256 * 1024
    const val FILE_CAP = 256 * 1024

    private const val UNSTAGED = "#unstaged"
    private const val STAGED = "#staged"

    /** One script: the root, status, then each numstat after its marker, NUL separated. */
    fun listCommand(dir: String?): String = HostScript.wrap(
        HostScript.cd(dir) + HostScript.GIT_ROOT + HostScript.capped(
            "printf '%s\\0' \"\$top\"\n" +
                "git status --porcelain=v1 -z --no-renames --untracked-files=all\n" +
                "printf '%s\\0' '$UNSTAGED'\n" +
                "git diff --numstat -z --no-renames --no-ext-diff\n" +
                "printf '%s\\0' '$STAGED'\n" +
                "git diff --cached --numstat -z --no-renames --no-ext-diff",
            LIST_CAP,
        ),
    )

    fun readList(out: CommandOutput?): HostRead<DiffListing> = HostScript.read(out) { text, _ -> parseList(text) }

    /** The unified diff of [file], from the top level the listing named. */
    fun fileCommand(dir: String?, file: ChangedFile): String {
        val path = HerdrApi.shQuote(file.path)
        val diff = when (file.area) {
            DiffArea.STAGED -> "git diff --cached --no-color --no-ext-diff --no-renames -- $path"
            DiffArea.UNSTAGED -> "git diff --no-color --no-ext-diff --no-renames -- $path"
            DiffArea.UNTRACKED -> "git diff --no-color --no-ext-diff --no-index -- /dev/null $path"
        }
        return HostScript.wrap(HostScript.cd(dir) + HostScript.GIT_ROOT + HostScript.capped(diff, FILE_CAP))
    }

    fun readFile(out: CommandOutput?): HostRead<List<DiffLine>> = HostScript.read(out) { text, _ -> parseDiff(text) }

    /** NUL-separated records: the root, status entries, then numstat entries after each marker. */
    internal fun parseList(text: String): DiffListing {
        val records = text.split('\u0000').toMutableList()
        // A cut output ends inside a record; the trailing empty string after the last NUL otherwise.
        records.removeAt(records.lastIndex)
        val root = records.removeFirstOrNull().orEmpty()
        val status = mutableListOf<String>()
        val unstaged = mutableMapOf<String, Numstat>()
        val staged = mutableMapOf<String, Numstat>()
        var section: MutableMap<String, Numstat>? = null
        for (record in records) {
            when {
                record == UNSTAGED -> section = unstaged
                record == STAGED -> section = staged
                section == null -> status += record
                else -> parseNumstat(record)?.let { section[it.path] = it }
            }
        }
        val entries = status.filter { it.length > 3 }.map { Triple(it[0], it[1], it.substring(3)) }
        val files = entries.filter { (x, _, _) -> x != ' ' && x != '?' }.map { (x, _, path) -> changed(path, DiffArea.STAGED, x, staged[path]) } +
            entries.filter { (x, y, _) -> y != ' ' && x != '?' }.map { (_, y, path) -> changed(path, DiffArea.UNSTAGED, y, unstaged[path]) } +
            entries.filter { (x, _, _) -> x == '?' }.map { (_, _, path) -> ChangedFile(path, DiffArea.UNTRACKED, '?') }
        return DiffListing(root, files)
    }

    private data class Numstat(val path: String, val added: Int?, val removed: Int?)

    // "added<TAB>removed<TAB>path"; a binary file counts "-".
    private fun parseNumstat(record: String): Numstat? {
        val parts = record.split('\t', limit = 3)
        if (parts.size != 3) return null
        return Numstat(parts[2], parts[0].toIntOrNull(), parts[1].toIntOrNull())
    }

    private fun changed(path: String, area: DiffArea, status: Char, numstat: Numstat?): ChangedFile = ChangedFile(
        path = path,
        area = area,
        status = status,
        added = numstat?.added,
        removed = numstat?.removed,
        binary = numstat != null && numstat.added == null,
    )

    /** Each line of a unified diff with its kind; headers before the first hunk are meta. */
    fun parseDiff(text: String): List<DiffLine> {
        if (text.isEmpty()) return emptyList()
        var inHunk = false
        return text.removeSuffix("\n").split('\n').map { line ->
            val kind = when {
                line.startsWith("diff ") -> {
                    inHunk = false
                    DiffLineKind.META
                }

                line.startsWith("@@") -> {
                    inHunk = true
                    DiffLineKind.HUNK
                }

                !inHunk -> DiffLineKind.META

                line.startsWith("+") -> DiffLineKind.ADDED

                line.startsWith("-") -> DiffLineKind.REMOVED

                line.startsWith("\\") -> DiffLineKind.META

                else -> DiffLineKind.CONTEXT
            }
            DiffLine(kind, line)
        }
    }
}
