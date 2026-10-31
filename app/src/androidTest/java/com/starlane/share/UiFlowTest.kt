package com.starlane.share

import android.content.Context
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI flows on a real Android runtime: onboarding, home, settings, Persian RTL localization. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() {
        ctx.getSharedPreferences("starlane", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun onboardingCreatesUsernameAndShowsHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Welcome aboard").assertIsDisplayed()
            compose.onNodeWithText("Username").performTextInput("Nova")
            compose.onNodeWithText("Start sharing").performClick()
            compose.onNodeWithText("Start Scanning").assertIsDisplayed()
            compose.onNodeWithText("Hi, Nova").assertIsDisplayed()
            assertEquals("Nova", Prefs(ctx).username)
        }
    }

    @Test
    fun settingsAndTurboAreReachable() {
        Prefs(ctx).username = "Orion"
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Turbo Mode").assertIsDisplayed()
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Your transfers will appear here.").assertIsDisplayed()
        }
    }

    @Test
    fun persianLanguageUsesRtlLayout() {
        Prefs(ctx).username = "سارا"
        Prefs(ctx).language = "fa"
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            compose.onNodeWithText("شروع جستجو").assertIsDisplayed()
            compose.onNodeWithText("حالت توربو").assertIsDisplayed()
            sc.onActivity { a -> assertEquals(View.LAYOUT_DIRECTION_RTL, a.window.decorView.layoutDirection) }
        }
    }
}
