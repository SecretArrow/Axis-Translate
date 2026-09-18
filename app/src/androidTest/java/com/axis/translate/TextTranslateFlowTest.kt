package com.axis.translate

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axis.translate.di.TestOverrides
import com.axis.translate.inference.FakeEngine
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end text translation flow on a fake engine:
 * type -> Translate -> result card rendered -> history recorded.
 */
@RunWith(AndroidJUnit4::class)
class TextTranslateFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun translateShowsResultCard() {
        composeRule.onNodeWithTag("home_input").performTextClearance()
        composeRule.onNodeWithTag("home_input").performTextInput("Hello world")
        composeRule.onNodeWithTag("home_translate").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(FakeTranslation.TEXT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(FakeTranslation.TEXT).assertExists()
        composeRule.onNodeWithContentDescription("Copy translation").assertExists()
        composeRule.onNodeWithContentDescription("Speak translation").assertExists()
    }

    @Test
    fun translatedItemAppearsInHistory() {
        composeRule.onNodeWithTag("home_input").performTextClearance()
        composeRule.onNodeWithTag("home_input").performTextInput("Good morning everyone")
        composeRule.onNodeWithTag("home_translate").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(FakeTranslation.TEXT).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("History").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Good morning everyone").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Good morning everyone").assertExists()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun installFakeEngine() {
            TestOverrides.engineFactoryOverride = { FakeEngine(FakeTranslation::respond) }
            // Non-null path so TranslationManager believes a model is installed;
            // FakeEngine never touches the file.
            TestOverrides.modelPathOverride = "/data/local/tmp/axis-fake-model.gguf"
        }

        @AfterClass
        @JvmStatic
        fun resetOverrides() {
            TestOverrides.reset()
        }
    }
}

private object FakeTranslation {
    const val TEXT = "Halo dunia dari Axis"

    fun respond(@Suppress("UNUSED_PARAMETER") prompt: String): String = TEXT
}
