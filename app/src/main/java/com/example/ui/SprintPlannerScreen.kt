package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SprintArtifact
import com.example.data.SprintCycle
import com.example.data.SprintPhase
import com.example.data.SprintProgress
import com.example.viewmodel.SwarmViewModel

@Composable
fun SprintPlannerScreen(
    viewModel: SwarmViewModel,
    onNavigateToSession: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCycle by viewModel.activeCycle.collectAsState()
    val cycleProgress by viewModel.sprintCycleProgress.collectAsState()
    val artifacts by viewModel.sprintArtifacts.collectAsState()
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600

    if (isWideScreen) {
        Row(modifier = modifier.fillMaxSize()) {
            SprintStepper(
                activeCycle = activeCycle,
                progress = cycleProgress,
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
            )
            Spacer(Modifier.width(12.dp))
            SprintMainPanel(
                activeCycle = activeCycle,
                progress = cycleProgress,
                artifacts = artifacts,
                onStartCycle = { goal -> viewModel.startSprintCycle(goal) },
                onPause = { viewModel.pauseSprintCycle() },
                onResume = { viewModel.resumeSprintCycle() },
                onCancel = { viewModel.cancelSprintCycle() },
                onNavigateToSession = onNavigateToSession,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 16.dp, top = 16.dp, bottom = 16.dp)
            )
        }
    } else {
        Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
            SprintStepper(
                activeCycle = activeCycle,
                progress = cycleProgress,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            SprintMainPanel(
                activeCycle = activeCycle,
                progress = cycleProgress,
                artifacts = artifacts,
                onStartCycle = { goal -> viewModel.startSprintCycle(goal) },
                onPause = { viewModel.pauseSprintCycle() },
                onResume = { viewModel.resumeSprintCycle() },
                onCancel = { viewModel.cancelSprintCycle() },
                onNavigateToSession = onNavigateToSession,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SprintStepper(
    activeCycle: SprintCycle?,
    progress: SprintProgress,
    modifier: Modifier = Modifier
) {
    val phases = SprintPhase.values()
    Column(modifier = modifier) {
        Text(
            text = "Sprint Phases",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        phases.forEachIndexed { index, phase ->
            val isDone = index < progress.completedPhases
            val isCurrent = phase == progress.phase && activeCycle?.status == "RUNNING"
            val isPending = !isDone && !isCurrent

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isDone -> Color(0xFF4CAF50)
                                isCurrent -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Done",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = phase.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isDone -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                )
                if (isCurrent) {
                    Spacer(Modifier.width(4.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // connector line between steps
            if (index < phases.size - 1) {
                Box(
                    modifier = Modifier
                        .padding(start = 14.dp)
                        .width(2.dp)
                        .height(8.dp)
                        .background(
                            if (index < progress.completedPhases) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
    }
}

@Composable
private fun SprintMainPanel(
    activeCycle: SprintCycle?,
    progress: SprintProgress,
    artifacts: List<SprintArtifact>,
    onStartCycle: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onNavigateToSession: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var goalText by remember { mutableStateOf("") }
    val isRunning = activeCycle?.status == "RUNNING"
    val isPaused = activeCycle?.status == "PAUSED"
    val hasActiveCycle = activeCycle != null && activeCycle.status in listOf("RUNNING", "PAUSED")

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Goal input / cycle launch
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesomeMotion,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Autonomous Sprint Cycle",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!hasActiveCycle) {
                        OutlinedTextField(
                            value = goalText,
                            onValueChange = { goalText = it },
                            label = { Text("Cycle goal") },
                            placeholder = { Text("e.g. Add batched file review to the approval flow") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { if (goalText.isNotBlank()) onStartCycle(goalText.trim()) },
                            enabled = goalText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Start Sprint Cycle")
                        }
                    } else {
                        Text(
                            text = activeCycle!!.goal,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isRunning) {
                                OutlinedButton(
                                    onClick = onPause,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Rounded.Pause, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pause")
                                }
                            }
                            if (isPaused) {
                                Button(
                                    onClick = onResume,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Resume")
                                }
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFEF4444)
                                )
                            ) {
                                Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        // Active phase card
        if (hasActiveCycle) {
            item {
                ActivePhaseCard(
                    progress = progress,
                    onNavigateToSession = onNavigateToSession
                )
            }
        }

        // Unresolved items banner
        val unresolvedCount = activeCycle?.unresolvedCount ?: 0
        if (unresolvedCount > 0) {
            item {
                Surface(
                    color = Color(0xFFEF4444).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$unresolvedCount unresolved item${if (unresolvedCount > 1) "s" else ""} " +
                                   "across ${activeCycle?.reimplCount ?: 0} re-implementation pass${if ((activeCycle?.reimplCount ?: 0) != 1) "es" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }

        // Completed artifact list
        if (artifacts.isNotEmpty()) {
            item {
                Text(
                    text = "Sprint Artifacts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(artifacts, key = { it.id }) { artifact ->
                ArtifactCard(artifact = artifact, onNavigateToSession = onNavigateToSession)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ActivePhaseCard(
    progress: SprintProgress,
    onNavigateToSession: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = phaseIcon(progress.phase),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = progress.phaseLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${progress.completedPhases}/${progress.totalPhases}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            if (progress.currentTaskId != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onNavigateToSession(progress.currentTaskId) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "View task #${progress.currentTaskId}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (progress.lastArtifactSummary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = progress.lastArtifactSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 3
                )
            }
        }
    }
}

@Composable
private fun ArtifactCard(
    artifact: SprintArtifact,
    onNavigateToSession: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasUnresolved = artifact.unresolvedItems.isNotBlank()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = phaseIcon(SprintPhase.valueOf(artifact.phase)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = artifact.phase,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = artifact.artifactPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                if (hasUnresolved) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Has unresolved items",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (artifact.gitCommitHash != null) {
                    Icon(
                        imageVector = Icons.Rounded.Commit,
                        contentDescription = "Git checkpoint",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    if (artifact.distilledSummary.isNotBlank()) {
                        Text(
                            text = "Summary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = artifact.distilledSummary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    if (hasUnresolved) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Unresolved",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFEF4444)
                        )
                        Spacer(Modifier.height(4.dp))
                        artifact.unresolvedItems.lines()
                            .filter { it.isNotBlank() }
                            .forEach { line ->
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        text = "•",
                                        color = Color(0xFFEF4444),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                    Text(
                                        text = line.removePrefix("[UNRESOLVED:").removeSuffix("]").trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                    }
                    if (artifact.gitCommitHash != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Commit,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = artifact.gitCommitHash.take(8),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { onNavigateToSession(artifact.taskId) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View task #${artifact.taskId}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun phaseIcon(phase: SprintPhase): ImageVector = when (phase) {
    SprintPhase.DISCOVERY     -> Icons.Rounded.Search
    SprintPhase.DESIGN        -> Icons.Rounded.Architecture
    SprintPhase.IMPLEMENTATION -> Icons.Rounded.Code
    SprintPhase.VERIFICATION  -> Icons.Rounded.FactCheck
    SprintPhase.INTEGRATION   -> Icons.AutoMirrored.Rounded.MergeType
    SprintPhase.RETROSPECTIVE -> Icons.Rounded.History
}
