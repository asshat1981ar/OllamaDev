package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.OllamaNode
import com.example.viewmodel.SwarmViewModel

@Composable
fun NodeScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val nodes by viewModel.allNodes.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("http://") }
    var modelsInput by remember { mutableStateOf("llama3, mistral") }
    var apiKeyInput by remember { mutableStateOf("") }
    var showValidationError by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .testTag("node_list"),
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
                            text = "DECENTRALIZED PEER CORES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Node Registry",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("add_node_button")
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = "Add Node")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Node", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Manage cluster endpoints where your swarms can deploy sub-tasks or query localized LLMs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            item {
                OllamaCloudWizardCard(
                    onAddCloudNode = {
                        nameInput = "My Ollama Cloud Core"
                        urlInput = "https://ollama.com"
                        modelsInput = "gpt-oss:120b, qwen3-coder:480b, glm-5.2"
                        apiKeyInput = ""
                        showAddDialog = true
                    }
                )
            }

            if (nodes.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        Text(
                            text = "No active node endpoints registered.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                items(nodes, key = { it.id }) { node ->
                    NodeCard(
                        node = node,
                        onPing = { viewModel.pingNode(node) },
                        onDelete = { viewModel.deleteNode(node) }
                    )
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                    showValidationError = false
                },
                title = { Text("Register Swarm Node") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Node Name") },
                            placeholder = { Text("e.g. GPU Edge Peer") },
                            modifier = Modifier.fillMaxWidth().testTag("node_name_input")
                        )
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Node API Endpoint") },
                            placeholder = { Text("http://192.168.1.100:11434") },
                            modifier = Modifier.fillMaxWidth().testTag("node_url_input")
                        )
                        OutlinedTextField(
                            value = modelsInput,
                            onValueChange = { modelsInput = it },
                            label = { Text("Available Models (comma-separated)") },
                            placeholder = { Text("llama3, mistral, phi3") },
                            modifier = Modifier.fillMaxWidth().testTag("node_models_input")
                        )
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("API Key (optional)") },
                            placeholder = { Text("Required for Ollama Cloud endpoints") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("node_api_key_input")
                        )
                        if (showValidationError) {
                            Text(
                                text = "Node name and URL are required.",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("node_validation_error")
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (nameInput.isNotEmpty() && urlInput.isNotEmpty()) {
                                viewModel.addNode(nameInput, urlInput, modelsInput, apiKeyInput)
                                nameInput = ""
                                urlInput = "http://"
                                modelsInput = "llama3, mistral"
                                apiKeyInput = ""
                                showValidationError = false
                                showAddDialog = false
                            } else {
                                showValidationError = true
                            }
                        },
                        modifier = Modifier.testTag("submit_node_button")
                    ) {
                        Text("Add Node")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showAddDialog = false
                        showValidationError = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun NodeCard(
    node: OllamaNode,
    onPing: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (node.status) {
        "Online" -> Color(0xFF4ADE80)
        "Offline" -> Color(0xFFEF4444)
        else -> Color(0xFFFACC15) // Connecting
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("node_card_${node.id}"),
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = node.url,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (node.status == "Online" && node.latencyMs > 0) {
                        Text(
                            text = "${node.latencyMs} ms",
                            color = Color(0xFF4ADE80),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color(0xFF4ADE80).copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, Color(0xFF4ADE80).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.border(0.5.dp, statusColor, RoundedCornerShape(4.dp))
                    ) {
                        Text(
                            text = node.status.uppercase(),
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    IconButton(
                        onClick = onPing,
                        enabled = node.status != "Connecting",
                        modifier = Modifier.size(24.dp).testTag("ping_node_button_${node.id}")
                    ) {
                        if (node.status == "Connecting") {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Ping Node",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp).testTag("delete_node_button_${node.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove Node",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF2C2C30))
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "AVAILABLE MODELS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold
                )
                
                val models = remember(node.availableModels) {
                    node.availableModels.split(",").map { it.trim() }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    models.forEach { model ->
                        key(model) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = model,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OllamaCloudWizardCard(
    onAddCloudNode: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Ollama Cloud™ Setup",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = Color(0xFF4ADE80).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.border(0.5.dp, Color(0xFF4ADE80), RoundedCornerShape(4.dp))
                ) {
                    Text(
                        text = "READY",
                        color = Color(0xFF4ADE80),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Zero local downloads or memory strain. Default agent personas are pre-configured to automatically query remote GPU instances via Ollama Cloud's low-latency endpoints.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddCloudNode,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Cloud, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Register Cloud Gateway", fontSize = 11.sp)
                }
            }
        }
    }
}
