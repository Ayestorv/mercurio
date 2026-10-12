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

import org.junit.Assert.assertEquals
import org.junit.Test
import solutions.ormus.logos.herd.HerdrPaneInfo
import solutions.ormus.logos.herd.HerdrSessionSnapshot

class WorkDirsTest {
    @Test
    fun focusedPaneFirst_thenTheOthers_eachDirectoryOnce() {
        val snapshot = HerdrSessionSnapshot(
            panes = listOf(
                HerdrPaneInfo("p1", cwd = "/work/a", terminalTitleStripped = "shell"),
                HerdrPaneInfo("p2", cwd = "/work/b", agent = "claude", displayAgent = "Claude"),
                HerdrPaneInfo("p3", cwd = "/work/a", agent = "codex"),
                HerdrPaneInfo("p4", cwd = null),
            ),
            focusedPaneId = "p2",
        )

        assertEquals(listOf(WorkDir("/work/b", "Claude"), WorkDir("/work/a", "shell")), WorkDirs.from(snapshot))
    }

    @Test
    fun noSnapshot_noDirectories() {
        assertEquals(emptyList<WorkDir>(), WorkDirs.from(null))
    }
}
