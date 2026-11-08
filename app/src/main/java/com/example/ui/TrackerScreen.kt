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
import com.example.data.FlowPatterns
import com.example.data.Initiators
import com.example.data.TrackerEvent
import com.example.ml.BayesianSurvivalModel
import com.example.ml.AccidentModel
import com.example.ml.BladderModel
import com.example.ml.Coaching
import com.example.ml.InteroSuccess
import com.example.ml.UroflowAnalyzer
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
    // Session state for the potty scale: timestamped readings feed the uroflow analysis.
    var activeEvent by remember { mutableStateOf<TrackerEvent?>(null) }
    var previousMaxWeight by remember { mutableStateOf(0) }
    val sessionSamples = remember { mutableListOf<UroflowAnalyzer.Sample>() }
    val preBuffer = remember { ArrayDeque<UroflowAnalyzer.Sample>() }

    LaunchedEffect(fillLevel) {
        val currentWeight = fillLevel ?: 0
        val now = System.currentTimeMillis()
        val sample = UroflowAnalyzer.Sample(now, currentWeight.toDouble())
        if (currentWeight >= 15) { // Minimum threshold weight of 15 g to count as a potty session
            val active = activeEvent
            if (active == null) {
                // Starting a new potty session; keep the readings just before it to catch the stream start.
                sessionSamples.clear()
                sessionSamples.addAll(preBuffer)
                sessionSamples.add(sample)
                val flow = UroflowAnalyzer.analyze(sessionSamples)
                val newEvent = TrackerEvent(
                    childId = childId,
                    timestamp = flow?.streamStartMs ?: now,
                    eventType = EventTypes.PEE,
                    volumeLevel = currentWeight, // raw grams from the scale
                    voidedVolumeMl = BladderModel.gramsToMl(currentWeight),
                    isAccident = false,
                    source = "potty"
                )
                activeEvent = newEvent
                previousMaxWeight = currentWeight
                save(newEvent)
            } else {
                sessionSamples.add(sample)
                if (currentWeight > previousMaxWeight) {
                    // Ongoing session: update volume and flow summary, keep the start time and other fields
                    previousMaxWeight = currentWeight
                    val flow = UroflowAnalyzer.analyze(sessionSamples)
                    val updated = active.copy(
                        volumeLevel = currentWeight,
                        voidedVolumeMl = BladderModel.gramsToMl(currentWeight),
                        flowMaxMlPerSec = flow?.maxFlowMlPerSec?.toFloat(),
                        flowPattern = flow?.pattern
                    )
                    activeEvent = updated
                    save(updated)
                }
            }
        } else {
            preBuffer.addLast(sample)
            while (preBuffer.isNotEmpty() && now - preBuffer.first().timestampMs > 5_000) preBuffer.removeFirst()
            if (currentWeight < 10) {
                // Potty is dry or emptied, reset session lock
                activeEvent = null
                previousMaxWeight = 0
            }
        }
    }

    val peeEvents = remember(allEvents) { allEvents.filter { it.eventType == EventTypes.PEE } }

    val filteredEvents = remember(allEvents, filterType) {
        when (filterType) {
            "Successful" -> allEvents.filter { it.eventType == EventTypes.PEE && !it.isAccident }
            "Accidents" -> allEvents.filter { (it.eventType == EventTypes.PEE && it.isAccident) || it.eventType == EventTypes.LEAK }
            "Sensations" -> allEvents.filter { it.eventType == EventTypes.SENSATION }
            else -> allEvents
        }
    }

    val totalCount = peeEvents.size
    val successCount = peeEvents.count { !it.isAccident }
    val accidentCount = peeEvents.count { it.isAccident }

    val mostRecentPee = remember(peeEvents) { peeEvents.maxByOrNull { it.timestamp } }
    val coachingSettings by viewModel.coachingSettings(childId).collectAsStateWithLifecycle()
    fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 800)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // At most one prompt/command per interval (keyed by the last void).
    var handledForVoid by remember { mutableStateOf<Long?>(null) }
    var coachDialog by remember { mutableStateOf<String?>(null) } // "go", "hold", "release", "feedback"
    var holdDialogMin by remember { mutableStateOf(0) }
    val decision = analytics?.decision
    val lastVoidTs = mostRecentPee?.timestamp
    LaunchedEffect(decision?.action, lastVoidTs) {
        val action = decision?.action ?: return@LaunchedEffect
        if (action == Coaching.Action.NONE || handledForVoid == lastVoidTs) return@LaunchedEffect
        handledForVoid = lastVoidTs
        val now = System.currentTimeMillis()
        when (action) {
            Coaching.Action.PROMPT_GO -> {
                save(TrackerEvent(childId = childId, timestamp = now, eventType = EventTypes.PROMPT, predictedProbability = currentProbability))
                coachDialog = "go"
            }
            Coaching.Action.COMMAND_HOLD -> {
                val minutes = decision.holdTargetMin ?: 1
                save(
                    TrackerEvent(
                        childId = childId, timestamp = now, eventType = EventTypes.COMMAND,
                        holdTargetMin = minutes, commandProbability = currentProbability
                    )
                )
                holdDialogMin = minutes
                coachDialog = "hold"
            }
            Coaching.Action.NONE -> {}
        }
        beep()
    }

    // End of a hold countdown: tell the child they may go now.
    val releaseTs = analytics?.activeHoldReleaseTs
    LaunchedEffect(releaseTs) {
        val ts = releaseTs ?: return@LaunchedEffect
        val wait = ts - System.currentTimeMillis()
        if (wait < -10 * 60_000L) return@LaunchedEffect // long past (screen reopened later)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        coachDialog = "release"
        beep()
    }

    // Feedback *after* a void, never before: "ты почувствовал вовремя!".
    val latestScore = analytics?.success?.scores?.maxByOrNull { it.timestamp }
    var feedbackShownFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(latestScore?.eventId, latestScore?.success) {
        val score = latestScore ?: return@LaunchedEffect
        val fresh = System.currentTimeMillis() - score.timestamp < 10 * 60_000L
        if (score.success && fresh && feedbackShownFor != score.eventId) {
            feedbackShownFor = score.eventId
            coachDialog = "feedback"
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
                SuccessCard(snapshot)
                InteroceptionCard(snapshot.interoception)
            }

            analytics?.let { snapshot ->
                CoachingCard(snapshot, onModeChange = { mode ->
                    viewModel.updateCoachingSettings(childId, coachingSettings.copy(mode = mode))
                })
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
                            details = eventDetails(event, analytics),
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
                    TextButton(
                        onClick = {
                            save(TrackerEvent(childId = childId, timestamp = System.currentTimeMillis(), eventType = EventTypes.LEAK, source = "manual"))
                            dialogType = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("leak_button")
                    ) {
                        Text("Протечка (капли, не авария)")
                    }
                    TextButton(
                        onClick = {
                            save(TrackerEvent(childId = childId, timestamp = System.currentTimeMillis(), eventType = EventTypes.SIT, source = "manual"))
                            dialogType = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("sit_button")
                    ) {
                        Text("Сел на горшок")
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

    when (coachDialog) {
        "go" -> CoachDialog(
            title = "Пора на горшок",
            text = "Похоже, скоро захочется. Сходим?",
            onDismiss = { coachDialog = null }
        )
        "hold" -> CoachDialog(
            title = "Потерпи $holdDialogMin мин",
            text = "Прислушайся к животику. Через $holdDialogMin мин можно будет пойти.",
            onDismiss = { coachDialog = null },
            secondaryLabel = "Не дотерпел (авария)",
            onSecondary = {
                save(TrackerEvent(childId = childId, timestamp = System.currentTimeMillis(), eventType = EventTypes.PEE, isAccident = true, source = "manual"))
                coachDialog = null
            }
        )
        "release" -> CoachDialog(
            title = "Молодец! Теперь можно",
            text = "Теперь можно идти на горшок.",
            onDismiss = { coachDialog = null }
        )
        "feedback" -> CoachDialog(
            title = "Ты почувствовал вовремя! 🎉",
            text = "Сам пошёл, и пузырь был полный — именно так.",
            onDismiss = { coachDialog = null }
        )
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
fun EventCard(event: TrackerEvent, interval: String?, details: List<String> = emptyList(), onDelete: () -> Unit) {
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
                if (event.eventType in listOf(
                        EventTypes.DRINK, EventTypes.SLEEP, EventTypes.WAKE,
                        EventTypes.LEAK, EventTypes.SIT, EventTypes.PROMPT, EventTypes.COMMAND
                    )
                ) {
                    Text(
                        text = when (event.eventType) {
                            EventTypes.DRINK -> "Питьё: ${event.fluidIntakeMl ?: "?"} мл"
                            EventTypes.SLEEP -> "Уснул"
                            EventTypes.WAKE -> "Проснулся"
                            EventTypes.LEAK -> "Протечка"
                            EventTypes.SIT -> "Сел на горшок"
                            EventTypes.PROMPT -> "Подсказка «пора»"
                            else -> "Потерпи ${event.holdTargetMin ?: "?"} мин"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(text = "Time: $dateString", style = MaterialTheme.typography.bodyMedium)
                    details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                    details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
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
    var initiator by remember { mutableStateOf(Initiators.CHILD) }
    
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
                    text = "Log pee event",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Кто начал:", style = MaterialTheme.typography.bodyMedium)
                    FilterChip(selected = initiator == Initiators.CHILD, onClick = { initiator = Initiators.CHILD }, label = { Text("Сам") })
                    FilterChip(selected = initiator == Initiators.PARENT, onClick = { initiator = Initiators.PARENT }, label = { Text("Напомнили") })
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isAccident,
                        onCheckedChange = { isAccident = it },
                        modifier = Modifier.testTag("accident_checkbox")
                    )
                    Text(if (isAccident) "Авария (объём оценит модель)" else "Was it an accident?")
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
                                    // An accident's volume is not measured: the model estimates it.
                                    volumeLevel = if (isAccident) null else volumeValue,
                                    voidedVolumeMl = if (isAccident) null else volumeValue,
                                    isAccident = isAccident,
                                    holdingBehavior = holdingLevel > 0,
                                    holdingLevel = holdingLevel,
                                    sensationLevel = sensationLevel,
                                    initiator = initiator,
                                    source = "manual"
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
        "strict_command", "command_probability", "command_result", "model_version",
        "initiator", "hold_target_min", "latency_ms", "flow_max_ml_s", "flow_pattern", "volume_estimated", "source"
    ).joinToString(",")
    val rows = events.sortedBy { it.timestamp }.map { e ->
        listOf(
            iso.format(Date(e.timestamp)), e.timestamp, e.eventType, e.voidedVolumeMl, e.volumeLevel, e.isAccident,
            e.sensationLevel, e.holdingLevel, e.fluidIntakeMl, e.predictedProbability, e.estimatedBladderCapacityMl,
            e.isStrictCommand, e.commandProbability, e.commandResult, e.modelVersion,
            e.initiator, e.holdTargetMin, e.latencyMs, e.flowMaxMlPerSec, e.flowPattern, e.volumeEstimated, e.source
        ).joinToString(",") { q(it) }
    }
    return (listOf(header) + rows).joinToString("\n")
}

/** Extra per-event lines derived from analytics (initiator, latency, flow, estimates, leak verdict). */
fun eventDetails(event: TrackerEvent, snapshot: TrackerAnalytics.Snapshot?): List<String> {
    val out = mutableListOf<String>()
    when (event.eventType) {
        EventTypes.PEE -> {
            val score = snapshot?.success?.scores?.firstOrNull { it.eventId == event.eventId }
            if (score != null) {
                out.add(if (score.initiator == Initiators.CHILD) "Пошёл сам" else if (score.initiator == Initiators.APP) "По подсказке приложения" else "Напомнили")
                score.fill?.let { out.add("Пузырь был заполнен ≈ ${(it * 100).roundToInt()}%") }
                score.latencyMs?.let { out.add("От посадки до потока: ${it / 1000} с") }
                if (score.success) out.add("✓ Интероцептивный успех")
            }
            when (event.flowPattern) {
                FlowPatterns.BELL -> out.add("Поток ровный" + (event.flowMaxMlPerSec?.let { ", пик ${"%.1f".format(it)} мл/с" } ?: ""))
                FlowPatterns.INTERRUPTED -> out.add("Поток прерывистый — был напряжён/стеснялся")
            }
            snapshot?.accidentVolumeEstimates?.get(event.eventId)?.let { out.add("Объём (оценка модели): ≈ ${it.roundToInt()} мл") }
            if (event.volumeEstimated) out.add("Протечка оказалась аварией (по следующему объёму)")
        }
        EventTypes.LEAK -> {
            when (snapshot?.leaks?.firstOrNull { it.eventId == event.eventId }?.verdict) {
                AccidentModel.LeakVerdict.LEAK -> out.add("Подтверждено: пузырь не опорожнился")
                AccidentModel.LeakVerdict.EMPTIED -> out.add("Похоже, это была авария: следующий объём слишком мал")
                else -> out.add("Проверится при следующем походе на горшок")
            }
        }
        EventTypes.COMMAND -> {
            val trial = snapshot?.staircase?.trials?.firstOrNull { it.commandTs == event.timestamp }
            out.add(
                when (trial?.outcome) {
                    Coaching.HoldOutcome.SUCCESS -> "Дотерпел ✓"
                    Coaching.HoldOutcome.FAILED_ACCIDENT -> "Авария/протечка до конца"
                    Coaching.HoldOutcome.FAILED_EARLY -> "Пошёл раньше времени"
                    else -> "Идёт…"
                }
            )
        }
    }
    return out
}

@Composable
fun CoachDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onDismiss, modifier = Modifier.testTag("coach_ok_button")) { Text("OK") } },
        dismissButton = if (secondaryLabel != null && onSecondary != null) {
            { TextButton(onClick = onSecondary) { Text(secondaryLabel, color = MaterialTheme.colorScheme.error) } }
        } else null
    )
}

@Composable
fun CoachingCard(snapshot: TrackerAnalytics.Snapshot, onModeChange: (Coaching.Mode) -> Unit) {
    val mode = snapshot.settings.mode
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("coaching_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val onColor = MaterialTheme.colorScheme.onTertiaryContainer
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = mode == Coaching.Mode.QUIET, onClick = { onModeChange(Coaching.Mode.QUIET) }, label = { Text("Тихо") })
                FilterChip(selected = mode == Coaching.Mode.SAFETY, onClick = { onModeChange(Coaching.Mode.SAFETY) }, label = { Text("Страховка") })
                FilterChip(selected = mode == Coaching.Mode.HOLD_TRAINING, onClick = { onModeChange(Coaching.Mode.HOLD_TRAINING) }, label = { Text("Тренировка") })
            }
            Text(
                when (mode) {
                    Coaching.Mode.QUIET -> "Приложение молчит и только измеряет — ребёнок учится слушать тело сам."
                    Coaching.Mode.SAFETY -> "Подсказка только перед вероятной аварией (порог ${pct(snapshot.decision.safetyThreshold)}; растёт с успехами ребёнка)."
                    Coaching.Mode.HOLD_TRAINING -> "«Потерпи» при сильном позыве: сейчас ${snapshot.decision.holdTargetMin ?: snapshot.staircase.nextTargetMin} мин " +
                        "(лестница: +${snapshot.settings.stepMin} мин после 3 успехов подряд, −${snapshot.settings.stepMin} после неудачи)."
                },
                style = MaterialTheme.typography.bodySmall, color = onColor
            )
            if (mode == Coaching.Mode.HOLD_TRAINING) {
                val done = snapshot.staircase.trials.count { it.outcome != Coaching.HoldOutcome.PENDING }
                Text(
                    "Удержаний: $done, успешных за последние 10: ${pct(snapshot.staircase.recentSuccessRate)}",
                    style = MaterialTheme.typography.bodySmall, color = onColor
                )
            }
            snapshot.activeHoldReleaseTs?.let { release ->
                val left = ((release - System.currentTimeMillis()) / 60_000.0).coerceAtLeast(0.0)
                Text("Идёт «потерпи»: осталось ≈ ${"%.0f".format(kotlin.math.ceil(left))} мин", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = onColor)
            }
        }
    }
}

@Composable
fun SuccessCard(snapshot: TrackerAnalytics.Snapshot) {
    val r = snapshot.success
    if (r.scores.isEmpty() && r.weeks.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("success_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val onColor = MaterialTheme.colorScheme.onPrimaryContainer
            Text("Интероцептивный успех", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onColor)
            Text(
                "За 14 дней: ${pct(r.recentSuccessRate)} походов — сам, вовремя, с полным пузырём (≥${(InteroSuccess.MIN_FILL * 100).toInt()}%)",
                style = MaterialTheme.typography.bodySmall, color = onColor
            )
            val weeks = r.weeks.takeLast(4)
            if (weeks.isNotEmpty()) {
                Text(
                    "По неделям: " + weeks.joinToString(" → ") { pct(it.successRate) } +
                        (weeks.last().let { " (аварий ${it.accidents}, протечек ${it.leaks}, рано сел ${it.earlySits})" }),
                    style = MaterialTheme.typography.bodySmall, color = onColor
                )
            }
            r.latencyNorm?.let {
                Text("Обычно поток через ${it.medianMs / 1000} с после посадки (n=${it.n})", style = MaterialTheme.typography.bodySmall, color = onColor)
            }
            if (r.difficultyRelaxing) {
                Text("Даже с полным пузырём долго не начинается — похоже, трудно расслабиться (не ошибка интероцепции)",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = onColor)
            }
            val b = snapshot.bladder
            val trend = snapshot.model.intervalTrendPerWeek
            Text(
                "Пузырь растёт: ${"%+.0f".format(b.capacityGrowthMlPerWeek)} мл/нед · интервал ${"%+.0f".format(trend * 100)}%/нед",
                style = MaterialTheme.typography.bodySmall, color = onColor
            )
            snapshot.interoception.sensitivity?.thresholdPct?.let {
                Text("Порог различения: ≈ ${it.roundToInt()}% наполненности (меньше = чутче)", style = MaterialTheme.typography.bodySmall, color = onColor)
            }
        }
    }
}
