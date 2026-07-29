package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SwarmConfig
import com.example.data.SwarmTask
import com.example.viewmodel.SwarmViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun isUnresolved(task: SwarmTask): Boolean =
    task.result?.contains("[UNRESOLVED]") == true || task.status == "Failed"

private fun formatDuration(ms: Long): String = when {
    ms >= 60_000 -> "${(ms / 60_000.0).roundToInt()}m ${((ms % 60_000) / 1000.0).roundToInt()}s"
    ms >= 1_000 -> "${(ms / 1000.0).roundToInt()}s"
    ms > 0 -> "${ms}ms"
    else -> "0s"
}

private val dayFormatter = SimpleDateFormat("MMM dd", Locale.getDefault())

@Composable
fun AnalyticsScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.allTasks.collectAsState()
    val configs by viewModel.allSwarmConfigs.collectAsState()

    val analytics by remember(tasks, configs) {
        derivedStateOf { buildAnalyticsSnapshot(tasks, configs) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .testTag("analytics_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            AnalyticsHeader()
        }

        item {
            AggregateCards(analytics)
        }

        item {
            DailyTaskVolumeChart(tasks = tasks)
        }

        item {
            SwarmBreakdownSection(configs = configs, tasks = tasks)
        }
    }
}

@Composable
private fun AnalyticsHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "TASK ANALYTICS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Cost / Time & Resolution Trends",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Aggregate and per-swarm telemetry from recorded SwarmTask executions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}

private data class AnalyticsSnapshot(
    val totalTasks: Int,
    val totalTokens: Int,
    val totalExecutionTimeMs: Long,
    val unresolvedCount: Int,
    val unresolvedRate: Float
)

private fun buildAnalyticsSnapshot(
    tasks: List<SwarmTask>,
    configs: List<SwarmConfig>
): AnalyticsSnapshot {
    val unresolved = tasks.count { isUnresolved(it) }
    return AnalyticsSnapshot(
        totalTasks = tasks.size,
        totalTokens = tasks.sumOf { it.tokenUsage },
        totalExecutionTimeMs = tasks.sumOf { it.executionTimeMs },
        unresolvedCount = unresolved,
        unresolvedRate = if (tasks.isEmpty()) 0f else unresolved.toFloat() / tasks.size
    )
}

@Composable
private fun AggregateCards(snapshot: AnalyticsSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "Total Tasks",
                value = "${snapshot.totalTasks}",
                icon = Icons.Rounded.Numbers,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .testTag("analytics_total_tasks_card")
            )
            StatCard(
                title = "Total Tokens",
                value = if (snapshot.totalTokens >= 1000) String.format(
                    "%.1fk",
                    snapshot.totalTokens / 1000f
                ) else "${snapshot.totalTokens}",
                icon = Icons.Rounded.Toll,
                tint = Color(0xFF818CF8),
                modifier = Modifier
                    .weight(1f)
                    .testTag("analytics_total_tokens_card")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "Exec. Time",
                value = formatDuration(snapshot.totalExecutionTimeMs),
                icon = Icons.Rounded.AccessTime,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .weight(1f)
                    .testTag("analytics_total_time_card")
            )
            StatCard(
                title = "Unresolved Rate",
                value = String.format("%.1f%%", snapshot.unresolvedRate * 100),
                icon = Icons.Rounded.ErrorOutline,
                tint = if (snapshot.unresolvedRate > 0.2f) Color(0xFFEF4444) else Color(0xFF4ADE80),
                modifier = Modifier
                    .weight(1f)
                    .testTag("analytics_unresolved_rate_card")
            )
        }
    }
}

private data class SwarmAnalytics(
    val config: SwarmConfig,
    val taskCount: Int,
    val avgTokens: Int,
    val avgTimeMs: Long,
    val unresolvedCount: Int,
    val unresolvedRate: Float
)

private fun buildSwarmAnalytics(config: SwarmConfig, tasks: List<SwarmTask>): SwarmAnalytics {
    val swarmTasks = tasks.filter { it.swarmName == config.name }
    val totalTokens = swarmTasks.sumOf { it.tokenUsage }
    val totalTime = swarmTasks.sumOf { it.executionTimeMs }
    val unresolved = swarmTasks.count { isUnresolved(it) }
    return SwarmAnalytics(
        config = config,
        taskCount = swarmTasks.size,
        avgTokens = if (swarmTasks.isEmpty()) 0 else (totalTokens.toDouble() / swarmTasks.size).roundToInt(),
        avgTimeMs = if (swarmTasks.isEmpty()) 0 else (totalTime / swarmTasks.size),
        unresolvedCount = unresolved,
        unresolvedRate = if (swarmTasks.isEmpty()) 0f else unresolved.toFloat() / swarmTasks.size
    )
}

@Composable
private fun SwarmBreakdownSection(
    configs: List<SwarmConfig>,
    tasks: List<SwarmTask>
) {
    val rows = remember(configs, tasks) {
        derivedStateOf { configs.map { buildSwarmAnalytics(it, tasks) } }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("analytics_swarm_breakdown_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PER-SWARM BREAKDOWN",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${configs.size} Configs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No swarm configurations yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.testTag("analytics_empty_state")
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("analytics_swarm_breakdown_list")
                ) {
                    rows.value.forEach { row ->
                        SwarmBreakdownRow(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwarmBreakdownRow(row: SwarmAnalytics) {
    val statusColor = when {
        row.unresolvedRate > 0.2f -> Color(0xFFEF4444)
        row.unresolvedRate > 0f -> Color(0xFFFACC15)
        else -> Color(0xFF4ADE80)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .testTag("analytics_swarm_row_${row.config.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.config.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.config.coordinationMode,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BreakdownMetric(label = "Tasks", value = "${row.taskCount}")
            BreakdownMetric(
                label = "Avg Tokens",
                value = if (row.avgTokens >= 1000) String.format(
                    "%.1fk",
                    row.avgTokens / 1000f
                ) else "${row.avgTokens}"
            )
            BreakdownMetric(label = "Avg Time", value = formatDuration(row.avgTimeMs))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${row.unresolvedCount}/${row.taskCount}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = statusColor
                )
                Text(
                    text = String.format("%.0f%%", row.unresolvedRate * 100),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun BreakdownMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun DailyTaskVolumeChart(tasks: List<SwarmTask>) {
    val dayBuckets by remember(tasks) {
        derivedStateOf {
            tasks
                .groupBy { dayFormatter.format(Date(it.timestamp)) }
                .mapValues { it.value.size }
                .toList()
                .sortedBy { it.first }
                .takeLast(7)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .testTag("analytics_daily_chart_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TASK VOLUME (LAST 7 DAYS)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                if (dayBuckets.isNotEmpty()) {
                    Text(
                        text = "Max ${dayBuckets.maxOf { it.second }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (dayBuckets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No task history yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                val primaryColor = MaterialTheme.colorScheme.primary
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("analytics_daily_chart")
                ) {
                    val width = size.width
                    val height = size.height
                    val labelHeight = 28.dp.toPx()
                    val chartHeight = height - labelHeight
                    val max = dayBuckets.maxOf { it.second }.coerceAtLeast(1)
                    val barWidth = (width / dayBuckets.size) * 0.6f
                    val stepX = width / dayBuckets.size

                    dayBuckets.forEachIndexed { index, (day, count) ->
                        val x = (index * stepX) + (stepX / 2f)
                        val barHeight = (count.toFloat() / max) * chartHeight
                        val top = chartHeight - barHeight
                        val color = if (count == max) primaryColor else primaryColor.copy(alpha = 0.7f)

                        drawRect(
                            color = color,
                            topLeft = Offset(x - barWidth / 2f, top),
                            size = Size(barWidth, barHeight)
                        )

                        drawLine(
                            color = onSurfaceColor.copy(alpha = 0.2f),
                            start = Offset(x, chartHeight),
                            end = Offset(x, chartHeight + 4.dp.toPx()),
                            strokeWidth = 1f
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    dayBuckets.forEach { (day, _) ->
                        Text(
                            text = day,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}
