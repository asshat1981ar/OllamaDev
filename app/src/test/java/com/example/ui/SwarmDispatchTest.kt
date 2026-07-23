package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

// Config qualifiers (not setCompactWidth()): RuntimeEnvironment.setQualifiers() only updates
// resources, it doesn't resize the already-created ComposeContentTestRule host, so content
// further down the screen ends up positioned outside the (unconfigured, undersized) viewport and
// fails assertIsDisplayed() with "component is not displayed."
class SwarmDispatchTest : UiTestBase() {

    @Config(qualifiers = "w360dp-h2400dp")
    @Test
    fun selectSwarm_enterPrompt_dispatch_taskAppearsAndTranscriptRenders() = runUiTest {
        setContent { SessionScreen(viewModel) }

        advanceUntilIdle()
        // Room seeds swarm configs via a real background executor thread, not testDispatcher, so
        // advanceUntilIdle() alone doesn't guarantee the seed data has landed yet.
        waitUntilTrue { viewModel.allSwarmConfigs.value.isNotEmpty() }
        composeRule.onNodeWithTag("session_swarm_dropdown").performClick()
        advanceUntilIdle()
        // The dropdown button itself already shows this text (Session auto-selects the first
        // swarm once configs load), so matching by text alone finds both the button and the
        // freshly-opened menu item. The menu item is the last match.
        composeRule.onAllNodesWithText("SDLC Spec & Design Swarm")[1].performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("session_input_field").performTextInput("Write a Kotlin fibonacci function")
        composeRule.onNodeWithTag("session_send_button").performClick()
        advanceUntilIdle()

        waitUntilTrue { viewModel.allTasks.value.any { it.prompt.contains("fibonacci") } }
        val task = viewModel.allTasks.value.firstOrNull { it.prompt.contains("fibonacci") }
        checkNotNull(task) { "Expected a task to be created" }

        // runSwarm() (via runSwarmFromSession) sets selectedTaskId as soon as the task row is
        // created (before execution finishes), so the transcript already shows this task's live
        // steps without needing to open the history drawer first.
        composeRule.onNodeWithText("FINAL_RESPONSE").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("session_history_toggle").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("history_item_${task.id}").performScrollTo().assertIsDisplayed()
    }
}
