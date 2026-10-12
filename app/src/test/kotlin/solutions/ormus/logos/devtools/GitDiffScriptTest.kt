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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The diff scripts run for real, as the host's shell would run them, against a
 * throwaway repository. Skipped where the test machine has no git.
 */
class GitDiffScriptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: File

    private fun sh(command: String, dir: File? = null): CommandOutput {
        val process = ProcessBuilder("sh", "-c", command).apply {
            dir?.let { directory(it) }
            // The build directory may sit inside a repository; git must not find it.
            environment()["GIT_CEILING_DIRECTORIES"] = tmp.root.path
        }.start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
        return CommandOutput(stdout, stderr, process.waitFor())
    }

    private fun git(vararg args: String) = check(sh("git " + args.joinToString(" "), repo).exitCode == 0) { args.toList() }

    private fun commit(vararg args: String) = git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false", "commit", "-q", *args)

    @Before
    fun repo() {
        assumeTrue(sh("command -v git").exitCode == 0)
        repo = tmp.newFolder("demo repo")
        git("init", "-q")
        commit("--allow-empty", "-m", "start")
        File(repo, "kept.txt").writeText("one\ntwo\nthree\n")
        git("add", "kept.txt")
        commit("-m", "kept")
    }

    @Test
    fun listsEachAreaFromASubdirectory() {
        File(repo, "staged.txt").writeText("a\nb\n")
        git("add", "staged.txt")
        File(repo, "kept.txt").writeText("one\n2\nthree\nfour\n")
        File(repo, "sub").mkdir()
        File(repo, "sub/new one.txt").writeText("x\n")

        val read = GitDiff.readList(sh(GitDiff.listCommand(File(repo, "sub").path))) as HostRead.Ok

        assertEquals(repo.canonicalPath, File(read.value.root).canonicalPath)
        assertEquals(
            listOf(
                ChangedFile("staged.txt", DiffArea.STAGED, 'A', 2, 0),
                ChangedFile("kept.txt", DiffArea.UNSTAGED, 'M', 2, 1),
                ChangedFile("sub/new one.txt", DiffArea.UNTRACKED, '?'),
            ),
            read.value.files,
        )
    }

    @Test
    fun diffsOneFileOfEachArea() {
        File(repo, "kept.txt").writeText("one\n2\nthree\n")
        File(repo, "fresh.txt").writeText("hello\n")

        val unstaged = GitDiff.readFile(sh(GitDiff.fileCommand(repo.path, ChangedFile("kept.txt", DiffArea.UNSTAGED, 'M')))) as HostRead.Ok
        val untracked = GitDiff.readFile(sh(GitDiff.fileCommand(repo.path, ChangedFile("fresh.txt", DiffArea.UNTRACKED, '?')))) as HostRead.Ok

        assertEquals(listOf("-two", "+2"), unstaged.value.filter { it.kind == DiffLineKind.REMOVED || it.kind == DiffLineKind.ADDED }.map { it.text })
        assertEquals(listOf("+hello"), untracked.value.filter { it.kind == DiffLineKind.ADDED }.map { it.text })
    }

    @Test
    fun aBigDiffIsCutAtTheCap() {
        File(repo, "kept.txt").writeText((1..10_000).joinToString("\n") { "line $it padded out to be long enough" })

        val read = GitDiff.readFile(sh(GitDiff.fileCommand(repo.path, ChangedFile("kept.txt", DiffArea.UNSTAGED, 'M')))) as HostRead.Ok

        assertTrue(read.truncated)
    }

    @Test
    fun plainDirectoryAndMissingDirectoryAreNamed() {
        val plain = tmp.newFolder("plain")

        assertEquals(HostProblem.NOT_A_REPO, (GitDiff.readList(sh(GitDiff.listCommand(plain.path))) as HostRead.Problem).reason)
        assertEquals(HostProblem.NO_DIR, (GitDiff.readList(sh(GitDiff.listCommand(File(plain, "gone").path))) as HostRead.Problem).reason)
    }
}
