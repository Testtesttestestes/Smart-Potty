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
import android.media.AudioManager
import android.media.ToneGenerator
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
    // Session states for bottom weight sensor scale
    var activeEventId by remember { mutableStateOf<String?>(null) }
    var previousMaxWeight by remember { mutableStateOf(0) }

    LaunchedEffect(fillLevel) {
        val currentWeight = fillLevel ?: 0
        if (currentWeight >= 15) { // Minimum threshold weight of 15g/ml to count as a potty session
            if (activeEventId == null) {
                // Starting a new potty session of child staying & peeing
                val now = System.currentTimeMillis()
                val newEvent = TrackerEvent(
                    childId = childId,
                    timestamp = now,
                    eventType = "pee",
                    volumeLevel = currentWeight,
                    voidedVolumeMl = currentWeight,
                    isAccident = false
                )
                activeEventId = newEvent.eventId
                previousMaxWeight = currentWeight
                viewModel.saveEvent(newEvent)
            } else {
                // Ongoing potty session: if weight increases, update the existing session event rather than creating a new one
                if (currentWeight > previousMaxWeight) {
                    previousMaxWeight = currentWeight
                    viewModel.saveEvent(
                        TrackerEvent(
                            eventId = activeEventId!!,
                            childId = childId,
                            timestamp = System.currentTimeMillis(),
                            eventType = "pee",
                            volumeLevel = currentWeight,
                            voidedVolumeMl = currentWeight,
                            isAccident = false
                        )
                    )
                }
            }
        } else if (currentWeight < 10) {
            // Potty is dry or emptied, reset session lock
            activeEventId = null
            previousMaxWeight = 0
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

    val bayesianModelResult = remember(peeEvents, currentTime) {
        val sortedAsc = peeEvents.sortedBy { it.timestamp }
        val intervals = mutableListOf<Long>()
        for (i in 1 until sortedAsc.size) {
            intervals.add(sortedAsc[i].timestamp - sortedAsc[i - 1].timestamp)
        }
        val mostRecentEvent = peeEvents.maxByOrNull { it.timestamp }
        val timeSinceLast = if (mostRecentEvent != null) currentTime - mostRecentEvent.timestamp else 0L
        
        com.example.ml.BayesianSurvivalModel.analyze(intervals, timeSinceLast)
    }

    val currentProbability = bayesianModelResult.probabilityNow

    val mostRecentPee = remember(peeEvents) { peeEvents.maxByOrNull { it.timestamp } }
    var hasTriggeredCommandThisCycle by remember(mostRecentPee) { mutableStateOf(false) }
    var showCommandDialog by remember { mutableStateOf(false) }

    LaunchedEffect(currentProbability) {
        if (currentProbability >= 0.70f && !hasTriggeredCommandThisCycle) {
            showCommandDialog = true
            hasTriggeredCommandThisCycle = true
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 800)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val calibrationStats = remember(allEvents) {
        val stats = mutableMapOf<String, MutableList<Int>>()
        val sortedAsc = allEvents.sortedBy { it.timestamp }
        
        for (i in sortedAsc.indices) {
            val event = sortedAsc[i]
            if (event.eventType == "pee" && event.voidedVolumeMl != null) {
                // Find most recent sensation event before this pee, within 2 hours
                val recentSensation = sortedAsc.subList(0, i).findLast { 
                    it.eventType == "sensation" && (event.timestamp - it.timestamp) < 2 * 60 * 60 * 1000L 
                } ?: if (event.sensationLevel != null && event.sensationLevel != "ничего") event else null
                
                if (recentSensation?.sensationLevel != null) {
                    val sens = recentSensation.sensationLevel
                    if (sens != "ничего") {
                        if (stats[sens] == null) stats[sens] = mutableListOf()
                        stats[sens]!!.add(event.voidedVolumeMl)
                    }
                }
            }
        }
        
        val results = mutableMapOf<String, Pair<Int, Int>>()
        for ((k, v) in stats) {
            if (v.isNotEmpty()) {
                val min = v.minOrNull() ?: 0
                val max = v.maxOrNull() ?: 0
                results[k] = Pair(min, max)
            }
        }
        results
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
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Probability: ${(currentProbability * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Text("Confidence: ${(bayesianModelResult.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Bayesian Expected: ${formatInterval(bayesianModelResult.expectedIntervalMillis)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Avg: ${formatInterval(intervalStats.avg)}", style = MaterialTheme.typography.bodySmall)
                            Text("Min: ${formatInterval(intervalStats.min)}", style = MaterialTheme.typography.bodySmall)
                            Text("Max: ${formatInterval(intervalStats.max)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (calibrationStats.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Interoceptive Calibration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.height(8.dp))
                        calibrationStats.forEach { (sensation, range) ->
                            Text(
                                text = "Когда ты говоришь \"$sensation\", обычно получается ${if (range.first == range.second) range.first else "${range.first}–${range.second}"} мл",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            val strictEvents = remember(allEvents) { allEvents.filter { it.isStrictCommand } }
            val strictTotal = strictEvents.size
            val strictSuccess = strictEvents.count { it.commandResult == "success" }
            val strictAccidents = strictEvents.count { it.commandResult == "accident" }
            val obedienceRate = if (strictTotal > 0) (strictSuccess * 100) / strictTotal else 100

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Жёсткое обучение (Дисциплина)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Соблюдение команд:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("$obedienceRate%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Успешные удержания: $strictSuccess", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("Аварии удержания: $strictAccidents", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Прогноз позыва >70% запускает приказ «Потерпи еще немного». Обучает ребёнка превосходить модель, опираясь на внутренние сигналы.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
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

    if (dialogType == "pee_strict_success") {
        AddEventDialog(
            childId = childId,
            isStrictHoldingSuccess = true,
            strictProbability = currentProbability,
            onDismiss = { dialogType = null },
            onSave = { event ->
                viewModel.saveEvent(event)
                dialogType = null
            }
        )
    }

    if (showCommandDialog) {
        Dialog(
            onDismissRequest = { /* Cannot dismiss */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().testTag("strict_command_dialog"),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "🚨 ДИСЦИПЛИНА: ПОТЕРПИ!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Text(
                        text = "Вероятность позыва составляет ${(currentProbability * 100).toInt()}%. Горшок дает приказ:\n\n«ПОТЕРПИ ЕЩЕ НЕМНОГО»",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                    
                    Text(
                        text = "Ребёнок должен подчиниться правилу горшка. Поход в туалет временно откладывается.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = {
                            dialogType = "pee_strict_success"
                            showCommandDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("strict_success_button")
                    ) {
                        Text("Удержал успешно (Иду на горшок)", fontWeight = FontWeight.Bold)
                    }
                    
                    FilledTonalButton(
                        onClick = {
                            viewModel.saveEvent(
                                TrackerEvent(
                                    childId = childId,
                                    timestamp = System.currentTimeMillis(),
                                    eventType = "pee",
                                    isAccident = true,
                                    isStrictCommand = true,
                                    commandProbability = currentProbability,
                                    commandResult = "accident",
                                    sensationLevel = "сильно"
                                )
                            )
                            showCommandDialog = false
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("strict_accident_button")
                    ) {
                        Text("Не удержал (Авария)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
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
                    if (event.holdingLevel != null && event.holdingLevel > 0) {
                        val holdingLabel = when (event.holdingLevel) {
                            1 -> "Легкий (ерзает)"
                            2 -> "Явный (сжимает ножки)"
                            3 -> "Критический (терпит из последних сил)"
                            else -> "Нет"
                        }
                        Text(text = "Holding: $holdingLabel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
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
                    
                    if (event.isStrictCommand) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (event.commandResult == "success") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "Дисциплина: " + (if (event.commandResult == "success") "Успешно удержано" else "Авария при удержании"),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (event.commandResult == "success") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    Text(text = "Time: $dateString", style = MaterialTheme.typography.bodyMedium)
                    val displayVol = event.voidedVolumeMl ?: event.volumeLevel
                    if (displayVol != null) {
                        Text(text = "Voided Volume: $displayVol ml ($displayVol g)", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (event.holdingLevel != null && event.holdingLevel > 0) {
                        val holdingLabel = when (event.holdingLevel) {
                            1 -> "Легкий (ерзает)"
                            2 -> "Явный (сжимает ножки)"
                            3 -> "Критический (терпит из последних сил)"
                            else -> "Нет"
                        }
                        Text(text = "Holding: $holdingLabel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                    if (event.estimatedBladderCapacityMl != null) {
                        Text(text = "Est. Bladder Capacity: ${event.estimatedBladderCapacityMl} ml", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
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
    onSave: (TrackerEvent) -> Unit,
    isStrictHoldingSuccess: Boolean = false,
    strictProbability: Float? = null
) {
    var isAccident by remember { mutableStateOf(false) }
    
    // We define clean intuitive presets to avoid pseudo-accurate data entries
    val presets = remember {
        listOf(
            Pair(50, "Очень мало (≈50 мл)"),
            Pair(100, "Мало (≈100 мл)"),
            Pair(150, "Средне (≈150 мл)"),
            Pair(200, "Много (≈200 мл)"),
            Pair(300, "Очень много (≈300 мл)")
        )
    }
    
    var selectedIndex by remember { mutableStateOf(2) } // default to Medium (150 ml)
    var holdingLevel by remember { mutableStateOf(0) } // Default: No holding behavior
    val holdingLabels = listOf("Нет", "Легкий (ерзает)", "Явный (крестит ножки)", "Критический (терпит изо последних сил)")

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
                    text = if (isStrictHoldingSuccess) "Дисциплина: Успешное Удержание!" else "Log pee event",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isStrictHoldingSuccess) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified
                )

                if (isStrictHoldingSuccess) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(
                            text = "Ребёнок удержал позыв по приказу горшка! Запишите фактический объём.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isAccident,
                        onCheckedChange = { isAccident = it },
                        modifier = Modifier.testTag("accident_checkbox")
                    )
                    Text("Was it an accident?")
                }

                Text(
                    text = "Объем: ${presets[selectedIndex].second}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Slider(
                    value = selectedIndex.toFloat(),
                    onValueChange = { selectedIndex = it.toInt() },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.testTag("volume_slider")
                )
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50 мл", style = MaterialTheme.typography.bodySmall)
                    Text("150 мл", style = MaterialTheme.typography.bodySmall)
                    Text("300 мл", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))
                
                var sensationLevel by remember { mutableStateOf("ничего") }
                val options = listOf("ничего", "немного", "заметно", "сильно")

                Text("Что чувствовал?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    options.forEach { option ->
                        FilterChip(
                            selected = sensationLevel == option,
                            onClick = { sensationLevel = option },
                            label = { Text(option, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Поведение удержания: ${holdingLabels[holdingLevel]}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )

                Slider(
                    value = holdingLevel.toFloat(),
                    onValueChange = { holdingLevel = it.toInt() },
                    valueRange = 0f..3f,
                    steps = 2,
                    modifier = Modifier.testTag("holding_slider")
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Нет", style = MaterialTheme.typography.bodySmall)
                    Text("Явный", style = MaterialTheme.typography.bodySmall)
                    Text("Критический", style = MaterialTheme.typography.bodySmall)
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
                            val volumeValue = presets[selectedIndex].first
                            onSave(
                                TrackerEvent(
                                    childId = childId,
                                    timestamp = System.currentTimeMillis(),
                                    eventType = "pee",
                                    volumeLevel = volumeValue,
                                    voidedVolumeMl = volumeValue,
                                    isAccident = isAccident,
                                    holdingBehavior = holdingLevel > 0,
                                    holdingLevel = holdingLevel,
                                    sensationLevel = sensationLevel,
                                    isStrictCommand = isStrictHoldingSuccess,
                                    commandProbability = strictProbability,
                                    commandResult = if (isStrictHoldingSuccess) (if (isAccident) "accident" else "success") else null
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
    
    var holdingLevel by remember { mutableStateOf(0) } // Default: No holding behavior
    val holdingLabels = listOf("Нет", "Легкий (ерзает)", "Явный (крестит ножки)", "Критический (терпит изо последних сил)")

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

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Поведение удержания: ${holdingLabels[holdingLevel]}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )

                Slider(
                    value = holdingLevel.toFloat(),
                    onValueChange = { holdingLevel = it.toInt() },
                    valueRange = 0f..3f,
                    steps = 2,
                    modifier = Modifier.testTag("holding_slider_sensation")
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Нет", style = MaterialTheme.typography.bodySmall)
                    Text("Явный", style = MaterialTheme.typography.bodySmall)
                    Text("Критический", style = MaterialTheme.typography.bodySmall)
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
                                    predictedProbability = currentProbability,
                                    holdingBehavior = holdingLevel > 0,
                                    holdingLevel = holdingLevel
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
