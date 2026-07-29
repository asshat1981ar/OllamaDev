package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.example.data.Agent
import com.example.data.AgentStateStore
import org.junit.Test

class AgentScreenAwaitingApprovalTest : UiTestBase() {

    @Test
    fun agentWithAwaitingApprovalStatus_rendersOrangeBadge() {
        val agent = Agent(id = 1, name = "Byte Code", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#4CAF50")
        AgentStateStore.initializeAgents(listOf(agent))
        AgentStateStore.setAgentActive(agent.id, true, "Awaiting Approval")

        setContent { AgentScreen(viewModel = viewModel) }

        composeRule.onNodeWithText("AWAITING APPROVAL").assertIsDisplayed()
    }
}
