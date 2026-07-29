package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class GitIntegrationTest : UiTestBase() {

    @Test
    fun gitTab_updateSettings_commit_showsHistory() = runUiTest {
        // No size qualifier: Robolectric's default viewport is small, and any Dialog+TextField
        // screen combined with a size qualifier (via @Config or RuntimeEnvironment.setQualifiers())
        // triggers a Robolectric bug where the dialog never reports idle
        // (https://github.com/robolectric/robolectric/issues/8460). We scroll to off-screen
        // content explicitly instead of enlarging the viewport.
        setContent { WorkspacePanel(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("compact_tab_git").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("git_settings_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("git_codename_input").performTextClearance()
        composeRule.onNodeWithTag("git_codename_input").performTextInput("nebula-test")
        composeRule.onNodeWithTag("git_repo_input").performTextClearance()
        composeRule.onNodeWithTag("git_repo_input").performTextInput("test-repo")
        composeRule.onNodeWithTag("git_remote_url_input").performTextClearance()
        composeRule.onNodeWithTag("git_remote_url_input").performTextInput("https://github.com/example/test-repo.git")
        composeRule.onNodeWithTag("save_git_settings_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithText("Codename: nebula-test").assertIsDisplayed()
        composeRule.onNodeWithText("Repo: test-repo").assertIsDisplayed()

        composeRule.onNodeWithTag("commit_msg_field").performTextInput("Initial import")
        composeRule.onNodeWithTag("commit_button").performClick()
        advanceUntilIdle()

        composeRule.onNodeWithText("Initial import").performScrollTo().assertIsDisplayed()
    }
}
