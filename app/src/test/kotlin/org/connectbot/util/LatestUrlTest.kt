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

package org.connectbot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestUrlTest {

    @Test
    fun theLastUrlWins() {
        val out = "Opened https://github.com/o/r/pull/1\nthen deployed to https://app.example.com/dashboard."
        assertEquals("https://app.example.com/dashboard", LatestUrl.find(out))
    }

    @Test
    fun markdownLinksQuotesAndPunctuation_giveJustTheAddress() {
        assertEquals("https://x.y/a?b=1", LatestUrl.find("see [the PR](https://x.y/a?b=1)."))
        assertEquals("http://100.100.1.1:8765/mercurio.apk", LatestUrl.find("APK: \"http://100.100.1.1:8765/mercurio.apk\""))
        assertEquals("https://x.y/z", LatestUrl.find("done: https://x.y/z!"))
    }

    // A sign-in screen as a CLI draws it in a 53-column pane: rules across the width, then
    // the link broken into rows at the edge.
    private val signInUrl = "https://accounts.google.com/o/oauth2/auth?access_type=offline" +
        "&client_id=1234567890-abcdefghijklmnop.apps.googleusercontent.com" +
        "&code_challenge=uAT4WWCPAAEiDNq5bTb5rLk1wXKHa6eGUtv&code_challenge_method=S256" +
        "&redirect_uri=https%3A%2F%2Fexample.google%2Foauth-callback&state=Na1bJMqwsS_-K3BhzpLEow"

    private fun signInScreen(width: Int): String {
        val rule = "\u2500".repeat(width)
        val rows = listOf("Open the URL below in your browser:", "", rule) +
            signInUrl.chunked(width) +
            listOf(rule, "", "Copy and paste the URL or click on the link below:", "", "authorization code...")
        return rows.joinToString("\n")
    }

    @Test
    fun aUrlBrokenAcrossRowsAtTheScreenEdge_isCopiedWhole() {
        assertEquals(signInUrl, LatestUrl.find(signInScreen(53)))
        assertEquals(signInUrl, LatestUrl.find(signInScreen(41)))
    }

    @Test
    fun aRowOneShortOfTheWidth_stillCountsAsFull() {
        // A program that keeps the last column clear wraps one short of its rules.
        val rows = listOf("\u2500".repeat(53)) + signInUrl.chunked(52) + listOf("\u2500".repeat(53))
        assertEquals(signInUrl, LatestUrl.find(rows.joinToString("\n")))
    }

    @Test
    fun aUrlThatEndsBeforeTheEdge_doesNotTakeTheNextRow() {
        val rule = "\u2500".repeat(53)
        val out = listOf(rule, "Opened https://github.com/o/r/pull/12", "Done", rule).joinToString("\n")
        assertEquals("https://github.com/o/r/pull/12", LatestUrl.find(out))
    }

    @Test
    fun aFullRowEndingInAUrl_thenARowWithAnotherUrl_areTwoUrls() {
        val first = "https://example.com/" + "a".repeat(33)
        val second = "https://example.com/second"
        val out = listOf("\u2500".repeat(53), first, second, "\u2500".repeat(53)).joinToString("\n")
        assertEquals(listOf(first, second), LatestUrl.findAll(out))
    }

    @Test
    fun aFullRowEndingInAUrl_thenARowStartingWithASpace_isTheEndOfIt() {
        val first = "https://example.com/" + "a".repeat(33)
        val out = listOf("\u2500".repeat(53), first, " next line", "\u2500".repeat(53)).joinToString("\n")
        assertEquals(first, LatestUrl.find(out))
    }

    @Test
    fun outputWithNoWrappedUrl_isLeftAsItIs() {
        val out = "line one\nhttps://x.y/z\n\nlast"
        assertEquals(out, LatestUrl.joinWrapped(out))
    }

    @Test
    fun noUrl_isNull() {
        assertNull(LatestUrl.find("nothing to open here, ftp://not.web"))
    }
}
