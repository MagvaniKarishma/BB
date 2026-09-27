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

    /** Waits for [text]; on timeout, says which step and what the screen showed instead. */
    private fun waitFor(text: String, substring: Boolean = false) {
        try {
            rule.waitUntil(30_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Waited 30 s for \"$text\". On screen: ${onScreen()}", e)
        }
    }

    private fun onScreen(): String = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
        .flatMap { root -> generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten() }
        .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { t -> t.text } }
        .distinct().take(60).joinToString(" | ")

    /** Taps the first clickable node with [text] that is actually on screen. */
    private fun clickVisible(text: String) {
        val root = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first().boundsInRoot
        val nodes = rule.onAllNodes(hasText(text) and hasClickAction())
        val index = nodes.fetchSemanticsNodes().indexOfFirst { n ->
            val b = n.boundsInRoot
            b.width > 0 && b.height > 0 && b.top >= root.top && b.bottom <= root.bottom
        }
        if (index < 0) throw AssertionError("No visible \"$text\" to tap. On screen: ${onScreen()}")
        nodes[index].performClick()
    }

    private fun waitForTextField() {
        try {
            rule.waitUntil(30_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("No search box. On screen: ${onScreen()}", e)
        }
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
        waitForTextField()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Rahul")
        // Whichever "Clients" was tapped, look in the All tab.
        waitFor("All")
        rule.onAllNodesWithText("All").onFirst().performClick()
        waitFor("Rahul Sharma")

        // Back to Home (the new-leads list has no bottom tabs), then whichever "Properties" is on screen.
        androidx.test.espresso.Espresso.pressBack()
        waitFor("Today's Work")
        clickVisible("Properties")
        waitForTextField()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Oberoi")
        waitFor("2 BHK in Oberoi Splendor")
    }
}
