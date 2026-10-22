/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

package org.connectbot.ui.screens.console

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.data.TailscaleDevice
import org.connectbot.data.entity.Host
import org.connectbot.service.AuthBanner
import org.connectbot.service.DisconnectReason
import org.connectbot.service.PromptRequest
import org.connectbot.service.TerminalBridge
import org.connectbot.terminal.ProgressState
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.transport.SSH
import org.connectbot.ui.LoadingScreen
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.commands.CommandPaletteSheet
import org.connectbot.ui.commands.PALETTE_HERDR_ACTIONS
import org.connectbot.ui.commands.PaletteContext
import org.connectbot.ui.commands.PaletteSession
import org.connectbot.ui.commands.PaletteTarget
import org.connectbot.ui.commands.SlashMenuSheet
import org.connectbot.ui.commands.runSlashCommand
import org.connectbot.ui.commands.slashTarget
import org.connectbot.ui.components.AuthBannerDialog
import org.connectbot.ui.components.DictationDraft
import org.connectbot.ui.components.DraftDialog
import org.connectbot.ui.components.InlinePrompt
import org.connectbot.ui.components.ResizeDialog
import org.connectbot.ui.components.SessionDrawer
import org.connectbot.ui.components.TerminalComposeBar
import org.connectbot.ui.components.UrlScanDialog
import org.connectbot.ui.components.copyDiagnostics
import org.connectbot.ui.machines.MachinesSection
import org.connectbot.ui.machines.MachinesViewModel
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.ui.screens.chat.AgentChat
import org.connectbot.ui.screens.chat.ChatViewDialog
import org.connectbot.ui.screens.devtools.DiffDialog
import org.connectbot.ui.screens.devtools.FilesDialog
import org.connectbot.ui.screens.devtools.PreviewDialog
import org.connectbot.ui.screens.herd.AgentsBoardDialog
import org.connectbot.ui.screens.herd.HerdDialog
import org.connectbot.ui.screens.herd.boardSnapshots
import org.connectbot.ui.screens.herd.herdrActionLabel
import org.connectbot.ui.screens.reader.OutputReaderDialog
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.terminal
import org.connectbot.ui.wish.SendToWishListButton
import org.connectbot.ui.wish.WishDialog
import org.connectbot.ui.wish.WishViewModel
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.util.PreferenceConstants
import org.connectbot.util.UrlUtils
import org.connectbot.util.loadLatestScreenshot
import org.connectbot.util.rememberTerminalTypefaceResultFromStoredValue
import solutions.ormus.logos.commands.PaletteScreen
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdResult
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrSwipe
import solutions.ormus.logos.terminal.BracketedPaste
import timber.log.Timber

/**
 * Check if a hardware keyboard is currently attached to the device.
 * Detects QWERTY and 12-key hardware keyboards, including Bluetooth keyboards.
 */
@Composable
private fun rememberHasHardwareKeyboard(): Boolean {
    val configuration = LocalConfiguration.current

    return remember(configuration) {
        val keyboardType = configuration.keyboard
        keyboardType == android.content.res.Configuration.KEYBOARD_QWERTY ||
            keyboardType == android.content.res.Configuration.KEYBOARD_12KEY
    }
}

@VisibleForTesting
const val AUTO_HIDE_DELAY_MS = 3000L

// Width of the invisible left-edge zone that opens the session drawer on a
// rightward swipe. The terminal consumes horizontal drags, so this strip is
// layered above it to catch the edge gesture.
private val EDGE_SWIPE_WIDTH = 24.dp

internal object ConsoleTestTags {
    const val AUTH_BANNER_MESSAGE = "auth_banner_message"
}

internal fun handleConsoleShortcut(
    keyEvent: KeyEvent,
    volumeKeysChangeFontSize: Boolean,
    copySelection: () -> Unit,
    pasteClipboardContents: () -> Unit,
    increaseFontSize: () -> Unit,
    decreaseFontSize: () -> Unit,
): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) return false

    return when {
        // Ctrl+Shift+C: copy selection
        keyEvent.key == Key.C && keyEvent.isCtrlPressed && keyEvent.isShiftPressed -> {
            copySelection()
            true
        }

        // Ctrl+Shift+V: paste clipboard content
        keyEvent.key == Key.V && keyEvent.isCtrlPressed && keyEvent.isShiftPressed -> {
            pasteClipboardContents()
            true
        }

        // Ctrl+Shift+= (Ctrl++): increase font size
        keyEvent.isCtrlPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Equals -> {
            increaseFontSize()
            true
        }

        // Ctrl+Shift+-: decrease font size
        keyEvent.isCtrlPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Minus -> {
            decreaseFontSize()
            true
        }

        // Volume keys: change font size
        volumeKeysChangeFontSize && keyEvent.key == Key.VolumeUp -> {
            increaseFontSize()
            true
        }

        volumeKeysChangeFontSize && keyEvent.key == Key.VolumeDown -> {
            decreaseFontSize()
            true
        }

        else -> false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConsoleScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPortForwards: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToConsole: (Long) -> Unit = {},
    onNavigateToHostList: () -> Unit = {},
    onNavigateToRoute: (String) -> Unit = {},
    viewModel: ConsoleViewModel = hiltViewModel(),
    wishViewModel: WishViewModel = hiltViewModel(),
    machinesViewModel: MachinesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val terminalManager = LocalTerminalManager.current
    val uiState by viewModel.uiState.collectAsState()
    val allBridges by viewModel.allBridges.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val usage = LocalUsageLog.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Usage log: count drawer opens by button and by edge swipe alike.
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) usage.log(UsageActions.DRAWER_OPEN)
    }
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // Capture latest callback for use in effects
    val currentOnNavigateBack by rememberUpdatedState(onNavigateBack)
    val currentOnNavigateToSettings by rememberUpdatedState(onNavigateToSettings)

    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }

    // Read preferences
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val keyboardAlwaysVisible = remember { prefs.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false) }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean(PreferenceConstants.FULLSCREEN, false)) }
    var titleBarHide by remember { mutableStateOf(prefs.getBoolean(PreferenceConstants.TITLEBARHIDE, false)) }
    val volumeKeysChangeFontSize = remember { prefs.getBoolean(PreferenceConstants.VOLUME_FONT, true) }
    val keepScreenAwake = remember { prefs.getBoolean(PreferenceConstants.KEEP_ALIVE, true) }

    // Keyboard state
    val hasHardwareKeyboard = rememberHasHardwareKeyboard()
    var showSoftwareKeyboard by remember { mutableStateOf(!hasHardwareKeyboard) }

    var rotation by remember(hasHardwareKeyboard) {
        val prefValue = prefs.getString(PreferenceConstants.ROTATION, PreferenceConstants.ROTATION_DEFAULT)
        mutableStateOf(
            if (prefValue == PreferenceConstants.ROTATION_DEFAULT) {
                if (hasHardwareKeyboard) {
                    PreferenceConstants.ROTATION_LANDSCAPE
                } else {
                    PreferenceConstants.ROTATION_PORTRAIT
                }
            } else {
                prefValue
            },
        )
    }

    val termFocusRequester = remember { FocusRequester() }

    var forceSize: Pair<Int, Int>? by remember { mutableStateOf(null) }

    var showMenu by remember { mutableStateOf(false) }
    var showUrlScanDialog by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showTextInputDialog by remember { mutableStateOf(false) }
    var showOutputReader by remember { mutableStateOf(false) }
    // The session whose agent chat is open, if any.
    var chatBridge by remember { mutableStateOf<TerminalBridge?>(null) }

    // The Herdr session that chat's pane is in: the terminal's for Read, the panel's for Chat.
    var chatSession by remember { mutableStateOf(HerdrCommands.DEFAULT_SESSION) }

    // The pane the chat opens on: an Agents board card's; null for the focused pane.
    var chatPane by remember { mutableStateOf<String?>(null) }

    // The session whose Agents board is open, if any.
    var boardBridge by remember { mutableStateOf<TerminalBridge?>(null) }
    var showWishDialog by remember { mutableStateOf(false) }
    // The session whose dev preview is open, if any.
    var previewBridge by remember { mutableStateOf<TerminalBridge?>(null) }
    // The Herdr session whose Herd screen is open, and the one whose Herdr panel (in the
    // compose bar, above the keys) is open, if any.
    var herdScreenBridge by remember { mutableStateOf<TerminalBridge?>(null) }
    var herdrPanelBridge by remember { mutableStateOf<TerminalBridge?>(null) }
    // The session whose diff viewer is open, if any.
    var diffBridge by remember { mutableStateOf<TerminalBridge?>(null) }
    // The session whose slash menu is open, if any, and whether the palette is.
    var slashBridge by remember { mutableStateOf<TerminalBridge?>(null) }
    var showPalette by remember { mutableStateOf(false) }
    // The session whose file browser is open, if any.
    var filesBridge by remember { mutableStateOf<TerminalBridge?>(null) }
    // Measured height of the persistent compose bar, so the terminal reserves
    // space below it and is never covered by it.
    var composeBarHeightPx by remember { mutableIntStateOf(0) }
    var showExtraKeyboard by remember { mutableStateOf(true) } // Start visible to show animation
    var hasPlayedKeyboardAnimation by remember { mutableStateOf(false) }
    var showTitleBar by remember { mutableStateOf(!titleBarHide) }
    // Non-state holder for auto-hide job to avoid unnecessary recompositions
    val autoHideJobRef = remember {
        object {
            var job: Job? = null
        }
    }
    var scannedUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectionController by remember { mutableStateOf<SelectionController?>(null) }
    var imeVisible by remember { mutableStateOf(false) }
    var keyboardScrollInProgress by remember { mutableStateOf(false) }

    // Get current prompt state to check if biometric prompt is active
    val currentBridgeForPrompt = uiState.bridges.getOrNull(uiState.currentBridgeIndex)
    val promptState by currentBridgeForPrompt?.promptManager?.promptState?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val authBanners by currentBridgeForPrompt?.authBanners?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val currentAuthBanner = authBanners.firstOrNull()
    var wasBiometricPromptActive by remember { mutableStateOf(false) }
    val isBiometricPromptActive = promptState is PromptRequest.BiometricPrompt

    // Check if any modal (menu or dialog) is currently active
    val anyModalActive = filesBridge != null || showMenu || showUrlScanDialog || showResizeDialog ||
        showDisconnectDialog || showTextInputDialog || isBiometricPromptActive || currentAuthBanner != null || previewBridge != null ||
        showOutputReader || chatBridge != null || herdScreenBridge != null || boardBridge != null ||
        slashBridge != null || showPalette || diffBridge != null

    /**
     * Unified interaction handler for terminal and keyboard.
     * Manages visibility of the extra keyboard and title bar based on preferences.
     *
     * Intent Matrix:
     * | keyboardAlwaysVisible | titleBarHide | keyboardScrollInProgress | anyModalActive | isTerminalTap | Action                     |
     * |-----------------------|--------------|--------------------------|----------------|---------------|----------------------------|
     * | false                 | any          | false                    | false          | any           | Show KB, Start/Reset Timer |
     * | any                   | true         | false                    | false          | true          | Show TB, Start/Reset Timer |
     * | true                  | false        | any                      | any            | any           | Ensure Both Shown, No Timer|
     * | any                   | any          | true                     | any            | any           | Show KB, Cancel Timer      |
     * | any                   | any          | any                      | true           | any           | Cancel Timer               |
     *
     * @param isTerminalTap Whether this call was triggered by a terminal tap or title bar action.
     * @param isInteraction Whether this call was triggered by a user interaction (tap, key press, scroll).
     *                      If false, only the timer is managed without forcing visibility to true.
     */
    fun handleTerminalInteraction(isTerminalTap: Boolean = false, isInteraction: Boolean = true) {
        autoHideJobRef.job?.cancel()

        if (isInteraction || keyboardScrollInProgress) {
            // Show emulated keyboard on any interaction or while scrolling (unless always visible)
            if (!keyboardAlwaysVisible) {
                showExtraKeyboard = true
            }
            // Show title bar temporarily ONLY when terminal is tapped (if auto-hide enabled)
            if (titleBarHide && isTerminalTap) {
                showTitleBar = true
            }
        }

        // Ensure they are shown if they should be permanent
        if (keyboardAlwaysVisible) showExtraKeyboard = true
        if (!titleBarHide) showTitleBar = true

        // Only start the auto-hide timer if we are not actively scrolling,
        // no modal is active, and at least one element is configured to auto-hide.
        if (!keyboardScrollInProgress && !anyModalActive && (!keyboardAlwaysVisible || titleBarHide)) {
            autoHideJobRef.job = coroutineScope.launch {
                delay(AUTO_HIDE_DELAY_MS)
                // Accessing Compose State within coroutine to avoid stale closures
                // Hide keyboard if not always visible
                if (!keyboardAlwaysVisible) {
                    showExtraKeyboard = false
                }
                // Hide title bar if auto-hide is enabled
                if (titleBarHide) {
                    showTitleBar = false
                }
                // Mark animation as played after first timeout
                hasPlayedKeyboardAnimation = true
            }
        }
    }

    // Sync our state when user dismisses modals or prompts
    LaunchedEffect(anyModalActive) {
        if (!anyModalActive) {
            // When modals are dismissed, restart the auto-hide timer
            handleTerminalInteraction(isInteraction = false)
        }
    }

    // Apply fullscreen mode and display cutout settings
    LaunchedEffect(fullscreen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window

        try {
            if (fullscreen) {
                // Enable fullscreen mode - hide system bars
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // Disable fullscreen mode - show system bars
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (e: IllegalArgumentException) {
            // Handle foldable device state issues
            Timber.e(e, "Error setting fullscreen mode (foldable device?)")
        }
    }

    // Navigate back if all bridges are closed (after initial loading)
    LaunchedEffect(uiState.bridges.size, uiState.isLoading) {
        if (uiState.bridges.isEmpty() && !uiState.isLoading) {
            currentOnNavigateBack()
        }
    }

    // Request focus on terminal when screen appears (e.g., returning from navigation)
    LaunchedEffect(Unit) {
        termFocusRequester.requestFocus()
        // Initial auto-hide timer start (without forcing show)
        handleTerminalInteraction(isInteraction = false)
    }

    // Track actual IME visibility using WindowInsets to detect user dismissing with back button
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val imeHeight = with(density) { imeInsets.getBottom(density).toDp() }
    val systemImeVisible = imeHeight > 0.dp
    var hasImeBeenVisible by remember { mutableStateOf(false) }

    // Sync our state when user dismisses IME externally (back button)
    LaunchedEffect(systemImeVisible) {
        if (systemImeVisible) {
            hasImeBeenVisible = true
        }
        // Only sync to hidden state after IME has been visible at least once.
        // This prevents canceling the keyboard before it has a chance to show.
        if (hasImeBeenVisible && !systemImeVisible && showSoftwareKeyboard) {
            showSoftwareKeyboard = false
        }
        imeVisible = systemImeVisible
    }

    // Show software keyboard after biometric prompt completes (unless hardware keyboard is connected)
    LaunchedEffect(isBiometricPromptActive) {
        if (wasBiometricPromptActive && !isBiometricPromptActive && !hasHardwareKeyboard) {
            showSoftwareKeyboard = true
        }
        wasBiometricPromptActive = isBiometricPromptActive
    }

    val currentBridge = uiState.bridges.getOrNull(uiState.currentBridgeIndex)
    // The compose bar's dictation field for this session; the slash menu puts "/name " in it.
    val dictationDraft = remember(currentBridge) { DictationDraft() }
    // These values are computed from bridge state and will recompute when uiState.revision changes
    val sessionOpen = currentBridge?.isSessionOpen == true
    val disconnected = currentBridge?.isDisconnected == true
    val connecting = currentBridge?.isConnecting == true
    val canForwardPorts = currentBridge?.canFowardPorts() == true
    // A pull down on the title bar: past one mark the session drawer, past two the
    // disconnect confirmation (no session, the drawer).
    val hasBridge by rememberUpdatedState(currentBridge != null)
    val titlePull = remember {
        TitlePull { level ->
            if (level == TitlePullLevel.DISCONNECT && hasBridge) {
                usage.log(UsageActions.TITLE_PULL_DISCONNECT)
                showDisconnectDialog = true
            } else if (level != TitlePullLevel.NONE) {
                usage.log(UsageActions.TITLE_PULL_DRAWER)
                coroutineScope.launch { drawerState.open() }
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    val isConnectionActive = currentBridge != null && !disconnected
    val keepScreenOn = keepScreenAwake && isConnectionActive

    // Show software keyboard when session becomes open (if no hardware keyboard)
    // Also show when switching to a different bridge that's already open
    LaunchedEffect(currentBridge, sessionOpen, hasHardwareKeyboard) {
        if (sessionOpen && !hasHardwareKeyboard) {
            showSoftwareKeyboard = true
        }
    }

    // Reset selection controller when bridge changes
    LaunchedEffect(currentBridge) {
        selectionController = null
    }

    // Initialize forceSize from profile when bridge changes
    LaunchedEffect(currentBridge) {
        currentBridge?.let { bridge ->
            val rows = bridge.profileForceSizeRows
            val cols = bridge.profileForceSizeColumns
            if (rows != null && cols != null) {
                forceSize = Pair(rows, cols)
            } else {
                forceSize = null
            }
        }
    }

    // Show snackbar for network status messages
    LaunchedEffect(Unit) {
        viewModel.networkStatusMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Show snackbar on each open when connections won't persist in background
    val notificationWarningMessage = stringResource(R.string.notification_permission_console_warning)
    val settingsLabel = stringResource(R.string.list_menu_settings)
    LaunchedEffect(Unit) {
        if (viewModel.shouldShowNotificationWarning()) {
            val result = snackbarHostState.showSnackbar(
                message = notificationWarningMessage,
                actionLabel = settingsLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                currentOnNavigateToSettings()
            }
        }
    }

    // Show snackbar when there's an error
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                withDismissAction = true,
            )
        }
    }

    val noUrlHandlerMessage = stringResource(R.string.console_url_no_handler)
    val copiedMessage = stringResource(R.string.copy_details_copied)
    val errorSavedMessage = stringResource(R.string.wish_error_saved)
    val diagnosticsClipboard = LocalClipboard.current

    val urlNotSupportedMessage = stringResource(R.string.console_url_not_supported)

    fun openUrl(url: String) {
        UrlUtils.openUrl(context, url).onFailure { e ->
            coroutineScope.launch {
                val message = if (e is ActivityNotFoundException) {
                    noUrlHandlerMessage
                } else {
                    urlNotSupportedMessage
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    var titleBarHeight by remember { mutableStateOf(0.dp) }

    fun pasteClipboardContents() {
        currentBridge?.let { bridge ->
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()

            if (!clip.isNullOrBlank()) {
                bridge.injectString(BracketedPaste.wrap(clip, bridge.bracketedPaste))
            }
        }
    }

    // Picking a session from the drawer: if it belongs to this host-scoped
    // console, just switch the active index; otherwise jump to that host's
    // console. Either way, close the drawer.
    fun onSelectSession(bridge: TerminalBridge) {
        val index = uiState.bridges.indexOf(bridge)
        if (index >= 0) {
            viewModel.selectBridge(index)
        } else {
            onNavigateToConsole(bridge.host.id)
        }
        coroutineScope.launch { drawerState.close() }
    }

    val quickConnectFailed = stringResource(R.string.drawer_quick_connect_failed)
    fun onQuickConnect(input: String) {
        coroutineScope.launch {
            val hostId = viewModel.quickConnect(input)
            drawerState.close()
            if (hostId != null) {
                onNavigateToConsole(hostId)
            } else {
                snackbarHostState.showSnackbar(quickConnectFailed)
            }
        }
    }

    // Console actions shared by the overflow menu and the command palette.
    fun copyDetails() {
        currentBridge?.let { bridge ->
            coroutineScope.launch {
                diagnosticsClipboard.copyDiagnostics(viewModel.diagnosticsReport(bridge))
                snackbarHostState.showSnackbar(copiedMessage)
            }
        }
    }

    fun sendErrorToWishList() {
        currentBridge?.let { bridge ->
            wishViewModel.saveError({ viewModel.diagnosticsReport(bridge) }, bridge.runsHerdr) {
                coroutineScope.launch { snackbarHostState.showSnackbar(errorSavedMessage) }
            }
        }
    }

    fun scanUrls() {
        currentBridge?.let { bridge ->
            scannedUrls = bridge.scanForURLs()
            showUrlScanDialog = true
        }
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        prefs.edit { putBoolean("fullscreen", fullscreen) }
    }

    fun toggleTitleBarHide() {
        titleBarHide = !titleBarHide
        prefs.edit { putBoolean("titlebarhide", titleBarHide) }
        handleTerminalInteraction(isTerminalTap = true)
    }

    // A Herdr action run from the palette reports back here, since the sheet
    // that shows its result line is not open.
    val herdrLabels = PALETTE_HERDR_ACTIONS.associateWith { herdrActionLabel(it) }
    val jumpLabel = stringResource(R.string.herdr_jump_tabs)
    val resources = LocalResources.current

    val paletteActions = object : PaletteTarget {
        override fun sendKey(sequence: String) {
            currentBridge?.injectString(sequence)
        }

        override fun herdr(action: HerdrAction, text: String?) {
            val herd = currentBridge?.herd ?: return
            herd.perform(action, text)
            val label = herdrLabels[action] ?: jumpLabel
            coroutineScope.launch {
                val outcome = herd.result.first { it != null && it.action == action && it !is HerdResult.Running }
                val message = when (outcome) {
                    is HerdResult.Failed -> resources.getString(R.string.herdr_result_failed, label, outcome.message)
                    is HerdResult.Unavailable -> resources.getString(R.string.herdr_result_unavailable, label)
                    else -> null
                }
                message?.let { snackbarHostState.showSnackbar(it) }
            }
        }

        override fun slash(command: SlashCommand) {
            val bridge = currentBridge ?: return
            runSlashCommand(command, bridge::injectString, dictationDraft::insertCommand) { send ->
                coroutineScope.launch { send() }
            }
        }

        override fun connectHost(hostId: Long) {
            val index = uiState.bridges.indexOfFirst { it.host.id == hostId }
            if (index >= 0) viewModel.selectBridge(index) else onNavigateToConsole(hostId)
        }

        override fun switchSession(hostId: Long) {
            allBridges.firstOrNull { it.host.id == hostId }?.let { onSelectSession(it) }
        }

        override fun connectMachine(device: TailscaleDevice) {
            coroutineScope.launch {
                val saved = machinesViewModel.findSavedHost(device)
                val known = machinesViewModel.knownUsername(device)
                when {
                    saved != null -> onNavigateToConsole(saved.id)

                    known != null -> machinesViewModel.saveHostFor(device, known)?.let { onNavigateToConsole(it.id) }

                    // No username known: the Machines list in the drawer asks for one.
                    else -> drawerState.open()
                }
            }
        }

        override fun open(screen: PaletteScreen) {
            val bridge = currentBridge
            when (screen) {
                PaletteScreen.HERD ->
                    herdScreenBridge = allBridges
                        .sortedByDescending { it === bridge }
                        .firstOrNull { it.runsHerdr }

                PaletteScreen.HERDR_SHEET -> herdrPanelBridge = bridge?.takeIf { it.runsHerdr }

                PaletteScreen.SLASH_MENU -> slashBridge = bridge

                PaletteScreen.READER -> showOutputReader = bridge != null

                PaletteScreen.SESSIONS, PaletteScreen.MACHINES -> coroutineScope.launch { drawerState.open() }

                PaletteScreen.WISH -> showWishDialog = true

                PaletteScreen.COPY_DETAILS -> copyDetails()

                PaletteScreen.SEND_ERROR -> sendErrorToWishList()

                PaletteScreen.TEXT_INPUT -> showTextInputDialog = bridge != null

                PaletteScreen.PASTE -> pasteClipboardContents()

                PaletteScreen.URL_SCAN -> scanUrls()

                PaletteScreen.RESIZE -> showResizeDialog = bridge != null

                PaletteScreen.DISCONNECT -> showDisconnectDialog = bridge != null

                PaletteScreen.RECONNECT -> bridge?.let { viewModel.reconnect(it) }

                PaletteScreen.PORT_FORWARDS -> bridge?.let { onNavigateToPortForwards(it.host.id) }

                PaletteScreen.FULLSCREEN -> toggleFullscreen()

                PaletteScreen.TITLE_BAR -> toggleTitleBarHide()
            }
        }

        override fun navigate(route: String) {
            if (route == NavDestinations.HOST_LIST) onNavigateToHostList() else onNavigateToRoute(route)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                bridges = allBridges,
                activeBridge = currentBridge,
                onSelect = { onSelectSession(it) },
                onDisconnect = { it.dispatchDisconnect(DisconnectReason.USER_REQUESTED) },
                onQuickConnect = { input -> onQuickConnect(input) },
                // The Herd lists the agents of a session that runs Herdr, this one first.
                onOpenHerd = allBridges
                    .sortedByDescending { it === currentBridge }
                    .firstOrNull { it.runsHerdr }
                    ?.let { herdBridge ->
                        {
                            coroutineScope.launch { drawerState.close() }
                            herdScreenBridge = herdBridge
                        }
                    },
                onOpenHostList = {
                    coroutineScope.launch { drawerState.close() }
                    onNavigateToHostList()
                },
                machines = {
                    MachinesSection(
                        onConnect = { host ->
                            coroutineScope.launch { drawerState.close() }
                            onNavigateToConsole(host.id)
                        },
                        preferredBridge = currentBridge,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    )
                },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = modifier
                .fillMaxSize()
                .then(if (keepScreenOn) Modifier.keepScreenOn() else Modifier),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets
                .union(WindowInsets.imeAnimationTarget),
        ) { innerPadding ->
            // Multiple sessions are switched from the left-edge session drawer
            // (swipe from the left or tap the menu icon), which replaced the old
            // top tab row to reclaim vertical terminal space.

            val handleShortcut: (KeyEvent) -> Boolean = { keyEvent ->
                handleConsoleShortcut(
                    keyEvent = keyEvent,
                    volumeKeysChangeFontSize = volumeKeysChangeFontSize,
                    copySelection = { selectionController?.copySelection() },
                    pasteClipboardContents = { pasteClipboardContents() },
                    increaseFontSize = { currentBridge?.increaseFontSize() },
                    decreaseFontSize = { currentBridge?.decreaseFontSize() },
                )
            }

            // Terminal content with keyboard overlay
            // This Box is transparent to accessibility - it's just for layout
            val layoutDirection = LocalLayoutDirection.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        end = innerPadding.calculateEndPadding(layoutDirection),
                        top = if (!titleBarHide) 0.dp else innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding(),
                    )
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
                    .onPreviewKeyEvent(handleShortcut),
            ) {
                when {
                    uiState.isLoading -> {
                        LoadingScreen(modifier = Modifier.fillMaxSize())
                    }

                    uiState.bridges.isNotEmpty() -> {
                        // TODO(Terminal): Re-implement support for switching between terminals
                        // For now, just show the current bridge directly without HorizontalPager
                        // to avoid accessibility issues. Maybe a tab strip across the top for
                        // small screen devices and a list of hosts on the left for large screen.

                        val bridge = uiState.bridges[uiState.currentBridgeIndex]

                        // Terminal view fills entire space with insets padding
                        // to avoid content being cut off by screen curves/notches
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    top = if (!titleBarHide) titleBarHeight else 0.dp,
                                ),
                        ) {
                            // Get font from profile (stored in bridge)
                            val fontResult = rememberTerminalTypefaceResultFromStoredValue(bridge.fontFamily)
                            val coroutineScope = rememberCoroutineScope()
                            // Observe font size changes for reactive updates
                            val fontSize by bridge.fontSizeFlow.collectAsState()
                            // Observe DEL key mode changes
                            val delKeyMode by bridge.delKeyModeFlow.collectAsState()

                            // Show snackbar if font loading failed
                            LaunchedEffect(fontResult.loadFailed, fontResult.isLoading) {
                                if (fontResult.loadFailed && !fontResult.isLoading) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "Failed to load font '${fontResult.requestedFontName}'. Using system default.",
                                        )
                                    }
                                }
                            }

                            // Herdr swipes (tabs, pane focus, workspaces), only while the session
                            // runs Herdr and no selection is up. Read at touch time, so the
                            // gesture input never restarts mid-touch.
                            val swipeBridge by rememberUpdatedState(bridge)
                            val haptic = LocalHapticFeedback.current
                            var lastSwipe by remember { mutableStateOf<HerdrSwipe?>(null) }
                            var swipesFired by remember { mutableIntStateOf(0) }
                            var swipeProblem by remember { mutableStateOf<String?>(null) }

                            // One time ever, the first time a Herdr session opens: swipes move between tabs and workspaces.
                            var swipeIntroSeen by remember { mutableStateOf(prefs.getBoolean(HERDR_SWIPE_INTRO_SEEN, false)) }
                            fun putSwipeIntroAway() {
                                if (swipeIntroSeen) return
                                swipeIntroSeen = true
                                prefs.edit { putBoolean(HERDR_SWIPE_INTRO_SEEN, true) }
                            }

                            // Taps reach a program that asked for the mouse (Herdr's sidebar) as clicks.
                            // Not under a forced size: termlib then picks its own font size.
                            val taps = remember { TerminalTaps() }
                            val cellSize = rememberCellSize(fontResult.typeface, fontSize)

                            // A slow one-finger drag scrolls a program that asked for the mouse (a Herdr pane) by wheel notches.
                            val wheelCell by rememberUpdatedState(cellSize)
                            val wheel = remember {
                                TerminalWheel(
                                    bridge = { swipeBridge },
                                    cell = { if (forceSize == null) wheelCell else Size.Zero },
                                    onDrag = { usage.log(UsageActions.HERDR_DRAG_SCROLL) },
                                )
                            }

                            // A long press held still, then dragged, sends arrow keys, in every session.
                            val arrows = remember {
                                DragArrows(
                                    bridge = { swipeBridge },
                                    cell = { if (forceSize == null) wheelCell else Size.Zero },
                                    enabled = { selectionController?.isSelectionActive != true },
                                    onStart = {
                                        selectionController?.clearSelection()
                                        usage.log(UsageActions.GESTURE_DRAG_ARROW)
                                    },
                                )
                            }

                            Terminal(
                                terminalEmulator = bridge.terminalEmulator,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        bottom = with(density) { composeBarHeightPx.toDp() },
                                    )
                                    .herdrSwipes(
                                        enabled = { swipeBridge.runsHerdr && selectionController?.isSelectionActive != true },
                                        wheel = wheel,
                                    ) { swipe, fingers ->
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        usage.log(usageIdFor(swipe, fingers, usesSwitcher(swipeBridge, swipe)))
                                        swipeProblem = runHerdrSwipe(swipeBridge, swipe)
                                        lastSwipe = swipe
                                        swipesFired++
                                        putSwipeIntroAway()
                                    }
                                    .dragArrows(arrows)
                                    .terminalTaps(taps)
                                    .testTag("terminal"),
                                typeface = fontResult.typeface,
                                initialFontSize = fontSize.sp,
                                keyboardEnabled = true,
                                showSoftKeyboard = showSoftwareKeyboard,
                                focusRequester = termFocusRequester,
                                forcedSize = forceSize,
                                modifierManager = bridge.keyHandler,
                                onSelectionControllerAvailable = { selectionController = it },
                                onTerminalTap = {
                                    val at = taps.take()
                                    if (at != null && forceSize == null && clickTap(bridge, at, cellSize)) {
                                        usage.log(UsageActions.TERMINAL_MOUSE_CLICK)
                                    }
                                    handleTerminalInteraction(isTerminalTap = true)
                                },
                                onImeVisibilityChanged = { visible ->
                                    imeVisible = visible
                                },
                                onHyperlinkClick = { url ->
                                    openUrl(url)
                                },
                                delKeyMode = delKeyMode,
                                onPasteRequest = {
                                    pasteClipboardContents()
                                },
                                onInterceptKey = handleShortcut,
                            )

                            // Set up text input request callback from bridge (for camera button)
                            SideEffect {
                                bridge.onTextInputRequested = {
                                    showTextInputDialog = true
                                }
                            }

                            // Left-edge swipe to open the session drawer. The Terminal
                            // AndroidView consumes horizontal drags, so the drawer's
                            // built-in edge gesture never fires over it. This thin strip
                            // sits above the terminal (but below the compose bar, drawn
                            // next, so it never blocks input) and opens the drawer on a
                            // rightward drag.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .width(EDGE_SWIPE_WIDTH)
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { change, dragAmount ->
                                            if (dragAmount > 0 && drawerState.isClosed) {
                                                change.consume()
                                                coroutineScope.launch { drawerState.open() }
                                            }
                                        }
                                    },
                            )

                            val herdrUp by (bridge.herd?.snapshot ?: NO_HERDR_SNAPSHOT).collectAsState()
                            if (!swipeIntroSeen && (Herd.runsHerdr(bridge.host) || herdrUp != null)) {
                                HerdrSwipeIntro(
                                    onDismiss = {
                                        usage.log(UsageActions.HERDR_SWIPE_INTRO_DISMISS)
                                        putSwipeIntroAway()
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = Ormus.spacing.sm),
                                )
                            }

                            HerdrSwipeHint(
                                swipe = lastSwipe,
                                fired = swipesFired,
                                problem = swipeProblem,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = Ormus.spacing.sm),
                            )

                            // Show inline prompts from the current bridge (non-modal at bottom)
                            val promptState by bridge.promptManager.promptState.collectAsState()

                            // Dictation-first command bar: a real TextField (full IME
                            // for voice/swipe) docked above the key strip. Send writes
                            // the whole line to the PTY so the dictated command runs.
                            // Inline prompts (host key, password, yes/no) take its place
                            // while they are up, so they never sit under its key rows.
                            ComposeBarOrPrompt(
                                promptActive = promptState != null,
                                onComposeBarHeightChange = { composeBarHeightPx = it },
                                composeBar = { barModifier ->
                                    TerminalComposeBar(
                                        bridge = bridge,
                                        onSend = { handleTerminalInteraction() },
                                        onPasteScreenshot = {
                                            if (ContextCompat.checkSelfPermission(
                                                    context,
                                                    mediaPermission,
                                                ) != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                mediaPermissionLauncher.launch(mediaPermission)
                                                null
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    val shot = loadLatestScreenshot(context)
                                                    if (shot == null) {
                                                        null
                                                    } else {
                                                        val remote =
                                                            "/tmp/logos-pastes/shot-${System.currentTimeMillis()}.png"
                                                        val ssh = bridge.transport as? SSH
                                                        if (ssh != null &&
                                                            runCatching { ssh.uploadFile(remote, shot) }.isSuccess
                                                        ) {
                                                            remote
                                                        } else {
                                                            null
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        // An agent pane reads as a chat; anything else as its output.
                                        onRead = {
                                            val attached = bridge.herd?.attached?.value ?: HerdrCommands.DEFAULT_SESSION
                                            if (AgentChat.target(bridge.herd?.snapshotOf(attached)) != null) {
                                                usage.log(UsageActions.CHAT_OPEN)
                                                chatSession = attached
                                                chatPane = null
                                                chatBridge = bridge
                                            } else {
                                                showOutputReader = true
                                            }
                                        },
                                        onChat = {
                                            usage.log(UsageActions.CHAT_OPEN)
                                            herdrPanelBridge = null
                                            chatSession = bridge.herd?.chosen?.value ?: HerdrCommands.DEFAULT_SESSION
                                            chatPane = null
                                            chatBridge = bridge
                                        },
                                        onBoard = {
                                            herdrPanelBridge = null
                                            boardBridge = bridge
                                        },
                                        boardOpen = boardBridge != null,
                                        // Herdr key once the session's Herdr side is up.
                                        herdrPanelOpen = herdrPanelBridge === bridge,
                                        onHerdrPanelChange = bridge.herd?.let {
                                            { open: Boolean -> herdrPanelBridge = if (open) bridge else null }
                                        },
                                        onSlashMenu = { slashBridge = bridge },
                                        onDiff = {
                                            herdrPanelBridge = null
                                            diffBridge = bridge
                                        },
                                        draft = dictationDraft,
                                        modifier = barModifier,
                                    )
                                },
                                prompt = { promptModifier ->
                                    InlinePrompt(
                                        promptRequest = promptState,
                                        onResponse = { response ->
                                            bridge.promptManager.respond(response)
                                        },
                                        onCancel = {
                                            bridge.promptManager.cancelPrompt()
                                        },
                                        onDismiss = {
                                            termFocusRequester.requestFocus()
                                        },
                                        modifier = promptModifier,
                                    )
                                },
                            )

                            // Legacy TerminalKeyboard strip retired: the TerminalComposeBar
                            // above is now the single control surface. Its compensating
                            // bottom padding is gone too, so the bar sits flush. (Dormant
                            // state: showExtraKeyboard / hasPlayedKeyboardAnimation /
                            // keyboardScrollInProgress await a dedicated cleanup pass.)

                            // Show reconnect/close overlay when session is disconnected
                            AnimatedVisibility(
                                visible = disconnected && !connecting && promptState == null,
                                enter = slideInVertically(initialOffsetY = { it }),
                                exit = slideOutVertically(targetOffsetY = { it }),
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                DisconnectOverlay(
                                    onClose = {
                                        usage.log(UsageActions.OVERLAY_CLOSE)
                                        bridge.dispatchDisconnect(DisconnectReason.USER_REQUESTED)
                                    },
                                    onReconnect = {
                                        usage.log(UsageActions.OVERLAY_RECONNECT)
                                        viewModel.reconnect(bridge)
                                    },
                                    report = { viewModel.diagnosticsReport(bridge) },
                                    wishListAction = {
                                        SendToWishListButton(
                                            onClick = {
                                                usage.log(UsageActions.OVERLAY_SEND_ERROR)
                                                wishViewModel.saveError({ viewModel.diagnosticsReport(bridge) }, bridge.runsHerdr) {
                                                    coroutineScope.launch { snackbarHostState.showSnackbar(errorSavedMessage) }
                                                }
                                            },
                                            color = MaterialTheme.colorScheme.terminal.overlayText,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Dialogs
            if (showWishDialog) {
                WishDialog(onDismiss = { showWishDialog = false })
            }

            previewBridge?.let { bridge ->
                PreviewDialog(bridge = bridge, onDismiss = { previewBridge = null })
            }

            if (showOutputReader && currentBridge != null) {
                OutputReaderDialog(bridge = currentBridge, onDismiss = { showOutputReader = false })
            }

            chatBridge?.let { bridge ->
                // Every active agent of every session, so the chat can move between conversations.
                val chatPrimary = bridge.herd?.snapshot?.collectAsState()?.value
                val chatOthers = bridge.herd?.others?.collectAsState()?.value ?: emptyMap()
                val chatSessions = bridge.herd?.sessions?.collectAsState()?.value ?: emptyList()
                val chatAgents = remember(chatPrimary, chatOthers, chatSessions) { AgentsBoard.cards(boardSnapshots(chatPrimary, chatOthers, chatSessions)) }
                ChatViewDialog(
                    bridge = bridge,
                    session = chatSession,
                    pane = chatPane,
                    onDismiss = { chatBridge = null },
                    onOpenReader = {
                        chatBridge = null
                        showOutputReader = true
                    },
                    agents = chatAgents,
                    onSwitch = { card ->
                        chatSession = card.session
                        chatPane = card.paneId
                    },
                )
            }

            // The Agents board stays open under a chat opened from it; a jump closes it.
            boardBridge?.let { bridge ->
                bridge.herd?.let { herd ->
                    AgentsBoardDialog(
                        session = herd,
                        onChat = { card ->
                            chatSession = card.session
                            chatPane = card.paneId
                            chatBridge = bridge
                        },
                        onJump = { card ->
                            boardBridge = null
                            if (bridge !== currentBridge) onSelectSession(bridge)
                            bridge.jumpToHerdrTab(card.jumpTarget(), card.paneId)?.let { why -> Toast.makeText(context, why, Toast.LENGTH_LONG).show() }
                        },
                        onDismiss = { boardBridge = null },
                    )
                }
            }

            diffBridge?.let { bridge ->
                DiffDialog(bridge = bridge, onDismiss = { diffBridge = null })
            }

            herdScreenBridge?.let { herdBridge ->
                HerdDialog(
                    session = herdBridge.herd,
                    onOpenAgent = { agent ->
                        // Focus the agent in Herdr, then show that session's console.
                        herdBridge.herd?.perform(HerdrAction.FocusAgent(agent.paneId))
                        herdScreenBridge = null
                        if (herdBridge !== currentBridge) onSelectSession(herdBridge)
                    },
                    onOpenCommands = {
                        herdScreenBridge = null
                        herdrPanelBridge = herdBridge
                        if (herdBridge !== currentBridge) onSelectSession(herdBridge)
                    },
                    onDismiss = { herdScreenBridge = null },
                )
            }

            filesBridge?.let { bridge ->
                FilesDialog(bridge = bridge, onDismiss = { filesBridge = null })
            }

            slashBridge?.let { bridge ->
                SlashMenuSheet(
                    target = remember(bridge) { bridge.slashTarget() },
                    onInsert = dictationDraft::insertCommand,
                    onDismiss = { slashBridge = null },
                )
            }

            if (showPalette) {
                val machines by machinesViewModel.machines.collectAsState()
                val snapshot = currentBridge?.herd?.snapshot?.collectAsState()?.value
                val herdrKeys = currentBridge?.herd?.keys?.collectAsState()?.value ?: HerdrKeys.DEFAULT
                CommandPaletteSheet(
                    context = PaletteContext(
                        hasSession = currentBridge != null,
                        runsHerdr = currentBridge?.runsHerdr == true,
                        herdrReady = currentBridge?.let { it.runsHerdr && it.herd != null } == true,
                        snapshot = snapshot,
                        herdrKeys = herdrKeys,
                        disconnected = disconnected,
                        canForwardPorts = canForwardPorts,
                        sessions = allBridges.filter { it !== currentBridge }.map { PaletteSession(it.host.id, it.host.nickname) },
                        machines = machines.devices,
                    ),
                    target = currentBridge?.let { remember(it) { it.slashTarget() } },
                    actions = paletteActions,
                    onDismiss = { showPalette = false },
                )
            }

            if (showUrlScanDialog) {
                UrlScanDialog(
                    urls = scannedUrls,
                    onDismiss = { showUrlScanDialog = false },
                    onUrlClick = { url ->
                        openUrl(url)
                    },
                )
            }

            currentAuthBanner?.let { banner ->
                AuthBannerDialog(
                    banner = banner,
                    onDismiss = {
                        currentBridgeForPrompt?.dismissAuthBanner(banner.id)
                    },
                )
            }

            if (showResizeDialog && currentBridge != null) {
                ResizeDialog(
                    currentBridge = currentBridge,
                    isForced = forceSize != null,
                    onDismiss = { showResizeDialog = false },
                    onResize = { width, height ->
                        // Resize the terminal emulator
                        forceSize = Pair(height, width)
                    },
                    onDisableForceSize = {
                        // Disable force size for this session
                        forceSize = null
                    },
                )
            }

            if (showDisconnectDialog && currentBridge != null) {
                HostDisconnectDialog(
                    host = currentBridge.host,
                    onDismiss = { showDisconnectDialog = false },
                    onConfirm = {
                        showDisconnectDialog = false
                        currentBridge.dispatchDisconnect(DisconnectReason.USER_REQUESTED)
                    },
                )
            }

            // The whole draft, large: read a long voice note before it goes out.
            if (showTextInputDialog && promptState == null && currentBridge != null) {
                DraftDialog(
                    draft = dictationDraft,
                    onDismiss = {
                        showTextInputDialog = false
                        termFocusRequester.requestFocus()
                    },
                )
            }

            // Overlay TopAppBar - always visible when titleBarHide is false,
            // or temporarily visible when titleBarHide is true and showTitleBar is true
            if (!titleBarHide || showTitleBar) {
                val density = LocalDensity.current
                TopAppBar(
                    // The host name opens the session drawer (sessions, Herd, Quick connect,
                    // Machines, the host list); the ▾ says it is tappable.
                    title = {
                        val openSessions = stringResource(R.string.drawer_sessions_title)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .testTag(CONSOLE_TITLE_TAG)
                                .clickable(onClickLabel = openSessions) {
                                    usage.log(UsageActions.DRAWER_OPEN)
                                    coroutineScope.launch { drawerState.open() }
                                },
                        ) {
                            Text(
                                currentBridge?.host?.nickname
                                    ?: stringResource(R.string.console_default_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    },
                    modifier = Modifier
                        .testTag("top_app_bar")
                        .titlePull(titlePull)
                        .onSizeChanged {
                            titleBarHeight = with(density) { it.height.toDp() }
                        },
                    navigationIcon = {
                        IconButton(onClick = {
                            usage.log(UsageActions.CONSOLE_BACK)
                            onNavigateBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.button_back),
                            )
                        }
                    },
                    colors = if (titleBarHide) {
                        // Translucent overlay when auto-hide is enabled
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        )
                    } else {
                        // Solid color when permanently visible
                        TopAppBarDefaults.topAppBarColors()
                    },
                    actions = {
                        // Text Input button
                        IconButton(
                            onClick = {
                                usage.log(UsageActions.CONSOLE_TEXT_INPUT)
                                showTextInputDialog = true
                            },
                            enabled = currentBridge != null,
                        ) {
                            // A notepad, not an expand arrow: it opens a page to write a long message on.
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = stringResource(R.string.console_menu_text_input),
                            )
                        }

                        // More menu
                        Box {
                            IconButton(
                                onClick = {
                                    usage.log(UsageActions.CONSOLE_MENU)
                                    // Refresh menu state to update enabled/disabled items
                                    viewModel.refreshMenuState()
                                    showMenu = true
                                },
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.button_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = {
                                    showMenu = false
                                    // Hide title bar again after closing menu if auto-hide is enabled
                                    if (titleBarHide) {
                                        showTitleBar = false
                                    }
                                    termFocusRequester.requestFocus()
                                },
                            ) {
                                // Reconnect (shown only when disconnected)
                                if (disconnected) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.console_menu_reconnect)) },
                                        onClick = {
                                            usage.log(UsageActions.MENU_RECONNECT)
                                            showMenu = false
                                            viewModel.reconnect(currentBridge)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Refresh, contentDescription = null)
                                        },
                                    )
                                }

                                // Command palette: one search over every action. Off the top bar,
                                // which keeps only what is used there.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.palette_open)) },
                                    onClick = {
                                        usage.log(UsageActions.PALETTE_OPEN)
                                        showMenu = false
                                        showPalette = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                    },
                                )

                                // Files: the repository tree at a commit, read with git over SSH.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_title)) },
                                    onClick = {
                                        usage.log(UsageActions.FILES_OPEN)
                                        showMenu = false
                                        filesBridge = currentBridge
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    },
                                    enabled = sessionOpen && currentBridge?.transport is SSH,
                                )

                                // Wish list: one field for a wish, typed or dictated.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.wish_menu_item)) },
                                    onClick = {
                                        usage.log(UsageActions.MENU_WISH)
                                        showMenu = false
                                        showWishDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                    },
                                )

                                // Diff: the work tree an agent is changing, read with git over SSH.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.diff_title)) },
                                    onClick = {
                                        usage.log(UsageActions.DIFF_OPEN)
                                        showMenu = false
                                        diffBridge = currentBridge
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Difference, contentDescription = null)
                                    },
                                    enabled = sessionOpen && currentBridge?.transport is SSH,
                                )

                                // Disconnect/Close
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (!sessionOpen && disconnected) {
                                                stringResource(R.string.console_menu_close)
                                            } else {
                                                stringResource(R.string.list_host_disconnect)
                                            },
                                        )
                                    },
                                    onClick = {
                                        usage.log(UsageActions.MENU_DISCONNECT)
                                        showMenu = false
                                        showDisconnectDialog = true
                                    },
                                    enabled = currentBridge != null,
                                    leadingIcon = {
                                        Icon(Icons.Default.LinkOff, null)
                                    },
                                )

                                // Copy details: diagnostics report of this session
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_details)) },
                                    onClick = {
                                        usage.log(UsageActions.MENU_COPY_DETAILS)
                                        showMenu = false
                                        copyDetails()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    },
                                    enabled = currentBridge != null,
                                )

                                // Send to wish list: the same report, queued for Sun
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.wish_send_error)) },
                                    onClick = {
                                        usage.log(UsageActions.MENU_SEND_ERROR)
                                        showMenu = false
                                        sendErrorToWishList()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Inbox, contentDescription = null)
                                    },
                                    enabled = currentBridge != null,
                                )

                                // Preview: a dev server on the host, forwarded over SSH into an in-app browser.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.preview_title)) },
                                    onClick = {
                                        usage.log(UsageActions.PREVIEW_OPEN)
                                        showMenu = false
                                        previewBridge = currentBridge
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Language, contentDescription = null)
                                    },
                                    enabled = sessionOpen && currentBridge?.transport is SSH,
                                )

                                // Resize
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.console_menu_resize)) },
                                    onClick = {
                                        usage.log(UsageActions.MENU_RESIZE)
                                        showMenu = false
                                        showResizeDialog = true
                                    },
                                    enabled = sessionOpen,
                                )

                                // Port Forwards (if available)
                                if (canForwardPorts) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.console_menu_portforwards)) },
                                        onClick = {
                                            usage.log(UsageActions.MENU_PORT_FORWARDS)
                                            showMenu = false
                                            currentBridge.host.id.let {
                                                onNavigateToPortForwards(
                                                    it,
                                                )
                                            }
                                        },
                                        enabled = sessionOpen,
                                    )
                                }

                                // Fullscreen toggle
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pref_fullscreen_title)) },
                                    onClick = {
                                        usage.log(UsageActions.MENU_FULLSCREEN)
                                        toggleFullscreen()
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = fullscreen,
                                            onCheckedChange = null,
                                        )
                                    },
                                )

                                // Title bar auto-hide toggle
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pref_titlebarhide_title)) },
                                    onClick = {
                                        usage.log(UsageActions.MENU_TITLE_BAR)
                                        toggleTitleBarHide()
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = titleBarHide,
                                            onCheckedChange = null,
                                        )
                                    },
                                )
                            }
                        }
                    },
                )

                TitlePullMark(titlePull, Modifier.padding(top = titleBarHeight))

                // Progress indicator for OSC 9;4 progress reporting
                val progressState = uiState.progressState
                if (progressState != null && progressState != ProgressState.HIDDEN) {
                    val progressColor = when (progressState) {
                        ProgressState.ERROR -> MaterialTheme.colorScheme.error
                        ProgressState.WARNING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }

                    if (progressState == ProgressState.INDETERMINATE) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = titleBarHeight),
                            color = progressColor,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { uiState.progressValue / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = titleBarHeight),
                            color = progressColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Docks [composeBar] at the bottom of the console, or, while [promptActive],
 * gives that spot to [prompt] instead. Both stay overlays on the terminal (the
 * caller is a [Box]).
 *
 * The bar is not drawn while a host-key, password or yes/no prompt is up, so
 * the prompt gets the whole console height (it fits with the soft keyboard up
 * or in landscape) and a stray tap on a key row cannot happen at all (#37).
 * The bar's last measured height is kept by the caller, so the terminal keeps
 * the same bottom padding and the PTY does not resize when a prompt opens.
 */
@Composable
internal fun BoxScope.ComposeBarOrPrompt(
    promptActive: Boolean,
    onComposeBarHeightChange: (Int) -> Unit,
    composeBar: @Composable (Modifier) -> Unit,
    prompt: @Composable (Modifier) -> Unit,
) {
    if (!promptActive) {
        composeBar(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { onComposeBarHeightChange(it.height) },
        )
    }
    prompt(Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun HostDisconnectDialog(
    host: Host,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(stringResource(R.string.disconnect_host_alert, host.nickname))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.button_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_no))
            }
        },
    )
}

/** Test tag on the tappable host name in the console top bar. */
const val CONSOLE_TITLE_TAG = "console_title"
