package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Child
import com.example.viewmodel.ChildViewModel
import java.util.UUID

import androidx.compose.material.icons.filled.Bluetooth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProfileScreen(
    viewModel: ChildViewModel, 
    onNavigateToTracker: (String) -> Unit,
    onNavigateToBle: () -> Unit
) {
    val children by viewModel.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var editingChild by remember { mutableStateOf<Child?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Baby Tracker", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToBle) {
                        Icon(Icons.Default.Bluetooth, contentDescription = "Smart Potty Settings")
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
                onClick = { 
                    editingChild = null
                    showDialog = true 
                },
                modifier = Modifier.testTag("add_child_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Child")
            }
        }
    ) { padding ->
        if (children.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No children registered yet.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(children, key = { it.childId }) { child ->
                    ChildCard(
                        child = child,
                        onEdit = {
                            editingChild = child
                            showDialog = true
                        },
                        onDelete = { viewModel.deleteChild(child.childId) },
                        onClick = { onNavigateToTracker(child.childId) }
                    )
                }
            }
        }
    }

    if (showDialog) {
        ChildFormDialog(
            initialChild = editingChild,
            onDismiss = { showDialog = false },
            onSave = { child ->
                viewModel.saveChild(child)
                showDialog = false
            }
        )
    }
}

@Composable
fun ChildCard(child: Child, onEdit: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("child_item_card")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = child.name.ifBlank { "Unknown Name" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Born: ${child.birthDate}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Gender: ${child.gender.replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (child.weight != null) {
                    Text(
                        text = "Weight: ${child.weight} kg",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (!child.sleepSchedule.isNullOrBlank()) {
                    Text(
                        text = "Sleep: ${child.sleepSchedule}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_child_button")) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Profile", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_child_button")) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Profile", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ChildFormDialog(
    initialChild: Child?,
    onDismiss: () -> Unit,
    onSave: (Child) -> Unit
) {
    var name by remember { mutableStateOf(initialChild?.name ?: "") }
    var birthDate by remember { mutableStateOf(initialChild?.birthDate ?: "") }
    var gender by remember { mutableStateOf(initialChild?.gender ?: "") }
    var weightText by remember { mutableStateOf(initialChild?.weight?.toString() ?: "") }
    var sleepSchedule by remember { mutableStateOf(initialChild?.sleepSchedule ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (initialChild == null) "Add Child" else "Edit Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("name_input")
                )

                OutlinedTextField(
                    value = birthDate,
                    onValueChange = { birthDate = it },
                    label = { Text("Birth Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("birth_date_input")
                )

                OutlinedTextField(
                    value = gender,
                    onValueChange = { gender = it },
                    label = { Text("Gender (e.g. female, male)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("gender_input")
                )

                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("Weight (kg) - Optional") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("weight_input")
                )

                OutlinedTextField(
                    value = sleepSchedule,
                    onValueChange = { sleepSchedule = it },
                    label = { Text("Sleep Schedule - Optional (e.g. 21:00-07:30)") },
                    modifier = Modifier.fillMaxWidth().testTag("sleep_input")
                )

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
                            val childId = initialChild?.childId ?: UUID.randomUUID().toString()
                            val weight = weightText.toFloatOrNull()
                            onSave(
                                Child(
                                    childId = childId,
                                    name = name.trim(),
                                    birthDate = birthDate.trim(),
                                    gender = gender.trim(),
                                    weight = weight,
                                    sleepSchedule = sleepSchedule.trim()
                                )
                            )
                        },
                        enabled = name.isNotBlank() && birthDate.isNotBlank() && gender.isNotBlank(),
                        modifier = Modifier.testTag("save_button")
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
