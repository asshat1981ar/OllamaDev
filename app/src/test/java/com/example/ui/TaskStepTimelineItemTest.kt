package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.data.TaskStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TaskStepTimelineItemTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun renderStep(actionType: String) {
        composeRule.setContent {
            TaskStepTimelineItem(
                step = TaskStep(
                    id = 1,
                    taskId = 1,
                    agentName = "Byte Code",
                    agentRole = "Programmer",
                    actionType = actionType,
                    content = "Step content for $actionType",
                    timestamp = 0
                )
            )
        }
    }

    @Test
    fun planStep_rendersActionBadgeAndIcon() {
        renderStep("PLAN")
        composeRule.onNodeWithTag("task_step_action_badge_PLAN").assertIsDisplayed()
        composeRule.onNodeWithTag("task_step_action_icon_PLAN").assertIsDisplayed()
    }

    @Test
    fun outputStep_rendersActionBadgeAndIcon() {
        renderStep("OUTPUT")
        composeRule.onNodeWithTag("task_step_action_badge_OUTPUT").assertIsDisplayed()
        composeRule.onNodeWithTag("task_step_action_icon_OUTPUT").assertIsDisplayed()
    }

    @Test
    fun failedStep_rendersActionBadgeAndIcon() {
        renderStep("MCP_CALL_FAILED")
        composeRule.onNodeWithTag("task_step_action_badge_MCP_CALL_FAILED").assertIsDisplayed()
        composeRule.onNodeWithTag("task_step_action_icon_MCP_CALL_FAILED").assertIsDisplayed()
    }

    @Test
    fun budgetHaltStep_rendersActionBadgeAndIcon() {
        renderStep("BUDGET_HALT")
        composeRule.onNodeWithTag("task_step_action_badge_BUDGET_HALT").assertIsDisplayed()
        composeRule.onNodeWithTag("task_step_action_icon_BUDGET_HALT").assertIsDisplayed()
    }
}
