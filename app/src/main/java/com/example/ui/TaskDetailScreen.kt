package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SwarmConfig
import com.example.data.SwarmTask
import com.example.data.TaskStep
import com.example.viewmodel.SwarmViewModel

@Composable
fun TaskDetailScreen(
    viewModel: SwarmViewModel,
    initialSwarm: SwarmConfig?,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.allTasks.collectAsState()
    val swarms by viewModel.allSwarmConfigs.collectAsState()
    val selectedTaskId by viewModel.selectedTaskId.collectAsState()
    val activeSteps by viewModel.activeSteps.collectAsState()
    val isExecuting by viewModel.isExecutingTask.collectAsState()

    var selectedSwarm by remember { mutableStateOf<SwarmConfig?>(null) }
    var promptInput by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(initialSwarm, swarms) {
        if (initialSwarm != null) {
            selectedSwarm = initialSwarm
        } else if (swarms.isNotEmpty() && selectedSwarm == null) {
            selectedSwarm = swarms.firstOrNull()
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Left Column: Historic Tasks List (35% width)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(12.dp)
        ) {
            Text(
                text = "EXECUTION HISTORIES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            if (tasks.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text(
                        text = "No prior runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(tasks, key = { it.id }) { task ->
                        HistoryTaskRow(
                            task = task,
                            isSelected = selectedTaskId == task.id,
                            onClick = { viewModel.selectTask(task.id) },
                            onDelete = { viewModel.deleteTask(task) }
                        )
                    }
                }
            }
        }

        VerticalDivider(color = Color(0xFF1F293D))

        // Right Column: Workspace Console & Task Steps Timeline (65% width)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1.8f)
                .padding(16.dp)
        ) {
            if (selectedTaskId == null) {
                // Task Dispatcher View
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Swarm Workspace Console",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Dispatch a new task or select a past history item from the left.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Task Submission Card
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Selector Dropdown
                            Box {
                                OutlinedButton(
                                    onClick = { dropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth().testTag("select_swarm_dropdown")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(selectedSwarm?.name ?: "Select Swarm Pipeline", fontSize = 13.sp)
                                        Icon(imageVector = Icons.Rounded.ArrowDropDown, contentDescription = null)
                                    }
                                }
                                DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false }
                                ) {
                                    swarms.forEach { swarm ->
                                        DropdownMenuItem(
                                            text = { Text(swarm.name) },
                                            onClick = {
                                                selectedSwarm = swarm
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Prompt field
                            OutlinedTextField(
                                value = promptInput,
                                onValueChange = { promptInput = it },
                                label = { Text("Task Instructions / Prompt") },
                                placeholder = { Text("Describe what the swarm should solve...") },
                                modifier = Modifier.fillMaxWidth().testTag("swarm_prompt_input")
                            )

                            // Submit Button
                            Button(
                                onClick = {
                                    val swarm = selectedSwarm
                                    if (swarm != null && promptInput.isNotEmpty()) {
                                        viewModel.runSwarm(swarm, promptInput)
                                        promptInput = ""
                                    }
                                },
                                enabled = selectedSwarm != null && promptInput.isNotEmpty() && !isExecuting,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().testTag("dispatch_swarm_button")
                            ) {
                                if (isExecuting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black)
                                } else {
                                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = "Run Swarm")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Dispatch Swarm")
                                }
                            }
                        }
                    }
                }
            } else {
                // Steps Timeline View for Selected Task
                val selectedTask = tasks.find { it.id == selectedTaskId }
                
                if (selectedTask != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Task Header
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedTask.swarmName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    
                                    IconButton(
                                        onClick = { viewModel.selectTask(null) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close Workspace", modifier = Modifier.size(16.dp))
                                    }
                                }
                                Text(
                                    text = selectedTask.prompt,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HeaderStatLabel(icon = Icons.Rounded.Schedule, label = "${selectedTask.executionTimeMs / 1000f}s")
                                    HeaderStatLabel(icon = Icons.Rounded.ConfirmationNumber, label = "${selectedTask.tokenUsage} tkn")
                                    
                                    val statColor = when (selectedTask.status) {
                                        "Completed" -> Color(0xFF4ADE80)
                                        "Failed" -> Color(0xFFEF4444)
                                        else -> Color(0xFFFACC15)
                                    }
                                    Text(
                                        text = selectedTask.status.uppercase(),
                                        color = statColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        // Steps List
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            items(activeSteps, key = { it.id }) { step ->
                                TaskStepTimelineItem(step)
                            }
                            
                            if (isExecuting && activeSteps.isNotEmpty() && activeSteps.lastOrNull()?.actionType != "FINAL_RESPONSE") {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Text("Swarm processing next agent pipeline step...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderStatLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
fun HistoryTaskRow(
    task: SwarmTask,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (task.status) {
        "Completed" -> Color(0xFF4ADE80)
        "Failed" -> Color(0xFFEF4444)
        else -> Color(0xFFFACC15) // Processing
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF0F0F12))
            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C30), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag("history_item_${task.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1.0f).padding(end = 4.dp)) {
            Text(
                text = task.prompt,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${task.swarmName} · ${task.status}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(24.dp).testTag("delete_task_button_${task.id}")
        ) {
            Icon(imageVector = Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun TaskStepTimelineItem(step: TaskStep) {
    val roleColor = when (step.agentRole.lowercase()) {
        "researcher" -> Color(0xFF3F51B5)
        "programmer" -> Color(0xFF4CAF50)
        "critic" -> Color(0xFFE91E63)
        "executive" -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Timeline Dot & Line left element
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(roleColor)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(80.dp)
                    .background(roleColor.copy(alpha = 0.3f))
            )
        }

        // Timeline Step Content Card
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
            border = BorderStroke(1.dp, Color(0xFF2C2C30))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = step.agentName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = roleColor
                        )
                        Surface(
                            color = roleColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = step.agentRole.uppercase(),
                                color = roleColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = step.actionType,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F0F12), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = step.content,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 18.sp,
                            fontFamily = if (step.content.contains("kotlin") || step.content.contains("```")) FontFamily.Monospace else FontFamily.Default,
                            fontSize = if (step.content.contains("kotlin") || step.content.contains("```")) 11.sp else 13.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}
