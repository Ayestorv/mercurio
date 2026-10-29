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
 * Every action id the usage log accepts. An id names a control or a feature, never
 * what was typed into it. [UsageTracker.log] drops any id that is not listed here,
 * so a stray string can never reach the log.
 *
 * `docs/usage-actions.txt` mirrors [ALL] (a test keeps them equal); the Sun-side
 * report reads that file to find controls that were never used. Add the id here
 * and there when you add a control.
 */
object UsageActions {
    // Compose bar keys (row 1 or the tray; the ids predate the tray).
    const val KEY_MODE = "key:mode"
    const val KEY_ESC = "key:esc"
    const val KEY_STOP = "key:stop"
    const val KEY_CTRL = "key:ctrl"
    const val KEY_CTRL_CHAR = "key:ctrl-char"
    const val KEY_Y = "key:y"
    const val KEY_N = "key:n"

    const val KEY_TAB = "key:tab"
    const val KEY_UP = "key:up"
    const val KEY_DOWN = "key:down"
    const val KEY_LEFT = "key:left"
    const val KEY_RIGHT = "key:right"
    const val KEY_BOTTOM = "key:bottom"
    const val KEY_CLEAR_LINE = "key:clear-line"
    const val KEY_ENTER = "key:enter"

    /** A compose bar key whose sequence has no id yet: a new control needs one. */
    const val KEY_OTHER = "key:other"

    // Compose bar: dictation row and paste screenshot.

    /** The bar's mic key, removed (dictation is the phone's overlay). Kept so older logs still read. */
    const val DICTATION_MIC = "dictation:mic"
    const val SEND = "send"
    const val SEND_IME = "send:ime"
    const val DRAFT_SEND = "draft:send"
    const val PASTE_SCREENSHOT = "paste-screenshot"

    // Compose bar: the tray of less used keys above the bar. Open counts taps on ⋯,
    // swipe counts opening it with a swipe up, close counts dismissing it without a
    // key (⋯ again, swipe down, back). A tray key closes it and logs only its own id.
    const val BAR_TRAY_OPEN = "bar:tray-open"
    const val BAR_TRAY_CLOSE = "bar:tray-close"
    const val BAR_TRAY_SWIPE = "bar:tray-swipe"
    const val BAR_URL_COPY = "bar:url-copy"
    const val BAR_URL_OPEN = "bar:url-open"
    const val BAR_REPLY_COPY = "bar:reply-copy"

    /** The / key in row 2 that opens the agent slash-command menu. */
    const val BAR_SLASH_MENU = "bar:slash-menu"

    // Compose bar: Herdr keys (Needs you in row 1, the rest in the tray).
    const val HERDR_NEEDS_YOU = "herdr:needs-you"
    const val HERDR_GOTO = "herdr:goto"
    const val HERDR_PREV_TAB = "herdr:prev-tab"
    const val HERDR_NEXT_TAB = "herdr:next-tab"
    const val HERDR_SCROLL = "herdr:scroll"
    const val HERDR_SIDEBAR = "herdr:sidebar"
    const val HERDR_PGUP = "herdr:pgup"
    const val HERDR_PGDN = "herdr:pgdn"
    const val HERDR_DONE = "herdr:done"

    // Output reader.
    const val READER_OPEN = "reader:open"
    const val READER_CLOSE = "reader:close"
    const val READER_REFRESH = "reader:refresh"
    const val READER_TOP = "reader:top"
    const val READER_PGUP = "reader:pgup"
    const val READER_PGDN = "reader:pgdn"
    const val READER_BOTTOM = "reader:bottom"
    const val READER_TEXT_SMALLER = "reader:text-smaller"
    const val READER_TEXT_LARGER = "reader:text-larger"

    // Agent chat: opened from the Herdr panel's Chat key or the Read key on an agent pane
    // (that key also logs reader:open), then closed, read again, a reply sent, Approve or
    // Deny, a tool row opened, the raw terminal output asked for, the pinned ask tapped,
    // a run of tool calls opened, a long reply shown in full, an option key of the ask tapped.
    // Never the reply's text, nor which option.
    const val CHAT_OPEN = "chat:open"
    const val CHAT_CLOSE = "chat:close"
    const val CHAT_REFRESH = "chat:refresh"
    const val CHAT_SEND = "chat:send"
    const val CHAT_APPROVE = "chat:approve"
    const val CHAT_DENY = "chat:deny"
    const val CHAT_TOOL = "chat:tool"
    const val CHAT_TERMINAL = "chat:terminal"

    /** Another agent's conversation chosen inside the chat: a tap on its chip, or a swipe across the transcript. */
    const val CHAT_PANE = "chat:pane"
    const val CHAT_SWIPE_PANE = "chat:swipe-pane"
    const val CHAT_ASK = "chat:ask"
    const val CHAT_TOOLS = "chat:tools"
    const val CHAT_MORE = "chat:more"
    const val CHAT_OPTION = "chat:option"

    // Diff viewer: opened from the console menu or the Herdr panel's Diff key.
    const val DIFF_OPEN = "diff:open"
    const val HERDR_SHEET_DIFF = "herdr-sheet:diff"
    const val DIFF_FILE = "diff:file"
    const val DIFF_BACK = "diff:back"
    const val DIFF_DIR = "diff:dir"
    const val DIFF_REFRESH = "diff:refresh"
    const val DIFF_CLOSE = "diff:close"

    // Session drawer and the machines list inside it.
    const val DRAWER_OPEN = "drawer:open"
    const val DRAWER_SWITCH = "drawer:switch"
    const val DRAWER_DISCONNECT = "drawer:disconnect"
    const val DRAWER_QUICK_CONNECT = "drawer:quick-connect"
    const val DRAWER_HOST_LIST = "drawer:host-list"

    // A downward pull on the console title bar: past one mark the drawer, past two Disconnect.
    const val TITLE_PULL_DRAWER = "title:pull-drawer"
    const val TITLE_PULL_DISCONNECT = "title:pull-disconnect"
    const val MACHINES_CONNECT = "machines:connect"
    const val MACHINES_REFRESH = "machines:refresh"
    const val MACHINES_FIX_PROMPT = "machines:fix-prompt"

    // Settings: the Tailscale API secret was saved or cleared (never the secret itself).
    const val MACHINES_API_SECRET = "machines:api-secret"

    // Console top bar, overflow menu and the disconnected overlay.
    const val CONSOLE_BACK = "console:back"
    const val CONSOLE_TEXT_INPUT = "console:text-input"
    const val CONSOLE_PASTE = "console:paste"
    const val CONSOLE_MENU = "console:menu"

    /** The gear on the console top bar: opens the settings panel. */
    const val CONSOLE_SETTINGS = "console:settings"
    const val MENU_RECONNECT = "menu:reconnect"
    const val MENU_DISCONNECT = "menu:disconnect"
    const val MENU_URL_SCAN = "menu:url-scan"
    const val MENU_RESIZE = "menu:resize"
    const val MENU_PORT_FORWARDS = "menu:port-forwards"
    const val MENU_FULLSCREEN = "menu:fullscreen"
    const val MENU_TITLE_BAR = "menu:title-bar"
    const val MENU_WISH = "menu:wish"
    const val MENU_COPY_DETAILS = "menu:copy-details"
    const val MENU_SEND_ERROR = "menu:send-error"
    const val OVERLAY_RECONNECT = "overlay:reconnect"
    const val OVERLAY_CLOSE = "overlay:close"
    const val OVERLAY_COPY_DETAILS = "overlay:copy-details"
    const val OVERLAY_SEND_ERROR = "overlay:send-error"

    // Dev preview: opened from the console menu; a server picked, the page reloaded, back
    // (in the page, then to the list), the list read again, closed.
    const val PREVIEW_OPEN = "preview:open"
    const val PREVIEW_PICK = "preview:pick"
    const val PREVIEW_RELOAD = "preview:reload"
    const val PREVIEW_BACK = "preview:back"
    const val PREVIEW_REFRESH = "preview:refresh"
    const val PREVIEW_CLOSE = "preview:close"

    // Host list.
    const val HOSTLIST_CONNECT = "hostlist:connect"
    const val HOSTLIST_ADD = "hostlist:add"
    const val HOSTLIST_SETTINGS = "hostlist:settings"
    const val HOSTLIST_WISH = "hostlist:wish"
    const val HOSTLIST_COPY_DETAILS = "hostlist:copy-details"
    const val HOSTLIST_SEND_ERROR = "hostlist:send-error"

    // Wish list (inbox).
    const val WISH_SAVE = "wish:save"
    const val WISH_EDIT = "wish:edit"
    const val WISH_DONE = "wish:done"
    const val WISH_DELETE = "wish:delete"

    // Settings: usage section.
    const val SETTINGS_USAGE_SUMMARY = "settings:usage-summary"
    const val SETTINGS_USAGE_CLEAR = "settings:usage-clear"
    const val SETTINGS_UPLOAD_NOW = "settings:upload-now"
    const val SETTINGS_WISHES = "settings:wishes"

    // Settings panel (the console's gear).
    const val PANEL_ENTER_NEW_LINE = "panel:enter-new-line"
    const val PANEL_ALL_SETTINGS = "panel:all-settings"

    // Herd screen (agents by who needs you).
    const val HERD_OPEN = "herd:open"
    const val HERD_CARD = "herd:card"
    const val HERD_REFRESH = "herd:refresh"
    const val HERD_COMMANDS = "herd:commands"
    const val HERD_CLOSE = "herd:close"

    // File browser: opened from the console menu.
    const val FILES_OPEN = "files:open"
    const val FILES_DIR = "files:dir"
    const val FILES_REV = "files:rev"
    const val FILES_FOLDER = "files:folder"
    const val FILES_UP = "files:up"
    const val FILES_FILE = "files:file"
    const val FILES_COPY_PATH = "files:copy-path"
    const val FILES_COPY_TEXT = "files:copy-text"
    const val FILES_REFRESH = "files:refresh"
    const val FILES_BACK = "files:back"
    const val FILES_CLOSE = "files:close"

    // Herdr command sheet: opened from the Herdr key, then one id per action that ran.
    const val HERDR_SHEET_OPEN = "herdr:sheet-open"
    const val HERDR_SHEET_NEXT_BLOCKED = "herdr-sheet:next-blocked"
    const val HERDR_SHEET_START_CLAUDE = "herdr-sheet:start-claude"
    const val HERDR_SHEET_START_CODEX = "herdr-sheet:start-codex"
    const val HERDR_SHEET_START_GROK = "herdr-sheet:start-grok"
    const val HERDR_SHEET_EXPLAIN = "herdr-sheet:explain"
    const val HERDR_SHEET_SPLIT_RIGHT = "herdr-sheet:split-right"
    const val HERDR_SHEET_SPLIT_DOWN = "herdr-sheet:split-down"
    const val HERDR_SHEET_ZOOM = "herdr-sheet:zoom"
    const val HERDR_SHEET_FOCUS_LEFT = "herdr-sheet:focus-left"
    const val HERDR_SHEET_FOCUS_RIGHT = "herdr-sheet:focus-right"
    const val HERDR_SHEET_FOCUS_UP = "herdr-sheet:focus-up"
    const val HERDR_SHEET_FOCUS_DOWN = "herdr-sheet:focus-down"
    const val HERDR_SHEET_SWAP_LEFT = "herdr-sheet:swap-left"
    const val HERDR_SHEET_SWAP_RIGHT = "herdr-sheet:swap-right"
    const val HERDR_SHEET_SWAP_UP = "herdr-sheet:swap-up"
    const val HERDR_SHEET_SWAP_DOWN = "herdr-sheet:swap-down"
    const val HERDR_SHEET_RENAME_PANE = "herdr-sheet:rename-pane"
    const val HERDR_SHEET_CLOSE_PANE = "herdr-sheet:close-pane"
    const val HERDR_SHEET_NEW_TAB = "herdr-sheet:new-tab"
    const val HERDR_SHEET_RENAME_TAB = "herdr-sheet:rename-tab"
    const val HERDR_SHEET_CLOSE_TAB = "herdr-sheet:close-tab"
    const val HERDR_SHEET_JUMP_TAB = "herdr-sheet:jump-tab"
    const val HERDR_SHEET_NEW_WORKSPACE = "herdr-sheet:new-workspace"
    const val HERDR_SHEET_RENAME_WORKSPACE = "herdr-sheet:rename-workspace"
    const val HERDR_SHEET_CLOSE_WORKSPACE = "herdr-sheet:close-workspace"
    const val HERDR_SHEET_JUMP_WORKSPACE = "herdr-sheet:jump-workspace"
    const val HERDR_SHEET_COPY_MODE = "herdr-sheet:copy-mode"
    const val HERDR_SHEET_GOTO = "herdr-sheet:goto"
    const val HERDR_SHEET_OPEN_NOTIFICATION = "herdr-sheet:open-notification"
    const val HERDR_SHEET_DETACH = "herdr-sheet:detach"
    const val HERDR_SHEET_RELOAD_CONFIG = "herdr-sheet:reload-config"
    const val HERDR_SHEET_HELP = "herdr-sheet:help"
    const val HERDR_SHEET_SIDEBAR = "herdr-sheet:sidebar"
    const val HERDR_SHEET_NAVIGATE_WORKSPACES = "herdr-sheet:navigate-workspaces"
    const val HERDR_SHEET_FIX_PROMPT = "herdr-sheet:fix-prompt"
    const val HERDR_SHEET_RETRY = "herdr-sheet:retry"

    // A numbered key in the tab row at the top of the Herdr panel.
    const val HERDR_SHEET_TAB_KEY = "herdr-sheet:tab-key"

    // A key in the session row at the top of the Herdr panel (the host runs more than one session).
    const val HERDR_SHEET_SESSION = "herdr-sheet:session"

    // Jump to: opened from the Herdr panel's Tabs group; a tab picked.
    const val HERDR_JUMP_OPEN = "herdr-jump:open"

    /** Jump to opened by holding the Herdr key (the panel's key logs [HERDR_JUMP_OPEN]). */
    const val HERDR_JUMP_KEY = "herdr-jump:key"
    const val HERDR_JUMP_PICK = "herdr-jump:pick"

    // The compose bar's Needs you key: a tap jumps to the next agent waiting for the user;
    // with none waiting, or on a long press, it opens the Agents board.
    const val NEEDS_YOU_JUMP = "needs-you:jump"
    const val NEEDS_YOU_BOARD = "needs-you:board"

    /** The compose bar's Chat key: the focused pane as a chat (the console then logs [CHAT_OPEN] or opens the reader). */
    const val CHAT_KEY = "chat:key"

    // Agents board: opened from the Herdr panel; a card tapped (chat, or jump for an agent
    // without one), long-pressed (jump), opened to its screen, answered, replied to; refreshed, closed;
    // an option key of the ask tapped (never which option).
    const val BOARD_OPEN = "board:open"
    const val BOARD_CHAT = "board:chat"
    const val BOARD_JUMP = "board:jump"
    const val BOARD_MORE = "board:more"
    const val BOARD_APPROVE = "board:approve"
    const val BOARD_DENY = "board:deny"
    const val BOARD_REPLY = "board:reply"
    const val BOARD_REFRESH = "board:refresh"
    const val BOARD_CLOSE = "board:close"
    const val BOARD_OPTION = "board:option"

    // The one-time "swipe for tabs" hint over a Herdr terminal, put away with its key.
    const val HERDR_SWIPE_INTRO_DISMISS = "herdr-swipe-intro:dismiss"
    const val HERDR_SHEET_STOP_SESSION = "herdr-sheet:stop-session"

    // Herdr panel layout: a long press opened a key's menu; a key hidden, edited (built-in
    // or the user's own), added, removed; Restore defaults confirmed. Never the label or key.
    const val HERDR_SHEET_KEY_MENU = "herdr-sheet:key-menu"
    const val HERDR_SHEET_KEY_HIDE = "herdr-sheet:key-hide"
    const val HERDR_SHEET_KEY_EDIT = "herdr-sheet:key-edit"
    const val HERDR_SHEET_KEY_ADD = "herdr-sheet:key-add"
    const val HERDR_SHEET_KEY_REMOVE = "herdr-sheet:key-remove"
    const val HERDR_SHEET_RESTORE = "herdr-sheet:restore-defaults"

    // Herdr connect picker: a Herdr host's workspaces offered before the attach. Shown,
    // then one of: a row picked (its workspace focused first), the gold attach key, Back.
    const val HERDR_PICKER_SHOW = "herdr-picker:show"
    const val HERDR_PICKER_PICK = "herdr-picker:pick"
    const val HERDR_PICKER_ATTACH = "herdr-picker:attach"
    const val HERDR_PICKER_DISMISS = "herdr-picker:dismiss"

    // Herdr notifications, when tapped; Approve and Deny on a "Needs you" (after the unlock);
    // an answer that had to log in in the background (no live session).
    const val NOTIFY_NEEDS_YOU_OPEN = "notify:needs-you-open"
    const val NOTIFY_FINISHED_OPEN = "notify:finished-open"
    const val NOTIFY_APPROVE = "notify:approve"
    const val NOTIFY_DENY = "notify:deny"
    const val NOTIFY_ANSWER_CONNECT = "notify:answer-connect"

    // Command palette: the search icon in the console top bar, then a result run.
    const val PALETTE_OPEN = "palette:open"
    const val PALETTE_RUN = "palette:run"

    // Slash menu (opened by the bar's "/" key, bar:slash-menu): a command sent or put in
    // the dictation field (never which command), the agent picked, the list read again.
    const val SLASH_SEND = "slash:send"
    const val SLASH_INSERT = "slash:insert"
    const val SLASH_AGENT = "slash:agent"
    const val SLASH_REFRESH = "slash:refresh"

    // Swipes on the terminal of a Herdr session: one finger across for tabs, two fingers
    // across for pane focus, two fingers up or down for workspaces, and a fast one-finger
    // flick up or down for workspaces too (its own ids, to tell it from two fingers). The
    // _SWITCHER ids: a workspace swipe sent as Herdr's switcher keys (the client has saved machines).
    const val SWIPE_PREV_TAB = "swipe:prev-tab"
    const val SWIPE_NEXT_TAB = "swipe:next-tab"
    const val SWIPE_PANE_LEFT = "swipe:pane-left"
    const val SWIPE_PANE_RIGHT = "swipe:pane-right"
    const val SWIPE_PREV_WORKSPACE = "swipe:prev-workspace"
    const val SWIPE_NEXT_WORKSPACE = "swipe:next-workspace"
    const val SWIPE_FLICK_PREV_WORKSPACE = "swipe:flick-prev-workspace"
    const val SWIPE_FLICK_NEXT_WORKSPACE = "swipe:flick-next-workspace"
    const val SWIPE_PREV_WORKSPACE_SWITCHER = "swipe:prev-workspace-switcher"
    const val SWIPE_NEXT_WORKSPACE_SWITCHER = "swipe:next-workspace-switcher"
    const val SWIPE_FLICK_PREV_WORKSPACE_SWITCHER = "swipe:flick-prev-workspace-switcher"
    const val SWIPE_FLICK_NEXT_WORKSPACE_SWITCHER = "swipe:flick-next-workspace-switcher"

    // A tap on the terminal sent as a mouse click, to a program that asked for the mouse.
    const val TERMINAL_MOUSE_CLICK = "terminal:mouse-click"

    // A slow one-finger drag on a Herdr terminal sent as mouse wheel reports. Once per drag.
    const val HERDR_DRAG_SCROLL = "herdr:drag-scroll"

    // A long press held still, then dragged: arrow keys, one per cell crossed. Once per drag.
    const val GESTURE_DRAG_ARROW = "gesture:drag-arrow"

    // Sessions. Recorded by the terminal service, not by a control.
    const val SESSION_START = "session:start"
    const val SESSION_END = "session:end"
    const val SESSION_FAIL = "session:fail"
    const val SESSION_RECONNECT = "session:reconnect"

    // A dropped link reconnected once on its own, with no tap.
    const val SESSION_AUTO_RECONNECT = "session:auto-reconnect"

    // Sync to Sun.
    const val UPLOAD_REFUSED_HOSTKEY = "key:upload-refused-hostkey"

    // Push alerts through UnifiedPush, recorded by the app, not by a control: the
    // distributor gave an endpoint, failed or dropped it; a Herdr host got the endpoint.
    const val PUSH_REGISTERED = "push:registered"
    const val PUSH_FAILED = "push:failed"
    const val PUSH_UNREGISTERED = "push:unregistered"
    const val PUSH_HOST_SYNCED = "push:host-synced"

    // A push message arrived; ignored when it was unusable or from a host this phone does not know.
    const val PUSH_RECEIVED = "push:received"
    const val PUSH_IGNORED = "push:ignored"

    // Login: a password typed at the connect prompt was stored after the server accepted
    // it ("Remember password" ticked). Recorded by the SSH transport; never the password.
    const val AUTH_PASSWORD_SAVED = "auth:password-saved"

    // Game controller (Ayn Thor and other pads): Guide toggled pad control, and one
    // Herdr action sent from a button or stick. Never which key text went out.
    const val PAD_TOGGLE = "pad:toggle"
    const val PAD_ACTION = "pad:action"

    // Second-screen Herdr sidebar: a tap focused a workspace or an agent's pane.
    const val SIDEBAR_WORKSPACE = "sidebar:workspace"
    const val SIDEBAR_AGENT = "sidebar:agent"

    val ALL: List<String> = listOf(
        KEY_MODE, KEY_ESC, KEY_STOP, KEY_CTRL, KEY_CTRL_CHAR, KEY_Y, KEY_N,
        KEY_TAB, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_BOTTOM, KEY_CLEAR_LINE, KEY_ENTER, KEY_OTHER,
        DICTATION_MIC, SEND, SEND_IME, DRAFT_SEND, PASTE_SCREENSHOT,
        BAR_TRAY_OPEN, BAR_TRAY_CLOSE, BAR_TRAY_SWIPE, BAR_URL_COPY, BAR_URL_OPEN, BAR_REPLY_COPY, BAR_SLASH_MENU,
        HERDR_NEEDS_YOU, HERDR_GOTO, HERDR_PREV_TAB, HERDR_NEXT_TAB, HERDR_SCROLL, HERDR_SIDEBAR,
        HERDR_PGUP, HERDR_PGDN, HERDR_DONE,
        READER_OPEN, READER_CLOSE, READER_REFRESH, READER_TOP, READER_PGUP, READER_PGDN,
        READER_BOTTOM, READER_TEXT_SMALLER, READER_TEXT_LARGER,
        CHAT_OPEN, CHAT_CLOSE, CHAT_REFRESH, CHAT_SEND, CHAT_APPROVE, CHAT_DENY, CHAT_TOOL, CHAT_TERMINAL, CHAT_PANE, CHAT_SWIPE_PANE, CHAT_ASK, CHAT_TOOLS, CHAT_MORE, CHAT_OPTION,
        DIFF_OPEN, HERDR_SHEET_DIFF, DIFF_FILE, DIFF_BACK, DIFF_DIR, DIFF_REFRESH, DIFF_CLOSE,
        DRAWER_OPEN, DRAWER_SWITCH, DRAWER_DISCONNECT, DRAWER_QUICK_CONNECT, DRAWER_HOST_LIST,
        TITLE_PULL_DRAWER, TITLE_PULL_DISCONNECT,
        MACHINES_CONNECT, MACHINES_REFRESH, MACHINES_FIX_PROMPT, MACHINES_API_SECRET,
        CONSOLE_BACK, CONSOLE_TEXT_INPUT, CONSOLE_PASTE, CONSOLE_MENU, CONSOLE_SETTINGS,
        MENU_RECONNECT, MENU_DISCONNECT, MENU_URL_SCAN, MENU_RESIZE, MENU_PORT_FORWARDS,
        MENU_FULLSCREEN, MENU_TITLE_BAR, MENU_WISH, MENU_COPY_DETAILS, MENU_SEND_ERROR,
        OVERLAY_RECONNECT, OVERLAY_CLOSE, OVERLAY_COPY_DETAILS, OVERLAY_SEND_ERROR,
        PREVIEW_OPEN, PREVIEW_PICK, PREVIEW_RELOAD, PREVIEW_BACK, PREVIEW_REFRESH, PREVIEW_CLOSE,
        HOSTLIST_CONNECT, HOSTLIST_ADD, HOSTLIST_SETTINGS, HOSTLIST_WISH, HOSTLIST_COPY_DETAILS, HOSTLIST_SEND_ERROR,
        WISH_SAVE, WISH_EDIT, WISH_DONE, WISH_DELETE,
        SETTINGS_USAGE_SUMMARY, SETTINGS_USAGE_CLEAR, SETTINGS_UPLOAD_NOW, SETTINGS_WISHES,
        PANEL_ENTER_NEW_LINE, PANEL_ALL_SETTINGS,
        SESSION_START, SESSION_END, SESSION_FAIL, SESSION_RECONNECT, SESSION_AUTO_RECONNECT,
        UPLOAD_REFUSED_HOSTKEY,
        HERD_OPEN, HERD_CARD, HERD_REFRESH, HERD_COMMANDS, HERD_CLOSE,
        FILES_OPEN, FILES_DIR, FILES_REV, FILES_FOLDER, FILES_UP, FILES_FILE, FILES_COPY_PATH, FILES_COPY_TEXT,
        FILES_REFRESH, FILES_BACK, FILES_CLOSE,
        HERDR_SHEET_OPEN, HERDR_SHEET_NEXT_BLOCKED, HERDR_SHEET_START_CLAUDE, HERDR_SHEET_START_CODEX,
        HERDR_SHEET_START_GROK, HERDR_SHEET_EXPLAIN, HERDR_SHEET_SPLIT_RIGHT, HERDR_SHEET_SPLIT_DOWN,
        HERDR_SHEET_ZOOM, HERDR_SHEET_FOCUS_LEFT, HERDR_SHEET_FOCUS_RIGHT, HERDR_SHEET_FOCUS_UP,
        HERDR_SHEET_FOCUS_DOWN, HERDR_SHEET_SWAP_LEFT, HERDR_SHEET_SWAP_RIGHT, HERDR_SHEET_SWAP_UP,
        HERDR_SHEET_SWAP_DOWN, HERDR_SHEET_RENAME_PANE, HERDR_SHEET_CLOSE_PANE, HERDR_SHEET_NEW_TAB,
        HERDR_SHEET_RENAME_TAB, HERDR_SHEET_CLOSE_TAB, HERDR_SHEET_JUMP_TAB, HERDR_SHEET_NEW_WORKSPACE,
        HERDR_SHEET_RENAME_WORKSPACE, HERDR_SHEET_CLOSE_WORKSPACE, HERDR_SHEET_JUMP_WORKSPACE,
        HERDR_SHEET_COPY_MODE, HERDR_SHEET_GOTO, HERDR_SHEET_OPEN_NOTIFICATION, HERDR_SHEET_DETACH,
        HERDR_SHEET_RELOAD_CONFIG, HERDR_SHEET_HELP, HERDR_SHEET_STOP_SESSION,
        HERDR_SHEET_SIDEBAR, HERDR_SHEET_NAVIGATE_WORKSPACES, HERDR_SHEET_FIX_PROMPT, HERDR_SHEET_RETRY,
        HERDR_SHEET_TAB_KEY, HERDR_SHEET_SESSION,
        HERDR_JUMP_OPEN, HERDR_JUMP_KEY, HERDR_JUMP_PICK, NEEDS_YOU_JUMP, NEEDS_YOU_BOARD, CHAT_KEY, HERDR_SWIPE_INTRO_DISMISS,
        BOARD_OPEN, BOARD_CHAT, BOARD_JUMP, BOARD_MORE, BOARD_APPROVE, BOARD_DENY, BOARD_REPLY, BOARD_REFRESH, BOARD_CLOSE, BOARD_OPTION,
        HERDR_SHEET_KEY_MENU, HERDR_SHEET_KEY_HIDE, HERDR_SHEET_KEY_EDIT, HERDR_SHEET_KEY_ADD,
        HERDR_SHEET_KEY_REMOVE, HERDR_SHEET_RESTORE,
        HERDR_PICKER_SHOW, HERDR_PICKER_PICK, HERDR_PICKER_ATTACH, HERDR_PICKER_DISMISS,
        NOTIFY_NEEDS_YOU_OPEN, NOTIFY_FINISHED_OPEN, NOTIFY_APPROVE, NOTIFY_DENY, NOTIFY_ANSWER_CONNECT,
        PALETTE_OPEN, PALETTE_RUN,
        SLASH_SEND, SLASH_INSERT, SLASH_AGENT, SLASH_REFRESH,
        SWIPE_PREV_TAB, SWIPE_NEXT_TAB, SWIPE_PANE_LEFT, SWIPE_PANE_RIGHT, SWIPE_PREV_WORKSPACE, SWIPE_NEXT_WORKSPACE,
        SWIPE_FLICK_PREV_WORKSPACE, SWIPE_FLICK_NEXT_WORKSPACE, SWIPE_PREV_WORKSPACE_SWITCHER, SWIPE_NEXT_WORKSPACE_SWITCHER,
        SWIPE_FLICK_PREV_WORKSPACE_SWITCHER, SWIPE_FLICK_NEXT_WORKSPACE_SWITCHER,
        TERMINAL_MOUSE_CLICK, HERDR_DRAG_SCROLL, GESTURE_DRAG_ARROW,
        PUSH_REGISTERED, PUSH_FAILED, PUSH_UNREGISTERED, PUSH_HOST_SYNCED, PUSH_RECEIVED, PUSH_IGNORED,
        AUTH_PASSWORD_SAVED,
        PAD_TOGGLE, PAD_ACTION, SIDEBAR_WORKSPACE, SIDEBAR_AGENT,
    )

    private val known: Set<String> = ALL.toSet()

    /** True when [id] is a listed action id. */
    fun isKnown(id: String): Boolean = id in known

    /**
     * Screen tokens come from navigation routes such as `console/{hostId}`: keep the
     * first path segment, lowercase letters, digits, `_` and `-` only, so no host
     * id, query or free text can ride along.
     */
    fun screenToken(route: String?): String? {
        val head = route?.substringBefore('/')?.substringBefore('?')?.lowercase() ?: return null
        return head.takeIf { SCREEN.matches(it) }
    }

    private val SCREEN = Regex("[a-z0-9_-]{1,32}")
}
