package app.hunter.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.ui.GarageScreen
import app.hunter.app.ui.HunterTheme
import app.hunter.app.ui.MapScreen
import app.hunter.app.ui.PodScreen
import app.hunter.app.ui.Tab
import app.hunter.app.ui.TripsScreen

class MainActivity : ComponentActivity() {
    private val vm: HunterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            HunterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { HunterRoot(vm) }
            }
        }
    }
}

@Composable
private fun HunterRoot(vm: HunterViewModel) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = Tab.entries[tabIndex]
    val nav by vm.nav.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Durante a navegação a barra some: mais mapa e nenhum toque acidental com a moto andando.
            if (!(nav.active && tab == Tab.Map)) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = i == tabIndex,
                            onClick = { tabIndex = i },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // O mapa fica SEMPRE montado por baixo: trocar de aba não recria o mapa nem recarrega os tiles
            // (era a maior causa de lentidão). As outras abas aparecem por cima, opacas.
            MapScreen(vm)
            Crossfade(targetState = tab, animationSpec = tween(160), label = "tab") { t ->
                if (t != Tab.Map) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            when (t) {
                                Tab.Trips -> TripsScreen(vm)
                                Tab.Garage -> GarageScreen(vm)
                                Tab.Pod -> PodScreen(vm)
                                Tab.Map -> {}
                            }
                        }
                    }
                }
            }
            // Composto depois do MapScreen para ter prioridade sobre o "voltar" do mapa.
            BackHandler(enabled = tab != Tab.Map) { tabIndex = Tab.Map.ordinal }
        }
    }
}
