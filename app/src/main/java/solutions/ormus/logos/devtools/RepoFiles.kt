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

/** One recent commit to browse at: its short id and its `--oneline` subject (with branch names). */
data class RepoCommit(val id: String, val subject: String)

/**
 * Where the browser starts. In a git work tree: the top level [root], the start
 * directory's [prefix] inside it ("" at the top) and the recent [commits]. Elsewhere
 * ([git] false): the directory itself, read with ls and cat.
 */
data class RepoHome(val git: Boolean, val root: String, val prefix: String, val commits: List<RepoCommit>)

/** One entry of a directory; [size] in bytes, null for a directory or when unknown. */
data class RepoEntry(val name: String, val dir: Boolean, val size: Long? = null)

/** A file's text, or only its [size] when it is binary (a NUL in its first bytes, as git decides). */
data class RepoFile(val size: Long?, val text: String?)

/**
 * The file browser's reads. In git: the tree at a commit with `git ls-tree`, a file with
 * `git show <rev>:<path>`. Elsewhere: `ls` and `cat` (head) with the same caps.
 */
object RepoFiles {
    const val LIST_CAP = 256 * 1024
    const val FILE_CAP = 256 * 1024
    const val LOG_COUNT = 30

    /** Bytes git reads to call a file binary. */
    private const val BINARY_PROBE = 8000

    /** Where [dir] sits: git root, prefix and recent commits; or a plain directory. */
    fun homeCommand(dir: String?): String = HostScript.wrap(
        HostScript.cd(dir) +
            "if command -v git >/dev/null 2>&1 && top=\$(git rev-parse --show-toplevel 2>/dev/null); then\n" +
            "export GIT_OPTIONAL_LOCKS=0\n" +
            HostScript.capped(
                "printf 'git\\0%s\\0%s\\0' \"\$top\" \"\$(git rev-parse --show-prefix)\"\n" +
                    "git log --oneline --decorate=short --no-color -n $LOG_COUNT",
                LIST_CAP,
            ) + "\nelse\n" +
            HostScript.capped("printf 'plain\\0%s\\0\\0' \"\$PWD\"", LIST_CAP) + "\nfi",
    )

    fun readHome(out: CommandOutput?): HostRead<RepoHome> = HostScript.read(out) { text, _ -> parseHome(text) }

    internal fun parseHome(text: String): RepoHome {
        val parts = text.split('\u0000', limit = 4)
        val commits = parts.getOrNull(3).orEmpty().lineSequence().filter { it.isNotBlank() }.map { line ->
            RepoCommit(line.substringBefore(' '), line.substringAfter(' ', ""))
        }.toList()
        return RepoHome(git = parts[0] == "git", root = parts.getOrNull(1).orEmpty(), prefix = parts.getOrNull(2).orEmpty(), commits = commits)
    }

    /** The entries of [path] ("" for the top) under [home], at [rev] in git. */
    fun listCommand(home: RepoHome, rev: String, path: String): String = if (home.git) {
        val tree = if (path.isEmpty()) "" else " -- ${HerdrApi.shQuote("$path/")}"
        HostScript.wrap(HostScript.cd(home.root) + HostScript.capped("git ls-tree -z --long ${HerdrApi.shQuote(rev)}$tree", LIST_CAP))
    } else {
        HostScript.wrap(HostScript.cd(join(home.root, path)) + HostScript.capped("ls -1Ap", LIST_CAP))
    }

    fun readList(home: RepoHome, out: CommandOutput?): HostRead<List<RepoEntry>> = HostScript.read(out) { text, truncated ->
        sorted(if (home.git) parseLsTree(text) else parseLs(text, truncated))
    }

    // "<mode> <type> <object> <size>\t<path>" NUL-terminated; a cut ends inside the last record.
    internal fun parseLsTree(text: String): List<RepoEntry> {
        val records = text.split('\u0000').dropLast(1)
        return records.mapNotNull { record ->
            val meta = record.substringBefore('\t').split(' ').filter { it.isNotEmpty() }
            val path = record.substringAfter('\t', "")
            if (meta.size < 4 || path.isEmpty()) return@mapNotNull null
            // A submodule ("commit") opens nowhere; it lists like a directory.
            val dir = meta[1] != "blob"
            RepoEntry(path.substringAfterLast('/'), dir, if (dir) null else meta[3].toLongOrNull())
        }
    }

    // One name per line, directories ending in "/".
    internal fun parseLs(text: String, truncated: Boolean): List<RepoEntry> {
        val lines = text.split('\n').let { if (truncated) it.dropLast(1) else it }
        return lines.filter { it.isNotEmpty() }.map { name ->
            if (name.endsWith("/")) RepoEntry(name.removeSuffix("/"), dir = true) else RepoEntry(name, dir = false)
        }
    }

    private fun sorted(entries: List<RepoEntry>): List<RepoEntry> = entries.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))

    /** [path]'s size on the first line, then its bytes up to the cap. */
    fun fileCommand(home: RepoHome, rev: String, path: String): String = if (home.git) {
        val obj = HerdrApi.shQuote("$rev:$path")
        HostScript.wrap(HostScript.cd(home.root) + HostScript.capped("git cat-file -s $obj && git show --no-textconv $obj", FILE_CAP))
    } else {
        val file = HerdrApi.shQuote(join(home.root, path))
        HostScript.wrap(HostScript.capped("wc -c < $file && head -c $FILE_CAP $file", FILE_CAP))
    }

    fun readFile(out: CommandOutput?): HostRead<RepoFile> = HostScript.read(out) { text, _ -> parseFile(text) }

    internal fun parseFile(text: String): RepoFile {
        val size = text.substringBefore('\n').trim().toLongOrNull()
        val body = text.substringAfter('\n', "")
        val binary = body.take(BINARY_PROBE).contains('\u0000')
        return RepoFile(size, if (binary) null else body)
    }

    /** [path] under [root], "" being [root] itself. */
    fun join(root: String, path: String): String = if (path.isEmpty()) root else root.trimEnd('/') + "/" + path
}
