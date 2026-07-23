package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.SwarmViewModel

/**
 * Administrative home for everything that isn't the live Session surface: swarm/agent/node
 * analytics and CRUD, plus MCP/Skills management. Mirrors the internal-TabRow pattern
 * UnifiedWorkspaceScreen already established.
 */
@Composable
fun ManageScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Dashboard", "Agents", "Nodes", "MCP & Skills")
    val tabIcons = listOf(
        Icons.Rounded.Dashboard,
        Icons.Rounded.SmartToy,
        Icons.Rounded.Hub,
        Icons.Rounded.Extension
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    modifier = Modifier.testTag("manage_tab_$index").height(56.dp),
                    icon = {
                        Icon(
                            imageVector = tabIcons[index],
                            contentDescription = title,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToSwarm = { swarm ->
                        viewModel.selectSessionSwarmConfig(swarm)
                        viewModel.requestTabSwitch("session")
                    }
                )
                1 -> AgentScreen(viewModel = viewModel)
                2 -> NodeScreen(viewModel = viewModel)
                3 -> McpSkillsScreen(viewModel = viewModel)
            }
        }
    }
}
