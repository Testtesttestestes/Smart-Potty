package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.TrackerEvent
import com.example.viewmodel.TrackerEventViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.example.viewmodel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    childId: String,
    viewModel: TrackerEventViewModel,
    bleViewModel: BleViewModel,
    onBack: () -> Unit
) {
    val allEvents by viewModel.getEventsForChild(childId).collectAsStateWithLifecycle(initialValue = emptyList())
    var dialogType by remember { mutableStateOf<String?>(null) }
    var filterType by remember { mutableStateOf("All") }
    val context = LocalContext.current

    val fillLevel by bleViewModel.fillLevel.collectAsStateWithLifecycle()
    var previousFillLevel by remember { mutableStateOf<Int?>(0) }
    var lastRecordedTime by remember { mutableStateOf(0L) }

    LaunchedEffect(fillLevel) {
        val currentFill = fillLevel ?: 0
        if (currentFill > 0 && (previousFillLevel ?: 0) == 0 && fillLevel != null) {
            val now = System.currentTimeMillis()
            if (now - lastRecordedTime > 60_000) { // debounce / prevent dupes
                viewModel.saveEvent(
                    TrackerEvent(
                        childId = childId,
                        timestamp = now,
                        eventType = "pee",
                        volumeLevel = currentFill,
                        isAccident = false
                    )
                )
                lastRecordedTime = now
            }
        }
        if (fillLevel != null) {
            previousFillLevel = currentFill
        }
    }

    val peeEvents = remember(allEvents) { allEvents.filter { it.eventType == "pee" } }

    val filteredEvents = remember(allEvents, filterType) {
        when (filterType) {
            "Successful" -> allEvents.filter { it.eventType == "pee" && !it.isAccident }
            "Accidents" -> allEvents.filter { it.eventType == "pee" && it.isAccident }
            "Sensations" -> allEvents.filter { it.eventType == "sensation" }
            else -> allEvents
        }
    }

    val totalCount = peeEvents.size
    val successCount = peeEvents.count { !it.isAccident }
    val accidentCount = peeEvents.count { it.isAccident }

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            currentTime = System.currentTimeMillis()
        }
    }

    val intervalStats = remember(peeEvents) {
        if (peeEvents.size < 2) return@remember null
        
        val sortedAsc = peeEvents.sortedBy { it.timestamp }
        val intervals = mutableListOf<Long>()
        for (i in 1 until sortedAsc.size) {
            intervals.add(sortedAsc[i].timestamp - sortedAsc[i - 1].timestamp)
        }
        
        val avg = intervals.average().toLong()
        val min = intervals.minOrNull() ?: 0L
        val max = intervals.maxOrNull() ?: 0L
        val sortedIntervals = intervals.sorted()
        val median = if (sortedIntervals.size % 2 == 0) {
            (sortedIntervals[sortedIntervals.size / 2 - 1] + sortedIntervals[sortedIntervals.size / 2]) / 2
        } else {
            sortedIntervals[sortedIntervals.size / 2]
        }
        
        IntervalStats(avg, min, max, median)
    }

    val currentProbability = remember(intervalStats, peeEvents, currentTime) {
        if (intervalStats != null && intervalStats.avg > 0) {
            val mostRecentEvent = peeEvents.maxByOrNull { it.timestamp }
            val timeSinceLast = if (mostRecentEvent != null) currentTime - mostRecentEvent.timestamp else 0L
            (timeSinceLast.toFloat() / intervalStats.avg.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pee Events") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                        val exportText = allEvents.joinToString("\n") { ev ->
                            val timeStr = format.format(Date(ev.timestamp))
                            if (ev.eventType == "sensation") {
                                "[$timeStr] Sensation: ${ev.sensationLevel}"
                            } else {
                                val type = if (ev.isAccident) "Accident" else "Successful"
                                "[$timeStr] $type - Vol: ${ev.volumeLevel ?: "?"}"
                            }
                        }
                        
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Exported Events")
                            putExtra(Intent.EXTRA_TEXT, exportText)
                        }
                        context.startActivity(Intent.createChooser(intent, "Export Data"))
                    }) {
                        Text("Export", color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { dialogType = "chooser" },
                modifier = Modifier.testTag("add_event_button")
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Stats Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Total: $totalCount", style = MaterialTheme.typography.bodyMedium)
                Text("Success: $successCount", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text("Accident: $accidentCount", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            if (intervalStats != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Intervals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Probability: ${(currentProbability * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Avg: ${formatInterval(intervalStats.avg)}", style = MaterialTheme.typography.bodySmall)
                            Text("Median: ${formatInterval(intervalStats.median)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Min: ${formatInterval(intervalStats.min)}", style = MaterialTheme.typography.bodySmall)
                            Text("Max: ${formatInterval(intervalStats.max)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterType == "All",
                    onClick = { filterType = "All" },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = filterType == "Successful",
                    onClick = { filterType = "Successful" },
                    label = { Text("Successful") }
                )
                FilterChip(
                    selected = filterType == "Accidents",
                    onClick = { filterType = "Accidents" },
                    label = { Text("Accidents") }
                )
                FilterChip(
                    selected = filterType == "Sensations",
                    onClick = { filterType = "Sensations" },
                    label = { Text("Sensations") }
                )
            }

            if (filteredEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No events found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredEvents.size) { index ->
                        val event = filteredEvents[index]
                        val interval = if (event.eventType == "pee") {
                            val previousEvent = peeEvents.filter { it.timestamp < event.timestamp }.maxByOrNull { it.timestamp }
                            previousEvent?.let { formatInterval(event.timestamp - it.timestamp) }
                        } else null

                        EventCard(
                            event = event,
                            interval = interval,
                            onDelete = { viewModel.deleteEvent(event.eventId) }
                        )
                    }
                }
            }
        }
    }

    if (dialogType == "chooser") {
        AlertDialog(
            onDismissRequest = { dialogType = null },
            title = { Text("Log Event") },
            text = {
                Column {
                    TextButton(onClick = { dialogType = "pee" }, modifier = Modifier.fillMaxWidth()) { 
                        Text("Log Pee") 
                    }
                    TextButton(onClick = { dialogType = "sensation" }, modifier = Modifier.fillMaxWidth()) { 
                        Text("Log Sensation") 
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (dialogType == "pee") {
        AddEventDialog(
            childId = childId,
            onDismiss = { dialogType = null },
            onSave = { event ->
                viewModel.saveEvent(event)
                dialogType = null
            }
        )
    }

    if (dialogType == "sensation") {
        AddSensationDialog(
            childId = childId,
            currentProbability = currentProbability,
            onDismiss = { dialogType = null },
            onSave = { event ->
                viewModel.saveEvent(event)
                dialogType = null
            }
        )
    }
}

fun formatInterval(millis: Long): String {
    val totalMins = millis / (1000 * 60)
    val hours = totalMins / 60
    val mins = totalMins % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

data class IntervalStats(
    val avg: Long,
    val min: Long,
    val max: Long,
    val median: Long
)

@Composable
fun EventCard(event: TrackerEvent, interval: String?, onDelete: () -> Unit) {
    val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    val dateString = format.format(Date(event.timestamp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("event_card"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (event.eventType == "sensation") {
                    Text(
                        text = "Sensation Log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Time: $dateString", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Feeling: ${event.sensationLevel ?: "Unknown"}", style = MaterialTheme.typography.bodyMedium)
                    if (event.predictedProbability != null) {
                        Text(
                            text = "Predicted: ${(event.predictedProbability * 100).toInt()}%", 
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text(
                        text = if (event.isAccident) "Accident" else "Successful",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (event.isAccident) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Time: $dateString", style = MaterialTheme.typography.bodyMedium)
                    if (event.volumeLevel != null) {
                        Text(text = "Volume: Level ${event.volumeLevel}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (interval != null) {
                        Text(
                            text = "Time since previous: $interval", 
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_event_button")) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Event", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun AddEventDialog(
    childId: String,
    onDismiss: () -> Unit,
    onSave: (TrackerEvent) -> Unit
) {
    var isAccident by remember { mutableStateOf(false) }
    var volumeLevel by remember { mutableStateOf<Int?>(2) } // default medium

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Log pee event",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isAccident,
                        onCheckedChange = { isAccident = it },
                        modifier = Modifier.testTag("accident_checkbox")
                    )
                    Text("Was it an accident?")
                }

                Text("Volume Level: ${volumeLevel ?: "?"}")
                Slider(
                    value = (volumeLevel ?: 2).toFloat(),
                    onValueChange = { volumeLevel = it.toInt() },
                    valueRange = 1f..3f,
                    steps = 1,
                    modifier = Modifier.testTag("volume_slider")
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Small", style = MaterialTheme.typography.bodySmall)
                    Text("Medium", style = MaterialTheme.typography.bodySmall)
                    Text("Large", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                TrackerEvent(
                                    childId = childId,
                                    timestamp = System.currentTimeMillis(),
                                    eventType = "pee",
                                    volumeLevel = volumeLevel,
                                    isAccident = isAccident
                                )
                            )
                        },
                        modifier = Modifier.testTag("save_event_button")
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
fun AddSensationDialog(
    childId: String,
    currentProbability: Float,
    onDismiss: () -> Unit,
    onSave: (TrackerEvent) -> Unit
) {
    var sensationLevel by remember { mutableStateOf("ничего") }
    val options = listOf("ничего", "немного", "заметно", "сильно")
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Что чувствует животик?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // The options logic
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sensationLevel == option,
                            onClick = { sensationLevel = option }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = option)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                TrackerEvent(
                                    childId = childId,
                                    timestamp = System.currentTimeMillis(),
                                    eventType = "sensation",
                                    sensationLevel = sensationLevel,
                                    predictedProbability = currentProbability
                                )
                            )
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
