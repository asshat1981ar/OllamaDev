package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.rounded.*
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
import com.example.data.Agent
import com.example.viewmodel.SwarmViewModel

private val OLLAMA_CLOUD_MODEL_PRESETS = listOf(
    "gpt-oss:120b", "qwen3-coder:480b", "glm-5.2", "kimi-k2.6", "deepseek-v4-pro", "minimax-m3", "gemma4:26b"
)

@Composable
fun AgentScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val agents by viewModel.allAgents.collectAsState()
    val agentStates by viewModel.agentStates.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var roleInput by remember { mutableStateOf("Researcher") }
    var modelInput by remember { mutableStateOf("llama3:8b") }
    var promptInput by remember { mutableStateOf("") }
    var colorInput by remember { mutableStateOf("#9C27B0") }

    val roles = listOf("Researcher", "Programmer", "Critic", "Executive", "Writer", "Analyst")
    val colors = listOf("#3F51B5", "#4CAF50", "#E91E63", "#FF9800", "#9C27B0", "#00B4D8", "#FF5722")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SWARM PERSONAS & INTENTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Agent Assembly",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("add_agent_button")
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = "Assemble Agent")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Assemble", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Configure agent cognitive directives (System Prompts) and specialize them for sequential or parallel collaboration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            item {
                AgentMetricsConsole(
                    agentStates = agentStates,
                    onClearMetrics = { viewModel.clearAgentMetrics() }
                )
            }

            if (agents.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        Text(
                            text = "No custom agent personas assembled.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                items(agents, key = { it.id }) { agent ->
                    AgentCard(
                        agent = agent,
                        metrics = agentStates[agent.id],
                        onDelete = { viewModel.deleteAgent(agent) }
                    )
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Assemble Swarm Agent") },
                text = {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("Agent Name") },
                                placeholder = { Text("e.g. Helix Code") },
                                modifier = Modifier.fillMaxWidth().testTag("agent_name_input")
                            )
                        }
                        
                        item {
                            Text("Cognitive Specialty / Role:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                roles.forEach { r ->
                                    val isSelected = roleInput == r
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier
                                            .clickable { roleInput = r }
                                            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C30), RoundedCornerShape(4.dp))
                                    ) {
                                        Text(
                                            text = r,
                                            fontSize = 11.sp,
                                            color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        
                        item {
                            OutlinedTextField(
                                value = modelInput,
                                onValueChange = { modelInput = it },
                                label = { Text("Agent Base Model") },
                                placeholder = { Text("e.g. llama3:8b, mistral:7b") },
                                modifier = Modifier.fillMaxWidth().testTag("agent_model_input")
                            )
                        }
                        
                        item {
                            Text("Ollama Cloud Base Model presets:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OLLAMA_CLOUD_MODEL_PRESETS.forEach { model ->
                                    val isSelected = modelInput == model
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier
                                            .clickable { modelInput = model }
                                            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C30), RoundedCornerShape(4.dp))
                                    ) {
                                        Text(
                                            text = model,
                                            fontSize = 11.sp,
                                            color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        
                        item {
                            OutlinedTextField(
                                value = promptInput,
                                onValueChange = { promptInput = it },
                                label = { Text("System Prompt (Cognitive Directives)") },
                                placeholder = { Text("You are an expert coder. Direct outputs to...") },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("agent_prompt_input")
                            )
                        }

                        item {
                            Text("Node Graph Theme Color:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                colors.forEach { c ->
                                    val isSelected = colorInput == c
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color(android.graphics.Color.parseColor(c)))
                                            .border(
                                                width = if (isSelected) 3.dp else 0.dp,
                                                color = if (isSelected) Color.White else Color.Transparent,
                                                shape = CircleShape
                                            )
                                            .clickable { colorInput = c }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (nameInput.isNotEmpty() && promptInput.isNotEmpty()) {
                                viewModel.addAgent(nameInput, roleInput, modelInput, promptInput, colorInput)
                                nameInput = ""
                                promptInput = ""
                                showAddDialog = false
                            }
                        },
                        modifier = Modifier.testTag("submit_agent_button")
                    ) {
                        Text("Assemble")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun AgentCard(
    agent: Agent,
    metrics: com.example.data.AgentMetrics?,
    onDelete: () -> Unit
) {
    val agentColor = try {
        Color(android.graphics.Color.parseColor(agent.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_card_${agent.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(agentColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when (agent.role.lowercase()) {
                            "researcher" -> Icons.Rounded.Search
                            "programmer" -> Icons.Rounded.Terminal
                            "critic" -> Icons.AutoMirrored.Rounded.Grading
                            "executive" -> Icons.Rounded.CheckCircle
                            else -> Icons.Rounded.SmartToy
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = agentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${agent.role} · Model: ${agent.modelName}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isActive = metrics?.isActive == true
                    if (isActive) {
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFF4CAF50)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = (metrics?.status ?: "ACTIVE").uppercase(),
                                color = Color(0xFF4CAF50),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (!agent.isSystemTemplate) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(24.dp).testTag("delete_agent_button_${agent.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Deconstruct Agent",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "SYSTEM",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF2C2C30))
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "COGNITIVE DIRECTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = agentColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = agent.systemPrompt,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (metrics != null && metrics.tasksExecuted > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = Color(0xFF2C2C30).copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PERFORMANCE METRICS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Tasks: ${metrics.tasksExecuted}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Avg Latency: ${String.format("%.2f", metrics.averageDurationMs / 1000f)}s",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Est. Tokens: ${metrics.totalTokensUsed}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentMetricsConsole(
    agentStates: Map<Int, com.example.data.AgentMetrics>,
    onClearMetrics: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_metrics_console"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Analytics,
                        contentDescription = "Analytics",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Centralized Agent State Monitor",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onClearMetrics,
                        modifier = Modifier.size(28.dp).testTag("clear_metrics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Clear Metrics",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Toggle Metrics Console",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                if (agentStates.isEmpty()) {
                    Text(
                        text = "No agents registered in centralized state store.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val activeCount = agentStates.values.count { it.isActive }
                        val totalExecutions = agentStates.values.sumOf { it.tasksExecuted }
                        val totalTokens = agentStates.values.sumOf { it.totalTokensUsed }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("ACTIVE AGENTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text("$activeCount / ${agentStates.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (activeCount > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("TOTAL TASKS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text("$totalExecutions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("EST. TOKENS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text("$totalTokens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        agentStates.values.forEach { metrics ->
                            val color = try {
                                Color(android.graphics.Color.parseColor(metrics.isActive.let { if (it) "#4CAF50" else "#9E9E9E" }))
                            } catch (e: Exception) {
                                MaterialTheme.colorScheme.primary
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                
                                Column(modifier = Modifier.weight(1.2f)) {
                                    Text(
                                        text = metrics.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (metrics.isActive) "Status: ${metrics.status}" else "Status: Idle",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (metrics.isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1.8f)
                                ) {
                                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                                        Text("Tasks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 8.sp)
                                        Text("${metrics.tasksExecuted}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.2f)) {
                                        Text("Latency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 8.sp)
                                        Text("${String.format("%.1f", metrics.averageDurationMs / 1000f)}s", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.3f)) {
                                        Text("Tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 8.sp)
                                        Text("${metrics.totalTokensUsed}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
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
