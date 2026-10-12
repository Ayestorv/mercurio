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

package org.connectbot.ui.screens.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrApi
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.tabLabel
import solutions.ormus.logos.herd.title
import solutions.ormus.logos.herd.workspaceLabel

/** One entry of the conversation: a user message, agent text, or a tool call on one line. */
@Serializable
data class ChatItem(
    /** "user", "agent" or "tool". */
    val k: String,
    /** The message (user, agent). */
    val text: String = "",
    /** A tool call collapsed to one line: "Edit app/Foo.kt", "Bash: ./gradlew test". */
    val line: String = "",
    /** The tool call's full target (path, command), capped; shown when the row is opened. */
    val detail: String = "",
    /** A tool call's state: "run" (no result yet), "ok" or "error". */
    val state: String = "",
) {
    val isUser: Boolean get() = k == USER
    val isTool: Boolean get() = k == TOOL

    companion object {
        const val USER = "user"
        const val AGENT = "agent"
        const val TOOL = "tool"
    }
}

/**
 * What the host-side read printed: the transcript's size (the next read's change check),
 * and either [same] (nothing new since the size the app passed) or the last [items].
 * [truncated] says older turns were left out.
 */
@Serializable
data class ChatPayload(
    val v: Int = 0,
    val size: Long = 0,
    val same: Boolean = false,
    val truncated: Boolean = false,
    val items: List<ChatItem> = emptyList(),
)

/** The focused pane's agent conversation: where it lives and what Herdr says it is doing. */
data class ChatTarget(
    val paneId: String,
    /** "claude" or "codex": which transcript format [sessionId] names. */
    val agent: String,
    val sessionId: String,
    val status: HerdrAgentStatus,
    val title: String? = null,
    val place: String? = null,
    /** The Herdr session the pane is in: pane ids repeat across sessions. */
    val session: String = HerdrCommands.DEFAULT_SESSION,
)

/**
 * Agent chat: the focused pane's agent conversation read from the agent's own transcript
 * on the host, over the SSH exec channel, as compact JSON. Nothing is installed on the
 * host: the reader is a python3 (stdlib only) heredoc, like [org.connectbot.ui.screens.reader.LastReply].
 *
 * Claude Code writes `~/.claude/projects/<project>/<session id>.jsonl`; Codex writes
 * `~/.codex/sessions/<y>/<m>/<d>/rollout-<time>-<session id>.jsonl`. Herdr names the
 * session id in the pane's `agent_session`.
 */
object AgentChat {
    /** Agents whose transcript format the reader knows. */
    val AGENTS = setOf("claude", "codex")

    // Session ids are UUIDs and pane ids Herdr's own; anything else never reaches a shell.
    private val SESSION_ID = Regex("[A-Za-z0-9-]{8,64}")
    private val PANE_ID = Regex("[A-Za-z0-9_.:-]+")

    // Only the end of a long transcript is read, and only this much goes back to the phone.
    const val TAIL_BYTES = 2 * 1024 * 1024
    const val MAX_ITEMS = 200
    const val MAX_TEXT_CHARS = 6000
    const val MAX_DETAIL_CHARS = 600
    const val MAX_OUTPUT_CHARS = 192 * 1024

    /** Exit code of the reader when the transcript file is not on the host. */
    const val EXIT_NO_TRANSCRIPT = 3

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pane [pane]'s conversation (the focused pane's when null) in Herdr session
     * [herdrSession], whose [snapshot] this is, when it runs an agent
     * whose transcript Mercurio can read and Herdr names its session id; null otherwise
     * (the Read view takes over).
     */
    fun target(snapshot: HerdrSessionSnapshot?, pane: String? = null, herdrSession: String = HerdrCommands.DEFAULT_SESSION): ChatTarget? {
        snapshot ?: return null
        val paneId = pane ?: snapshot.focusedPaneId?.ifBlank { null } ?: return null
        val paneInfo = snapshot.panes.firstOrNull { it.paneId == paneId } ?: return null
        val session = paneInfo.agentSession ?: return null
        val agent = session.agent ?: return null
        val id = session.value ?: return null
        // A pane whose agent has exited keeps its session: there is no agent left to reply to.
        if (agent !in AGENTS || session.kind != "id" || paneInfo.agent != agent || !SESSION_ID.matches(id)) return null
        val info = snapshot.agents.firstOrNull { it.paneId == paneId }
        val place = listOfNotNull(
            snapshot.workspaceLabel(info?.workspaceId?.ifBlank { null } ?: paneInfo.workspaceId),
            snapshot.tabLabel(info?.tabId?.ifBlank { null } ?: paneInfo.tabId),
        ).joinToString(" / ").ifEmpty { null }
        return ChatTarget(
            paneId = paneId,
            agent = agent,
            sessionId = id,
            status = info?.agentStatus ?: paneInfo.agentStatus,
            title = info?.title(),
            place = place,
            session = herdrSession,
        )
    }

    /** Whether [target]'s pane is still in [snapshot], running the same agent session. */
    fun stillThere(snapshot: HerdrSessionSnapshot?, target: ChatTarget): Boolean = target(snapshot, pane = target.paneId)?.let { it.agent == target.agent && it.sessionId == target.sessionId } == true

    /**
     * The shell command that types [text] into pane [paneId] and submits it: Herdr's
     * send-text, a short pause, then Enter as its own key press so the agent sees a
     * keypress rather than a newline in the paste. It goes to that pane whatever has
     * focus, in Herdr session [session]. The one place a reply is addressed.
     */
    fun sendCommand(paneId: String, text: String, session: String = HerdrCommands.DEFAULT_SESSION): String {
        require(PANE_ID.matches(paneId)) { "Unexpected Herdr pane id: $paneId" }
        val pane = HerdrApi.shQuote(paneId)
        val herdr = if (HerdrSessions.isDefault(session)) "herdr" else "herdr --session ${HerdrSessions.quoted(session)}"
        return HerdrApi.loginShell("$herdr pane send-text $pane ${HerdrApi.shQuote(text)} && sleep 0.05 && $herdr pane send-keys $pane enter")
    }

    /** Parse the reader's output; null when it is not the reader's JSON. */
    fun parse(raw: String): ChatPayload? = try {
        json.decodeFromString<ChatPayload>(raw.trim()).takeIf { it.v == 1 }
    } catch (_: Exception) {
        null
    }

    /**
     * The shell command that prints [target]'s conversation as [ChatPayload] JSON, or only
     * `same` when the transcript is still [knownSize] bytes. Null for an unknown agent or a
     * malformed id.
     */
    fun command(agent: String, sessionId: String, knownSize: Long = -1): String? {
        if (agent !in AGENTS || !SESSION_ID.matches(sessionId)) return null
        return "python3 - '$agent' '$sessionId' '$knownSize' <<'MERCURIO_CHAT'\n$READER\nMERCURIO_CHAT"
    }

    // The reader. Claude Code: user messages (not tool results, meta or sidechain entries),
    // assistant text and tool_use blocks, each tool_result marking its call ok or error.
    // Codex: user_message events, assistant output_text, function and custom tool calls and
    // their outputs (a non-zero exit code is an error). Tool output itself is never sent.
    private val READER = """
        |import glob, json, os, re, sys
        |agent, sid, known = sys.argv[1], sys.argv[2], sys.argv[3]
        |home = os.path.expanduser('~')
        |if agent == 'claude':
        |    files = glob.glob(os.path.join(home, '.claude', 'projects', '*', sid + '.jsonl'))
        |else:
        |    files = glob.glob(os.path.join(home, '.codex', 'sessions', '*', '*', '*', 'rollout-*' + sid + '.jsonl'))
        |if not files:
        |    sys.exit($EXIT_NO_TRANSCRIPT)
        |path = max(files, key=os.path.getmtime)
        |size = os.path.getsize(path)
        |if str(size) == known:
        |    print(json.dumps({'v': 1, 'size': size, 'same': True}))
        |    sys.exit(0)
        |with open(path, 'rb') as f:
        |    f.seek(max(0, size - $TAIL_BYTES))
        |    lines = f.read().decode('utf-8', 'replace').splitlines()
        |cut = size > $TAIL_BYTES
        |if cut:
        |    lines = lines[1:]
        |items, calls = [], {}
        |def clip(s, n):
        |    s = s.strip()
        |    return s if len(s) <= n else s[:n - 1].rstrip() + '…'
        |def tilde(p):
        |    p = str(p)
        |    return '~' + p[len(home):] if p.startswith(home + '/') else p
        |def short(p):
        |    parts = tilde(p).rstrip('/').split('/')
        |    return '/'.join(parts[-2:])
        |def first_line(s):
        |    s = str(s).strip()
        |    return clip(s.splitlines()[0] if s else '', 80)
        |def say(kind, text):
        |    text = (text or '').strip()
        |    if not text:
        |        return
        |    if kind == 'agent' and items and items[-1]['k'] == 'agent':
        |        items[-1]['text'] += '\n\n' + text
        |    else:
        |        items.append({'k': kind, 'text': text})
        |def tool(call_id, line, detail):
        |    item = {'k': 'tool', 'line': line, 'detail': clip(detail, $MAX_DETAIL_CHARS), 'state': 'run'}
        |    items.append(item)
        |    if call_id:
        |        calls[call_id] = item
        |def done(call_id, error):
        |    if call_id in calls:
        |        calls[call_id]['state'] = 'error' if error else 'ok'
        |def claude_tool(block):
        |    name = str(block.get('name') or 'tool')
        |    if name.startswith('mcp__'):
        |        name = name.split('__')[-1]
        |    args = block.get('input') if isinstance(block.get('input'), dict) else {}
        |    for key in ('file_path', 'notebook_path', 'path'):
        |        if args.get(key):
        |            return name + ' ' + short(args[key]), tilde(args[key])
        |    for key in ('command', 'pattern', 'query', 'url', 'description', 'skill'):
        |        if args.get(key):
        |            return name + ': ' + first_line(args[key]), str(args[key])
        |    return name, ''
        |def claude_user(content):
        |    if isinstance(content, str):
        |        text = content
        |    else:
        |        parts = []
        |        for c in content or []:
        |            if isinstance(c, dict) and c.get('type') == 'text':
        |                parts.append(c.get('text') or '')
        |            elif isinstance(c, dict) and c.get('type') == 'image':
        |                parts.append('[image]')
        |        text = '\n'.join(parts)
        |    name = re.search(r'<command-name>(.*?)</command-name>', text, re.S)
        |    if name:
        |        args = re.search(r'<command-args>(.*?)</command-args>', text, re.S)
        |        return (name.group(1).strip() + ' ' + (args.group(1).strip() if args else '')).strip()
        |    text = re.sub(r'<system-reminder>.*?</system-reminder>', '', text, flags=re.S).strip()
        |    if text.startswith(('<local-command', '<task-notification', '<command-message', '<bash-', 'Caveat:', 'Stop hook feedback')):
        |        return ''
        |    return text
        |for line in lines:
        |    try:
        |        e = json.loads(line)
        |    except ValueError:
        |        continue
        |    if not isinstance(e, dict):
        |        continue
        |    if agent == 'claude':
        |        if e.get('isMeta') or e.get('isSidechain') or e.get('isCompactSummary'):
        |            continue
        |        content = (e.get('message') or {}).get('content')
        |        if e.get('type') == 'user':
        |            if isinstance(content, list):
        |                for c in content:
        |                    if isinstance(c, dict) and c.get('type') == 'tool_result':
        |                        done(c.get('tool_use_id'), c.get('is_error'))
        |            say('user', claude_user(content))
        |        elif e.get('type') == 'assistant':
        |            if isinstance(content, str):
        |                say('agent', content)
        |            for c in content if isinstance(content, list) else []:
        |                if not isinstance(c, dict):
        |                    continue
        |                if c.get('type') == 'text':
        |                    say('agent', c.get('text'))
        |                elif c.get('type') == 'tool_use':
        |                    line_, detail = claude_tool(c)
        |                    tool(c.get('id'), line_, detail)
        |    else:
        |        p = e.get('payload') if isinstance(e.get('payload'), dict) else {}
        |        kind = p.get('type')
        |        if e.get('type') == 'event_msg' and kind == 'user_message':
        |            say('user', p.get('message'))
        |        elif e.get('type') == 'response_item' and kind == 'message' and p.get('role') == 'assistant':
        |            say('agent', '\n'.join(c.get('text') or '' for c in p.get('content') or [] if isinstance(c, dict) and c.get('type') in ('output_text', 'text')))
        |        elif kind == 'function_call':
        |            try:
        |                args = json.loads(p.get('arguments') or '{}')
        |            except ValueError:
        |                args = {}
        |            cmd = args.get('cmd') or args.get('command') if isinstance(args, dict) else None
        |            if isinstance(cmd, list):
        |                cmd = cmd[-1] if cmd else ''
        |            name = str(p.get('name') or 'tool')
        |            if cmd:
        |                tool(p.get('call_id'), ('Shell' if name in ('exec_command', 'shell', 'local_shell') else name) + ': ' + first_line(cmd), str(cmd))
        |            else:
        |                tool(p.get('call_id'), name, '')
        |        elif kind == 'custom_tool_call':
        |            name, text = str(p.get('name') or 'tool'), str(p.get('input') or '')
        |            files = re.findall(r'^\*\*\* (?:Update|Add|Delete) File: (.+)$', text, re.M)
        |            if files:
        |                tool(p.get('call_id'), name + ' ' + ', '.join(short(f) for f in files[:3]) + (' +%d' % (len(files) - 3) if len(files) > 3 else ''), '\n'.join(tilde(f) for f in files))
        |            else:
        |                tool(p.get('call_id'), name + ': ' + first_line(text), text)
        |        elif kind in ('function_call_output', 'custom_tool_call_output'):
        |            out = p.get('output')
        |            code = re.search(r'(?:Process exited with code|Exit code:)\s*(-?\d+)', out) if isinstance(out, str) else None
        |            done(p.get('call_id'), bool(code and code.group(1) != '0'))
        |        elif kind == 'web_search_call':
        |            query = (p.get('action') or {}).get('query') or ''
        |            tool(None, 'Search: ' + first_line(query) if query else 'Search', str(query))
        |            if p.get('status') == 'completed':
        |                items[-1]['state'] = 'ok'
        |truncated = cut or len(items) > $MAX_ITEMS
        |items = items[-$MAX_ITEMS:]
        |for item in items:
        |    if 'text' in item:
        |        item['text'] = clip(item['text'], $MAX_TEXT_CHARS)
        |out = json.dumps({'v': 1, 'size': size, 'truncated': truncated, 'items': items})
        |while len(out) > $MAX_OUTPUT_CHARS and items:
        |    items = items[max(1, len(items) // 4):]
        |    out = json.dumps({'v': 1, 'size': size, 'truncated': True, 'items': items})
        |print(out)
    """.trimMargin()
}
