package com.starlane.share

import android.content.Context
import android.view.View
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starlane.share.ui.Perms
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI flows on a real Android runtime: onboarding, home, history, Persian RTL localization. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext

    @Before
    fun reset() {
        ctx.getSharedPreferences("starlane", Context.MODE_PRIVATE).edit().clear().commit()
        // Pre-grant what the app may ask for, so no system dialog covers the Compose window.
        val perms = Perms.notifications() + Perms.legacyWrite() + Perms.discovery() + Perms.bluetooth() + Perms.media()
        perms.forEach { p -> runCatching { instr.uiAutomation.grantRuntimePermission(ctx.packageName, p) } }
    }

    /** First launch on a cold emulator can take several seconds before setContent attaches. */
    private fun awaitCompose() {
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    @Test
    fun onboardingCreatesUsernameAndShowsHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitCompose()
            compose.waitUntilAtLeastOneExists(hasText("Welcome aboard"), 10_000)
            compose.onNodeWithText("Welcome aboard").assertIsDisplayed()
            compose.onNodeWithText("Username").performTextInput("Nova")
            compose.onNodeWithText("Start sharing").performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasText("Start Scanning"), 5_000)
            compose.onNodeWithText("Hi, Nova").assertIsDisplayed()
            assertEquals("Nova", Prefs(ctx).username)
        }
    }

    @Test
    fun turboAndHistoryAreReachable() {
        Prefs(ctx).username = "Orion"
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitCompose()
            compose.waitUntilAtLeastOneExists(hasText("Turbo Mode"), 10_000)
            compose.onNodeWithText("Turbo Mode").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("History").performScrollTo().performClick()
            // History loads on a background thread; wait for the real (empty) result.
            compose.waitUntilAtLeastOneExists(hasText("Your transfers will appear here."), 5_000)
        }
    }

    @Test
    fun persianLanguageUsesRtlLayout() {
        Prefs(ctx).username = "سارا"
        Prefs(ctx).language = "fa"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                awaitCompose()
                compose.waitUntilAtLeastOneExists(hasText("شروع جستجو"), 10_000)
                compose.onNodeWithText("شروع جستجو").assertIsDisplayed()
                compose.onNodeWithText("حالت توربو").performScrollTo().assertIsDisplayed()
                sc.onActivity { a -> assertEquals(View.LAYOUT_DIRECTION_RTL, a.window.decorView.layoutDirection) }
            }
        } finally {
            Prefs(ctx).language = ""
        }
    }
}
