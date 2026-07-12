package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ClaudeSkill
import com.example.data.McpServer
import com.example.viewmodel.SwarmViewModel

@Composable
fun McpSkillsScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val mcpServers by viewModel.mcpServers.collectAsState()
    val claudeSkills by viewModel.claudeSkills.collectAsState()
    val recommendedSkills by viewModel.recommendedSkills.collectAsState()
    val agents by viewModel.allAgents.collectAsState()
    val mcpError by viewModel.mcpError.collectAsState()

    var showAddServerDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All") }

    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 800

    val categories = listOf("All", "Development", "Automation", "Analysis", "Productivity")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0A0E))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Welcome and Header Block
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MCP & CLAUDE SKILLS INTEGRATION",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Source Model Context Protocol servers & manage intelligent skill capabilities",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Button(
                onClick = { showAddServerDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("add_mcp_server_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add MCP Server", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (mcpError != null) {
            Surface(
                color = Color(0xFFEF4444).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFFEF4444)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = mcpError ?: "",
                    color = Color(0xFFEF4444),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Claude Skills Recommendations Panel
        RecommendationsSection(
            recommendedSkills = recommendedSkills,
            mcpServers = mcpServers,
            hasProgrammerAgent = agents.any { it.role.lowercase().contains("programmer") },
            onToggleSkill = { viewModel.toggleClaudeSkill(it) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Left Column: MCP Servers
                Column(modifier = Modifier.weight(1f)) {
                    McpServersSection(
                        mcpServers = mcpServers,
                        onToggleStatus = { viewModel.toggleMcpServerStatus(it) },
                        onDeleteServer = { viewModel.deleteMcpServer(it) }
                    )
                }

                // Right Column: Claude Skills
                Column(modifier = Modifier.weight(1.2f)) {
                    ClaudeSkillsSection(
                        claudeSkills = claudeSkills,
                        mcpServers = mcpServers,
                        categories = categories,
                        selectedCategory = selectedCategory,
                        onCategorySelect = { selectedCategory = it },
                        onToggleSkill = { viewModel.toggleClaudeSkill(it) }
                    )
                }
            }
        } else {
            // Stacked for narrow layouts
            McpServersSection(
                mcpServers = mcpServers,
                onToggleStatus = { viewModel.toggleMcpServerStatus(it) },
                onDeleteServer = { viewModel.deleteMcpServer(it) }
            )

            Spacer(modifier = Modifier.height(20.dp))

            ClaudeSkillsSection(
                claudeSkills = claudeSkills,
                mcpServers = mcpServers,
                categories = categories,
                selectedCategory = selectedCategory,
                onCategorySelect = { selectedCategory = it },
                onToggleSkill = { viewModel.toggleClaudeSkill(it) }
            )
        }
    }

    if (showAddServerDialog) {
        var serverName by remember { mutableStateOf("") }
        var serverType by remember { mutableStateOf("GitHub") } // GitHub, Docker, Database, Slack, Filesystem
        var sourceUrl by remember { mutableStateOf("") }
        var configJson by remember { mutableStateOf("{}") }
        var authToken by remember { mutableStateOf("") }

        val serverTypes = listOf("GitHub", "Docker", "Database", "Slack", "Filesystem")

        AlertDialog(
            onDismissRequest = { showAddServerDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Source MCP Server", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = { Text("Server Name (e.g. Brave Search API)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("mcp_name_input")
                    )

                    Column {
                        Text("Server Type / Transport Mode", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            serverTypes.forEach { type ->
                                FilterChip(
                                    selected = serverType == type,
                                    onClick = { serverType = type },
                                    label = { Text(type, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = sourceUrl,
                        onValueChange = { sourceUrl = it },
                        label = { Text("MCP Endpoint URL (Streamable HTTP)") },
                        placeholder = { Text("https://your-mcp-server.example.com/mcp") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("mcp_source_input")
                    )
                    Text(
                        "Must be a real MCP server reachable over HTTP(S) and supporting the Streamable HTTP transport -- local/stdio servers cannot be reached from this app.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )

                    OutlinedTextField(
                        value = authToken,
                        onValueChange = { authToken = it },
                        label = { Text("Auth Token (optional, sent as Bearer)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("mcp_token_input")
                    )

                    OutlinedTextField(
                        value = configJson,
                        onValueChange = { configJson = it },
                        label = { Text("Configuration JSON parameters") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag("mcp_params_input"),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (serverName.isNotBlank() && sourceUrl.isNotBlank()) {
                            viewModel.addMcpServer(serverName, serverType, sourceUrl, configJson, authToken)
                            showAddServerDialog = false
                        }
                    },
                    modifier = Modifier.testTag("mcp_confirm_button")
                ) {
                    Text("Integrate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddServerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RecommendationsSection(
    recommendedSkills: List<ClaudeSkill>,
    mcpServers: List<McpServer>,
    hasProgrammerAgent: Boolean,
    onToggleSkill: (ClaudeSkill) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141219)),
        border = BorderStroke(1.dp, Color(0xFF262232)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = "CLAUDE-STYLE RECOMMENDATION ENGINE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (recommendedSkills.isEmpty()) {
                Text(
                    text = "No custom recommendations at the moment. Add more skills to view recommendations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(recommendedSkills) { skill ->
                        val requiresServer = skill.requiredMcpServerType != "None"
                        val serverActive = mcpServers.any { it.type == skill.requiredMcpServerType && it.status == "Connected" }
                        val isEnabled = skill.isEnabled

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1824)),
                            border = BorderStroke(1.dp, if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color(0xFF2D283A)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .width(280.dp)
                                .clickable { onToggleSkill(skill) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = skill.category.uppercase(),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { onToggleSkill(skill) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier.scale(0.7f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = skill.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 14.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Recommendation feedback string
                                val reason = when {
                                    skill.name.contains("Linter") && hasProgrammerAgent -> 
                                        "Recommended: Your 'Byte Code' Programmer Agent can automate lints using this skill."
                                    requiresServer && serverActive -> 
                                        "Recommended: Required active transport link '${skill.requiredMcpServerType}' is online!"
                                    requiresServer -> 
                                        "Requires '${skill.requiredMcpServerType}' MCP server setup to activate."
                                    else -> 
                                        "Recommended: Optimized workflow helper skill."
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (requiresServer && !serverActive) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = if (requiresServer && !serverActive) Color(0xFFFF9800) else Color(0xFF4CAF50),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = reason,
                                        fontSize = 8.sp,
                                        color = if (requiresServer && !serverActive) Color(0xFFFF9800) else Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
fun McpServersSection(
    mcpServers: List<McpServer>,
    onToggleStatus: (McpServer) -> Unit,
    onDeleteServer: (McpServer) -> Unit
) {
    Column {
        Text(
            text = "SOURCED MCP SERVERS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (mcpServers.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141219)),
                border = BorderStroke(1.dp, Color(0xFF231F2E))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Extension, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No MCP Servers connected yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        } else {
            mcpServers.forEach { server ->
                val isConnected = server.status == "Connected"
                val isConnecting = server.status == "Connecting"
                val isError = server.status == "Error"

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141219)),
                    border = BorderStroke(1.dp, if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color(0xFF262232)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = when (server.type) {
                                        "GitHub" -> Icons.Rounded.AccountTree
                                        "Docker" -> Icons.Rounded.Inbox
                                        "Database" -> Icons.Rounded.Storage
                                        else -> Icons.Rounded.Extension
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                        if (isError) {
                                            Surface(
                                                color = Color(0xFFEF4444).copy(alpha = 0.15f),
                                                border = BorderStroke(1.dp, Color(0xFFEF4444)),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "ERROR",
                                                    color = Color(0xFFEF4444),
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        "${server.type} • ${if (isConnected) "${server.toolsCount} available tools" else server.status}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { onToggleStatus(server) },
                                    enabled = !isConnecting,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isConnected) Color(0xFFE91E63).copy(alpha = 0.15f) else Color(0xFF4CAF50).copy(alpha = 0.15f),
                                        contentColor = if (isConnected) Color(0xFFE91E63) else Color(0xFF4CAF50)
                                    ),
                                    border = BorderStroke(1.dp, if (isConnected) Color(0xFFE91E63) else Color(0xFF4CAF50)),
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    if (isConnecting) {
                                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Connecting…", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Text(if (isConnected) "Disconnect" else "Connect", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                IconButton(
                                    onClick = { onDeleteServer(server) },
                                    modifier = Modifier.size(26.dp).testTag("delete_server_${server.id}")
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Source URL
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0D0B12), RoundedCornerShape(4.dp))
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = server.sourceUrl,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color.LightGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClaudeSkillsSection(
    claudeSkills: List<ClaudeSkill>,
    mcpServers: List<McpServer>,
    categories: List<String>,
    selectedCategory: String,
    onCategorySelect: (String) -> Unit,
    onToggleSkill: (ClaudeSkill) -> Unit
) {
    Column {
        Text(
            text = "CLAUDE SKILLSET CAPABILITIES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Categories Row Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            categories.forEach { cat ->
                val isSelected = cat == selectedCategory
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelect(cat) },
                    label = { Text(cat, fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val filteredSkills = if (selectedCategory == "All") claudeSkills else claudeSkills.filter { it.category == selectedCategory }

        if (filteredSkills.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141219)),
                border = BorderStroke(1.dp, Color(0xFF231F2E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No skills found under this category.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        } else {
            filteredSkills.forEach { skill ->
                var isExpanded by remember { mutableStateOf(false) }
                val isEnabled = skill.isEnabled
                
                val requiresServer = skill.requiredMcpServerType != "None"
                val serverActive = mcpServers.any { it.type == skill.requiredMcpServerType && it.status == "Connected" }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141219)),
                    border = BorderStroke(
                        1.dp,
                        if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color(0xFF231F2E)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                    
                                    if (requiresServer) {
                                        Surface(
                                            color = if (serverActive) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFFF9800).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "${skill.requiredMcpServerType} MCP",
                                                color = if (serverActive) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                                fontSize = 7.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { onToggleSkill(skill) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.testTag("skill_toggle_${skill.id}")
                            )
                        }

                        if (skill.usageExample.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isExpanded = !isExpanded }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Interactive Usage Example", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0B0A0E), RoundedCornerShape(6.dp))
                                        .border(BorderStroke(1.dp, Color(0xFF1F1B2A)), RoundedCornerShape(6.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = skill.usageExample,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFF80CBC4)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Simple helper to scale elements easily in Compose
private fun Modifier.scale(scale: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
            placeable.placeRelativeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
)
