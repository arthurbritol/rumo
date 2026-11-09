package app.hunter.nav

import app.hunter.model.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Decide o que falar, sem Android: cada manobra é anunciada a ~800 m, ~250 m e ~60 m (uma vez cada). */
class NavAnnouncer(private val thresholdsM: IntArray = intArrayOf(800, 250, 60)) {
    private var step = -1
    private val spoken = HashSet<Int>()
    private var arrivedSpoken = false

    fun reset() {
        step = -1
        spoken.clear()
        arrivedSpoken = false
    }

    fun next(p: NavProgress): String? {
        if (p.arrived) {
            if (arrivedSpoken) return null
            arrivedSpoken = true
            return "Você chegou ao seu destino."
        }
        if (p.upcomingIndex != step) {
            step = p.upcomingIndex
            spoken.clear()
        }
        val d = p.distanceToUpcomingM
        val crossed = thresholdsM.filter { d <= it && it !in spoken }
        if (crossed.isEmpty()) return null
        spoken += thresholdsM.filter { d <= it } // se já está perto, não repete os anúncios mais longos depois
        val text = p.upcoming.verbalPre?.takeIf { it.isNotBlank() } ?: p.upcoming.instruction
        return if (d <= thresholdsM.min()) text else "Em ${spokenDistance(d)}. $text"
    }

    companion object {
        fun spokenDistance(m: Double): String =
            if (m < 1000) "${((m / 50).roundToInt() * 50).coerceAtLeast(50)} metros"
            else {
                val km = (m / 100).roundToInt() / 10.0
                val s = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
                if (km <= 1.0) "$s quilômetro" else "$s quilômetros"
            }
    }
}

/**
 * Acompanha a navegação ponto a ponto: atualiza [NavStatus], detecta saída da rota e pede uma nova rota
 * (no máximo a cada 15 s). Devolve frases para falar. O recálculo usa a posição atual até o mesmo destino.
 */
class NavSession(
    private val scope: CoroutineScope,
    private val reroute: suspend (from: LatLon, to: Place, profile: RouteProfile, via: List<LatLon>) -> Route?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var engine: NavEngine? = null
    private var version = -1
    private var lastRerouteAt = Long.MIN_VALUE / 2
    private var rerouteLaunched = false
    private val announcer = NavAnnouncer()

    /** @return frases a falar (pode ser vazio), ou null se não há navegação ativa. */
    fun onPoint(p: TrackPoint): List<String>? {
        val st = NavStatus.state.value
        val route = st.route
        if (!st.active || route == null) {
            engine = null
            version = -1
            return null
        }
        val speech = mutableListOf<String>()
        if (st.routeVersion != version) {
            val wasReroute = rerouteLaunched
            rerouteLaunched = false
            engine = NavEngine(route)
            version = st.routeVersion
            announcer.reset()
            if (!wasReroute) route.maneuvers.firstOrNull()?.verbalPre?.let { speech += it }
        }
        val here = LatLon(p.latitude, p.longitude)
        val prog = engine!!.update(here, p.accuracyM)
        NavStatus.setProgress(prog)

        val dest = st.destination
        if (prog.offRoute && !st.rerouting && dest != null && now() - lastRerouteAt > REROUTE_COOLDOWN_MS) {
            lastRerouteAt = now()
            rerouteLaunched = true
            NavStatus.setRerouting(true)
            speech += "Recalculando."
            // Só as paradas que ainda estão à frente (não volta a um posto já passado).
            val pending = st.via.filter { route.alongOf(it) > prog.alongM + 50.0 }
            scope.launch {
                val r = try { reroute(here, dest, route.profile, pending) } catch (e: Exception) { null }
                if (r != null) NavStatus.setRoute(r) else { rerouteLaunched = false; NavStatus.setRerouting(false) }
            }
        } else if (!st.rerouting) {
            announcer.next(prog)?.let { speech += it }
        }
        return speech
    }

    private companion object {
        const val REROUTE_COOLDOWN_MS = 15_000L
    }
}
