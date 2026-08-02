package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
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
        fakeMcpClient.scriptedToolResults["read_workspace_file"] = "package com.example"

        setExpandedWidth()
        setContent { WorkspaceScreen(viewModel = viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("workspace_file_item_app/src/main/java/com/example/MainActivity.kt").assertIsDisplayed()
        // The editor only shows the textarea once a file is selected (otherwise it renders a
        // "Select a file from the list to edit." placeholder).
        composeRule.onNodeWithTag("workspace_file_item_app/src/main/java/com/example/MainActivity.kt").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("workspace_editor_textarea").assertIsDisplayed()
    }

    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun workspaceScreen_createFileButtonOpensDialog() = runUiTest {
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"README.md\"]"
        fakeMcpClient.scriptedToolResults["write_workspace_file"] = "OK"
        fakeMcpClient.scriptedToolResults["read_workspace_file"] = ""
        fakeMcpClient.scriptedToolResults["create_workspace_file"] = "OK"

        setCompactWidth()
        setContent { WorkspaceScreen(viewModel = viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("workspace_create_file_button").performClick()
        advanceUntilIdle()

        // Robolectric dialog windows can compose content that reports "not displayed"
        // (known Robolectric dialog+TextField quirk, cf. McpServerAndRegistryTest), so assert
        // existence of the in-dialog fields rather than display, then exercise the create flow.
        composeRule.onNodeWithTag("workspace_create_filename_field").assertExists()
        composeRule.onNodeWithTag("workspace_create_confirm_button").assertExists()

        composeRule.onNodeWithTag("workspace_create_filename_field").performTextInput("notes/idea.md")
        composeRule.onNodeWithTag("workspace_create_confirm_button").performClick()
        advanceUntilIdle()

        assertEquals("Created notes/idea.md", viewModel.voiceFeedback.value)
        assertEquals("notes/idea.md", viewModel.selectedWorkspaceFile.value)
    }

    @Config(qualifiers = "w800dp-h1280dp")
    @Test
    fun workspaceScreenExpanded_deleteButtonVisibleOnFileItems() = runUiTest {
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"app/src/Temp.kt\"]"

        setExpandedWidth()
        setContent { WorkspaceScreen(viewModel = viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("workspace_delete_file_app/src/Temp.kt").assertIsDisplayed()
    }

    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun workspaceScreen_outlineSwitchShowsOutlinePanel() = runUiTest {
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"app/src/Main.kt\"]"
        fakeMcpClient.scriptedToolResults["read_workspace_file"] = "package com.example"
        fakeMcpClient.scriptedToolResults["get_file_outline"] = "Outline of MainActivity.kt (1 signatures)\n1: package com.example"

        setCompactWidth()
        setContent { WorkspaceScreen(viewModel = viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("workspace_file_item_app/src/Main.kt").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("workspace_outline_switch").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("workspace_outline_text").assertIsDisplayed()
    }
}
