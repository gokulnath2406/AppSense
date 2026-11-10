package com.appsense.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawingPadding

@Composable
fun IntentionSettingsScreen(
    context: Context,
    onBack: () -> Unit
) {
    var settings by remember {
        mutableStateOf(IntentionPreferences.read(context))
    }

    val interventionsEnabled =
        remember { InterventionPreferences.read(context).interventionEnabled }

    fun update(next: IntentionSettings) {
        settings = next.normalized()
        IntentionPreferences.write(context, settings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(Color(0xFFF7F8FA))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        Text(
            text = "At-open question",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF16181D)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Ask 'Why are you here?' when I open a tracked app",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF16181D)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pick 'Just bored' or 'Only 1-2 minutes'. AppSense then sets your reminder schedule for that session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF747985)
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            update(settings.copy(enabled = enabled))
                        }
                    )
                }

                if (!interventionsEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Turn on interventions first, otherwise this question is not shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3261E)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Just bored", style = MaterialTheme.typography.titleMedium, color = Color(0xFF16181D))
                Spacer(modifier = Modifier.height(14.dp))
                IntentionMinutesStepper("First reminder", settings.bored.firstMinutes) { value ->
                    update(settings.copy(bored = settings.bored.copy(firstMinutes = value)))
                }
                Spacer(modifier = Modifier.height(16.dp))
                IntentionMinutesStepper("Repeat reminder", settings.bored.repeatMinutes) { value ->
                    update(settings.copy(bored = settings.bored.copy(repeatMinutes = value)))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        IntentionScheduleNote()

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Only 1-2 minutes", style = MaterialTheme.typography.titleMedium, color = Color(0xFF16181D))
                Spacer(modifier = Modifier.height(14.dp))
                IntentionMinutesStepper("First reminder", settings.quick.firstMinutes) { value ->
                    update(settings.copy(quick = settings.quick.copy(firstMinutes = value)))
                }
                Spacer(modifier = Modifier.height(16.dp))
                IntentionMinutesStepper("Repeat reminder", settings.quick.repeatMinutes) { value ->
                    update(settings.copy(quick = settings.quick.copy(repeatMinutes = value)))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        IntentionScheduleNote()

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                IntentionValueStepper(
                    title = "Pause before answering",
                    valueText = "${settings.pauseSeconds} seconds",
                    value = settings.pauseSeconds,
                    minimum = 3,
                    maximum = 10,
                    onValueChange = { value ->
                        update(settings.copy(pauseSeconds = value))
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

@Composable
private fun IntentionScheduleNote() {
    Text(
        text = "Reminders never come later than your normal intervention schedule.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF747985),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun IntentionMinutesStepper(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    IntentionValueStepper(
        title = title,
        valueText = "$value min",
        value = value,
        minimum = 1,
        maximum = 120,
        onValueChange = onValueChange
    )
}

@Composable
private fun IntentionValueStepper(
    title: String,
    valueText: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFF16181D))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = { onValueChange((value - 1).coerceAtLeast(minimum)) },
                enabled = value > minimum
            ) {
                Text("−")
            }
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = Color(0xFF16181D))
            OutlinedButton(
                onClick = { onValueChange((value + 1).coerceAtMost(maximum)) },
                enabled = value < maximum
            ) {
                Text("+")
            }
        }
    }
}
