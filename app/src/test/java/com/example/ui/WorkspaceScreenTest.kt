package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

class WorkspaceScreenTest : UiTestBase() {

    private val fakeMcpClient: FakeMcpClient
        get() = SwarmViewModel::class.java
            .getDeclaredField("mcpClient")
            .apply { isAccessible = true }
            .get(viewModel) as FakeMcpClient

    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun workspaceScreen_showsFileListAndOpensEditor() = runUiTest {
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"app/build.gradle.kts\", \"README.md\"]"
        fakeMcpClient.scriptedToolResults["read_workspace_file"] = "# OllamaDev\n\nNative Android MCP workspace."

        setCompactWidth()
        setContent { WorkspaceScreen(viewModel = viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("workspace_server_selector").assertIsDisplayed()
        composeRule.onNodeWithText("FILES (2)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_file_item_app/build.gradle.kts").assertIsDisplayed()

        composeRule.onNodeWithTag("workspace_file_item_README.md").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("workspace_editor_textarea").assertIsDisplayed()
        composeRule.onNodeWithText("Native Android MCP workspace.", substring = true).assertIsDisplayed()
    }

    @Config(qualifiers = "w800dp-h1280dp")
    @Test
    fun workspaceScreenExpanded_showsFileListAndEditorSideBySide() = runUiTest {
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"app/src/main/java/com/example/MainActivity.kt\"]"

        setExpandedWidth()
        setContent { WorkspaceScreen(viewModel = viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("workspace_file_item_app/src/main/java/com/example/MainActivity.kt").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_editor_textarea").assertIsDisplayed()
    }
}
