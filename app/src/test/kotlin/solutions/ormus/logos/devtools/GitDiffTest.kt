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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diff viewer's parsers on synthetic git output, and its exit codes. */
class GitDiffTest {
    private fun ok(stdout: String) = CommandOutput(stdout = stdout, stderr = "", exitCode = 0)

    private fun whole(text: String) = text + "\n${HostScript.END}\n"

    @Test
    fun listSplitsStagedUnstagedAndUntrackedWithCounts() {
        val text = listOf(
            "/work/demo",
            "M  src/a.kt",
            "MM src/b.kt",
            " D old.txt",
            "?? notes/new file.md",
            "#unstaged",
            "2\t1\tsrc/b.kt",
            "0\t3\told.txt",
            "#staged",
            "4\t0\tsrc/a.kt",
            "-\t-\tsrc/b.kt",
        ).joinToString("") { "$it\u0000" }

        val read = GitDiff.readList(ok(whole(text))) as HostRead.Ok

        assertEquals(false, read.truncated)
        assertEquals("/work/demo", read.value.root)
        assertEquals(
            listOf(
                ChangedFile("src/a.kt", DiffArea.STAGED, 'M', 4, 0),
                ChangedFile("src/b.kt", DiffArea.STAGED, 'M', null, null, binary = true),
                ChangedFile("src/b.kt", DiffArea.UNSTAGED, 'M', 2, 1),
                ChangedFile("old.txt", DiffArea.UNSTAGED, 'D', 0, 3),
                ChangedFile("notes/new file.md", DiffArea.UNTRACKED, '?'),
            ),
            read.value.files,
        )
    }

    @Test
    fun aCutListDropsItsPartialRecordAndSaysSo() {
        val read = GitDiff.readList(ok("/work/demo\u0000M  a.kt\u0000?? half-writ")) as HostRead.Ok

        assertTrue(read.truncated)
        assertEquals(listOf(ChangedFile("a.kt", DiffArea.STAGED, 'M')), read.value.files)
    }

    @Test
    fun exitCodesNameTheProblem() {
        fun problem(code: Int?) = (GitDiff.readList(CommandOutput("", "boom\n", code)) as HostRead.Problem).reason
        assertEquals(HostProblem.NO_DIR, problem(2))
        assertEquals(HostProblem.NOT_A_REPO, problem(3))
        assertEquals(HostProblem.NO_GIT, problem(4))
        assertEquals(HostProblem.FAILED, problem(1))
        assertEquals("boom", (GitDiff.readList(CommandOutput("", "boom\n", 1)) as HostRead.Problem).detail)
        assertEquals(HostRead.Problem(HostProblem.NOT_CONNECTED), GitDiff.readList(null))
    }

    @Test
    fun diffLinesTakeTheirKind() {
        val text = """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1,3 +1,3 @@
             keep
            -old
            +new
            \ No newline at end of file
        """.trimIndent() + "\n"

        val kinds = GitDiff.parseDiff(text).map { it.kind }

        assertEquals(
            listOf(
                DiffLineKind.META,
                DiffLineKind.META,
                DiffLineKind.META,
                DiffLineKind.HUNK,
                DiffLineKind.CONTEXT,
                DiffLineKind.REMOVED,
                DiffLineKind.ADDED,
                DiffLineKind.META,
            ),
            kinds,
        )
    }

    @Test
    fun fileCommandQuotesThePath() {
        val command = GitDiff.fileCommand("/work/it's here", ChangedFile("a b'c.kt", DiffArea.STAGED, 'M'))
        val script = command.removePrefix("sh -c ").removeSurrounding("'").replace("'\\''", "'")

        assertTrue(script, script.contains("cd -- '/work/it'\\''s here'"))
        assertTrue(script, script.contains("git diff --cached --no-color --no-ext-diff --no-renames -- 'a b'\\''c.kt'"))
        assertTrue(script, script.endsWith("| head -c ${GitDiff.FILE_CAP}"))
    }
}
