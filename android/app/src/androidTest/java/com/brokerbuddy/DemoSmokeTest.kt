package com.brokerbuddy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brokerbuddy.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On an emulator: the installed app starts, "Try the demo" opens Home with the demo banner, and
 * the sample clients, properties and follow-ups load — all without a server.
 */
@RunWith(AndroidJUnit4::class)
class DemoSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, substring: Boolean = false) = rule.waitUntil(20_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun demoOpensAndShowsSampleData() {
        waitFor("Try the demo", substring = true)
        rule.onNodeWithText("Try the demo", substring = true).performScrollTo().performClick()

        waitFor("Today's Work")
        rule.onNodeWithText("Demo · sample data, saved on this phone only").assertIsDisplayed()
        // Further down Home: scroll to it, then check it's shown.
        rule.onNodeWithText("99acres Leads").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Housing.com Leads").performScrollTo().assertIsDisplayed()

        // The bottom tabs (the Home tiles with the same names go to the same screens).
        // Search puts the row at the top (long lists only draw what's on screen).
        rule.onAllNodes(hasText("Clients") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Rahul")
        waitFor("Rahul Sharma")

        rule.onAllNodes(hasText("Properties") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Oberoi")
        waitFor("2 BHK in Oberoi Splendor")
    }
}
