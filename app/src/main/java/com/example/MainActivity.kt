package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.ChildRepository
import com.example.ui.ChildProfileScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ChildViewModel
import com.example.viewmodel.ChildViewModelFactory

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.TrackerEventRepository
import com.example.viewmodel.TrackerEventViewModel
import com.example.viewmodel.TrackerEventViewModelFactory
import com.example.ui.TrackerScreen
import com.example.ui.BleSettingsScreen
import com.example.viewmodel.BleViewModel
import com.example.viewmodel.BleViewModelFactory

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val database = AppDatabase.getDatabase(this)
    val childRepository = ChildRepository(database.childDao())
    val childFactory = ChildViewModelFactory(childRepository)
    val eventRepository = TrackerEventRepository(database.trackerEventDao())
    val eventFactory = TrackerEventViewModelFactory(eventRepository)

    setContent {
      MyApplicationTheme {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = "profiles") {
            composable("profiles") {
                val viewModel: ChildViewModel = viewModel(factory = childFactory)
                ChildProfileScreen(
                    viewModel = viewModel,
                    onNavigateToTracker = { childId ->
                        navController.navigate("tracker/$childId")
                    },
                    onNavigateToBle = {
                        navController.navigate("ble_settings")
                    }
                )
            }
            composable("tracker/{childId}") { backStackEntry ->
                val childId = backStackEntry.arguments?.getString("childId") ?: return@composable
                val viewModel: TrackerEventViewModel = viewModel(factory = eventFactory)
                val bleFactory = BleViewModelFactory(application)
                val bleViewModel: BleViewModel = viewModel(factory = bleFactory)
                TrackerScreen(
                    childId = childId,
                    viewModel = viewModel,
                    bleViewModel = bleViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("ble_settings") {
                val bleFactory = BleViewModelFactory(application)
                val bleViewModel: BleViewModel = viewModel(factory = bleFactory)
                BleSettingsScreen(viewModel = bleViewModel, onBack = { navController.popBackStack() })
            }
        }
      }
    }
  }
}

