package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import kotlin.math.roundToInt

/** Grupos que o usuário liga/desliga nas configurações. */
enum class CategoriaPerigo(val rotulo: String, val explicacao: String, val ligadaPorPadrao: Boolean = true) {
    RADARES("Radares e semáforos com câmera", "Radar de velocidade, radar de trecho e câmera de semáforo"),
    LOMBADAS("Lombadas", "Quebra-molas, lombadas, platôs e faixas sonoras"),
    TRAVESSIAS("Travessias de trem", "Passagens de nível (ferrovia cruza a via)"),
    ATENCAO(
        "Pontos de atenção",
        "Curva perigosa, zona escolar, travessia de animais, cruzamento perigoso e queda de pedras",
        ligadaPorPadrao = false,
    ),
    ;

    companion object {
        /** Categorias ligadas quando a pessoa ainda não mexeu em nada. */
        fun padrao(): Set<CategoriaPerigo> = values().filter { it.ligadaPorPadrao }.toSet()
    }
}

/**
 * Tipos de aviso. [limiaresM] são as distâncias (m) em que a voz fala, do mais longe ao mais perto; o último de
 * [curtoNoUltimo] vira só "X adiante" (sem repetir a distância).
 */
enum class Perigo(
    val categoria: CategoriaPerigo,
    /** Nome falado/mostrado ("Radar", "Lombada"...). */
    val nome: String,
    val limiaresM: IntArray,
    /** O último limiar fala só "Radar adiante" em vez de repetir a distância. */
    val curtoNoUltimo: Boolean = false,
    /** A tag `direction` de câmeras indica para onde ela aponta (eixo da via), não o sentido: só o eixo vale. */
    val soEixo: Boolean = false,
) {
    RADAR(CategoriaPerigo.RADARES, "Radar", intArrayOf(800, 300, 100), curtoNoUltimo = true, soEixo = true),
    SEMAFORO_COM_CAMERA(CategoriaPerigo.RADARES, "Semáforo com câmera", intArrayOf(400, 150), soEixo = true),
    LOMBADA(CategoriaPerigo.LOMBADAS, "Lombada", intArrayOf(300, 100)),
    FAIXA_SONORA(CategoriaPerigo.LOMBADAS, "Faixa sonora", intArrayOf(150)),
    TRAVESSIA_FERROVIARIA(CategoriaPerigo.TRAVESSIAS, "Travessia de trem", intArrayOf(400)),
    /** Radar de velocidade média (Pardal `SECTION`): avisa na entrada do trecho, como o radar, sem o "adiante" curto. */
    RADAR_DE_TRECHO(CategoriaPerigo.RADARES, "Radar de trecho", intArrayOf(800, 300)),
    CURVA_PERIGOSA(CategoriaPerigo.ATENCAO, "Curva perigosa", intArrayOf(300)),
    ZONA_ESCOLAR(CategoriaPerigo.ATENCAO, "Zona escolar", intArrayOf(300)),
    TRAVESSIA_DE_ANIMAIS(CategoriaPerigo.ATENCAO, "Travessia de animais", intArrayOf(400)),
    CRUZAMENTO_PERIGOSO(CategoriaPerigo.ATENCAO, "Cruzamento perigoso", intArrayOf(200)),
    QUEDA_DE_PEDRAS(CategoriaPerigo.ATENCAO, "Queda de pedras", intArrayOf(300));

    /** Maior distância de aviso (m): fora disso o perigo nem entra no cartão. */
    val alcanceM: Int get() = limiaresM.max()
}

/**
 * Um ponto de aviso vindo do OpenStreetMap.
 * [id] é "n123" (nó), "w123" (via) ou "r123" (relação): estável entre consultas.
 * [limiteKmh] só em radares com `maxspeed` numérico. [direcaoGraus] (0..360) quando a tag existe em forma usável.
 * [sentido]: true quando [direcaoGraus] é o rumo do trânsito fiscalizado (Pardal `direction_deg`): o aviso só vale a até 60º dele.
 * false (OpenStreetMap) mantém o comportamento antigo (eixo para câmeras, 100º para os demais).
 * [fimLat]/[fimLon]: fim do trecho, só em radar de trecho (o ponto é a entrada).
 */
data class PerigoPonto(
    val id: String,
    val tipo: Perigo,
    val lat: Double,
    val lon: Double,
    val limiteKmh: Int? = null,
    val direcaoGraus: Double? = null,
    val sentido: Boolean = false,
    val fimLat: Double? = null,
    val fimLon: Double? = null,
) {
    val latLon get() = LatLon(lat, lon)
}

/** Texto de distância falado: "300 metros", "1,2 quilômetros" (arredonda de 50 em 50 m). */
fun distanciaFalada(m: Double): String =
    if (m < 1000) "${((m / 50).roundToInt() * 50).coerceAtLeast(50)} metros"
    else {
        val km = (m / 100).roundToInt() / 10.0
        val s = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
        if (km <= 1.0) "$s quilômetro" else "$s quilômetros"
    }
