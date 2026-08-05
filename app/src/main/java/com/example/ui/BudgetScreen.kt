package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.SwarmViewModel

@Composable
fun BudgetScreen(
    viewModel: SwarmViewModel,
    modifier: Modifier = Modifier
) {
    val cap by viewModel.cloudTokenCap.collectAsState()
    val totalTokens by viewModel.totalTokensUsed.collectAsState()
    val totalCost by viewModel.totalCostSavingsUsd.collectAsState()

    var draftCap by remember(cap) { mutableStateOf(cap.toString()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Cloud Budget",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Session totals",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Estimated tokens used: $totalTokens", style = MaterialTheme.typography.bodyMedium)
                Text("Estimated savings vs. cloud: $${"%.2f".format(totalCost)}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        OutlinedTextField(
            value = draftCap,
            onValueChange = { draftCap = it.filter { c -> c.isDigit() } },
            label = { Text("Cloud token cap per task (0 = unlimited)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("cloud_token_cap_input")
        )

        Button(
            onClick = { viewModel.setCloudTokenCap(draftCap.toIntOrNull() ?: 0) },
            modifier = Modifier.align(Alignment.End).testTag("save_cloud_token_cap_button")
        ) {
            Text("Save cap")
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Token counts are estimates derived from character length ((prompt + output) / 2 + 100), not a real tokenizer, so actual usage may vary. The loop stops starting new iterations once the estimate reaches this cap; an in-progress call is allowed to finish and may exceed it. Set to 0 for the legacy unlimited behavior.",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
