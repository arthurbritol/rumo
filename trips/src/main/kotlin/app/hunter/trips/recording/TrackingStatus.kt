package app.hunter.trips.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado da gravação visível para a UI (o serviço publica aqui; processo único, então um singleton basta). */
object TrackingStatus {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal fun publish(s: RecordingState) {
        _state.value = s
    }
}
