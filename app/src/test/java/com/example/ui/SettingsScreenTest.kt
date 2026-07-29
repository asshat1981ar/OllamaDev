package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

// Config qualifiers (not setCompactWidth()): RuntimeEnvironment.setQualifiers() only updates
// resources, it doesn't resize the already-created ComposeContentTestRule host, so content
// further down the screen ends up positioned outside the (unconfigured, undersized) viewport and
// fails assertIsDisplayed() with "component is not displayed."
class SettingsScreenTest : UiTestBase() {

    @Config(qualifiers = "w360dp-h2400dp")
    @Test
    fun systemConfigTabs_switchBetweenNodeAgentAndMcpScreens() = runUiTest {
        setContent { SystemConfigScreen(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("system_tab_0").assertIsDisplayed()
        composeRule.onNodeWithTag("add_node_button").assertIsDisplayed()

        composeRule.onNodeWithTag("system_tab_1").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("add_agent_button").assertIsDisplayed()

        composeRule.onNodeWithTag("system_tab_2").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("add_mcp_server_button").assertIsDisplayed()
    }
}
