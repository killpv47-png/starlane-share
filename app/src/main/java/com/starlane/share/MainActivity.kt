package com.starlane.share

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.starlane.share.apps.AppEntry
import com.starlane.share.engine.ConnectError
import com.starlane.share.engine.Msg
import com.starlane.share.engine.PeerAction
import com.starlane.share.engine.ShareManager
import com.starlane.share.engine.UiEvent
import com.starlane.share.files.Selection
import com.starlane.share.files.UriInfo
import com.starlane.share.ui.LocalReduceMotion
import com.starlane.share.ui.Perms
import com.starlane.share.ui.StarlaneTheme
import com.starlane.share.ui.screens.AppDetailScreen
import com.starlane.share.ui.screens.DevicesScreen
import com.starlane.share.ui.screens.DiscoveryScreen
import com.starlane.share.ui.screens.HistoryScreen
import com.starlane.share.ui.screens.HomeScreen
import com.starlane.share.ui.screens.IncomingDialog
import com.starlane.share.ui.screens.OnboardingScreen
import com.starlane.share.ui.screens.PickerScreen
import com.starlane.share.ui.screens.Route
import com.starlane.share.ui.screens.SettingsScreen
import com.starlane.share.ui.screens.TransfersScreen
import com.starlane.share.ui.screens.UsbScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single activity. Navigation is a tiny back stack of [Route]s (no navigation library needed),
 * which keeps startup and APK size small. The native splash screen is dismissed as soon as the
 * first frame is ready; there is no artificial delay.
 */
class MainActivity : ComponentActivity() {
    private var stack by mutableStateOf(listOf(Route.HOME))
    private var appDetail: AppEntry? = null
    private var themeVersion by mutableIntStateOf(0)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            @Suppress("UNUSED_EXPRESSION") themeVersion // recompose on appearance change
            val prefs = App.instance.prefs
            var onboarded by remember { mutableStateOf(prefs.onboarded) }
            val reduce = prefs.reduceAnimations || (ShareManager.isStarted && ShareManager.deviceProfile.lowEnd)
            StarlaneTheme(prefs.theme) {
                CompositionLocalProvider(LocalReduceMotion provides reduce) {
                    if (!onboarded) {
                        OnboardingScreen { onboarded = true; askEssentialPermissions() }
                        return@CompositionLocalProvider
                    }
                    val incoming by ShareManager.incoming.collectAsState()
                    LaunchedEffect(Unit) { collectEvents() }
                    val current = stack.last()
                    BackHandler(enabled = stack.size > 1) { pop() }
                    AnimatedContent(current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { r ->
                        when (r) {
                            Route.HOME -> HomeScreen { push(it) }
                            Route.DISCOVERY -> DiscoveryScreen(onBack = { pop() })
                            Route.PICKER -> PickerScreen(onBack = { pop() }, onOpenApp = { appDetail = it; push(Route.APP_DETAIL) })
                            Route.APP_DETAIL -> appDetail?.let { AppDetailScreen(it, onBack = { pop() }) } ?: pop()
                            Route.TRANSFERS -> TransfersScreen(onBack = { pop() })
                            Route.HISTORY -> HistoryScreen(onBack = { pop() })
                            Route.DEVICES -> DevicesScreen(onBack = { pop() }, onScan = { replace(Route.DISCOVERY) })
                            Route.USB -> UsbScreen(onBack = { pop() }, onWireless = { replace(Route.DISCOVERY) })
                            Route.SETTINGS -> SettingsScreen(
                                onBack = { pop() },
                                onLanguageChanged = { recreate() },
                                onAppearanceChanged = { themeVersion++ },
                            )
                        }
                    }
                    incoming?.let { if (!it.isDecided) IncomingDialog(it) }
                }
            }
        }
    }

    private fun push(r: Route) {
        if (r == Route.DISCOVERY) askDiscoveryPermissions()
        stack = stack + r
    }

    private fun replace(r: Route) {
        if (r == Route.DISCOVERY) askDiscoveryPermissions()
        stack = stack.dropLast(1) + r
    }

    private fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    private suspend fun collectEvents() {
        ShareManager.events.collect { e ->
            when (e) {
                is UiEvent.Connected -> {
                    // SEND opens the picker (or sends a pre-selected Sharesheet batch right away).
                    if (e.action == PeerAction.SEND) {
                        if (!Selection.isEmpty) {
                            val (items, groups) = Selection.build()
                            if (ShareManager.send(items, groups)) Selection.clear()
                        } else stack = listOf(Route.HOME, Route.PICKER)
                    } else stack = listOf(Route.HOME, Route.TRANSFERS)
                }
                is UiEvent.ConnectFailed -> toast(connectErrorText(e.reason))
                is UiEvent.Message -> {
                    toast(messageText(e.key))
                    if (e.key == Msg.PEER_WANTS_TO_RECEIVE && stack.last() != Route.PICKER) stack = listOf(Route.HOME, Route.PICKER)
                }
                UiEvent.OpenTransfer -> if (stack.last() != Route.TRANSFERS) stack = listOf(Route.HOME, Route.TRANSFERS)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun connectErrorText(e: ConnectError) = getString(
        when (e) {
            ConnectError.NO_ROUTE -> R.string.connect_failed_NO_ROUTE
            ConnectError.WIFI_DIRECT_FAILED -> R.string.connect_failed_WIFI_DIRECT_FAILED
            ConnectError.BLUETOOTH_FAILED -> R.string.connect_failed_BLUETOOTH_FAILED
            ConnectError.AUTH_FAILED -> R.string.connect_failed_AUTH_FAILED
            ConnectError.TIMEOUT -> R.string.connect_failed_TIMEOUT
            ConnectError.NEEDS_PERMISSION -> R.string.connect_failed_NEEDS_PERMISSION
        },
    )

    private fun messageText(m: Msg) = getString(
        when (m) {
            Msg.DISCONNECTED -> R.string.msg_DISCONNECTED
            Msg.RECOVERING -> R.string.msg_RECOVERING
            Msg.RECOVERED -> R.string.msg_RECOVERED
            Msg.RECOVERY_FAILED -> R.string.msg_RECOVERY_FAILED
            Msg.PEER_WANTS_TO_RECEIVE -> R.string.msg_PEER_WANTS_TO_RECEIVE
            Msg.NOT_CONNECTED -> R.string.msg_NOT_CONNECTED
            Msg.HISTORY_CLEARED -> R.string.msg_HISTORY_CLEARED
        },
    )

    /** Notifications (API 33+) and legacy write access (API ≤ 28) — asked once after onboarding. */
    private fun askEssentialPermissions() {
        val want = Perms.missing(this, Perms.notifications() + Perms.legacyWrite())
        if (want.isNotEmpty()) permLauncher.launch(want)
    }

    /** Discovery needs nearby-devices/location and Bluetooth permissions; requested when the user scans. */
    private fun askDiscoveryPermissions() {
        val want = Perms.missing(this, Perms.discovery() + Perms.bluetooth() + Perms.notifications())
        if (want.isNotEmpty()) permLauncher.launch(want)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Sharesheet (SEND / SEND_MULTIPLE), USB accessory attach, and notification deep links. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let { addShared(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                uris?.let { addShared(it) }
            }
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                ShareManager.handleUsbIntent(intent)
                stack = listOf(Route.HOME, Route.USB)
            }
        }
        when (intent.getStringExtra("open")) {
            "transfer", "incoming" -> stack = listOf(Route.HOME, Route.TRANSFERS)
            "history" -> stack = listOf(Route.HOME, Route.HISTORY)
        }
    }

    private fun addShared(uris: List<Uri>) {
        Thread {
            val items = uris.mapNotNull { UriInfo.describe(this, it) }
            runOnUiThread {
                Selection.addAll(items)
                if (ShareManager.currentConnection() != null) {
                    val (all, groups) = Selection.build()
                    if (ShareManager.send(all, groups)) Selection.clear()
                } else {
                    stack = listOf(Route.HOME, Route.DISCOVERY)
                    askDiscoveryPermissions()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ShareManager.stopScanning()
            ShareManager.releaseIfIdle()
        }
    }

    @Suppress("unused") private suspend fun io(block: () -> Unit) = withContext(Dispatchers.IO) { block() }
}
