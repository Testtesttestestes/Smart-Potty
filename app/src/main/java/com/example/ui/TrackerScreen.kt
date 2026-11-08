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
import com.example.data.EventTypes
import com.example.data.TrackerEvent
import com.example.ml.BayesianSurvivalModel
import com.example.ml.BladderModel
import com.example.ml.InteroceptionMetrics
import com.example.ml.SignalModel
import com.example.ml.TrackerAnalytics
import kotlin.math.roundToInt
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

    val analytics by remember(childId) { viewModel.analyticsForChild(childId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val currentProbability = analytics?.probNext5 ?: 0f

    // Every saved event gets the current capacity estimate (pee) and the model version of any stored probability.
    fun save(event: TrackerEvent) {
        val snapshot = analytics
        var e = event
        if (e.eventType == EventTypes.PEE && e.estimatedBladderCapacityMl == null && snapshot != null) {
            e = e.copy(estimatedBladderCapacityMl = snapshot.bladder.personalCapacityMl.roundToInt())
        }
        if (snapshot == null || !snapshot.model.hasLastVoid) {
            // No real prediction available yet: don't store a fake 0% that would distort the metrics.
            e = e.copy(predictedProbability = null, commandProbability = null)
        }
        if ((e.predictedProbability != null || e.commandProbability != null) && e.modelVersion == null) {
            e = e.copy(modelVersion = BayesianSurvivalModel.MODEL_VERSION)
        }
        viewModel.saveEvent(e)
    }

    val fillLevel by bleViewModel.fillLevel.collectAsStateWithLifecycle()
    // Session state for the bottom weight sensor scale
    var activeEvent by remember { mutableStateOf<TrackerEvent?>(null) }
    var previousMaxWeight by remember { mutableStateOf(0) }

    LaunchedEffect(fillLevel) {
        val currentWeight = fillLevel ?: 0
        if (currentWeight >= 15) { // Minimum threshold weight of 15 g to count as a potty session
            val active = activeEvent
            if (active == null) {
                // Starting a new potty session
                val newEvent = TrackerEvent(
                    childId = childId,
                    timestamp = System.currentTimeMillis(),
                    eventType = EventTypes.PEE,
                    volumeLevel = currentWeight, // raw grams from the scale
                    voidedVolumeMl = BladderModel.gramsToMl(currentWeight),
                    isAccident = false
                )
                activeEvent = newEvent
                previousMaxWeight = currentWeight
                save(newEvent)
            } else if (currentWeight > previousMaxWeight) {
                // Ongoing session: update the volume only, keep the session start time and other fields
                previousMaxWeight = currentWeight
                val updated = active.copy(
                    volumeLevel = currentWeight,
                    voidedVolumeMl = BladderModel.gramsToMl(currentWeight)
                )
                activeEvent = updated
                save(updated)
            }
        } else if (currentWeight < 10) {
            // Potty is dry or emptied, reset session lock
            activeEvent = null
            previousMaxWeight = 0
        }
    }

    val peeEvents = remember(allEvents) { allEvents.filter { it.eventType == EventTypes.PEE } }

    val filteredEvents = remember(allEvents, filterType) {
        when (filterType) {
            "Successful" -> allEvents.filter { it.eventType == EventTypes.PEE && !it.isAccident }
            "Accidents" -> allEvents.filter { it.eventType == EventTypes.PEE && it.isAccident }
            "Sensations" -> allEvents.filter { it.eventType == EventTypes.SENSATION }
            else -> allEvents
        }
    }

    val totalCount = peeEvents.size
    val successCount = peeEvents.count { !it.isAccident }
    val accidentCount = peeEvents.count { it.isAccident }

    val mostRecentPee = remember(peeEvents) { peeEvents.maxByOrNull { it.timestamp } }
    var hasTriggeredCommandThisCycle by remember(mostRecentPee) { mutableStateOf(false) }
    var showCommandDialog by remember { mutableStateOf(false) }

    val shouldAlert = analytics?.shouldAlert == true
    LaunchedEffect(shouldAlert, mostRecentPee) {
        if (shouldAlert && !hasTriggeredCommandThisCycle) {
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
                        val exportText = eventsToCsv(allEvents)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Exported Events (CSV)")
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

            analytics?.let { snapshot ->
                ForecastCard(snapshot)
                InteroceptionCard(snapshot.interoception)
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
                        text = "Вероятность позыва в ближайшие 5 минут ≥${(TrackerAnalytics.COMMAND_THRESHOLD * 100).toInt()}% (время + свежие ощущения и поведение удержания) запускает приказ «Потерпи еще немного». Во время сна приказ не срабатывает.",
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
                        val interval = if (event.eventType == EventTypes.PEE) {
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
                    TextButton(onClick = { dialogType = "drink" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Питьё")
                    }
                    val asleep = analytics?.isAsleepNow == true
                    TextButton(
                        onClick = {
                            save(
                                TrackerEvent(
                                    childId = childId,
                                    timestamp = System.currentTimeMillis(),
                                    eventType = if (asleep) EventTypes.WAKE else EventTypes.SLEEP
                                )
                            )
                            dialogType = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("sleep_toggle_button")
                    ) {
                        Text(if (asleep) "Проснулся" else "Уснул")
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
                save(event)
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
                save(event)
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
                        text = "Вероятность позыва в ближайшие 5 минут — ${(currentProbability * 100).toInt()}%. Горшок дает приказ:\n\n«ПОТЕРПИ ЕЩЕ НЕМНОГО»",
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
                            save(
                                TrackerEvent(
                                    childId = childId,
                                    timestamp = System.currentTimeMillis(),
                                    eventType = EventTypes.PEE,
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

    if (dialogType == "drink") {
        AddDrinkDialog(
            childId = childId,
            onDismiss = { dialogType = null },
            onSave = { event ->
                save(event)
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
                save(event)
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
                if (event.eventType == EventTypes.DRINK || event.eventType == EventTypes.SLEEP || event.eventType == EventTypes.WAKE) {
                    Text(
                        text = when (event.eventType) {
                            EventTypes.DRINK -> "Питьё: ${event.fluidIntakeMl ?: "?"} мл"
                            EventTypes.SLEEP -> "Уснул"
                            else -> "Проснулся"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(text = "Time: $dateString", style = MaterialTheme.typography.bodyMedium)
                } else if (event.eventType == EventTypes.SENSATION) {
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
                        Text(text = "Voided Volume: $displayVol ml", style = MaterialTheme.typography.bodyMedium)
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
                                    eventType = EventTypes.PEE,
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
                                    eventType = EventTypes.SENSATION,
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

private fun pct(x: Double?): String = x?.let { "${(it * 100).roundToInt()}%" } ?: "—"

@Composable
fun ForecastCard(snapshot: TrackerAnalytics.Snapshot) {
    val m = snapshot.model
    val b = snapshot.bladder
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("forecast_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Прогноз", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Column(horizontalAlignment = Alignment.End) {
                    if (snapshot.isAsleepNow) {
                        Text("Спит", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    } else if (m.hasLastVoid) {
                        Text("В ближайшие 5 мин: ${pct(snapshot.probNext5.toDouble())}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Text("Уверенность: ${pct(m.confidence.toDouble())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Text(
                "Обычный интервал: ${formatInterval(m.medianIntervalMillis)} (80%: ${formatInterval(m.interval80Millis.first)}–${formatInterval(m.interval80Millis.second)})",
                style = MaterialTheme.typography.bodySmall
            )
            if (m.hasLastVoid && !snapshot.isAsleepNow) {
                Text(
                    "Только по времени: 5 мин ${pct(m.probNext5.toDouble())} · 30 мин ${pct(m.probNext30.toDouble())} · 60 мин ${pct(m.probNext60.toDouble())}" +
                        (m.medianTimeLeftMillis?.let { " · осталось ≈ ${formatInterval(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Пузырь: ёмкость ≈ ${b.personalCapacityMl.roundToInt()} мл (по возрасту ${b.expectedCapacityMl.roundToInt()})" +
                    (b.currentFill?.let { " · наполнен ≈ ${pct(it.coerceAtMost(1.5))}" } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )
            val sig = snapshot.currentSignal
            if (sig != SignalModel.Signal.NONE) {
                Text(
                    "Сейчас: ощущение «${InteroceptionMetrics.LEVELS[sig.sensation]}», удержание ${sig.holding}/3 → шансы ×${"%.1f".format(kotlin.math.exp(snapshot.signalCoefficients.logOdds(sig)))}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (m.hasLastVoid && !snapshot.isAsleepNow) {
                Text(
                    "Для точного 5-минутного прогноза отмечайте ощущения и удержание",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val cal = snapshot.calibration
            Text(
                if (cal.n < 10 || cal.brier == null) "Проверка точности на истории: мало данных (дневных интервалов: ${snapshot.dayIntervalCount})"
                else "Проверка на истории: Brier ${"%.2f".format(cal.brier)} (n=${cal.n})" +
                    (cal.skill?.let { ", лучше базового на ${pct(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InteroceptionCard(r: InteroceptionMetrics.Result) {
    if (r.pairCount == 0 && r.comparison.n == 0) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("interoception_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val onColor = MaterialTheme.colorScheme.onSecondaryContainer
            Text("Интероцепция", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onColor)
            r.levels.filter { it.n > 0 }.forEach { l ->
                val range = if (l.q25FillPct != null && l.q75FillPct != null && l.n >= 3)
                    " (${l.q25FillPct.roundToInt()}–${l.q75FillPct.roundToInt()}%)" else ""
                Text(
                    "«${l.level}»: пузырь ≈ ${l.medianFillPct?.roundToInt() ?: "?"}%$range, n=${l.n}",
                    style = MaterialTheme.typography.bodySmall, color = onColor
                )
            }
            r.sensationFillCorrelation?.let {
                Text("Связь ощущения с наполнением: ρ = ${"%.2f".format(it)}", style = MaterialTheme.typography.bodySmall, color = onColor)
            }
            val c = r.comparison
            val phaseText = when (c.phase) {
                InteroceptionMetrics.Phase.INSUFFICIENT_DATA -> "мало данных для сравнения с моделью (n=${c.n})"
                InteroceptionMetrics.Phase.A_MODEL_BETTER -> "фаза A: модель предсказывает лучше"
                InteroceptionMetrics.Phase.B_SIMILAR -> "фаза B: ребёнок и модель наравне"
                InteroceptionMetrics.Phase.C_CHILD_BETTER -> "фаза C: ребёнок лучше модели"
            }
            val aucText = if (c.aucChild != null && c.aucModel != null)
                " · AUC ребёнок ${"%.2f".format(c.aucChild)} / модель ${"%.2f".format(c.aucModel)}" else ""
            Text(phaseText + aucText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = onColor)
        }
    }
}

@Composable
fun AddDrinkDialog(
    childId: String,
    onDismiss: () -> Unit,
    onSave: (TrackerEvent) -> Unit
) {
    val presets = listOf(50, 100, 150, 200, 250)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сколько выпил?") },
        text = {
            Column {
                presets.forEach { ml ->
                    TextButton(
                        onClick = {
                            onSave(
                                TrackerEvent(
                                    childId = childId,
                                    timestamp = System.currentTimeMillis(),
                                    eventType = EventTypes.DRINK,
                                    fluidIntakeMl = ml
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("drink_preset_$ml")
                    ) { Text("≈ $ml мл") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Full labelled dataset (all fields) for analysis or training future sensors. */
fun eventsToCsv(events: List<TrackerEvent>): String {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    fun q(v: Any?): String {
        val str = v?.toString() ?: ""
        return if (str.any { it == ',' || it == '"' || it == '\n' }) "\"" + str.replace("\"", "\"\"") + "\"" else str
    }
    val header = listOf(
        "time", "timestamp_ms", "event_type", "voided_volume_ml", "raw_weight_g", "is_accident",
        "sensation", "holding_level", "fluid_intake_ml", "predicted_probability", "estimated_capacity_ml",
        "strict_command", "command_probability", "command_result", "model_version"
    ).joinToString(",")
    val rows = events.sortedBy { it.timestamp }.map { e ->
        listOf(
            iso.format(Date(e.timestamp)), e.timestamp, e.eventType, e.voidedVolumeMl, e.volumeLevel, e.isAccident,
            e.sensationLevel, e.holdingLevel, e.fluidIntakeMl, e.predictedProbability, e.estimatedBladderCapacityMl,
            e.isStrictCommand, e.commandProbability, e.commandResult, e.modelVersion
        ).joinToString(",") { q(it) }
    }
    return (listOf(header) + rows).joinToString("\n")
}
