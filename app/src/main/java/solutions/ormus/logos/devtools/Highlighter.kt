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

enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER }

/** A highlighted stretch of one line, [start] inclusive to [end] exclusive. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/** How one language family reads: its keywords, comment marks and string quotes. */
class Syntax(
    val keywords: Set<String>,
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    val quotes: String = "\"'",
)

/**
 * A small highlighter for the file browser: keywords, strings, comments and numbers for
 * common languages, picked by file extension. A single pass per line, no grammar and no
 * library; block comments carry from line to line. Good enough to read code on a phone,
 * not a parser.
 */
object Highlighter {
    private val C_LIKE = Syntax(
        keywords = (
            "abstract as async await break case catch class const continue data default defer do else enum export " +
                "extends false final finally fn for fun func function go if impl implements import in interface internal " +
                "is let loop match mod mut new nil null object override package private protected pub public return " +
                "sealed self static struct super suspend switch this throw trait true try type typeof use val var void " +
                "when where while yield"
            ).split(' ').toSet(),
        lineComment = "//",
        blockComment = "/*" to "*/",
        quotes = "\"'`",
    )

    private val PYTHON = Syntax(
        keywords = (
            "and as assert async await break class continue def del elif else except False finally for from global " +
                "if import in is lambda None nonlocal not or pass raise return self True try while with yield"
            ).split(' ').toSet(),
        lineComment = "#",
    )

    private val SHELL = Syntax(
        keywords = "case do done elif else esac export fi for function if in local return then until while".split(' ').toSet(),
        lineComment = "#",
    )

    private val CONFIG = Syntax(keywords = setOf("true", "false", "null", "yes", "no"), lineComment = "#")

    private val JSON = Syntax(keywords = setOf("true", "false", "null"), quotes = "\"")

    private val CSS = Syntax(keywords = emptySet(), blockComment = "/*" to "*/")

    private val MARKUP = Syntax(keywords = emptySet(), blockComment = "<!--" to "-->", quotes = "\"")

    private val BY_EXTENSION: Map<String, Syntax> =
        listOf("kt", "kts", "java", "js", "mjs", "cjs", "jsx", "ts", "tsx", "go", "rs", "c", "h", "cc", "cpp", "hpp", "swift", "cs", "scala", "dart", "gradle")
            .associateWith { C_LIKE } +
            listOf("py").associateWith { PYTHON } +
            listOf("sh", "bash", "zsh").associateWith { SHELL } +
            listOf("yml", "yaml", "toml", "ini", "conf", "properties").associateWith { CONFIG } +
            listOf("json").associateWith { JSON } +
            listOf("css", "scss").associateWith { CSS } +
            listOf("html", "xml", "svg").associateWith { MARKUP }

    /** The syntax for [fileName] by its extension; null for prose and unknown kinds. */
    fun syntaxFor(fileName: String): Syntax? = BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()]

    /** The tokens of each line in order. */
    fun tokens(lines: List<String>, syntax: Syntax): List<List<Token>> {
        var inBlock = false
        return lines.map { line ->
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < line.length) {
                val block = syntax.blockComment
                if (inBlock && block != null) {
                    val close = line.indexOf(block.second, i)
                    val end = if (close < 0) line.length else close + block.second.length
                    tokens += Token(i, end, TokenKind.COMMENT)
                    inBlock = close < 0
                    i = end
                    continue
                }
                if (block != null && line.startsWith(block.first, i)) {
                    inBlock = true
                    continue
                }
                if (syntax.lineComment != null && line.startsWith(syntax.lineComment, i)) {
                    tokens += Token(i, line.length, TokenKind.COMMENT)
                    break
                }
                val c = line[i]
                val start = i
                when {
                    c in syntax.quotes -> {
                        i = stringEnd(line, i)
                        tokens += Token(start, i, TokenKind.STRING)
                    }

                    c.isDigit() && (i == 0 || !isWord(line[i - 1])) -> {
                        while (i < line.length && (isWord(line[i]) || line[i] == '.')) i++
                        tokens += Token(start, i, TokenKind.NUMBER)
                    }

                    isWord(c) -> {
                        while (i < line.length && isWord(line[i])) i++
                        if (line.substring(start, i) in syntax.keywords) tokens += Token(start, i, TokenKind.KEYWORD)
                    }

                    else -> i++
                }
            }
            tokens
        }
    }

    // Past the closing quote (a backslash escapes), or the end of the line.
    private fun stringEnd(line: String, open: Int): Int {
        val quote = line[open]
        var i = open + 1
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                else -> i++
            }
        }
        return line.length
    }

    private fun isWord(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
