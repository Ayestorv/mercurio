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
import org.junit.Assert.assertNull
import org.junit.Test

class HighlighterTest {
    private fun kinds(line: String, name: String = "a.kt"): List<Pair<String, TokenKind>> = Highlighter.tokens(listOf(line), checkNotNull(Highlighter.syntaxFor(name)))
        .single()
        .map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun keywordsStringsNumbersAndLineComments() {
        assertEquals(
            listOf("val" to TokenKind.KEYWORD, "\"a \\\" b\"" to TokenKind.STRING, "42" to TokenKind.NUMBER, "// note" to TokenKind.COMMENT),
            kinds("val x = \"a \\\" b\" + 42 // note"),
        )
    }

    @Test
    fun wordsWithDigitsAreNotNumbers_andIdentifiersAreNotKeywords() {
        assertEquals(emptyList<Pair<String, TokenKind>>(), kinds("x2 = valid"))
    }

    @Test
    fun blockCommentsCarryAcrossLines() {
        val lines = listOf("a /* one", "two", "end */ return")
        val tokens = Highlighter.tokens(lines, checkNotNull(Highlighter.syntaxFor("x.ts")))

        assertEquals(listOf(Token(2, 8, TokenKind.COMMENT)), tokens[0])
        assertEquals(listOf(Token(0, 3, TokenKind.COMMENT)), tokens[1])
        assertEquals(listOf(Token(0, 6, TokenKind.COMMENT), Token(7, 13, TokenKind.KEYWORD)), tokens[2])
    }

    @Test
    fun hashCommentsForPythonAndShell() {
        assertEquals(listOf("def" to TokenKind.KEYWORD, "# hi" to TokenKind.COMMENT), kinds("def f(): # hi", "a.py"))
        assertEquals(listOf("if" to TokenKind.KEYWORD, "'x'" to TokenKind.STRING), kinds("if [ 'x' ]", "run.sh"))
    }

    @Test
    fun proseHasNoSyntax() {
        assertNull(Highlighter.syntaxFor("README.md"))
        assertNull(Highlighter.syntaxFor("Makefile"))
    }
}
