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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file browser's parsers on synthetic output. */
class RepoFilesTest {
    private fun ok(text: String, whole: Boolean = true) = CommandOutput(if (whole) text + "\n${HostScript.END}\n" else text, "", 0)

    private val git = RepoHome(git = true, root = "/work/demo", prefix = "", commits = emptyList())

    @Test
    fun homeReadsRootPrefixAndCommits() {
        val read = RepoFiles.readHome(ok("git\u0000/work/demo\u0000src/\u0000abc1234 (HEAD -> main) Add parser\ndef5678 Start\n")) as HostRead.Ok

        assertEquals(
            RepoHome(true, "/work/demo", "src/", listOf(RepoCommit("abc1234", "(HEAD -> main) Add parser"), RepoCommit("def5678", "Start"))),
            read.value,
        )
    }

    @Test
    fun homeOutsideGitIsPlain() {
        val read = RepoFiles.readHome(ok("plain\u0000/work/notes\u0000\u0000")) as HostRead.Ok

        assertEquals(RepoHome(false, "/work/notes", "", emptyList()), read.value)
    }

    @Test
    fun lsTreeListsFoldersFirstWithSizes() {
        val text = listOf(
            "100644 blob 1111111111111111111111111111111111111111     120\tsrc/b.kt",
            "040000 tree 2222222222222222222222222222222222222222       -\tsrc/util",
            "100644 blob 3333333333333333333333333333333333333333       7\tsrc/A file.kt",
            "160000 commit 4444444444444444444444444444444444444444       -\tsrc/vendored",
        ).joinToString("") { "$it\u0000" }

        val read = RepoFiles.readList(git, ok(text)) as HostRead.Ok

        assertEquals(
            listOf(
                RepoEntry("util", dir = true),
                RepoEntry("vendored", dir = true),
                RepoEntry("A file.kt", dir = false, size = 7),
                RepoEntry("b.kt", dir = false, size = 120),
            ),
            read.value,
        )
    }

    @Test
    fun aCutLsTreeDropsItsPartialRecord() {
        val read = RepoFiles.readList(git, ok("100644 blob 1111111111111111111111111111111111111111       1\ta.kt\u0000100644 blob 22", whole = false)) as HostRead.Ok

        assertTrue(read.truncated)
        assertEquals(listOf(RepoEntry("a.kt", dir = false, size = 1)), read.value)
    }

    @Test
    fun lsMarksFoldersBySlash() {
        val plain = git.copy(git = false)

        val read = RepoFiles.readList(plain, ok("b.txt\nnotes/\n.hidden\n")) as HostRead.Ok

        assertEquals(listOf(RepoEntry("notes", dir = true), RepoEntry(".hidden", dir = false), RepoEntry("b.txt", dir = false)), read.value)
    }

    @Test
    fun fileTextOrBinary() {
        assertEquals(RepoFile(6, "hello\n"), (RepoFiles.readFile(ok("6\nhello\n")) as HostRead.Ok).value)
        val binary = (RepoFiles.readFile(ok("4\nab\u0000c")) as HostRead.Ok).value
        assertEquals(4L, binary.size)
        assertNull(binary.text)
    }

    @Test
    fun commandsQuoteRevAndPath() {
        val script = RepoFiles.fileCommand(git, "HEAD~1", "it's.kt").removePrefix("sh -c ").removeSurrounding("'").replace("'\\''", "'")

        assertTrue(script, script.contains("git cat-file -s 'HEAD~1:it'\\''s.kt' && git show --no-textconv 'HEAD~1:it'\\''s.kt'"))
        assertTrue(script, script.endsWith("| head -c ${RepoFiles.FILE_CAP}"))
    }
}
