package com.axis.translate

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke e2e (SPEC #46-class verification on a cold app):
 * the app installs, launches, shows the main scaffold and every bottom
 * navigation destination opens without crashing.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun packageNameIsCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.axis.translate", context.packageName)
    }

    @Test
    fun homeScreenShowsTitleAndTranslateButton() {
        composeRule.onNodeWithText("Axis Translate").assertExists()
        composeRule.onNodeWithText("Translate").assertExists()
    }

    @Test
    fun bottomNavigationOpensAllTabs() {
        composeRule.onNodeWithText("Camera").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("History").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Favorites").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Appearance").assertExists()
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Axis Translate").assertExists()
    }
}
