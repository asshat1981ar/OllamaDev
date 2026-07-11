package com.example.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import com.example.data.WorkspaceFile
import com.example.data.ChatMessage
import com.example.data.GitCommit
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.launch

@Composable
fun IdeWorkspaceScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val files by viewModel.workspaceFiles.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val messages by viewModel.chatMessages.collectAsState()
    val commits by viewModel.gitCommits.collectAsState()
    
    val repoName by viewModel.gitRepoName.collectAsState()
    val codename by viewModel.gitCodename.collectAsState()
    val isSynced by viewModel.isGitSynced.collectAsState()
    val isSyncing by viewModel.isGitSyncing.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var activeSubTab by remember { mutableStateOf("files") } // "files", "git", "chat"
    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 800

    // Set default selected file on launch if none selected
    LaunchedEffect(files) {
        if (selectedFile == null && files.isNotEmpty()) {
            viewModel.selectFile(files.firstOrNull())
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
                val fileName = getFileName(context, uri) ?: "uploaded_file.txt"
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val content = inputStream.bufferedReader().use { it.readText() }
                    viewModel.uploadFile(fileName, content)
                }
            } catch (e: Exception) {
                // Squelch or show toast in real implementation
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0A0E))
    ) {
        if (isExpanded) {
            // Three-pane layout for large screens: Left Sidebar (Files & Git), Middle Pane (Code Editor), Right Sidebar (Chat)
            
            // Pane 1: File Browser & Github (25% weight)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(BorderStroke(1.dp, Color(0xFF1F1929)), RoundedCornerShape(0.dp))
                    .padding(12.dp)
            ) {
                // Split Browser tabs
                TabRow(
                    selectedTabIndex = if (activeSubTab == "files") 0 else 1,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = activeSubTab == "files",
                        onClick = { activeSubTab = "files" },
                        text = { Text("Explorer", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("explorer_tab_files")
                    )
                    Tab(
                        selected = activeSubTab == "git",
                        onClick = { activeSubTab = "git" },
                        text = { Text("GitHub", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("explorer_tab_git")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (activeSubTab == "files") {
                        FileBrowserSection(
                            files = files,
                            selectedFile = selectedFile,
                            onFileSelect = { viewModel.selectFile(it) },
                            onFileDelete = { viewModel.deleteFile(it) },
                            onUploadClick = { filePickerLauncher.launch("*/*") },
                            onNewFileCreate = { name, code -> viewModel.createFile(name, code) }
                        )
                    } else {
                        GithubIntegrationSection(
                            repoName = repoName,
                            codename = codename,
                            commits = commits,
                            isSynced = isSynced,
                            isSyncing = isSyncing,
                            onSettingsUpdate = { repo, code -> viewModel.updateGitSettings(repo, code) },
                            onCommit = { viewModel.commitChanges(it) },
                            onPush = { viewModel.pushToGit() }
                        )
                    }
                }
            }

            // Pane 2: Code Editor (45% weight)
            Column(
                modifier = Modifier
                    .weight(1.8f)
                    .fillMaxHeight()
                    .border(BorderStroke(1.dp, Color(0xFF1F1929)), RoundedCornerShape(0.dp))
                    .padding(12.dp)
            ) {
                CodeEditorSection(
                    viewModel = viewModel,
                    selectedFile = selectedFile,
                    isSynced = isSynced,
                    onSave = { id, content -> viewModel.saveFile(id, content) }
                )
            }

            // Pane 3: Chat Pane (30% weight)
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .padding(12.dp)
            ) {
                ChatPaneSection(
                    messages = messages,
                    selectedFile = selectedFile,
                    onSendMessage = { viewModel.sendChatMessage(it) },
                    onClearChat = { viewModel.clearChat() }
                )
            }
        } else {
            // Tablet/Phone compact: Show tabs to switch active views
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(
                    selectedTabIndex = when (activeSubTab) {
                        "files" -> 0
                        "editor" -> 1
                        "git" -> 2
                        else -> 3
                    },
                    containerColor = Color(0xFF15131A),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = activeSubTab == "files",
                        onClick = { activeSubTab = "files" },
                        text = { Text("Files", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("compact_tab_files")
                    )
                    Tab(
                        selected = activeSubTab == "editor",
                        onClick = { activeSubTab = "editor" },
                        text = { Text("Editor", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("compact_tab_editor")
                    )
                    Tab(
                        selected = activeSubTab == "git",
                        onClick = { activeSubTab = "git" },
                        text = { Text("GitHub", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("compact_tab_git")
                    )
                    Tab(
                        selected = activeSubTab == "chat",
                        onClick = { activeSubTab = "chat" },
                        text = { Text("Chat", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Rounded.Chat, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("compact_tab_chat")
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(12.dp)
                ) {
                    when (activeSubTab) {
                        "files" -> {
                            FileBrowserSection(
                                files = files,
                                selectedFile = selectedFile,
                                onFileSelect = {
                                    viewModel.selectFile(it)
                                    activeSubTab = "editor" // auto switch to editor
                                },
                                onFileDelete = { viewModel.deleteFile(it) },
                                onUploadClick = { filePickerLauncher.launch("*/*") },
                                onNewFileCreate = { name, code -> viewModel.createFile(name, code) }
                            )
                        }
                        "editor" -> {
                            CodeEditorSection(
                                viewModel = viewModel,
                                selectedFile = selectedFile,
                                isSynced = isSynced,
                                onSave = { id, content -> viewModel.saveFile(id, content) }
                            )
                        }
                        "git" -> {
                            GithubIntegrationSection(
                                repoName = repoName,
                                codename = codename,
                                commits = commits,
                                isSynced = isSynced,
                                isSyncing = isSyncing,
                                onSettingsUpdate = { repo, code -> viewModel.updateGitSettings(repo, code) },
                                onCommit = { viewModel.commitChanges(it) },
                                onPush = { viewModel.pushToGit() }
                            )
                        }
                        "chat" -> {
                            ChatPaneSection(
                                messages = messages,
                                selectedFile = selectedFile,
                                onSendMessage = { viewModel.sendChatMessage(it) },
                                onClearChat = { viewModel.clearChat() }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Subcomponent: File Browser
@Composable
fun FileBrowserSection(
    files: List<WorkspaceFile>,
    selectedFile: WorkspaceFile?,
    onFileSelect: (WorkspaceFile) -> Unit,
    onFileDelete: (WorkspaceFile) -> Unit,
    onUploadClick: () -> Unit,
    onNewFileCreate: (String, String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "WORKSPACE BROWSER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onUploadClick,
                    modifier = Modifier.size(28.dp).testTag("upload_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = "Upload Local File",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.size(28.dp).testTag("add_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "New File",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = Color.Gray.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Browser empty. Upload or create a file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(files) { file ->
                    val isSelected = selectedFile?.id == file.id
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
                            .clickable { onFileSelect(file) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = when {
                                    file.filePath.endsWith(".py") -> Icons.Rounded.Terminal
                                    file.filePath.endsWith(".json") -> Icons.Rounded.Settings
                                    else -> Icons.Rounded.Description
                                },
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = file.filePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = { onFileDelete(file) },
                            modifier = Modifier.size(24.dp).testTag("delete_file_${file.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Delete File",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Create Virtual File") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = newFileName,
                            onValueChange = { newFileName = it },
                            label = { Text("File Path (e.g. index.js, README.md)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("new_file_name_field")
                        )
                        OutlinedTextField(
                            value = newFileContent,
                            onValueChange = { newFileContent = it },
                            label = { Text("Initial Code/Content") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .testTag("new_file_content_field"),
                            maxLines = 10
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newFileName.isNotBlank()) {
                                onNewFileCreate(newFileName, newFileContent)
                                newFileName = ""
                                newFileContent = ""
                                showCreateDialog = false
                            }
                        },
                        modifier = Modifier.testTag("confirm_create_file_button")
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// Subcomponent: GitHub Settings & Commits
@Composable
fun GithubIntegrationSection(
    repoName: String,
    codename: String,
    commits: List<GitCommit>,
    isSynced: Boolean,
    isSyncing: Boolean,
    onSettingsUpdate: (String, String) -> Unit,
    onCommit: (String) -> Unit,
    onPush: () -> Unit
) {
    var editRepo by remember { mutableStateOf(repoName) }
    var editCode by remember { mutableStateOf(codename) }
    var commitMsg by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Repo status
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF15131A)),
            border = BorderStroke(1.dp, Color(0xFF2C2C30))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("GIT STATUS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.size(24.dp).testTag("git_settings_button")) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }

                Text(
                    text = "Codename: $codename",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Repo: $repoName",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        color = if (isSynced) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFFF9800).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (isSynced) Color(0xFF4CAF50) else Color(0xFFFF9800)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isSynced) "SYNCED" else "UNPUSHED CHANGES",
                            color = if (isSynced) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (!isSynced) {
                        Button(
                            onClick = onPush,
                            enabled = !isSyncing,
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("push_origin_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(12.dp), strokeWidth = 1.dp)
                            } else {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Push to Origin", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // Commit Form Panel
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF15131A)),
            border = BorderStroke(1.dp, Color(0xFF2C2C30))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STAGE & COMMIT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = commitMsg,
                    onValueChange = { commitMsg = it },
                    placeholder = { Text("Commit message...", fontSize = 11.sp, color = Color.Gray) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("commit_msg_field"),
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
                Button(
                    onClick = {
                        if (commitMsg.isNotBlank()) {
                            onCommit(commitMsg)
                            commitMsg = ""
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .testTag("commit_button"),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Commit Changes", fontSize = 11.sp)
                }
            }
        }

        // Commit History logs
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("COMMIT HISTORY LOG", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
            if (commits.isEmpty()) {
                Text("No commits recorded yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
            } else {
                commits.forEach { commit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF15131A), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF222026), RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(commit.message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Author: ${commit.author}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = commit.commitHash.take(6).uppercase(),
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Git Repository Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editCode,
                        onValueChange = { editCode = it },
                        label = { Text("Project Codename") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("git_codename_input")
                    )
                    OutlinedTextField(
                        value = editRepo,
                        onValueChange = { editRepo = it },
                        label = { Text("GitHub Repo Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("git_repo_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSettingsUpdate(editRepo, editCode)
                        showSettingsDialog = false
                    },
                    modifier = Modifier.testTag("save_git_settings_button")
                ) {
                    Text("Save Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Subcomponent: Monospace Code Editor & Sandbox Compiler Runner
@Composable
fun CodeEditorSection(
    viewModel: SwarmViewModel,
    selectedFile: WorkspaceFile?,
    isSynced: Boolean,
    onSave: (Int, String) -> Unit
) {
    if (selectedFile == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No file active in editor. Choose or create a file in the Browser.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    var textInput by remember(selectedFile.id) { mutableStateOf(selectedFile.content) }
    val isEdited = textInput != selectedFile.content

    val isRunning by viewModel.isSandboxRunning.collectAsState()
    val consoleOutput by viewModel.sandboxConsoleOutput.collectAsState()
    val exitCode by viewModel.sandboxExitCode.collectAsState()
    val language by viewModel.sandboxLanguage.collectAsState()
    val memoryUsed by viewModel.sandboxMemoryUsed.collectAsState()
    val timeMs by viewModel.sandboxTimeMs.collectAsState()

    var showConsole by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ACTIVE EDITOR: ${selectedFile.filePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isEdited) Color(0xFFFF9800) else Color(0xFF4CAF50))
                    )
                    Text(
                        text = if (isEdited) "Unsaved changes" else "All changes saved",
                        fontSize = 9.sp,
                        color = if (isEdited) Color(0xFFFF9800) else Color(0xFF4CAF50)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Compile & Run Button
                Button(
                    onClick = {
                        if (isEdited) {
                            onSave(selectedFile.id, textInput)
                        }
                        viewModel.runSandbox(selectedFile)
                        showConsole = true
                    },
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("run_sandbox_button"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Compiling...", fontSize = 10.sp)
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Compile & Run", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (isEdited) {
                    Button(
                        onClick = { onSave(selectedFile.id, textInput) },
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("save_file_button"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save", fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Monospace text field editor
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (showConsole) 0.55f else 0.95f)
                .background(Color(0xFF0F0E12), RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, Color(0xFF231E29)), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            BasicTextField(
                value = textInput,
                onValueChange = { textInput = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFECEFF1),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag("code_editor_textarea")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Console Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF15131A))
                .clickable { showConsole = !showConsole }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = if (isRunning) Color(0xFF4CAF50) else Color.Gray,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "SANDBOX COMPILER & RUNNER CONSOLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            Icon(
                imageVector = if (showConsole) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(16.dp)
            )
        }

        // Console Screen
        AnimatedVisibility(
            visible = showConsole,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.weight(if (showConsole) 0.4f else 0.001f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(Color(0xFF050508), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .border(BorderStroke(1.dp, Color(0xFF1E1C24)), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0E0D12), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "MEM: $memoryUsed",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "TIME: ${timeMs}ms",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                        if (exitCode != null) {
                            Text(
                                text = "EXIT: $exitCode",
                                color = if (exitCode == 0) Color(0xFF4CAF50) else Color(0xFFF44336),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF030305), RoundedCornerShape(4.dp))
                        .border(BorderStroke(1.dp, Color(0xFF131118)), RoundedCornerShape(4.dp))
                        .padding(6.dp)
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(consoleOutput) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }

                    Text(
                        text = if (consoleOutput.isEmpty()) "Console idle. Click 'Compile & Run' to execute code." else consoleOutput,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = if (exitCode == null) Color(0xFF00FF66) else if (exitCode == 0) Color(0xFF00FF66) else Color(0xFFFF4545),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

// Subcomponent: Swarm Chat Pane
@Composable
fun ChatPaneSection(
    messages: List<ChatMessage>,
    selectedFile: WorkspaceFile?,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit
) {
    var chatInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("SWARM INTELLIGENCE CHAT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onClearChat, modifier = Modifier.size(24.dp).testTag("clear_chat_button")) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Clear Chat", tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Message List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0F0E12), RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, Color(0xFF1E1B24)), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                val bubbleColor = when (message.role) {
                    "user" -> Color(0xFF2C1C3F) // User dark indigo
                    "system" -> Color(0xFF15131A) // System dark slate
                    else -> {
                        // Attempt to parse agent custom color or fallback to slate
                        try {
                            Color(android.graphics.Color.parseColor(message.colorHex)).copy(alpha = 0.15f)
                        } catch (e: Exception) {
                            Color(0xFF263238).copy(alpha = 0.2f)
                        }
                    }
                }

                val borderColor = when (message.role) {
                    "user" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    "system" -> Color.Gray.copy(alpha = 0.2f)
                    else -> {
                        try {
                            Color(android.graphics.Color.parseColor(message.colorHex)).copy(alpha = 0.4f)
                        } catch (e: Exception) {
                            Color.Transparent
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start
                ) {
                    Text(
                        text = message.sender.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                        color = if (message.role == "user") MaterialTheme.colorScheme.primary else {
                            try {
                                Color(android.graphics.Color.parseColor(message.colorHex))
                            } catch (e: Exception) {
                                Color.Gray
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .background(bubbleColor, RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = message.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (message.role == "system") FontFamily.Monospace else FontFamily.Default
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Contextual action buttons to let Swarm interact with Editor context!
        if (selectedFile != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onSendMessage("Explain the active file: ${selectedFile.filePath}") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1625)),
                    border = BorderStroke(1.dp, Color(0xFF3B2F4E)),
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .testTag("quick_action_explain"),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Explain Code", fontSize = 9.sp, color = Color(0xFFECEFF1))
                }
                Button(
                    onClick = { onSendMessage("Refactor or optimize this code to be cleaner and more performant: ${selectedFile.filePath}") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1625)),
                    border = BorderStroke(1.dp, Color(0xFF3B2F4E)),
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .testTag("quick_action_optimize"),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Optimize Code", fontSize = 9.sp, color = Color(0xFFECEFF1))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Send row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = { Text("Ask Swarm...", fontSize = 12.sp, color = Color.Gray) },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("chat_input_field"),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            IconButton(
                onClick = {
                    if (chatInput.isNotBlank()) {
                        onSendMessage(chatInput)
                        chatInput = ""
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .testTag("send_chat_button")
            ) {
                Icon(Icons.Rounded.Send, contentDescription = "Send", tint = Color.White)
            }
        }
    }
}

// Helper function to read display filename from ContentResolver Uri
private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}
