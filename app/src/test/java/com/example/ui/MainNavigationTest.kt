package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

// Config qualifiers (not setCompactWidth()/setExpandedWidth()): RuntimeEnvironment.setQualifiers()
// only updates resources, it doesn't resize the already-created ComposeContentTestRule host, so
// content further down the screen ends up positioned outside the (unconfigured, undersized)
// viewport and fails assertIsDisplayed() with "component is not displayed."
class MainNavigationTest : UiTestBase() {

    @Config(qualifiers = "w360dp-h2400dp")
    @Test
    fun compactBottomNav_switchesBetweenSessionManageSettings() = runUiTest {
        var activeTab by mutableStateOf("session")
        val items = listOf("session", "manage", "settings")

        setContent {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        items.forEach { item ->
                            NavigationBarItem(
                                selected = activeTab == item,
                                onClick = { activeTab = item },
                                icon = { Icon(navIcon(), contentDescription = null) },
                                label = { Text(item) },
                                modifier = Modifier.testTag("nav_bottom_$item")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    when (activeTab) {
                        "session" -> SessionScreen(viewModel)
                        "manage" -> ManageScreen(viewModel)
                        "settings" -> SystemConfigScreen(viewModel)
                    }
                }
            }
        }

        advanceUntilIdle()
        composeRule.onNodeWithTag("nav_bottom_session").assertIsDisplayed()
        composeRule.onNodeWithTag("session_input_field").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_bottom_manage").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("manage_tab_0").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_bottom_settings").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("system_tab_0").assertIsDisplayed()
    }

    @Config(qualifiers = "w800dp-h2400dp")
    @Test
    fun expandedNavigationRail_switchesBetweenSessionManageSettings() = runUiTest {
        var activeTab by mutableStateOf("session")
        val items = listOf("session", "manage", "settings")

        setContent {
            Scaffold { innerPadding ->
                Row(modifier = Modifier.padding(innerPadding)) {
                    NavigationRail {
                        items.forEach { item ->
                            NavigationRailItem(
                                selected = activeTab == item,
                                onClick = { activeTab = item },
                                icon = { Icon(navIcon(), contentDescription = null) },
                                label = { Text(item) },
                                modifier = Modifier.testTag("nav_rail_$item")
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        when (activeTab) {
                            "session" -> SessionScreen(viewModel)
                            "manage" -> ManageScreen(viewModel)
                            "settings" -> SystemConfigScreen(viewModel)
                        }
                    }
                }
            }
        }

        advanceUntilIdle()
        composeRule.onNodeWithTag("nav_rail_session").assertIsDisplayed()
        composeRule.onNodeWithTag("session_swarm_dropdown").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_rail_manage").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("manage_tab_0").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_rail_settings").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithTag("system_tab_0").assertIsDisplayed()
    }

    @Config(qualifiers = "w360dp-h2400dp")
    @Test
    fun dashboardSwarmItem_requestsSwitchBackToSession() = runUiTest {
        setContent { ManageScreen(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("swarm_config_item_1").assertIsDisplayed()

        composeRule.onNodeWithTag("swarm_config_item_1").performClick()
        advanceUntilIdle()

        assert(viewModel.selectedSessionSwarmConfig.value != null)
        assert(viewModel.requestedTab.value == "session")
    }
}

private fun navIcon(): ImageVector = Icons.Rounded.Home
