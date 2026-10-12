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

package solutions.ormus.logos.push

import org.connectbot.data.entity.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.herd.HerdApproval
import solutions.ormus.logos.herd.HerdAttention.Notice
import solutions.ormus.logos.herd.HerdrAgentStatus

/** A Herdr host's push: what the phone accepts, the notice it becomes, and which saved host it is about. */
class HerdPushAlertTest {
    // The watcher's message as docs/PUSH.md on feature/herdr-push-watcher gives it.
    private val blocked = """
        {"v":1,"event":"pane.agent_status_changed","state":"blocked","host":"Sun","session":"default",
         "pane_id":"w1:p3","agent":"claude","workspace_id":"w1","workspace":"mercurio","tab_id":"w1:t2","tab":"build",
         "ts":1791214536}
    """.trimIndent()

    private fun parse(text: String) = HerdPushAlert.parse(text.toByteArray())

    @Test
    fun parse_readsTheWatchersMessage() {
        val alert = checkNotNull(parse(blocked))
        assertEquals("Sun", alert.host)
        assertEquals("w1:p3", alert.paneId)
        assertEquals(HerdrAgentStatus.BLOCKED, alert.state)
        assertEquals("claude", alert.agent)
        assertEquals("mercurio", alert.workspace)
        assertEquals("build", alert.tab)
    }

    @Test
    fun parse_refusesWhatThePhoneCannotActOn() {
        assertNull("not JSON", parse("hello"))
        assertNull("empty", parse(""))
        assertNull("another schema", parse(blocked.replace("\"v\":1", "\"v\":2")))
        assertNull("another event", parse(blocked.replace("pane.agent_status_changed", "pane.created")))
        assertNull("no host", parse(blocked.replace("\"host\":\"Sun\"", "\"host\":\"\"")))
        assertNull("odd pane id", parse(blocked.replace("w1:p3\"", "w1:p3; rm -rf ~\"")))
        assertNull("odd session name", parse(blocked.replace("\"session\":\"default\"", "\"session\":\"work; rm -rf ~\"")))
        assertTrue("no session field", parse(blocked.replace("\"session\":\"default\",", "")) != null)
    }

    @Test
    fun notice_carriesItsSession_theDefaultWhenTheWatcherNamesNone() {
        // Pane ids repeat across sessions: the notice answers in the session the alert came from.
        assertEquals("work", parse(blocked.replace("\"session\":\"default\"", "\"session\":\"work\""))?.notice()?.session)
        assertEquals("default", parse(blocked)?.notice()?.session)
        assertEquals("default", parse(blocked.replace("\"session\":\"default\",", ""))?.notice()?.session)
    }

    @Test
    fun notice_blockedIsNeedsYouWithApproveForProvenAgents_doneIsFinished_restNothing() {
        val needsYou = checkNotNull(parse(blocked)?.notice()) as Notice.NeedsYou
        assertEquals("w1:p3", needsYou.agent.paneId)
        assertEquals("mercurio", needsYou.workspaceLabel)
        assertEquals("build", needsYou.tabLabel)
        assertNull("no pane text in a push", needsYou.paneTail)
        assertTrue(HerdApproval.offersAnswers(needsYou.agent))

        val done = parse(blocked.replace("\"blocked\"", "\"done\""))?.notice()
        assertTrue(done is Notice.Finished)
        assertNull(parse(blocked.replace("\"blocked\"", "\"working\""))?.notice())
        // A state this version does not know reads as unknown: no notice, no crash.
        assertNull(parse(blocked.replace("\"blocked\"", "\"thinking\""))?.notice())

        val unlabeled = checkNotNull(parse(blocked.replace("\"mercurio\"", "null").replace("\"build\"", "\"\""))?.notice())
        assertNull(unlabeled.workspaceLabel)
        assertNull(unlabeled.tabLabel)
    }

    @Test
    fun matchHost_byNicknameThenHostnameThenShortName_sshOnly() {
        val sunIp = Host(id = 1, nickname = "Sun", hostname = "100.100.1.1")
        val lab = Host(id = 2, nickname = "lab box", hostname = "lab.example.ts.net")
        val telnet = Host(id = 3, nickname = "nas", protocol = "telnet", hostname = "10.0.2.2")
        val hosts = listOf(sunIp, lab, telnet)

        assertEquals(sunIp, HerdPushAlert.matchHost("Sun", hosts))
        assertEquals("case", sunIp, HerdPushAlert.matchHost("sun", hosts))
        assertEquals("FQDN to nickname", sunIp, HerdPushAlert.matchHost("sun.example.ts.net", hosts))
        assertEquals("hostname", lab, HerdPushAlert.matchHost("lab.example.ts.net", hosts))
        assertEquals("short hostname", lab, HerdPushAlert.matchHost("lab", hosts))
        assertNull("telnet hosts never answer", HerdPushAlert.matchHost("nas", hosts))
        assertNull("unknown host", HerdPushAlert.matchHost("venus", hosts))
        assertNull("an IP is not a short name", HerdPushAlert.matchHost("100", hosts))
    }

    @Test
    fun matchHost_prefersTheHerdrHostAmongEquals() {
        val plain = Host(id = 1, nickname = "sun-root", hostname = "sun.example.ts.net", username = "root")
        val herdr = Host(id = 2, nickname = "sun-alice", hostname = "sun.example.ts.net", postLogin = "herdr")
        assertEquals(herdr, HerdPushAlert.matchHost("sun.example.ts.net", listOf(plain, herdr)))
        // A closer tier still wins.
        val nick = Host(id = 3, nickname = "sun.example.ts.net", hostname = "10.0.0.5")
        assertEquals(nick, HerdPushAlert.matchHost("sun.example.ts.net", listOf(plain, herdr, nick)))
    }
}
