package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.McpServer
import com.example.viewmodel.SwarmViewModel

/**
 * Top-level workspace browser driven by a connected filesystem-class MCP server.
 *
 * Lets the user list, search, read, and edit files in the project workspace directly through
 * the companion MCP server without relying on the agentic loop.
 */
@Composable
fun WorkspaceScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val servers by viewModel.mcpServers.collectAsState()
    val workspaceFiles by viewModel.workspaceBrowserFiles.collectAsState()
    val selectedFile by viewModel.selectedWorkspaceFile.collectAsState()
    val fileContent by viewModel.workspaceFileContent.collectAsState()
    val selectedServerId by viewModel.selectedWorkspaceServerId.collectAsState()
    val isLoading by viewModel.isWorkspaceLoading.collectAsState()
    val error by viewModel.workspaceError.collectAsState()
    val fileOutline by viewModel.workspaceFileOutline.collectAsState()

    val filesystemServers = servers.filter { it.type == "Filesystem" }
    val activeServer = filesystemServers.firstOrNull { it.id == selectedServerId }
        ?: filesystemServers.firstOrNull { it.status == "Connected" }

    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 600

    var searchQuery by remember { mutableStateOf("") }
    var editorText by remember(selectedFile) { mutableStateOf(fileContent) }
    var isReadOnly by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createFileName by remember { mutableStateOf("") }
    var showOutline by remember { mutableStateOf(false) }

    LaunchedEffect(fileContent) {
        editorText = fileContent
    }

    LaunchedEffect(activeServer) {
        activeServer?.let { viewModel.selectWorkspaceServer(it.id) }
    }

    LaunchedEffect(activeServer, searchQuery) {
        activeServer?.takeIf { it.status == "Connected" }?.let {
            viewModel.loadWorkspaceFiles(it.id, searchQuery)
        }
    }

    LaunchedEffect(selectedFile, showOutline, activeServer) {
        activeServer?.takeIf { it.status == "Connected" && showOutline && selectedFile != null }?.let {
            viewModel.loadFileOutline(it.id, selectedFile!!)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0A0E))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "MCP WORKSPACE",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Browse and edit files through a connected filesystem MCP server",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter files...", fontSize = 12.sp, color = Color.Gray) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    modifier = Modifier.width(180.dp).height(44.dp).testTag("workspace_search_field"),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                ServerSelector(
                    servers = filesystemServers,
                    selected = activeServer,
                    onSelect = { viewModel.selectWorkspaceServer(it.id) }
                )

                IconButton(
                    onClick = {
                        activeServer?.takeIf { it.status == "Connected" }?.let {
                            viewModel.loadWorkspaceFiles(it.id, searchQuery)
                        }
                    },
                    enabled = activeServer?.status == "Connected" && !isLoading,
                    modifier = Modifier.size(44.dp).testTag("workspace_refresh_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                    }
                }

                IconButton(
                    onClick = { showCreateDialog = true },
                    enabled = activeServer?.status == "Connected" && !isLoading,
                    modifier = Modifier.size(44.dp).testTag("workspace_create_file_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "New file", modifier = Modifier.size(20.dp))
                }
            }
        }

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
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = {
                    showCreateDialog = false
                    createFileName = ""
                },
                title = { Text("Create new file") },
                text = {
                    OutlinedTextField(
                        value = createFileName,
                        onValueChange = { createFileName = it },
                        placeholder = { Text("path/to/file.kt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("workspace_create_filename_field")
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            activeServer?.let { server ->
                                val name = createFileName.trim()
                                if (name.isNotBlank()) {
                                    viewModel.createWorkspaceFile(server.id, name)
                                }
                            }
                            showCreateDialog = false
                            createFileName = ""
                        },
                        enabled = createFileName.trim().isNotBlank(),
                        modifier = Modifier.testTag("workspace_create_confirm_button")
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCreateDialog = false
                            createFileName = ""
                        },
                        modifier = Modifier.testTag("workspace_create_cancel_button")
                    ) { Text("Cancel") }
                }
            )
        }

        if (filesystemServers.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.FolderOpen,
                message = "No filesystem MCP servers connected.",
                hint = "Add the OllamaDev Tools server from the MCP & Skills screen."
            )
            return
        }

        if (activeServer?.status != "Connected") {
            EmptyState(
                icon = Icons.Rounded.LinkOff,
                message = "Selected server is not connected.",
                hint = "Connect a filesystem MCP server and refresh."
            )
            return
        }

        // Main adaptive content
        if (isExpanded) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FileListPanel(
                    files = workspaceFiles,
                    selectedFile = selectedFile,
                    onSelect = { path ->
                        activeServer?.let { viewModel.readWorkspaceFile(it.id, path) }
                    },
                    onDelete = { path ->
                        activeServer?.let { viewModel.deleteWorkspaceFile(it.id, path) }
                    },
                    modifier = Modifier.weight(1f)
                )
                EditorPanel(
                    filePath = selectedFile,
                    text = editorText,
                    isReadOnly = isReadOnly,
                    onReadOnlyChange = { isReadOnly = it },
                    onTextChange = { editorText = it },
                    onSave = {
                        activeServer?.let { server ->
                            selectedFile?.let { path ->
                                viewModel.saveWorkspaceFile(server.id, path, editorText)
                            }
                        }
                    },
                    outline = fileOutline,
                    showOutline = showOutline,
                    onShowOutlineChange = { showOutline = it },
                    modifier = Modifier.weight(2f)
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                var showEditor by remember { mutableStateOf(false) }
                if (!showEditor) {
                    FileListPanel(
                        files = workspaceFiles,
                        selectedFile = selectedFile,
                        onSelect = { path ->
                            activeServer?.let {
                                viewModel.readWorkspaceFile(it.id, path)
                                showEditor = true
                            }
                        },
                        onDelete = { path ->
                            activeServer?.let { viewModel.deleteWorkspaceFile(it.id, path) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    EditorPanel(
                        filePath = selectedFile,
                        text = editorText,
                        isReadOnly = isReadOnly,
                        onReadOnlyChange = { isReadOnly = it },
                        onTextChange = { editorText = it },
                        onBack = { showEditor = false },
                        onSave = {
                            activeServer?.let { server ->
                                selectedFile?.let { path ->
                                    viewModel.saveWorkspaceFile(server.id, path, editorText)
                                }
                            }
                        },
                        outline = fileOutline,
                        showOutline = showOutline,
                        onShowOutlineChange = { showOutline = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerSelector(
    servers: List<McpServer>,
    selected: McpServer?,
    onSelect: (McpServer) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(44.dp).testTag("workspace_server_selector")
        ) {
            Icon(
                imageVector = selected?.let { Icons.Rounded.Folder } ?: Icons.Rounded.LinkOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = selected?.name ?: "No server",
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = if (server.status == "Connected") Icons.Rounded.CheckCircle else Icons.Rounded.LinkOff,
                                contentDescription = null,
                                tint = if (server.status == "Connected") Color(0xFF4CAF50) else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(server.name, fontSize = 13.sp)
                        }
                    },
                    onClick = {
                        onSelect(server)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FileListPanel(
    files: List<String>,
    selectedFile: String?,
    onSelect: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "FILES (${files.size})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.FolderOpen,
                message = "No files found.",
                hint = "Refresh the server or clear the filter."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(files, key = { it }) { path ->
                    val isSelected = path == selectedFile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF1F1B24) else Color(0xFF15131A))
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color(0xFF232029)
                                ),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelect(path) }
                            .padding(10.dp)
                            .testTag("workspace_file_item_$path"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                path.endsWith(".kt") -> Icons.Rounded.Terminal
                                path.endsWith(".json") || path.endsWith(".toml") -> Icons.Rounded.Settings
                                path.endsWith(".md") -> Icons.Rounded.Description
                                else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )

                        if (onDelete != null) {
                            IconButton(
                                onClick = { onDelete(path) },
                                modifier = Modifier.size(24.dp).testTag("workspace_delete_file_$path")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Delete",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(16.dp)
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
private fun EditorPanel(
    filePath: String?,
    text: String,
    isReadOnly: Boolean,
    onReadOnlyChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: (() -> Unit)? = null,
    outline: String = "",
    showOutline: Boolean = false,
    onShowOutlineChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp).testTag("workspace_editor_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                    }
                }
                Column {
                    Text(
                        text = "EDITOR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = filePath ?: "No file selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (filePath == null) Color.Gray else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Outline", fontSize = 11.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = showOutline,
                        onCheckedChange = onShowOutlineChange,
                        modifier = Modifier.testTag("workspace_outline_switch")
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read-only", fontSize = 11.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = isReadOnly,
                        onCheckedChange = onReadOnlyChange,
                        modifier = Modifier.testTag("workspace_readonly_switch")
                    )
                }
                Button(
                    onClick = onSave,
                    enabled = filePath != null && !isReadOnly,
                    modifier = Modifier.height(34.dp).testTag("workspace_save_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filePath == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select a file from the list to edit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(if (showOutline) 2f else 1f)
                        .fillMaxWidth()
                        .background(Color(0xFF0F0E12), RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, Color(0xFF231E29)), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        readOnly = isReadOnly,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFECEFF1),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag("workspace_editor_textarea")
                    )
                }

                if (showOutline) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF0F0E12), RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, Color(0xFF231E29)), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = outline.ifBlank { "Loading outline..." },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFB0B0B0),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .testTag("workspace_outline_text")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    hint: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.Gray.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Color.Gray.copy(alpha = 0.6f))
        }
    }
}
