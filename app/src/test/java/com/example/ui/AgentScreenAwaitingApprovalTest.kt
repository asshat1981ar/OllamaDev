package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.example.data.AgentStateStore
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

class AgentScreenAwaitingApprovalTest : UiTestBase() {

    // Tall viewport so the agent cards (rendered below the header + metrics console in the
    // LazyColumn) are actually on-screen; otherwise assertIsDisplayed fails with "not displayed".
    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun agentWithAwaitingApprovalStatus_rendersOrangeBadge() = runUiTest {
        // Render the screen first so Compose subscribes to viewModel.allAgents,
        // causing the WhileSubscribed StateFlow to start and emit the seeded agents.
        setContent { AgentScreen(viewModel = viewModel) }
        advanceUntilIdle()

        // Now allAgents.value is populated. Find "Byte Code" (seeded as id=2).
        val agents = viewModel.allAgents.value
        val byteCode = agents.firstOrNull { it.name == "Byte Code" }
            ?: agents.firstOrNull()
            ?: throw IllegalStateException("FakeAppDatabase must seed at least one agent")

        // AgentStateStore.setAgentActive is a no-op for agents not in the store yet.
        // Initialize the full roster so every agent gets an entry, then set the status.
        AgentStateStore.initializeAgents(agents)
        AgentStateStore.setAgentActive(byteCode.id, true, "Awaiting Approval")

        // Give Compose a chance to recompose after the state change
        advanceUntilIdle()

        composeRule.onNodeWithText("AWAITING APPROVAL").assertIsDisplayed()
    }
}
