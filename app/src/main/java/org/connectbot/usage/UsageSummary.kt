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

package org.connectbot.usage

/**
 * Plain-text summary for "Copy usage summary": top actions, least-used and
 * never-used controls, and splits per screen and per host type. Counts raw events
 * and daily roll-ups alike.
 */
object UsageSummary {
    private const val TOP = 10

    // Recorded by the app, not tapped: left out of the least-used list.
    private val notControls = setOf(
        UsageActions.SESSION_START,
        UsageActions.SESSION_END,
        UsageActions.SESSION_FAIL,
        UsageActions.SESSION_RECONNECT,
        UsageActions.SESSION_AUTO_RECONNECT,
        UsageActions.UPLOAD_REFUSED_HOSTKEY,
        UsageActions.KEY_OTHER,
    )

    private data class Row(val action: String, val screen: String?, val herdr: Boolean?, val count: Long)

    fun build(snapshot: UsageSnapshot): String {
        val rows = snapshot.events.map { Row(it.action, it.screen, it.herdr, 1) } +
            snapshot.dailies.map { Row(it.action, it.screen, it.herdr, it.count) }
        val total = rows.sumOf { it.count }
        val byAction = rows.groupBy { it.action }.mapValues { (_, r) -> r.sumOf { it.count } }
        val controls = UsageActions.ALL.filter { it !in notControls }
        val ranked = byAction.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        val leastUsed = controls.filter { (byAction[it] ?: 0) > 0 }.sortedBy { byAction[it] }.take(TOP)
        val never = controls.filter { (byAction[it] ?: 0) == 0L }

        return buildString {
            appendLine("Mercurio usage summary")
            appendLine("events: $total")
            appendLine()
            appendLine("Top actions")
            ranked.take(TOP).forEach { appendLine("  ${it.key}: ${it.value}") }
            appendLine()
            appendLine("Least used")
            leastUsed.forEach { appendLine("  $it: ${byAction[it]}") }
            appendLine()
            appendLine("Never used (${never.size})")
            if (never.isNotEmpty()) appendLine("  " + never.joinToString(", "))
            appendLine()
            appendLine("Per screen")
            rows.groupBy { it.screen ?: "none" }.mapValues { (_, r) -> r.sumOf { it.count } }
                .entries.sortedByDescending { it.value }
                .forEach { appendLine("  ${it.key}: ${it.value}") }
            appendLine()
            appendLine("Per host type")
            val herdr = rows.filter { it.herdr == true }
            val plain = rows.filter { it.herdr == false }
            appendLine("  herdr: ${herdr.sumOf { it.count }}${topOf(herdr)}")
            append("  plain: ${plain.sumOf { it.count }}${topOf(plain)}")
        }
    }

    private fun topOf(rows: List<Row>): String {
        val top = rows.groupBy { it.action }.mapValues { (_, r) -> r.sumOf { it.count } }
            .entries.sortedByDescending { it.value }.take(3)
        return if (top.isEmpty()) "" else " (" + top.joinToString(", ") { "${it.key} ${it.value}" } + ")"
    }
}
