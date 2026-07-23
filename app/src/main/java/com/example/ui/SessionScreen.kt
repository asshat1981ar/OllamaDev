package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ChatMessage
import com.example.data.SwarmConfig
import com.example.data.SwarmTask
import com.example.data.TaskStep
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.launch

/** One merged, time-ordered entry in Session's transcript -- either a chat turn or an agent
 *  execution step, rendered by [TaskStepTimelineItem] (reused from TaskDetailScreen.kt) or a
 *  local chat bubble respectively. */
private sealed class SessionTimelineItem {
    abstract val timestamp: Long
    data class Chat(val message: ChatMessage) : SessionTimelineItem() {
        override val timestamp: Long = message.timestamp
    }
    data class Step(val step: TaskStep) : SessionTimelineItem() {
        override val timestamp: Long = step.timestamp
    }
}

private val sessionSamplePrompts = listOf(
    "Write a fast fibonacci function in Kotlin",
    "Research the tradeoffs of on-device LLM processing",
    "Review this workspace's code for security issues",
    "Draft a spec for a new authentication flow"
)

/**
 * The Manus-AI/REPL-style home surface: a live transcript of chat + agent execution steps, a
 * bottom input bar (with inline voice capture), a swarm picker, and a collapsible history
 * drawer -- paired on tablet with a "computer panel" ([WorkspacePanel]) showing live file/git
 * context, or reachable via a bottom sheet on phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val activeSteps by viewModel.activeSteps.collectAsState()
    val isExecuting by viewModel.isExecutingTask.collectAsState()
    val swarmConfigs by viewModel.allSwarmConfigs.collectAsState()
    val selectedConfig by viewModel.selectedSessionSwarmConfig.collectAsState()
    val allTasks by viewModel.allTasks.collectAsState()
    val selectedTaskId by viewModel.selectedTaskId.collectAsState()
    val isVoiceListening by viewModel.isVoiceListening.collectAsState()
    val voiceTranscript by viewModel.voiceTranscript.collectAsState()
    val isVoiceProcessing by viewModel.isVoiceProcessing.collectAsState()
    val isComputerPanelExpanded by viewModel.isComputerPanelExpanded.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isHistoryOpen by remember { mutableStateOf(false) }
    var isSwarmPickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(swarmConfigs) {
        if (selectedConfig == null && swarmConfigs.isNotEmpty()) {
            viewModel.selectSessionSwarmConfig(swarmConfigs.first())
        }
    }

    LaunchedEffect(voiceTranscript, isVoiceListening) {
        if (isVoiceListening) {
            inputText = voiceTranscript
        }
    }

    val timeline = remember(chatMessages, activeSteps) {
        (chatMessages.map { SessionTimelineItem.Chat(it) } + activeSteps.map { SessionTimelineItem.Step(it) })
            .sortedBy { it.timestamp }
    }

    fun dispatch() {
        val prompt = inputText.trim()
        if (prompt.isNotEmpty() && !isExecuting) {
            viewModel.runSwarmFromSession(prompt)
            inputText = ""
        }
    }

    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 800

    if (isExpanded) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                SessionConversationPane(
                    timeline = timeline,
                    isExecuting = isExecuting,
                    swarmConfigs = swarmConfigs,
                    selectedConfig = selectedConfig,
                    isSwarmPickerOpen = isSwarmPickerOpen,
                    onSwarmPickerToggle = { isSwarmPickerOpen = it },
                    onSwarmSelected = { viewModel.selectSessionSwarmConfig(it) },
                    allTasks = allTasks,
                    selectedTaskId = selectedTaskId,
                    isHistoryOpen = isHistoryOpen,
                    onHistoryToggle = { isHistoryOpen = it },
                    onHistorySelect = { viewModel.selectTask(it) },
                    onHistoryDelete = { viewModel.deleteTask(it) },
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = ::dispatch,
                    isVoiceListening = isVoiceListening,
                    isVoiceProcessing = isVoiceProcessing,
                    onMicClick = {
                        if (isVoiceListening) viewModel.stopListeningAndProcess() else viewModel.startListening()
                    },
                    onComputerPanelToggle = null
                )
            }
            VerticalDivider(color = Color(0xFF1F293D))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                WorkspacePanel(viewModel = viewModel)
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
        ) {
            SessionConversationPane(
                timeline = timeline,
                isExecuting = isExecuting,
                swarmConfigs = swarmConfigs,
                selectedConfig = selectedConfig,
                isSwarmPickerOpen = isSwarmPickerOpen,
                onSwarmPickerToggle = { isSwarmPickerOpen = it },
                onSwarmSelected = { viewModel.selectSessionSwarmConfig(it) },
                allTasks = allTasks,
                selectedTaskId = selectedTaskId,
                isHistoryOpen = isHistoryOpen,
                onHistoryToggle = { isHistoryOpen = it },
                onHistorySelect = { viewModel.selectTask(it) },
                onHistoryDelete = { viewModel.deleteTask(it) },
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = ::dispatch,
                isVoiceListening = isVoiceListening,
                isVoiceProcessing = isVoiceProcessing,
                onMicClick = {
                    if (isVoiceListening) viewModel.stopListeningAndProcess() else viewModel.startListening()
                },
                onComputerPanelToggle = { viewModel.setComputerPanelExpanded(true) }
            )
        }

        if (isComputerPanelExpanded) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.setComputerPanelExpanded(false) },
                modifier = Modifier.testTag("session_computer_panel_sheet")
            ) {
                WorkspacePanel(viewModel = viewModel, modifier = Modifier.fillMaxWidth().height(500.dp))
            }
        }
    }
}

@Composable
private fun SessionConversationPane(
    timeline: List<SessionTimelineItem>,
    isExecuting: Boolean,
    swarmConfigs: List<SwarmConfig>,
    selectedConfig: SwarmConfig?,
    isSwarmPickerOpen: Boolean,
    onSwarmPickerToggle: (Boolean) -> Unit,
    onSwarmSelected: (SwarmConfig) -> Unit,
    allTasks: List<SwarmTask>,
    selectedTaskId: Int?,
    isHistoryOpen: Boolean,
    onHistoryToggle: (Boolean) -> Unit,
    onHistorySelect: (Int) -> Unit,
    onHistoryDelete: (SwarmTask) -> Unit,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isVoiceListening: Boolean,
    isVoiceProcessing: Boolean,
    onMicClick: () -> Unit,
    onComputerPanelToggle: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top row: swarm picker, history toggle, (phone) computer-panel toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { onSwarmPickerToggle(true) },
                    modifier = Modifier.fillMaxWidth().testTag("session_swarm_dropdown")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedConfig?.name ?: "Select Swarm",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(imageVector = Icons.Rounded.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = isSwarmPickerOpen, onDismissRequest = { onSwarmPickerToggle(false) }) {
                    swarmConfigs.forEach { config ->
                        DropdownMenuItem(
                            text = { Text(config.name) },
                            onClick = {
                                onSwarmSelected(config)
                                onSwarmPickerToggle(false)
                            }
                        )
                    }
                }
            }

            IconButton(
                onClick = { onHistoryToggle(!isHistoryOpen) },
                modifier = Modifier.testTag("session_history_toggle")
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = "Task History",
                    tint = if (isHistoryOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (onComputerPanelToggle != null) {
                IconButton(
                    onClick = onComputerPanelToggle,
                    modifier = Modifier.testTag("session_open_computer_panel")
                ) {
                    Icon(imageVector = Icons.Rounded.Storage, contentDescription = "Open Workspace Panel")
                }
            }
        }

        AnimatedHistoryDrawer(
            isOpen = isHistoryOpen,
            allTasks = allTasks,
            selectedTaskId = selectedTaskId,
            onSelect = onHistorySelect,
            onDelete = onHistoryDelete
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Transcript
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        LaunchedEffect(timeline.size) {
            if (timeline.isNotEmpty()) {
                scope.launch { listState.animateScrollToItem(timeline.size - 1) }
            }
        }

        if (timeline.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Describe a task below to start a session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(timeline, key = {
                    when (it) {
                        is SessionTimelineItem.Chat -> "chat_${it.message.id}"
                        is SessionTimelineItem.Step -> "step_${it.step.id}"
                    }
                }) { item ->
                    when (item) {
                        is SessionTimelineItem.Chat -> SessionChatBubble(item.message)
                        is SessionTimelineItem.Step -> TaskStepTimelineItem(item.step)
                    }
                }
            }
        }

        if (inputText.isBlank() && timeline.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessionSamplePrompts) { sample ->
                    AssistChip(
                        onClick = { onInputChange(sample) },
                        label = { Text(sample, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("session_sample_prompt")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                placeholder = { Text("Describe what the swarm should do...", fontSize = 12.sp) },
                modifier = Modifier.weight(1f).testTag("session_input_field"),
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 1,
                maxLines = 5
            )

            IconButton(
                onClick = onMicClick,
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isVoiceListening) Color(0xFFEF4444) else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp)
                    )
                    .testTag("session_mic_button")
            ) {
                if (isVoiceProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (isVoiceListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = if (isVoiceListening) "Stop Listening" else "Start Listening",
                        tint = if (isVoiceListening) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isExecuting,
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .testTag("session_send_button")
            ) {
                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Icon(imageVector = Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun AnimatedHistoryDrawer(
    isOpen: Boolean,
    allTasks: List<SwarmTask>,
    selectedTaskId: Int?,
    onSelect: (Int) -> Unit,
    onDelete: (SwarmTask) -> Unit
) {
    if (!isOpen) return

    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .background(Color(0xFF0F0F12), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text(
            text = "TASK HISTORY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (allTasks.isEmpty()) {
            Text(
                text = "No prior runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(allTasks, key = { it.id }) { task ->
                    HistoryTaskRow(
                        task = task,
                        isSelected = selectedTaskId == task.id,
                        onClick = { onSelect(task.id) },
                        onDelete = { onDelete(task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionChatBubble(message: ChatMessage) {
    val bubbleColor = when (message.role) {
        "user" -> Color(0xFF2C1C3F)
        "system" -> Color(0xFF15131A)
        else -> try {
            Color(android.graphics.Color.parseColor(message.colorHex)).copy(alpha = 0.15f)
        } catch (e: Exception) {
            Color(0xFF263238).copy(alpha = 0.2f)
        }
    }
    val borderColor = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        "system" -> Color.Gray.copy(alpha = 0.2f)
        else -> try {
            Color(android.graphics.Color.parseColor(message.colorHex)).copy(alpha = 0.4f)
        } catch (e: Exception) {
            Color.Transparent
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start
    ) {
        Text(
            text = message.sender.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
            color = if (message.role == "user") MaterialTheme.colorScheme.primary else try {
                Color(android.graphics.Color.parseColor(message.colorHex))
            } catch (e: Exception) {
                Color.Gray
            },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(bubbleColor, RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
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
