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
 * The file browser's scripts run for real, as the host's shell would run them,
 * against a throwaway repository and a plain directory. Skipped without git.
 */
class RepoFilesScriptTest {
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

    private fun commit(message: String) = git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)

    @Before
    fun repo() {
        assumeTrue(sh("command -v git").exitCode == 0)
        repo = tmp.newFolder("demo")
        git("init", "-q")
        File(repo, "src").mkdir()
        File(repo, "src/main.kt").writeText("fun main() {}\n")
        File(repo, "logo.bin").writeBytes(byteArrayOf(1, 0, 2, 3))
        git("add", "-A")
        commit("first")
        File(repo, "src/main.kt").writeText("fun main() { println(1) }\n")
        git("add", "-A")
        commit("second")
    }

    private fun home(dir: File): RepoHome = (RepoFiles.readHome(sh(RepoFiles.homeCommand(dir.path))) as HostRead.Ok).value

    @Test
    fun startsAtThePanesDirectoryWithRecentCommits() {
        val home = home(File(repo, "src"))

        assertTrue(home.git)
        assertEquals("src/", home.prefix)
        assertEquals(listOf("second", "first"), home.commits.map { it.subject.substringAfter(") ") })
    }

    @Test
    fun listsAndShowsAtHeadAndAtAnOlderCommit() {
        val home = home(repo)
        val top = (RepoFiles.readList(home, sh(RepoFiles.listCommand(home, "HEAD", ""))) as HostRead.Ok).value
        val src = (RepoFiles.readList(home, sh(RepoFiles.listCommand(home, "HEAD", "src"))) as HostRead.Ok).value
        val now = (RepoFiles.readFile(sh(RepoFiles.fileCommand(home, "HEAD", "src/main.kt"))) as HostRead.Ok).value
        val before = (RepoFiles.readFile(sh(RepoFiles.fileCommand(home, home.commits.last().id, "src/main.kt"))) as HostRead.Ok).value
        val binary = (RepoFiles.readFile(sh(RepoFiles.fileCommand(home, "HEAD", "logo.bin"))) as HostRead.Ok).value

        assertEquals(listOf(RepoEntry("src", true), RepoEntry("logo.bin", false, 4)), top)
        assertEquals(listOf(RepoEntry("main.kt", false, 26)), src)
        assertEquals("fun main() { println(1) }\n", now.text)
        assertEquals("fun main() {}\n", before.text)
        assertEquals(RepoFile(4, null), binary)
    }

    @Test
    fun aPlainDirectoryUsesLsAndCat() {
        val plain = tmp.newFolder("plain")
        File(plain, "sub").mkdir()
        File(plain, "a.txt").writeText("x".repeat(RepoFiles.FILE_CAP + 10))
        val home = home(plain)

        val list = (RepoFiles.readList(home, sh(RepoFiles.listCommand(home, "HEAD", ""))) as HostRead.Ok).value
        val file = RepoFiles.readFile(sh(RepoFiles.fileCommand(home, "HEAD", "a.txt"))) as HostRead.Ok

        assertEquals(false, home.git)
        assertEquals(listOf(RepoEntry("sub", true), RepoEntry("a.txt", false)), list)
        assertTrue(file.truncated)
        assertEquals((RepoFiles.FILE_CAP + 10).toLong(), file.value.size)
    }
}
