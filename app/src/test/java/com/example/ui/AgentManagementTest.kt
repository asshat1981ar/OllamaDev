package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class AgentManagementTest : UiTestBase() {

    @Test
    fun addAgent_roleModelColor_validationErrorThenSubmit_deleteAndClearMetrics() = runUiTest {
        // No setCompactWidth(): AgentScreen has no width-based layout branch, and setting a size
        // qualifier here triggers a Robolectric bug where a Dialog containing a TextField never
        // reports idle (https://github.com/robolectric/robolectric/issues/8460). That leaves the
        // default (undersized) Robolectric window, so the dialog's LazyColumn only composes its
        // first couple of items -- performScrollToIndex() (index-based) can force later, not-yet-
        // composed items into existence, unlike performScrollTo() which requires the node to
        // already be in the semantics tree.
        //
        // waitForIdle() after each scroll (confirmed empirically): scrolling the list then
        // immediately performClick()-ing a *different* node makes that click silently fire twice
        // -- waitForIdle() flushes the scroll's pending frame/gesture state before the next action.
        setContent { AgentScreen(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("add_agent_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("submit_agent_button").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("agent_dialog_fields").performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_validation_error").assertIsDisplayed()

        // Each field lives in its own LazyColumn item (0=name, 1=role, 2=model input, 3=model
        // preset, 4=prompt, 5=color); the tiny default window only keeps a couple composed at
        // once, so scroll each one into view individually rather than assuming prior items stay
        // composed after scrolling further down the list.
        composeRule.onNodeWithTag("agent_dialog_fields").performScrollToIndex(1)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_role_chip_Programmer").performClick()
        composeRule.onNodeWithTag("agent_dialog_fields").performScrollToIndex(3)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_model_preset_mistral").performClick()
        composeRule.onNodeWithTag("agent_dialog_fields").performScrollToIndex(5)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_color_#4CAF50").performClick()

        composeRule.onNodeWithTag("agent_dialog_fields").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_name_input").performTextInput("Helix Code")
        composeRule.onNodeWithTag("agent_dialog_fields").performScrollToIndex(4)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_prompt_input").performTextInput("You are an elite coder.")
        composeRule.onNodeWithTag("submit_agent_button").performClick()
        advanceUntilIdle()

        waitUntilTrue { viewModel.allAgents.value.any { it.name == "Helix Code" } }
        val agent = viewModel.allAgents.value.first { it.name == "Helix Code" }
        assert(agent.modelName == "mistral")
        assert(agent.colorHex == "#4CAF50")

        // The new agent card is the last item in AgentScreen's main LazyColumn (index = header +
        // metrics console + its position in the list); the default (undersized) Robolectric
        // window won't have composed it yet, so scroll to its index first.
        val agentIndex = viewModel.allAgents.value.indexOfFirst { it.id == agent.id }
        composeRule.onNodeWithTag("agent_list").performScrollToIndex(2 + agentIndex)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Helix Code").assertIsDisplayed()
        // Not an exact match: AgentCard renders this as a combined "${role} · Model: ${modelName}"
        // string (AgentScreen.kt:367), never "Programmer" alone.
        composeRule.onNodeWithText("Programmer", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("delete_agent_button_${agent.id}").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithText("Helix Code").assertDoesNotExist()

        composeRule.onNodeWithTag("agent_list").performScrollToIndex(1)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("clear_metrics_button").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("agent_metrics_console").assertIsDisplayed()
    }
}
