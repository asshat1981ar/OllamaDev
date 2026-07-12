package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SwarmConfig
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SwarmViewModel
import com.example.viewmodel.SwarmViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: SwarmViewModel by viewModels {
        SwarmViewModelFactory(application)
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncWorkspace(isAutomatic = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                var activeTab by remember { mutableStateOf("dashboard") }
                var selectedSwarmToDispatch by remember { mutableStateOf<SwarmConfig?>(null) }

                val configuration = LocalConfiguration.current
                val isExpanded = configuration.screenWidthDp >= 600

                val navItems = listOf(
                    NavigationItem("dashboard", "Dashboard", Icons.Rounded.Dashboard),
                    NavigationItem("workspace", "Workspace", Icons.Rounded.Terminal),
                    NavigationItem("voice", "Voice", Icons.Rounded.Mic),
                    NavigationItem("settings", "System", Icons.Rounded.Settings)
                )

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    bottomBar = {
                        if (!isExpanded) {
                            NavigationBar(
                                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 8.dp
                            ) {
                                navItems.forEach { item ->
                                    NavigationBarItem(
                                        selected = activeTab == item.id,
                                        onClick = { activeTab = item.id },
                                        icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                                        label = { Text(item.label, fontSize = 11.sp) },
                                        modifier = Modifier.testTag("nav_bottom_${item.id}"),
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = innerPadding.calculateTopPadding(),
                                bottom = if (isExpanded) innerPadding.calculateBottomPadding() else 0.dp
                            )
                    ) {
                        if (isExpanded) {
                            // Left Navigation Rail for Tablet / Foldable (Adaptive layout)
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surface,
                                header = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Hub,
                                            contentDescription = "Ollama Swarm Logo",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "SWARM",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                navItems.forEach { item ->
                                    NavigationRailItem(
                                        selected = activeTab == item.id,
                                        onClick = { activeTab = item.id },
                                        icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                                        label = { Text(item.label) },
                                        modifier = Modifier.testTag("nav_rail_${item.id}"),
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        )
                                    )
                                }
                            }
                        }

                        // Tab Contents Panel
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            when (activeTab) {
                                "dashboard" -> DashboardScreen(
                                    viewModel = viewModel,
                                    onNavigateToSwarm = { swarm ->
                                        selectedSwarmToDispatch = swarm
                                        activeTab = "workspace"
                                    }
                                )
                                "workspace" -> {
                                    UnifiedWorkspaceScreen(
                                        viewModel = viewModel,
                                        initialSwarm = selectedSwarmToDispatch
                                    )
                                    // Clear temporary selected swarm state after displaying
                                    SideEffect {
                                        selectedSwarmToDispatch = null
                                    }
                                }
                                "voice" -> VoiceScreen(
                                    viewModel = viewModel
                                )
                                "settings" -> SystemConfigScreen(
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class NavigationItem(
    val id: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
