package app.hunter.app.ui

import app.hunter.app.BuildConfig

/**
 * Camada de trânsito (imagens de fluxo da TomTom, pedidas ao servidor intermediário do Rumo: a chave da TomTom não vai
 * no app; o servidor fica em workers/traffic-proxy, veja docs/TRANSITO.md). O endereço e o token vêm do build (`TRAFFIC_TILE_URL` e `TRAFFIC_TOKEN`); sem eles a camada não existe.
 */
object TrafficLayer {
    /** Modelo de endereço das imagens para o MapLibre, ou null se o build não tem servidor de trânsito configurado. */
    fun template(base: String = BuildConfig.TRAFFIC_TILE_URL, token: String = BuildConfig.TRAFFIC_TOKEN): String? {
        val root = base.trim().trimEnd('/')
        if (root.isEmpty()) return null
        val query = if (token.isBlank()) "" else "?t=" + java.net.URLEncoder.encode(token.trim(), "UTF-8")
        return "$root/flow/{z}/{x}/{y}.png$query"
    }
}
