package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.data.Agent
import com.example.data.AgentMetrics
import com.example.data.OllamaNode
import com.example.data.SwarmConfig
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    viewModel: SwarmViewModel,
    onNavigateToSwarm: (SwarmConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val nodes by viewModel.allNodes.collectAsState()
    val agents by viewModel.allAgents.collectAsState()
    val swarms by viewModel.allSwarmConfigs.collectAsState()
    val isExecuting by viewModel.isExecutingTask.collectAsState()
    val agentStates by viewModel.agentStates.collectAsState()
    
    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 600

    if (isExpanded) {
        // Master-Detail / Side-by-Side layout for tablets/foldables (Adaptive Layout Guideline)
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left Pane: Swarm Graph & Stats & Agent Status Grid
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DashboardHeader()
                StatsSummaryRow(nodes, isExecuting)
                AnalyticsSavingsRow(viewModel)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "ACTIVE SWARM PIPELINE GRAPH",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SwarmActiveGraph(agents = agents, isExecuting = isExecuting)
                    }
                }
                AgentStatusSection(agents = agents, agentStates = agentStates)
            }

            // Right Pane: Swarm Selection & Peer Nodes List
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SwarmConfigurationsSection(swarms, onNavigateToSwarm)
                NodesStatusSection(nodes, onRefresh = { viewModel.refreshNodes() })
            }
        }
    } else {
        // Standard vertical scrolling list for compact devices
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                DashboardHeader()
            }

            item {
                StatsSummaryRow(nodes, isExecuting)
            }

            item {
                AnalyticsSavingsRow(viewModel)
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "ACTIVE SWARM PIPELINE GRAPH",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SwarmActiveGraph(agents = agents, isExecuting = isExecuting)
                    }
                }
            }

            item {
                AgentStatusSection(agents = agents, agentStates = agentStates)
            }

            item {
                SwarmConfigurationsSection(swarms, onNavigateToSwarm)
            }

            item {
                NodesStatusSection(nodes, onRefresh = { viewModel.refreshNodes() })
            }
        }
    }
}

@Composable
fun DashboardHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "DECENTRALIZED SWARM",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Orchestration Center",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Manage cooperative on-device LLMs & local socket peers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun StatsSummaryRow(nodes: List<OllamaNode>, isExecuting: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val onlineCount = nodes.count { node -> node.status == "Online" }
        
        StatCard(
            title = "Swarm Peers",
            value = "$onlineCount / ${nodes.size}",
            icon = Icons.Rounded.Share,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "Swarm State",
            value = if (isExecuting) "Coordinating" else "Standby",
            icon = Icons.Rounded.Hub,
            tint = if (isExecuting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "Battery Draw",
            value = if (isExecuting) "0.08%/s" else "Optimal",
            icon = Icons.Rounded.BatteryChargingFull,
            tint = if (isExecuting) Color(0xFFFACC15) else Color(0xFF4ADE80),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SwarmActiveGraph(agents: List<Agent>, isExecuting: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "token_flow")
    val flowProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(12.dp))
    ) {
        // Draw the connecting neural links on Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val padding = 60f
            
            // Map 4 nodes on the canvas
            val nodeCount = 4
            val points = List(nodeCount) { i ->
                val x = padding + (width - padding * 2) * (i.toFloat() / (nodeCount - 1))
                val y = height / 2 + if (i % 2 == 0) -40f else 40f
                Offset(x, y)
            }

            // Draw connecting lines with glowing gradients
            for (i in 0 until points.size - 1) {
                val start = points[i]
                val end = points[i + 1]

                // Static dotted connection
                drawLine(
                    color = Color(0x33D0BCFF),
                    start = start,
                    end = end,
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )

                // Animated glow token passing between agents
                if (isExecuting) {
                    val tokenX = start.x + (end.x - start.x) * flowProgress
                    val tokenY = start.y + (end.y - start.y) * flowProgress
                    drawCircle(
                        color = Color(0xFFD0BCFF),
                        radius = 8f,
                        center = Offset(tokenX, tokenY)
                    )
                    drawCircle(
                        color = Color(0x66D0BCFF),
                        radius = 16f,
                        center = Offset(tokenX, tokenY)
                    )
                }
            }
        }

        // Overlay Interactive Node Composables directly over the Canvas
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val width = maxWidth
            val height = maxHeight
            val padding = 20.dp
            
            val displayAgents = agents.take(4).ifEmpty {
                listOf(
                    Agent(name = "Researcher", role = "Researcher", modelName = "llama3", systemPrompt = "", colorHex = "#3F51B5"),
                    Agent(name = "Programmer", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#4CAF50"),
                    Agent(name = "Critic", role = "Critic", modelName = "mistral", systemPrompt = "", colorHex = "#E91E63"),
                    Agent(name = "Executive", role = "Executive", modelName = "mistral", systemPrompt = "", colorHex = "#FF9800")
                )
            }

            displayAgents.forEachIndexed { index, agent ->
                val biasX = index.toFloat() / (displayAgents.size - 1)
                val offsetValY = if (index % 2 == 0) -18.dp else 18.dp
                
                Box(
                    modifier = Modifier
                        .align(
                            when (index) {
                                0 -> Alignment.CenterStart
                                1 -> Alignment.Center
                                2 -> Alignment.Center
                                else -> Alignment.CenterEnd
                            }
                        )
                        .offset(
                            x = when (index) {
                                0 -> 16.dp
                                1 -> (-30).dp
                                2 -> 30.dp
                                else -> (-16).dp
                            },
                            y = offsetValY
                        )
                ) {
                    AgentNodeBadge(agent, index + 1, isExecuting)
                }
            }
        }
    }
}

@Composable
fun AgentNodeBadge(agent: Agent, nodeNum: Int, isAnimating: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val scale = if (isAnimating) pulseScale else 1f
    val agentColor = try {
        Color(android.graphics.Color.parseColor(agent.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(90.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF0F0F12))
                .border(2.dp * scale, agentColor, CircleShape)
                .padding(4.dp)
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
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = agent.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = agent.modelName.substringBefore(":"),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SwarmConfigurationsSection(
    swarms: List<SwarmConfig>,
    onNavigateToSwarm: (SwarmConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ACTIVE SWARM CONFIGS",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${swarms.size} Available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            if (swarms.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                ) {
                    Text(
                        text = "No custom swarms found. Creating Templates...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    swarms.forEach { swarm ->
                        SwarmRowItem(swarm = swarm, onClick = { onNavigateToSwarm(swarm) })
                    }
                }
            }
        }
    }
}

@Composable
fun SwarmRowItem(swarm: SwarmConfig, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2B2930))
            .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("swarm_config_item_${swarm.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val modeIcon = when (swarm.coordinationMode) {
            "SEQUENTIAL" -> Icons.Rounded.FormatListNumbered
            "PEER_TO_PEER" -> Icons.AutoMirrored.Rounded.CompareArrows
            "CONSENSUS_VOTE" -> Icons.Rounded.HowToVote
            else -> Icons.Rounded.DynamicFeed
        }
        
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = modeIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = swarm.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = swarm.description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun NodesStatusSection(nodes: List<OllamaNode>, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DECENTRALIZED NODE REGISTRY",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(24.dp).testTag("refresh_nodes_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh Nodes Status",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nodes.forEach { node ->
                    NodeMiniRow(node)
                }
            }
        }
    }
}

@Composable
fun NodeMiniRow(node: OllamaNode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1C1B1F))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Column {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = node.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        val statusColor = when (node.status) {
            "Online" -> Color(0xFF4ADE80)
            "Offline" -> Color(0xFFEF4444)
            else -> Color(0xFFFACC15) // Connecting
        }
        
        Surface(
            color = statusColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.border(0.5.dp, statusColor, RoundedCornerShape(4.dp))
        ) {
            Text(
                text = node.status.uppercase(),
                color = statusColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("node_status_${node.id}")
            )
        }
    }
}

@Composable
fun AgentStatusSection(
    agents: List<Agent>,
    agentStates: Map<Int, AgentMetrics>
) {
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp >= 900 -> 3
        configuration.screenWidthDp >= 600 -> 2
        else -> 1
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0xFF2C2C30))
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
                        imageVector = Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "AGENT SWARM STATUS",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val activeCount = agentStates.values.count { it.isActive }
                Surface(
                    color = if (activeCount > 0) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, if (activeCount > 0) Color(0xFF4CAF50) else Color.Gray),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "$activeCount ACTIVE",
                        color = if (activeCount > 0) Color(0xFF4CAF50) else Color.Gray,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            if (agents.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                ) {
                    Text(
                        text = "No agents found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                val chunkedAgents = agents.chunked(columns)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    chunkedAgents.forEach { rowAgents ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowAgents.forEach { agent ->
                                val metrics = agentStates[agent.id]
                                DashboardAgentCard(
                                    agent = agent,
                                    metrics = metrics,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Spacer placeholder for grid alignment
                            val emptySlots = columns - rowAgents.size
                            if (emptySlots > 0) {
                                repeat(emptySlots) {
                                    Spacer(modifier = Modifier.weight(1f))
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
fun DashboardAgentCard(
    agent: Agent,
    metrics: AgentMetrics?,
    modifier: Modifier = Modifier
) {
    val agentColor = try {
        Color(android.graphics.Color.parseColor(agent.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    val isActive = metrics?.isActive == true
    
    // Pulse animation for active state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_border")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        modifier = modifier
            .testTag("dashboard_agent_card_${agent.id}"),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1C1B1F) // ImmersiveSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) agentColor.copy(alpha = borderAlpha) else Color(0xFF2C2C30)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F0F12))
                            .border(1.dp, agentColor.copy(alpha = 0.5f), CircleShape)
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
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = agent.role.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Active status indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Current status text
            Text(
                text = if (isActive) "Status: ${metrics?.status}" else "Status: Standby",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Model: ${agent.modelName.substringBefore(":")}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (metrics != null && metrics.tasksExecuted > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF2C2C30).copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("TASKS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 7.sp)
                        Text("${metrics.tasksExecuted}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("AVG LATENCY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 7.sp)
                        Text("${String.format("%.1f", metrics.averageDurationMs / 1000f)}s", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("TOKENS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 7.sp)
                        Text("${metrics.totalTokensUsed}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AnalyticsSavingsRow(viewModel: SwarmViewModel) {
    val totalTokens by viewModel.totalTokensUsed.collectAsState()
    val totalSavings by viewModel.totalCostSavingsUsd.collectAsState()
    val totalSandboxRuns by viewModel.totalSandboxRuns.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            title = "Tokens Processed",
            value = if (totalTokens >= 1000) String.format("%.1fk", totalTokens / 1000f) else "$totalTokens",
            icon = Icons.Rounded.Analytics,
            tint = Color(0xFF818CF8),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "API Cost Savings",
            value = String.format("$%.3f", totalSavings),
            icon = Icons.Rounded.Paid,
            tint = Color(0xFF34D399),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "Sandbox Compiles",
            value = "$totalSandboxRuns",
            icon = Icons.Rounded.Computer,
            tint = Color(0xFFFB923C),
            modifier = Modifier.weight(1f)
        )
    }
}
