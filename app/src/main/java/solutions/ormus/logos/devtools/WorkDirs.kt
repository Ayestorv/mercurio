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

import solutions.ormus.logos.herd.HerdrPaneInfo
import solutions.ormus.logos.herd.HerdrSessionSnapshot

/** A directory a dev tool can read: a Herdr pane's cwd, named after the pane. */
data class WorkDir(val path: String, val label: String)

object WorkDirs {
    /**
     * The panes' working directories, the focused pane's first, each directory once.
     * Empty without a snapshot (a plain SSH session): the tools then read the home directory.
     */
    fun from(snapshot: HerdrSessionSnapshot?): List<WorkDir> {
        val panes = snapshot?.panes.orEmpty()
        val focused = panes.firstOrNull { it.paneId == snapshot?.focusedPaneId } ?: panes.firstOrNull { it.focused }
        return (listOfNotNull(focused) + panes)
            .mapNotNull { pane -> pane.cwd?.takeIf { it.isNotBlank() }?.let { WorkDir(it, label(pane)) } }
            .distinctBy { it.path }
    }

    private fun label(pane: HerdrPaneInfo): String = pane.displayAgent?.ifBlank { null }
        ?: pane.agent?.ifBlank { null }
        ?: pane.terminalTitleStripped?.ifBlank { null }
        ?: pane.paneId
}
