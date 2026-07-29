package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.example.data.Agent
import com.example.data.AgentStateStore
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class AgentScreenAwaitingApprovalTest : UiTestBase() {

    @Test
    fun agentWithAwaitingApprovalStatus_rendersOrangeBadge() = runUiTest {
        val agent = Agent(id = 1, name = "Byte Code", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#4CAF50")
        viewModel.addAgent(agent.name, agent.role, agent.modelName, agent.systemPrompt, agent.colorHex)
        advanceUntilIdle()

        val inserted = viewModel.allAgents.value.firstOrNull { it.name == agent.name } ?: agent

        // AgentStateStore.setAgentActive is a no-op when the agent has no entry yet.
        // Explicitly initialize the store so the agent entry exists before setting the status.
        AgentStateStore.initializeAgents(listOf(inserted))
        AgentStateStore.setAgentActive(inserted.id, true, "Awaiting Approval")

        setContent { AgentScreen(viewModel = viewModel) }
        composeRule.onNodeWithText("AWAITING APPROVAL").assertIsDisplayed()
    }
}
