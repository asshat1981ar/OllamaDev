package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.SwarmViewModel

@Composable
fun VoiceScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val isListening by viewModel.isVoiceListening.collectAsState()
    val transcript by viewModel.voiceTranscript.collectAsState()
    val feedback by viewModel.voiceFeedback.collectAsState()
    val isProcessing by viewModel.isVoiceProcessing.collectAsState()

    val sampleVoiceCommands = listOf(
        "Ask the elite code swarm to write a fast fibonacci function in Kotlin",
        "Assemble a research swarm to review the benefits of on-device LLM processing",
        "Connect decentralized node Peer-C at http://192.168.0.45:11434",
        "Create a new critic agent named Helix Critic specializing in security auditing"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "VOICE RECOGNITION CO-PILOT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Vocal Swarm Orchestrator",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Use natural speech commands to assemble nodes, customize agent personas, or execute parallel tasks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        // Voice Ripple & Mic Area
        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                if (isListening) {
                    VoiceWaveAnimation()
                }
                
                MicButton(
                    isListening = isListening,
                    isProcessing = isProcessing,
                    onClick = {
                        if (isListening) {
                            viewModel.stopListeningAndProcess()
                        } else {
                            viewModel.startListening()
                        }
                    }
                )
            }
        }

        // Processing / Status Area
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "VOICE CONSOLE OUTPUT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F0F12), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "Transcript:",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (transcript.isEmpty()) "Tap the microphone to speak, or select a command below." else transcript,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (transcript.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    
                    if (feedback.isNotEmpty() || isProcessing) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Verified,
                                    contentDescription = null,
                                    tint = Color(0xFF4ADE80),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = feedback,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        // Rapid Voice Deck
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "RAPID TEST DECKS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                
                sampleVoiceCommands.forEach { cmd ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!isProcessing) {
                                    viewModel.stopListeningAndProcess(cmd)
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                        border = BorderStroke(1.dp, Color(0xFF2C2C30))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = cmd,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MicButton(
    isListening: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pulse_button")
    val pulseSize by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "size"
    )

    val scaleModifier = if (isListening) Modifier.scale(pulseSize) else Modifier

    Box(
        contentAlignment = Alignment.Center,
        modifier = scaleModifier
            .size(100.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = if (isListening) {
                        listOf(Color(0xFFEF4444), Color(0xFF991B1B))
                    } else if (isProcessing) {
                        listOf(Color(0xFFFACC15), Color(0xFF854D0E))
                    } else {
                        listOf(Color(0xFFD0BCFF), Color(0xFF381E72))
                    }
                )
            )
            .clickable(onClick = onClick)
            .testTag("voice_mic_button")
    ) {
        if (isProcessing) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp
            )
        } else {
            Icon(
                imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = if (isListening) "Stop Listening" else "Start Listening",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
fun VoiceWaveAnimation() {
    val transition = rememberInfiniteTransition(label = "wave_scale")
    val scale1 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale_inner"
    )
    val scale2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale_outer"
    )

    Canvas(modifier = Modifier.size(240.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        
        // Inner pulse circle
        drawCircle(
            color = Color(0x22EF4444),
            radius = (size.width / 4) * scale1,
            center = center,
            style = Stroke(width = 4f)
        )
        
        // Outer pulse circle
        drawCircle(
            color = Color(0x11EF4444),
            radius = (size.width / 4) * scale2,
            center = center,
            style = Stroke(width = 2f)
        )
    }
}
