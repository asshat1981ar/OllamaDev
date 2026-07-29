package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class NodeManagementTest : UiTestBase() {

    @Test
    fun addNode_validationErrorThenSubmit_pingAndDelete() = runUiTest {
        // No setCompactWidth(): NodeScreen has no width-based layout branch, and setting a size
        // qualifier here triggers a Robolectric bug where a Dialog containing a TextField never
        // reports idle (https://github.com/robolectric/robolectric/issues/8460). That leaves the
        // default (undersized) Robolectric window, so the dialog content needs its own
        // verticalScroll (added to NodeScreen.kt) for performScrollTo() to bring the validation
        // text into view.
        setContent { NodeScreen(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("add_node_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("submit_node_button").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("node_validation_error").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("node_name_input").performTextInput("Test Loopback Node")
        composeRule.onNodeWithTag("node_url_input").performTextClearance()
        composeRule.onNodeWithTag("node_url_input").performTextInput("http://localhost:11434")
        composeRule.onNodeWithTag("node_models_input").performTextClearance()
        composeRule.onNodeWithTag("node_models_input").performTextInput("llama3")
        composeRule.onNodeWithTag("submit_node_button").performClick()
        advanceUntilIdle()

        waitUntilTrue { viewModel.allNodes.value.any { it.name == "Test Loopback Node" } }
        val node = viewModel.allNodes.value.first { it.name == "Test Loopback Node" }
        // The new node card is the last item in NodeScreen's main LazyColumn (header + wizard card
        // + its position in the list); the default (undersized) Robolectric window won't have
        // composed it yet, so scroll to its index first.
        val nodeIndex = viewModel.allNodes.value.indexOfFirst { it.id == node.id }
        composeRule.onNodeWithTag("node_list").performScrollToIndex(2 + nodeIndex)
        composeRule.onNodeWithText("Test Loopback Node").assertIsDisplayed()

        composeRule.onNodeWithTag("ping_node_button_${node.id}").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithText("ONLINE").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("delete_node_button_${node.id}").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithText("Test Loopback Node").assertDoesNotExist()
    }
}
