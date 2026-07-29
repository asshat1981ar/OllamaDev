package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

// Config qualifiers (not setCompactWidth()/setExpandedWidth()): RuntimeEnvironment.setQualifiers()
// only updates resources, it doesn't resize the already-created ComposeContentTestRule host, so
// content further down the screen ends up positioned outside the (unconfigured, undersized)
// viewport and fails assertIsDisplayed() with "component is not displayed."
//
// h6000dp (not h2400dp): DashboardScreen's compact layout is a single LazyColumn holding the
// header, stats, the 7 seeded agent cards (~190dp each), swarm configs, and nodes section all in
// one vertical list -- confirmed by dumping the semantics tree that at h2400dp the list's tail
// (remaining swarm configs + the entire nodes section) never gets composed at all, since
// LazyColumn only composes items that intersect the viewport/prefetch window.
class DashboardFlowTest : UiTestBase() {

    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun dashboard_rendersStatsAgentCardsSwarmConfigsAndNodes() = runUiTest {
        setContent { DashboardScreen(viewModel, onNavigateToSwarm = {}) }

        advanceUntilIdle()
        composeRule.onNodeWithText("Swarm Peers").assertIsDisplayed()
        composeRule.onNodeWithText("Standby").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_agent_card_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("swarm_config_item_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Local Node (Loopback)").performScrollTo().assertIsDisplayed()
    }

    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun refreshNodes_updatesLocalNodeStatus() = runUiTest {
        setContent { DashboardScreen(viewModel, onNavigateToSwarm = {}) }

        advanceUntilIdle()
        // SwarmViewModel.init already calls refreshNodes() on startup (so agent dispatch can
        // route to a genuinely reachable node instead of trusting stale seeded status), so node 1
        // (127.0.0.1 loopback, the only URL FakeOllamaService reports reachable) is already Online
        // by the time this test's first assertion runs -- there's no "Offline" state to observe.
        composeRule.onNodeWithTag("node_status_1").performScrollTo().assertTextContains("ONLINE")
        composeRule.onNodeWithTag("node_status_2").performScrollTo().assertTextContains("OFFLINE")

        composeRule.onNodeWithTag("refresh_nodes_button").performScrollTo().performClick()
        advanceUntilIdle()

        composeRule.onNodeWithTag("node_status_1").performScrollTo().assertTextContains("ONLINE")
        composeRule.onNodeWithTag("node_status_2").performScrollTo().assertTextContains("OFFLINE")
    }

    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun tapSwarmConfig_requestsSwitchBackToSessionWithConfigSelected() = runUiTest {
        // ManageScreen wires DashboardScreen's onNavigateToSwarm to pre-select the swarm for
        // Session and request MainActivity switch its tab back to "session" -- this is the real
        // production wiring, not a hand-rolled local callback.
        setContent { ManageScreen(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("swarm_config_item_1").performScrollTo().performClick()
        advanceUntilIdle()

        assert(viewModel.selectedSessionSwarmConfig.value?.id == 1)
        assert(viewModel.requestedTab.value == "session")
    }
}
