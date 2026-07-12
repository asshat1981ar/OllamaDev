package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.RegistryServerDetail
import com.example.data.inferServerType
import com.example.data.resolveStreamableHttpUrl
import com.example.viewmodel.SwarmViewModel

@Composable
fun RegistryBrowserDialog(
    viewModel: SwarmViewModel,
    onDismiss: () -> Unit
) {
    val results by viewModel.registryResults.collectAsState()
    val isLoading by viewModel.isRegistryLoading.collectAsState()
    val error by viewModel.registryError.collectAsState()
    val nextCursor by viewModel.registryNextCursor.collectAsState()

    var query by remember { mutableStateOf("") }
    var installedName by remember { mutableStateOf<String?>(null) }

    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 600

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0B0A0E),
            modifier = Modifier
                .fillMaxWidth(if (isExpanded) 0.85f else 0.95f)
                .fillMaxHeight(if (isExpanded) 0.85f else 0.95f)
                .border(BorderStroke(1.dp, Color(0xFF262232)), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "MCP Registry",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Browse and install servers from registry.modelcontextprotocol.io",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search servers") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchRegistry(query) }),
                    trailingIcon = {
                        IconButton(onClick = { viewModel.searchRegistry(query) }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (error != null) {
                    Surface(
                        color = Color(0xFFEF4444).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error ?: "",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (results.isEmpty() && !isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.TravelExplore,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Search the MCP Registry to discover servers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(results, key = { it.name }) { server ->
                            RegistryServerCard(
                                server = server,
                                isInstalling = installedName == server.name && isLoading,
                                onInstall = {
                                    installedName = server.name
                                    viewModel.installRegistryServer(server)
                                }
                            )
                        }

                        if (nextCursor != null) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Button(
                                        onClick = { viewModel.searchRegistry(query, append = true) },
                                        enabled = !isLoading
                                    ) {
                                        if (isLoading) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text("Load more")
                                    }
                                }
                            }
                        }
                    }
                }

                if (isLoading && results.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                if (installedName != null && !isLoading) {
                    LaunchedEffect(installedName) {
                        installedName = null
                    }
                }
            }
        }
    }
}

@Composable
private fun RegistryServerCard(
    server: RegistryServerDetail,
    isInstalling: Boolean,
    onInstall: () -> Unit
) {
    val serverType = server.inferServerType()
    val streamableUrl = server.resolveStreamableHttpUrl()

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141219)),
        border = BorderStroke(1.dp, Color(0xFF262232)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (serverType) {
                                "GitHub" -> Icons.Rounded.AccountTree
                                "Database" -> Icons.Rounded.Storage
                                "Browser" -> Icons.Rounded.TravelExplore
                                "Search" -> Icons.Rounded.Search
                                "Docker" -> Icons.Rounded.Inbox
                                "Slack" -> Icons.Rounded.ChatBubble
                                "Filesystem" -> Icons.Rounded.Folder
                                else -> Icons.Rounded.Hub
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.title ?: server.name.substringAfterLast("/").replace("-", " "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = server.description ?: "No description available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = serverType.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (streamableUrl != null) {
                    Text(
                        text = streamableUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF80CBC4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = "No Streamable-HTTP endpoint",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9800)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onInstall,
                    enabled = streamableUrl != null && !isInstalling,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    if (isInstalling) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Installing…", fontSize = 11.sp)
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Install", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
