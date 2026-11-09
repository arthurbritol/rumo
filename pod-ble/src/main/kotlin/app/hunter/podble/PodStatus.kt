package app.hunter.podble

import android.content.Context
import app.hunter.pod.CompassDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PodPhase { IDLE, SCANNING, CONNECTING, AWAITING_PIN, CONNECTED }

data class PodUiState(
    val phase: PodPhase = PodPhase.IDLE,
    /** Mensagem curta para o usuário (erro, dica de ignição, etc.). */
    val message: String? = null,
    /** Setor da bússola enviado ao Pod, quando conectado e em movimento. */
    val direction: CompassDirection? = null,
    val pairedName: String? = null,
)

/** Estado do Pod visível para a UI (processo único → singleton). */
object PodStatus {
    private val _state = MutableStateFlow(PodUiState())
    val state: StateFlow<PodUiState> = _state.asStateFlow()

    internal fun update(f: (PodUiState) -> PodUiState) {
        _state.value = f(_state.value)
    }
}

/** Guarda qual Pod já foi pareado (MAC) para usar o handshake de retomada, sem PIN. */
internal class PodPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("pod", Context.MODE_PRIVATE)

    fun pairedMac(): String? = sp.getString("mac", null)
    fun pairedName(): String? = sp.getString("name", null)
    fun markPaired(mac: String, name: String?) = sp.edit().putString("mac", mac).putString("name", name).apply()
    fun forget() = sp.edit().remove("mac").remove("name").apply()
    var is12h: Boolean
        get() = sp.getBoolean("is12h", false)
        set(v) { sp.edit().putBoolean("is12h", v).apply() }
}
