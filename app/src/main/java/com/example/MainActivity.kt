package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onNotificationPermissionResult(isGranted)
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncWorkspace(isAutomatic = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Room migration safeguard (Tier 6.4) — see docs/adr/ADR-0002-room-migration-policy.md.
        // Destructive migration fallback is enabled in AppDatabase as a documented last resort;
        // a schema bump without an explicit MIGRATION_* wipes local data. Export/back up before upgrading.
        Log.w("RoomMigration", "Destructive migration fallback enabled (ADR-0002): a Room version bump without an explicit MIGRATION_* wipes local Ollama Swarm data. Export/back up before upgrading.")
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
                                    Text("Reason: ${approval.detail}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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

                val shouldRequestNotificationPermission by viewModel.shouldRequestNotificationPermission.collectAsState()
                shouldRequestNotificationPermission?.let { rationale ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissNotificationPermissionRequest() },
                        title = { Text("Enable Background Notifications") },
                        text = { Text(rationale) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.dismissNotificationPermissionRequest()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                modifier = Modifier.testTag("grant_notification_permission_button")
                            ) { Text("Grant") }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissNotificationPermissionRequest() }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                val pendingFileChangeBatch by viewModel.pendingFileChangeBatch.collectAsState()
                pendingFileChangeBatch?.let { batch ->
                    val decisions = remember(batch.id) { mutableStateMapOf<String, Boolean>() }
                    AlertDialog(
                        onDismissRequest = { viewModel.rejectAllPendingFileChanges() },
                        title = { Text("${batch.changes.size} File Changes Proposed") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("by ${batch.agentName}", style = MaterialTheme.typography.bodySmall)
                                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                                    items(batch.changes) { change ->
                                        val approved = decisions[change.filePath] ?: false
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = change.filePath,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        TextButton(
                                                            onClick = {
                                                                decisions[change.filePath] = false
                                                                viewModel.rejectBatchFileChange(change.filePath)
                                                            },
                                                            modifier = Modifier.testTag("reject_batch_file_${change.filePath}")
                                                        ) { Text("Reject") }
                                                        Button(
                                                            onClick = {
                                                                decisions[change.filePath] = true
                                                                viewModel.acceptBatchFileChange(change.filePath)
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                            modifier = Modifier.testTag("accept_batch_file_${change.filePath}")
                                                        ) { Text("Accept", color = Color.White) }
                                                    }
                                                }
                                                Text(
                                                    text = if (approved) "Accepted" else "Pending",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (approved) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(120.dp)
                                                        .background(Color(0xFF0F0E12), RoundedCornerShape(6.dp))
                                                        .border(BorderStroke(1.dp, Color(0xFF231E29)), RoundedCornerShape(6.dp))
                                                        .padding(4.dp)
                                                ) {
                                                    DiffView(
                                                        diffLines = computeSimpleLineDiff(change.originalContent, change.proposedContent),
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.confirmPendingFileChangeBatch() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                modifier = Modifier.testTag("confirm_batch_file_changes_button")
                            ) { Text("Confirm", color = Color.White) }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { viewModel.rejectAllPendingFileChanges() },
                                modifier = Modifier.testTag("reject_all_batch_file_changes_button")
                            ) { Text("Reject All") }
                        }
                    )
                }

                val configuration = LocalConfiguration.current
                val isExpanded = configuration.screenWidthDp >= 600

                val navItems = listOf(
                    NavigationItem("session", "Session", Icons.Rounded.Terminal),
                    NavigationItem("sprints", "Sprints", Icons.Rounded.AutoAwesomeMotion),
                    NavigationItem("manage", "Manage", Icons.Rounded.Dashboard),
                    NavigationItem("workspace", "Workspace", Icons.Rounded.Folder),
                    NavigationItem("analytics", "Analytics", Icons.Rounded.Analytics),
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
                                "sprints" -> SprintPlannerScreen(
                                    viewModel = viewModel,
                                    onNavigateToSession = { taskId ->
                                        activeTab = "session"
                                        viewModel.selectTask(taskId)
                                    }
                                )
                                "manage" -> ManageScreen(viewModel = viewModel)
                                "workspace" -> WorkspaceScreen(viewModel = viewModel)
                                "analytics" -> AnalyticsScreen(viewModel = viewModel)
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
