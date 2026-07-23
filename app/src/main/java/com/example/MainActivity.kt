package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.data.ApprovalRiskCategory
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
                var activeTab by remember { mutableStateOf("session") }

                val requestedTab by viewModel.requestedTab.collectAsState()
                LaunchedEffect(requestedTab) {
                    requestedTab?.let {
                        activeTab = it
                        viewModel.clearRequestedTabSwitch()
                    }
                }

                // Agentic-loop human-oversight dialogs, hoisted here (not scoped to any one
                // screen) so a task started from another tab never sits silently blocked on a
                // dialog the user isn't viewing.
                val pendingApproval by viewModel.pendingApproval.collectAsState()
                pendingApproval?.let { approval ->
                    AlertDialog(
                        onDismissRequest = { viewModel.rejectPendingAction() },
                        title = { Text("Approval Required") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = when (approval.riskCategory) {
                                        ApprovalRiskCategory.GIT_PUSH -> "Git Push"
                                        ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL -> "Potentially Destructive Tool Call"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("Requested by ${approval.agentName}: ${approval.description}", style = MaterialTheme.typography.bodySmall)
                                if (approval.detail.isNotBlank()) {
                                    Text(approval.detail, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.approvePendingAction() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                modifier = Modifier.testTag("approve_action_button")
                            ) { Text("Approve", color = Color.White) }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.rejectPendingAction() }, modifier = Modifier.testTag("reject_action_button")) {
                                Text("Reject")
                            }
                        }
                    )
                }

                val pendingFileChange by viewModel.pendingFileChange.collectAsState()
                pendingFileChange?.let { change ->
                    AlertDialog(
                        onDismissRequest = { viewModel.rejectPendingFileChange() },
                        title = { Text(if (change.isNewFile) "New File Proposed" else "File Change Proposed") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "${change.filePath} (by ${change.agentName})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .background(Color(0xFF0F0E12), RoundedCornerShape(8.dp))
                                        .border(BorderStroke(1.dp, Color(0xFF231E29)), RoundedCornerShape(8.dp))
                                        .padding(4.dp)
                                ) {
                                    DiffView(
                                        diffLines = computeSimpleLineDiff(change.originalContent, change.proposedContent),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.acceptPendingFileChange() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                modifier = Modifier.testTag("accept_file_change_button")
                            ) { Text("Apply", color = Color.White) }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.rejectPendingFileChange() }, modifier = Modifier.testTag("reject_file_change_button")) {
                                Text("Reject")
                            }
                        }
                    )
                }

                val configuration = LocalConfiguration.current
                val isExpanded = configuration.screenWidthDp >= 600

                val navItems = listOf(
                    NavigationItem("session", "Session", Icons.Rounded.Terminal),
                    NavigationItem("manage", "Manage", Icons.Rounded.Dashboard),
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
                                "session" -> SessionScreen(viewModel = viewModel)
                                "manage" -> ManageScreen(viewModel = viewModel)
                                "settings" -> SystemConfigScreen(viewModel = viewModel)
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
