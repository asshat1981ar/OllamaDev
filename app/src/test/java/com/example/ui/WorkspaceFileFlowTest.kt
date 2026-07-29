package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

// Config qualifiers (not setExpandedWidth()): RuntimeEnvironment.setQualifiers() only updates
// resources, it doesn't resize the already-created ComposeContentTestRule host, so content
// further down the screen ends up positioned outside the (unconfigured, undersized) viewport and
// fails assertIsDisplayed() with "component is not displayed."
class WorkspaceFileFlowTest : UiTestBase() {

    @Config(qualifiers = "w800dp-h2400dp")
    @Test
    fun createFile_selectEditSave_runSandbox() = runUiTest {
        setContent { WorkspacePanel(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("add_file_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("new_file_name_field").performTextInput("hello.kt")
        composeRule.onNodeWithTag("new_file_content_field").performTextInput("fun main() {}")
        composeRule.onNodeWithTag("confirm_create_file_button").performClick()
        advanceUntilIdle()

        waitUntilTrue { viewModel.workspaceFiles.value.any { it.filePath == "hello.kt" } }
        val file = viewModel.workspaceFiles.value.first { it.filePath == "hello.kt" }
        composeRule.onNodeWithTag("workspace_file_item_${file.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_file_item_${file.id}").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("code_editor_textarea").performTextInput("\n// edited")
        advanceUntilIdle()
        composeRule.onNodeWithTag("save_file_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("run_sandbox_button").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithText("KOTLIN COMPILER & RUNNER").assertIsDisplayed()
    }
}
