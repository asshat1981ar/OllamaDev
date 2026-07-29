package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class McpServerAndRegistryTest : UiTestBase() {

    @Test
    fun addServer_connect_browseRegistry_install_delete_toggleSkill() = runUiTest {
        // No setCompactWidth(): Robolectric's default width is already well under the 800dp
        // expanded-layout threshold, and setting a size qualifier here triggers a Robolectric bug
        // where a Dialog containing a TextField never reports idle
        // (https://github.com/robolectric/robolectric/issues/8460).
        setContent { McpSkillsScreen(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("add_mcp_server_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("mcp_server_type_chip_Database").performClick()
        composeRule.onNodeWithTag("mcp_name_input").performTextInput("Test Postgres")
        composeRule.onNodeWithTag("mcp_source_input").performTextInput("https://example.com/postgres/mcp")
        composeRule.onNodeWithTag("mcp_confirm_button").performClick()
        advanceUntilIdle()

        // addMcpServer() already auto-connects the new server itself (SwarmViewModel.kt:354-372
        // calls connectMcpServer() right after inserting the row), so there's no separate manual
        // "Connect" step to perform here -- clicking connect_server_button again would just toggle
        // an already-Connected server back to Disconnected.
        waitUntilTrue { viewModel.mcpServers.value.first { it.name == "Test Postgres" }.status == "Connected" }
        composeRule.onNodeWithText("Test Postgres").performScrollTo().assertIsDisplayed()
        // The app never renders the literal string "Connected" -- the connect button's own label
        // flips from "Connect" to "Disconnect" once server.status == "Connected" (McpSkillsScreen.kt:561).
        composeRule.onNodeWithText("Disconnect").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("browse_registry_button").performScrollTo().performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("registry_search_input").performTextInput("brave")
        composeRule.onNodeWithTag("registry_search_button").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("registry_install_button_example/brave-search").performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("registry_install_button_example/brave-search").performClick()
        advanceUntilIdle()

        // The registry dialog doesn't auto-dismiss after install, and it still shows "Brave Search"
        // as a search result -- close it first so the name is unambiguous (otherwise it also
        // matches the newly-installed server row underneath).
        composeRule.onNodeWithContentDescription("Close").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithText("Brave Search").performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()

        waitUntilTrue { viewModel.mcpServers.value.any { it.name == "Brave Search" } }
        val installed = viewModel.mcpServers.value.first { it.name == "Brave Search" }
        composeRule.onNodeWithTag("delete_server_${installed.id}").performScrollTo().performClick()
        advanceUntilIdle()
        composeRule.onNodeWithText("Brave Search").assertDoesNotExist()

        val skill = viewModel.claudeSkills.value.first()
        val initialEnabled = skill.isEnabled
        composeRule.onNodeWithTag("skill_toggle_${skill.id}").performScrollTo().performClick()
        advanceUntilIdle()
        val updated = viewModel.claudeSkills.value.first { it.id == skill.id }
        assert(updated.isEnabled != initialEnabled)
    }
}
