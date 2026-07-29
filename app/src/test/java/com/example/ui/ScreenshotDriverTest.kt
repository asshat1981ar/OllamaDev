package com.example.ui

import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Headless screenshot driver for agents. Renders real screens (wired to the same
 * [UiTestBase] fakes the flow tests use) through Robolectric and dumps a PNG via Roborazzi
 * -- no emulator/device available in this container, so this is the only way to see pixels.
 * Run via the `run-ollamadev` skill; output lands in app/build/outputs/roborazzi/.
 */
class ScreenshotDriverTest : UiTestBase() {

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun dashboard() = runUiTest {
        setContent { DashboardScreen(viewModel, onNavigateToSwarm = {}) }
        advanceUntilIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/dashboard.png")
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun agents() = runUiTest {
        setContent { AgentScreen(viewModel) }
        advanceUntilIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/agents.png")
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun nodes() = runUiTest {
        setContent { NodeScreen(viewModel) }
        advanceUntilIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/nodes.png")
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun session() = runUiTest {
        setContent { SessionScreen(viewModel) }
        advanceUntilIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/session.png")
    }

    @Config(qualifiers = "w900dp-h800dp")
    @Test
    fun sessionExpanded() = runUiTest {
        setContent { SessionScreen(viewModel) }
        advanceUntilIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/session_expanded.png")
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun manage() = runUiTest {
        setContent { ManageScreen(viewModel) }
        advanceUntilIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/manage.png")
    }
}
