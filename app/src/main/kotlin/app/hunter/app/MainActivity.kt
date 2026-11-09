package app.hunter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.hunter.app.ui.FuelScreen
import app.hunter.app.ui.HistoryScreen
import app.hunter.app.ui.HunterTheme
import app.hunter.app.ui.MaintenanceScreen
import app.hunter.app.ui.PodScreen
import app.hunter.app.ui.RideScreen
import app.hunter.app.ui.Tab

class MainActivity : ComponentActivity() {
    private val vm: HunterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HunterTheme {
                Surface { HunterRoot(vm) }
            }
        }
    }
}

@Composable
private fun HunterRoot(vm: HunterViewModel) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = Tab.entries[tabIndex]
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = i == tabIndex,
                        onClick = { tabIndex = i },
                        icon = { Text(t.emoji) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(Modifier.padding(padding).statusBarsPadding()) {
            when (tab) {
                Tab.Ride -> RideScreen(vm) { tabIndex = it.ordinal }
                Tab.History -> HistoryScreen(vm)
                Tab.Fuel -> FuelScreen(vm)
                Tab.Maintenance -> MaintenanceScreen(vm)
                Tab.Pod -> PodScreen(vm)
            }
        }
    }
}
