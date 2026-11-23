package app.hunter.nav.perigos

import app.hunter.model.TrackPoint
import app.hunter.nav.LatLon
import app.hunter.nav.Maneuver
import app.hunter.nav.NavProgress
import app.hunter.nav.NavUiState
import app.hunter.nav.Route
import app.hunter.nav.RouteProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val M = 111_194.9

/** Ponto a [eastM] m a leste e [northM] m ao norte da origem (equador, longitude 0). */
internal fun ll(eastM: Double, northM: Double = 0.0) = LatLon(northM / M, eastM / M)

internal fun perigo(id: String, eastM: Double, northM: Double = 0.0, tipo: Perigo = Perigo.RADAR, limite: Int? = null, dir: Double? = null) =
    PerigoPonto(id, tipo, northM / M, eastM / M, limite, dir)

/** Rota reta para o leste de 5 km, vértices a cada 500 m. */
internal fun rotaLeste(km: Int = 5): Route {
    val shape = (0..km * 2).map { ll(it * 500.0) }
    val ms = listOf(
        Maneuver(1, "Siga", "Siga.", null, km * 1000.0, 600.0, 0, shape.lastIndex, null),
        Maneuver(4, "Chegou", "Chegou.", null, 0.0, 0.0, shape.lastIndex, shape.lastIndex, null),
    )
    return Route(shape, ms, km * 1000.0, 600.0, false, false, RouteProfile.FASTEST)
}

class PerigosAdianteTest {
    private val rota = rotaLeste()

    private fun naRota(alongM: Double, perigos: List<PerigoPonto>, atual: String? = null) =
        PerigosAdiante.adiante(ll(alongM), 90.0, 15.0, rota, alongM, perigos, atual)

    // ---------- com rota ----------

    @Test fun `na rota so conta o que esta adiante ate 1,5 km`() {
        val ps = listOf(
            perigo("atras", 900.0), perigo("perto", 1_300.0), perigo("meio", 2_200.0),
            perigo("limite", 2_480.0), perigo("longe", 2_600.0),
        )
        val r = naRota(1_000.0, ps)
        assertEquals(listOf("perto", "meio", "limite"), r.map { it.perigo.id })
        assertEquals(300.0, r[0].distanciaM, 1.0)
        assertEquals(1_480.0, r[2].distanciaM, 1.0)
    }

    @Test fun `na rota ignora o que esta a mais de 30 m do tracado`() {
        val ps = listOf(perigo("na-via", 1_300.0, 12.0), perigo("na-paralela", 1_300.0, 45.0), perigo("quase", 1_400.0, 29.0))
        assertEquals(listOf("na-via", "quase"), naRota(1_000.0, ps).map { it.perigo.id })
    }

    @Test fun `distancia e medida pelo tracado mesmo com vertices longe uns dos outros`() {
        // Perigo a 750 m, entre vértices de 500/1000: a projeção no segmento (e não no vértice) dá 750.
        val r = naRota(0.0, listOf(perigo("x", 750.0, 5.0)))
        assertEquals(750.0, r.single().distanciaM, 1.0)
    }

    @Test fun `na rota o sentido contrario por direction e ignorado`() {
        val ps = listOf(
            perigo("mesmo-sentido", 1_300.0, tipo = Perigo.LOMBADA, dir = 90.0),
            perigo("sentido-contrario", 1_400.0, tipo = Perigo.LOMBADA, dir = 270.0),
            perigo("sem-direcao", 1_500.0, tipo = Perigo.LOMBADA),
        )
        assertEquals(listOf("mesmo-sentido", "sem-direcao"), naRota(1_000.0, ps).map { it.perigo.id })
    }

    @Test fun `camera usa so o eixo da via`() {
        val ps = listOf(
            perigo("de-frente", 1_300.0, dir = 270.0), // mira para o oeste = eixo da via: vale
            perigo("de-costas", 1_350.0, dir = 90.0),
            perigo("cruzando", 1_400.0, dir = 0.0), // mira para o norte: outra rua
        )
        assertEquals(listOf("de-frente", "de-costas"), naRota(1_000.0, ps).map { it.perigo.id })
    }

    @Test fun `rota que volta sobre si mesma nao confunde o trecho de ida com o de volta`() {
        // Ida e volta pela mesma rua (5 m de diferença): perigo no meio dela.
        val shape = listOf(ll(0.0), ll(2_000.0), ll(2_000.0, 5.0), ll(0.0, 5.0))
        val r = Route(shape, rota.maneuvers.map { it.copy(beginShapeIndex = 0, endShapeIndex = 3) }, 4_005.0, 0.0, false, false, RouteProfile.FASTEST)
        val p = perigo("x", 1_000.0, 2.0)
        // Na ida (along 500) ele está 500 m adiante, no trecho de ida (e não 2500 m, no de volta).
        assertEquals(500.0, PerigosAdiante.adiante(ll(500.0), 90.0, 10.0, r, 500.0, listOf(p)).single().distanciaM, 5.0)
        // Na volta (along 2505, a leste 1500) também está 500 m adiante.
        assertEquals(500.0, PerigosAdiante.adiante(ll(1_500.0, 5.0), 270.0, 10.0, r, 2_505.0, listOf(p)).single().distanciaM, 5.0)
        // Depois de passar na volta, não há mais nada adiante.
        assertTrue(PerigosAdiante.adiante(ll(900.0, 5.0), 270.0, 10.0, r, 3_105.0, listOf(p)).isEmpty())
    }

    // ---------- sem rota ----------

    private fun livre(rumo: Double?, vel: Double, perigos: List<PerigoPonto>, atual: String? = null) =
        PerigosAdiante.adiante(ll(0.0), rumo, vel, null, null, perigos, atual)

    @Test fun `livre pega os de ate 600 m dentro de 35 graus do rumo`() {
        val ps = listOf(
            perigo("a-frente", 400.0, 0.0),
            perigo("longe", 700.0, 0.0),
            perigo("de-lado", 0.0, 300.0),
            perigo("atras", -300.0, 0.0),
            perigo("a-30-graus", 400.0, 230.0), // atan(230/400)=29,9º
            perigo("a-40-graus", 400.0, 336.0), // 40º
        )
        val r = livre(90.0, 15.0, ps)
        assertEquals(listOf("a-frente", "a-30-graus"), r.map { it.perigo.id })
        assertEquals(400.0, r[0].distanciaM, 1.0)
    }

    @Test fun `livre exige velocidade acima de 3 m por s e rumo`() {
        val ps = listOf(perigo("a", 300.0))
        assertTrue(livre(90.0, 2.5, ps).isEmpty())
        assertTrue(livre(90.0, 0.0, ps).isEmpty())
        assertTrue(livre(null, 15.0, ps).isEmpty())
        assertEquals(1, livre(90.0, 3.5, ps).size)
    }

    @Test fun `livre com rumo para o norte`() {
        val ps = listOf(perigo("norte", 0.0, 500.0), perigo("leste", 500.0, 0.0))
        assertEquals(listOf("norte"), livre(0.0, 10.0, ps).map { it.perigo.id })
    }

    @Test fun `livre respeita direction de lombada (sentido contrario)`() {
        val ps = listOf(perigo("contra", 300.0, tipo = Perigo.LOMBADA, dir = 270.0), perigo("a-favor", 350.0, tipo = Perigo.LOMBADA, dir = 80.0))
        assertEquals(listOf("a-favor"), livre(90.0, 10.0, ps).map { it.perigo.id })
    }

    @Test fun `histerese segura o alvo um pouco alem dos limites`() {
        val ps = listOf(perigo("a", 620.0, 0.0), perigo("b", 400.0, 340.0)) // b: 40º
        assertTrue(livre(90.0, 10.0, ps).isEmpty())
        assertEquals(listOf("a"), livre(90.0, 10.0, ps, atual = "a").map { it.perigo.id })
        assertEquals(listOf("b"), livre(90.0, 10.0, ps, atual = "b").map { it.perigo.id })
    }

    @Test fun `histerese na rota aceita ate 40 m de desvio para o alvo`() {
        val ps = listOf(perigo("x", 1_300.0, 35.0))
        assertTrue(naRota(1_000.0, ps).isEmpty())
        assertEquals(1, naRota(1_000.0, ps, atual = "x").size)
    }

    @Test fun `diferenca angular`() {
        assertEquals(10.0, PerigosAdiante.difAngular(355.0, 5.0), 1e-9)
        assertEquals(180.0, PerigosAdiante.difAngular(0.0, 180.0), 1e-9)
        assertEquals(0.0, PerigosAdiante.difAngular(90.0, 90.0), 1e-9)
        assertEquals(90.0, PerigosAdiante.difAngular(10.0, 280.0), 1e-9)
    }
}

class AvisadorDePerigosTest {
    private val rota = rotaLeste()

    private fun passo(av: AvisadorDePerigos, along: Double, ps: List<PerigoPonto>, velMps: Double = 15.0, usaRota: Boolean = true) =
        av.atualizar(ll(along), 90.0, velMps, if (usaRota) rota else null, if (usaRota) along else null, ps)

    @Test fun `radar fala a 800, 300 e adiante, uma vez cada`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("r", 2_000.0))
        assertTrue(passo(av, 1_100.0, ps).falas.isEmpty()) // a 900 m
        assertEquals(listOf("Radar à frente, 800 metros."), passo(av, 1_210.0, ps).falas)
        assertTrue(passo(av, 1_300.0, ps).falas.isEmpty())
        assertEquals(listOf("Radar à frente, 300 metros."), passo(av, 1_710.0, ps).falas)
        assertTrue(passo(av, 1_800.0, ps).falas.isEmpty())
        assertEquals(listOf("Radar adiante."), passo(av, 1_905.0, ps).falas)
        assertTrue(passo(av, 1_950.0, ps).falas.isEmpty())
    }

    @Test fun `lombada fala a 300 e 100`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("l", 2_000.0, tipo = Perigo.LOMBADA))
        assertTrue(passo(av, 1_600.0, ps).falas.isEmpty())
        assertEquals(listOf("Lombada à frente, 300 metros."), passo(av, 1_710.0, ps).falas)
        assertEquals(listOf("Lombada à frente, 100 metros."), passo(av, 1_905.0, ps).falas)
    }

    @Test fun `travessia fala a 400 e semaforo com camera a 400 e 150`() {
        val av = AvisadorDePerigos()
        val t = listOf(perigo("t", 2_000.0, tipo = Perigo.TRAVESSIA_FERROVIARIA))
        assertEquals(listOf("Travessia de trem à frente, 400 metros."), passo(av, 1_650.0, t).falas)
        val av2 = AvisadorDePerigos()
        val s = listOf(perigo("s", 2_000.0, tipo = Perigo.SEMAFORO_COM_CAMERA))
        assertEquals(listOf("Semáforo com câmera à frente, 400 metros."), passo(av2, 1_650.0, s).falas)
        assertEquals(listOf("Semáforo com câmera à frente, 150 metros."), passo(av2, 1_860.0, s).falas)
    }

    @Test fun `entrar perto fala so o limiar mais curto e nao repete os longos`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("r", 2_000.0))
        assertEquals(listOf("Radar à frente, 300 metros."), passo(av, 1_750.0, ps).falas)
        assertTrue(passo(av, 1_760.0, ps).falas.isEmpty())
        assertTrue(passo(av, 1_780.0, ps).falas.isEmpty()) // o 800 já foi dado por superado
    }

    @Test fun `ultrapassado nao volta a avisar`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("r", 2_000.0))
        passo(av, 1_905.0, ps)
        assertNotNull(passo(av, 1_990.0, ps).alerta)
        val depois = passo(av, 2_030.0, ps)
        assertNull(depois.alerta)
        assertTrue(depois.falas.isEmpty())
        assertTrue(passo(av, 2_100.0, ps).falas.isEmpty())
    }

    @Test fun `modo livre tambem dedupe e esquece o ultrapassado`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("r", 500.0))
        // anda para o leste, a 80 m/s... de 0 a 500 m
        assertEquals(listOf("Radar à frente, 300 metros."), av.atualizar(ll(220.0), 90.0, 15.0, null, null, ps).falas)
        assertEquals(listOf("Radar adiante."), av.atualizar(ll(420.0), 90.0, 15.0, null, null, ps).falas)
        assertNotNull(av.atualizar(ll(480.0), 90.0, 15.0, null, null, ps).alerta)
        // passou: o perigo está atrás
        assertNull(av.atualizar(ll(530.0), 90.0, 15.0, null, null, ps).alerta)
        assertTrue(av.atualizar(ll(560.0), 90.0, 15.0, null, null, ps).falas.isEmpty())
        // virou e voltou de longe (> 2 km): nova passagem, avisa de novo
        assertNull(av.atualizar(ll(3_000.0), 270.0, 15.0, null, null, ps).alerta)
        assertEquals(listOf("Radar à frente, 300 metros."), av.atualizar(ll(790.0), 270.0, 15.0, null, null, ps).falas)
    }

    @Test fun `radar com limite fala o limite e avisa quando esta acima`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("r", 2_000.0, limite = 60))
        // 90 km/h = 25 m/s
        val r = passo(av, 1_710.0, ps, velMps = 25.0)
        assertEquals(listOf("Radar à frente, 300 metros, limite 60. Você está acima do limite."), r.falas)
        assertTrue(r.alerta!!.acimaDoLimite)
        assertEquals(60, r.alerta!!.limiteKmh)
        // abaixo: só informa o limite
        val av2 = AvisadorDePerigos()
        val r2 = passo(av2, 1_710.0, ps, velMps = 14.0) // 50 km/h
        assertEquals(listOf("Radar à frente, 300 metros, limite 60."), r2.falas)
        assertFalse(r2.alerta!!.acimaDoLimite)
        // dentro da margem do GPS (61 km/h) não conta como acima
        assertFalse(passo(AvisadorDePerigos(), 1_710.0, ps, velMps = 61 / 3.6).alerta!!.acimaDoLimite)
    }

    @Test fun `limite so vale para radar`() {
        val av = AvisadorDePerigos()
        val r = passo(av, 1_710.0, listOf(perigo("l", 2_000.0, tipo = Perigo.LOMBADA, limite = 30)), velMps = 25.0)
        assertFalse(r.alerta!!.acimaDoLimite)
        assertEquals(listOf("Lombada à frente, 300 metros."), r.falas)
    }

    @Test fun `o cartao mostra o mais perto dentro do alcance do tipo`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("lomb", 1_500.0, tipo = Perigo.LOMBADA), perigo("radar", 1_700.0))
        // a 1000 m da lombada (alcance 300) e 1200 do radar (alcance 800): nada
        assertNull(passo(av, 500.0, ps).alerta)
        // a 600 do radar: só o radar entra
        assertEquals("radar", passo(av, 1_100.0, ps).alerta!!.perigo.id)
        // a 200 da lombada e 400 do radar: a lombada, que é a mais perto
        val r = passo(av, 1_300.0, ps)
        assertEquals("lomb", r.alerta!!.perigo.id)
        assertEquals(200, r.alerta!!.distanciaM)
    }

    @Test fun `dois perigos em sequencia avisam cada um e o segundo depois do primeiro passar`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("l1", 1_500.0, tipo = Perigo.LOMBADA), perigo("l2", 1_700.0, tipo = Perigo.LOMBADA))
        assertEquals(listOf("Lombada à frente, 300 metros."), passo(av, 1_210.0, ps).falas)
        assertEquals(listOf("Lombada à frente, 100 metros."), passo(av, 1_410.0, ps).falas)
        passo(av, 1_490.0, ps)
        val r = passo(av, 1_520.0, ps)
        assertEquals("l2", r.alerta!!.perigo.id)
        assertEquals(listOf("Lombada à frente, 300 metros."), r.falas) // l2 a 180 m: o aviso de 300 m dele ainda não tinha sido dado
    }

    @Test fun `reset zera a memoria`() {
        val av = AvisadorDePerigos()
        val ps = listOf(perigo("r", 2_000.0))
        passo(av, 1_710.0, ps)
        av.reset()
        assertEquals(listOf("Radar à frente, 300 metros."), passo(av, 1_720.0, ps).falas)
    }

    @Test fun `distancia falada`() {
        assertEquals("300 metros", distanciaFalada(300.0))
        assertEquals("1,2 quilômetros", distanciaFalada(1_200.0))
        assertEquals("1 quilômetro", distanciaFalada(1_000.0))
    }
}

class PerigoMonitorTest {
    private var t = 0L
    private val publicados = mutableListOf<AlertaPerigo?>()
    private var cats = CategoriaPerigo.values().toSet()
    private val rota = rotaLeste()
    private val todos = listOf(
        perigo("radar", 1_500.0),
        perigo("lombada", 1_500.0, 3.0, Perigo.LOMBADA),
        perigo("trem", 1_500.0, -3.0, Perigo.TRAVESSIA_FERROVIARIA),
    )

    private fun monitor(lista: List<PerigoPonto> = todos) =
        PerigoMonitor(repo = null, categorias = { cats }, publicar = { publicados += it }, agora = { t }, perigos = { lista })

    private fun ponto(along: Double, vel: Float = 15f, acc: Float = 5f) =
        TrackPoint(t, 0.0, along / M, speedMps = vel, accuracyM = acc, bearingDeg = 90f)

    private fun navegando(along: Double, offRoute: Boolean = false): NavUiState {
        val m = rota.maneuvers
        val prog = NavProgress(along, 5_000 - along, 0.0, 0, m[0], 0.0, m[1], if (offRoute) 100.0 else 2.0, offRoute, false)
        return NavUiState(active = true, route = rota, progress = prog, routeVersion = 1)
    }

    @Test fun `na rota avisa por voz e publica o cartao`() {
        val m = monitor(listOf(todos[0]))
        val falas = m.onPoint(ponto(1_210.0), navegando(1_210.0))
        assertEquals(listOf("Radar à frente, 300 metros."), falas)
        assertEquals("radar", publicados.last()!!.perigo.id)
    }

    @Test fun `modo livre usa rumo e velocidade do GPS`() {
        val m = monitor(listOf(todos[0]))
        assertEquals(listOf("Radar à frente, 300 metros."), m.onPoint(ponto(1_210.0), NavUiState()))
        t += 1_000
        assertTrue(m.onPoint(ponto(1_300.0, vel = 1f), NavUiState()).isEmpty()) // parado: sem alerta
        assertNull(publicados.last())
    }

    @Test fun `fora da rota cai no modo livre`() {
        val m = monitor(listOf(todos[0]))
        assertEquals(1, m.onPoint(ponto(1_210.0), navegando(1_210.0, offRoute = true)).size)
    }

    @Test fun `desligar uma categoria some com os avisos dela`() {
        cats = setOf(CategoriaPerigo.RADARES, CategoriaPerigo.TRAVESSIAS)
        val m = monitor()
        m.onPoint(ponto(1_210.0), navegando(1_210.0))
        assertTrue(publicados.last()!!.perigo.tipo != Perigo.LOMBADA)
        cats = setOf(CategoriaPerigo.LOMBADAS)
        t += 1_000
        m.onPoint(ponto(1_300.0), navegando(1_300.0))
        assertEquals(Perigo.LOMBADA, publicados.last()!!.perigo.tipo)
        cats = emptySet()
        t += 1_000
        assertTrue(m.onPoint(ponto(1_350.0), navegando(1_350.0)).isEmpty())
        assertNull(publicados.last())
    }

    @Test fun `precisao ruim nao avisa`() {
        val m = monitor(listOf(todos[0]))
        assertTrue(m.onPoint(ponto(1_210.0, acc = 250f), navegando(1_210.0)).isEmpty())
        assertNull(publicados.last())
    }

    @Test fun `limita a checagem a cerca de 1 Hz`() {
        val m = monitor()
        m.onPoint(ponto(100.0), navegando(100.0))
        t += 200
        m.onPoint(ponto(103.0), navegando(103.0))
        assertEquals(1, publicados.size)
        t += 800
        m.onPoint(ponto(120.0), navegando(120.0))
        assertEquals(2, publicados.size)
    }

    @Test fun `encerrar limpa o cartao`() {
        val m = monitor(listOf(todos[0]))
        m.onPoint(ponto(1_210.0), navegando(1_210.0))
        m.encerrar()
        assertNull(publicados.last())
    }
}
