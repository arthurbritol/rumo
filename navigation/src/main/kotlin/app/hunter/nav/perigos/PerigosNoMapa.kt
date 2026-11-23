package app.hunter.nav.perigos

import app.hunter.model.Geo

/** Quais perigos vão para a camada do mapa: só das categorias ligadas, num raio ao redor da posição e com teto de pontos. */
object PerigosNoMapa {
    const val RAIO_M = 25_000.0
    const val MAX_PONTOS = 3_000

    /** Os [max] mais perto de ([lat],[lon]) dentro de [raioM]; sem posição ([lat] nulo) não mostra nada além do teto, do começo da lista. */
    fun selecionar(
        todos: List<PerigoPonto>,
        categorias: Set<CategoriaPerigo>,
        lat: Double?,
        lon: Double?,
        raioM: Double = RAIO_M,
        max: Int = MAX_PONTOS,
    ): List<PerigoPonto> {
        val ligados = todos.filter { it.tipo.categoria in categorias }
        if (lat == null || lon == null) return ligados.take(max)
        return ligados.asSequence()
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
            .filter { it.second <= raioM }
            .sortedBy { it.second }
            .take(max)
            .map { it.first }
            .toList()
    }
}
