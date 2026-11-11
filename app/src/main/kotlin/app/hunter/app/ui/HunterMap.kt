package app.hunter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hunter.app.fuel.PriceSource
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.model.GeoBox
import app.hunter.model.TrackPoint
import app.hunter.nav.LatLon
import java.util.Locale
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Marcador de um amigo na estrada exibido no mapa. */
data class FriendMarker(
    val uid: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float? = null,
    val timeAgoText: String = "",
)

/**
 * Formata o tempo decorrido desde a última atualização de localização de um amigo.
 * Retorna null se expirado (> 5 minutos), "agora" se < 30s, "há 1 min", "há 3 min", etc.
 */
fun formatFriendElapsed(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
    val diff = nowMs - updatedAtMs
    if (diff > 5 * 60_000L) return null
    if (diff < 30_000L) return "agora"
    val minutes = maxOf(1L, diff / 60_000L)
    return "há $minutes min"
}

/** Como a câmera se comporta. */
sealed interface MapCamera {
    /** O usuário mexe livremente. */
    data object Free : MapCamera

    /** Segue a posição; [rotate] gira o mapa com o rumo (modo navegação), [tilt] inclina. */
    data class Follow(val zoom: Double = 16.0, val tilt: Double = 0.0, val rotate: Boolean = false) : MapCamera

    /** Enquadra todos os [points] (pré-visualização de rota, detalhe de viagem). */
    data class Fit(val points: List<LatLon>) : MapCamera

    /** Foca em coordenadas específicas (ex.: amigo no mapa). */
    data class Target(val lat: Double, val lon: Double, val zoom: Double = 16.0) : MapCamera
}

// "positron" é bem mais leve que o "liberty" (sem prédios 3D nem centenas de ícones): rola sem travar.
internal const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
internal const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val REASON_API_GESTURE = 1

private const val SRC_ALT = "alt-src"
private const val SRC_ROUTE = "route-src"
private const val SRC_TRAIL = "trail-src"
private const val SRC_DEST = "dest-src"
private const val SRC_FRIENDS = "friends-src"
private const val SRC_ME = "me-src"
private const val SRC_FUEL = "fuel-src"
private const val SRC_FUEL_STATE = "fuel-state-src"

/** Abaixo deste zoom aparece a média do estado; a partir dele, os postos com preço próprio. */
private const val STATION_MIN_ZOOM = 9f

/**
 * Mapa (MapLibre + OpenFreeMap, sem chave de API). Desenha: rota principal, rotas alternativas, trilha
 * gravada colorida por velocidade, destino, amigos na estrada, postos de combustível e a posição do motociclista.
 * Tudo é redesenhado quando os parâmetros mudam; a câmera obedece a [camera].
 */
@Composable
fun HunterMap(
    modifier: Modifier = Modifier,
    dark: Boolean = LocalDark.current,
    me: TrackPoint? = null,
    friends: List<FriendMarker> = emptyList(),
    fuelStations: List<ResolvedFuelStation> = emptyList(),
    stateAverages: List<app.hunter.app.fuel.StateAverage> = emptyList(),
    route: List<LatLon>? = null,
    altRoutes: List<List<LatLon>> = emptyList(),
    trail: List<TrackPoint>? = null,
    destination: LatLon? = null,
    camera: MapCamera = MapCamera.Free,
    /** Muda para reaplicar a câmera (ex.: botão "centralizar"). */
    cameraNonce: Int = 0,
    paddingTopDp: Int = 0,
    paddingBottomDp: Int = 0,
    interactive: Boolean = true,
    onLongPress: ((LatLon) -> Unit)? = null,
    onFuelStationClick: (ResolvedFuelStation) -> Unit = {},
    onBoundingBoxChanged: (GeoBox) -> Unit = {},
    onUserGesture: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also {
            it.onCreate(null)
            it.setMaximumFps(30) // menos GPU/bateria; a câmera já é suavizada
        }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    val latestLongPress by rememberUpdatedState(onLongPress)
    val latestGesture by rememberUpdatedState(onUserGesture)
    val latestFuelStations by rememberUpdatedState(fuelStations)
    val latestFuelClick by rememberUpdatedState(onFuelStationClick)
    val latestBoxChanged by rememberUpdatedState(onBoundingBoxChanged)

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                setCompassEnabled(false)
                setLogoEnabled(false)
                setAttributionGravity(Gravity.TOP or Gravity.END)
                setAttributionMargins(0, (8 * density).toInt(), (8 * density).toInt(), 0)
            }
            m.addOnMapLongClickListener { ll ->
                latestLongPress?.invoke(LatLon(ll.latitude, ll.longitude)) != null
            }
            m.addOnMapClickListener { latLng ->
                val screenPoint = m.projection.toScreenLocation(latLng)
                val features = m.queryRenderedFeatures(screenPoint, "fuel-circle", "fuel-symbol", "fuel-label")
                if (features.isNotEmpty()) {
                    val stationId = features.first().getStringProperty("id")
                    val foundStation = latestFuelStations.find { it.id == stationId }
                    if (foundStation != null) {
                        latestFuelClick(foundStation)
                        return@addOnMapClickListener true
                    }
                }
                false
            }
            m.addOnCameraIdleListener {
                val region = m.projection.visibleRegion.latLngBounds
                val box = GeoBox(
                    minLat = minOf(region.latitudeSouth, region.latitudeNorth),
                    minLon = minOf(region.longitudeWest, region.longitudeEast),
                    maxLat = maxOf(region.latitudeSouth, region.latitudeNorth),
                    maxLon = maxOf(region.longitudeWest, region.longitudeEast),
                )
                latestBoxChanged(box)
            }
            m.addOnCameraMoveStartedListener { reason -> if (reason == REASON_API_GESTURE) latestGesture() }
            val startTarget = me?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(-23.5505, -46.6333)
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(startTarget).zoom(14.0).build()))
            val initialBounds = m.projection.visibleRegion.latLngBounds
            latestBoxChanged(
                GeoBox(
                    minLat = minOf(initialBounds.latitudeSouth, initialBounds.latitudeNorth),
                    minLon = minOf(initialBounds.longitudeWest, initialBounds.longitudeEast),
                    maxLat = maxOf(initialBounds.latitudeSouth, initialBounds.latitudeNorth),
                    maxLon = maxOf(initialBounds.longitudeWest, initialBounds.longitudeEast),
                ),
            )
            map = m
        }
    }

    LaunchedEffect(map, interactive) {
        map?.uiSettings?.apply {
            setScrollGesturesEnabled(interactive)
            setZoomGesturesEnabled(interactive)
            setRotateGesturesEnabled(interactive)
            setTiltGesturesEnabled(interactive)
        }
    }

    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(if (dark) STYLE_DARK else STYLE_LIGHT) { st ->
            setupStyle(st, density, dark)
            style = st
        }
    }

    LaunchedEffect(map, paddingTopDp, paddingBottomDp) {
        map?.setPadding(0, (paddingTopDp * density).toInt(), 0, (paddingBottomDp * density).toInt())
    }

    LaunchedEffect(style, route) {
        style?.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(lines(listOfNotNull(route)))
    }
    LaunchedEffect(style, altRoutes) {
        style?.getSourceAs<GeoJsonSource>(SRC_ALT)?.setGeoJson(lines(altRoutes))
    }
    LaunchedEffect(style, trail) {
        style?.getSourceAs<GeoJsonSource>(SRC_TRAIL)?.setGeoJson(trailFeatures(trail))
    }
    LaunchedEffect(style, destination) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_DEST) ?: return@LaunchedEffect
        src.setGeoJson(
            destination?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }
                ?: FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
    }
    LaunchedEffect(style, friends) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_FRIENDS) ?: return@LaunchedEffect
        src.setGeoJson(friendsFeatures(friends))
    }
    LaunchedEffect(style, stateAverages) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_FUEL_STATE) ?: return@LaunchedEffect
        src.setGeoJson(stateAverageFeatures(stateAverages))
    }
    LaunchedEffect(style, fuelStations) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_FUEL) ?: return@LaunchedEffect
        src.setGeoJson(fuelStationsFeatures(fuelStations))
    }
    LaunchedEffect(style, me?.timeMs, me?.latitude, me?.longitude) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_ME) ?: return@LaunchedEffect
        val f = me?.let {
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).also { ft ->
                ft.addNumberProperty("b", (it.bearingDeg ?: 0f).toDouble())
            }
        }
        src.setGeoJson(f?.let { FeatureCollection.fromFeature(it) } ?: FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    // Enquadramento (rota inteira / viagem).
    LaunchedEffect(map, style, camera, cameraNonce, paddingTopDp, paddingBottomDp) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val c = camera
        if (c is MapCamera.Fit && c.points.size >= 2) fit(m, c.points, density)
    }

    // Focar em coordenadas específicas (ex.: amigo).
    LaunchedEffect(map, style, camera, cameraNonce) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val c = camera as? MapCamera.Target ?: return@LaunchedEffect
        val pos = CameraPosition.Builder().target(LatLng(c.lat, c.lon)).zoom(c.zoom).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 700)
    }

    // Seguir o motociclista.
    LaunchedEffect(map, style, camera, cameraNonce, me?.timeMs) {
        val m = map ?: return@LaunchedEffect
        val c = camera as? MapCamera.Follow ?: return@LaunchedEffect
        val p = me ?: return@LaunchedEffect
        val moving = (p.speedMps ?: 0f) > 1.5f && p.bearingDeg != null
        val bearing = when {
            !c.rotate -> 0.0
            moving -> p.bearingDeg!!.toDouble()
            else -> m.cameraPosition.bearing
        }
        val pos = CameraPosition.Builder().target(LatLng(p.latitude, p.longitude)).zoom(c.zoom).tilt(c.tilt).bearing(bearing).build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 1000)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun fit(m: MapLibreMap, pts: List<LatLon>, density: Float) {
    try {
        val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        val pad = (40 * density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, pad, pad, pad, pad), 700)
    } catch (_: Exception) {
        // pontos coincidentes ou mapa sem tamanho ainda: ignora
    }
}

private fun lines(list: List<List<LatLon>>): FeatureCollection =
    FeatureCollection.fromFeatures(
        list.filter { it.size >= 2 }.map { pts ->
            Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) }))
        },
    )

private val Slow = intArrayOf(0x4D, 0xA3, 0xFF)
private val Fast = intArrayOf(0xFF, 0x7A, 0x1A)

private fun speedColor(t: Float): String {
    val c = IntArray(3) { (Slow[it] + (Fast[it] - Slow[it]) * t.coerceIn(0f, 1f)).toInt() }
    return "#%02X%02X%02X".format(c[0], c[1], c[2])
}

/** Trilha em trechos, cada um com a cor da velocidade média (azul lento → laranja rápido). */
private fun trailFeatures(points: List<TrackPoint>?): FeatureCollection {
    if (points == null || points.size < 2) return FeatureCollection.fromFeatures(emptyList<Feature>())
    val vmax = maxOf(points.maxOf { it.speedMps ?: 0f }, 1f)
    val chunk = maxOf(2, points.size / 250)
    val out = ArrayList<Feature>()
    var i = 0
    while (i < points.size - 1) {
        val end = minOf(i + chunk, points.size - 1)
        val seg = points.subList(i, end + 1)
        val avg = seg.map { it.speedMps ?: 0f }.average().toFloat() / vmax
        val f = Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.longitude, it.latitude) }))
        f.addStringProperty("c", speedColor(avg))
        out += f
        i = end
    }
    return FeatureCollection.fromFeatures(out)
}

private fun friendsFeatures(friends: List<FriendMarker>): FeatureCollection {
    if (friends.isEmpty()) return FeatureCollection.fromFeatures(emptyList())
    val features = friends.map { f ->
        val label = if (f.timeAgoText.isNotBlank()) "${f.name} · ${f.timeAgoText}" else f.name
        val feat = Feature.fromGeometry(Point.fromLngLat(f.lon, f.lat))
        feat.addStringProperty("id", f.uid)
        feat.addStringProperty("name", f.name)
        feat.addStringProperty("timeAgo", f.timeAgoText)
        feat.addStringProperty("label", label)
        feat.addNumberProperty("b", (f.bearing ?: 0f).toDouble())
        feat
    }
    return FeatureCollection.fromFeatures(features)
}

private fun setupStyle(st: Style, density: Float, dark: Boolean) {
    st.addImage("me", meBitmap(density))
    st.addImage("friend-bike", friendBikeBitmap(density))
    st.addImage("fuel-station", fuelStationBitmap(density))
    listOf(SRC_ALT, SRC_ROUTE, SRC_TRAIL, SRC_DEST, SRC_FRIENDS, SRC_ME, SRC_FUEL, SRC_FUEL_STATE).forEach { st.addSource(GeoJsonSource(it)) }

    st.addLayer(
        LineLayer("alt-line", SRC_ALT).withProperties(
            lineColor(if (dark) "#7C8794" else "#8A94A0"), lineWidth(6f), lineOpacity(0.75f), lineCap("round"), lineJoin("round"),
        ),
    )
    st.addLayer(
        LineLayer("route-casing", SRC_ROUTE).withProperties(
            lineColor(if (dark) "#1A0B00" else "#FFFFFF"), lineWidth(12f), lineCap("round"), lineJoin("round"),
        ),
    )
    st.addLayer(
        LineLayer("route-line", SRC_ROUTE).withProperties(lineColor("#FF7A1A"), lineWidth(7f), lineCap("round"), lineJoin("round")),
    )
    st.addLayer(
        LineLayer("trail-casing", SRC_TRAIL).withProperties(
            lineColor(if (dark) "#0B0D10" else "#FFFFFF"), lineWidth(10f), lineCap("round"), lineJoin("round"),
        ),
    )
    st.addLayer(
        LineLayer("trail-line", SRC_TRAIL).withProperties(
            lineColor(Expression.get("c")), lineWidth(6f), lineCap("round"), lineJoin("round"),
        ),
    )
    st.addLayer(
        CircleLayer("dest-circle", SRC_DEST).withProperties(
            circleRadius(11f), circleColor("#FF7A1A"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(4f),
        ),
    )
    // Bolinha simples por baixo do ícone: aparece mesmo se a imagem ou a fonte do estilo falharem.
    st.addLayer(
        CircleLayer("fuel-circle", SRC_FUEL).withProperties(
            circleRadius(9f), circleColor("#FF7A1A"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f),
        ).also { it.minZoom = STATION_MIN_ZOOM },
    )
    st.addLayer(
        SymbolLayer("fuel-symbol", SRC_FUEL).withProperties(
            iconImage("fuel-station"),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
        ).also { it.minZoom = STATION_MIN_ZOOM },
    )
    st.addLayer(
        SymbolLayer("fuel-label", SRC_FUEL).withProperties(
            textField(Expression.get("price")),
            textFont(arrayOf("Noto Sans Bold")),
            textSize(12f),
            textColor(if (dark) "#FFFFFF" else "#0F172A"),
            textHaloColor(if (dark) "#0B0D10" else "#FFFFFF"),
            textHaloWidth(2f),
            textAnchor("top"),
            textOffset(arrayOf(0f, 1.4f)),
            textAllowOverlap(true),
            textIgnorePlacement(false),
        ).also { it.minZoom = STATION_MIN_ZOOM },
    )
    st.addLayer(
        SymbolLayer("fuel-state", SRC_FUEL_STATE).withProperties(
            textField(Expression.get("label")),
            textFont(arrayOf("Noto Sans Bold")),
            textSize(13f),
            textColor(if (dark) "#FFFFFF" else "#0F172A"),
            textHaloColor(if (dark) "#0B0D10" else "#FFFFFF"),
            textHaloWidth(2f),
            textAllowOverlap(true),
        ).also { it.maxZoom = STATION_MIN_ZOOM },
    )
    st.addLayer(
        SymbolLayer("friends-symbol", SRC_FRIENDS).withProperties(
            iconImage("friend-bike"),
            iconRotate(Expression.get("b")),
            iconRotationAlignment("map"),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
        ),
    )
    st.addLayer(
        SymbolLayer("friends-label", SRC_FRIENDS).withProperties(
            textField(Expression.get("label")),
            textSize(11f),
            textColor(if (dark) "#FFFFFF" else "#0F172A"),
            textHaloColor(if (dark) "#0B0D10" else "#FFFFFF"),
            textHaloWidth(2f),
            textAnchor("top"),
            textOffset(arrayOf(0f, 1.6f)),
            textAllowOverlap(true),
            textIgnorePlacement(false),
        ),
    )
    st.addLayer(
        SymbolLayer("me-symbol", SRC_ME).withProperties(
            iconImage("me"), iconRotate(Expression.get("b")), iconRotationAlignment("map"),
            iconAllowOverlap(true), iconIgnorePlacement(true),
        ),
    )
}

/** Formata o preço principal do posto para exibição na pílula do mapa (ex: "R$ 5,79" ou "~R$ 5,82"). */
fun formatStationMapPrice(station: ResolvedFuelStation): String {
    val price = station.precoGasolina ?: station.precoEtanol ?: station.precoDiesel
    if (price == null || !price.isFinite() || price <= 0.0) return "-"
    val formatted = String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f", price)
    return when (station.fonte) {
        PriceSource.POSTO_ANP -> formatted
        PriceSource.MEDIA_MUNICIPAL, PriceSource.MEDIA_ESTADUAL -> "~$formatted"
    }
}

/** Médias estaduais da ANP como rótulos ("ES
R$ 6,48"), usadas na vista aberta do mapa. */
fun stateAverageFeatures(states: List<app.hunter.app.fuel.StateAverage>): FeatureCollection {
    val features = states.mapNotNull { st ->
        val price = st.gasolina ?: return@mapNotNull null
        val f = Feature.fromGeometry(Point.fromLngLat(st.lon, st.lat))
        f.addStringProperty("label", st.uf + "\n" + String.format(Locale.forLanguageTag("pt-BR"), "~R$ %.2f", price))
        f
    }
    return FeatureCollection.fromFeatures(features)
}

/** Converte lista de postos resolvidos em FeatureCollection para o MapLibre GeoJsonSource. */
fun fuelStationsFeatures(stations: List<ResolvedFuelStation>): FeatureCollection {
    if (stations.isEmpty()) return FeatureCollection.fromFeatures(emptyList<Feature>())
    val features = stations.map { station ->
        val feature = Feature.fromGeometry(Point.fromLngLat(station.lon, station.lat))
        feature.addStringProperty("id", station.id)
        feature.addStringProperty("price", formatStationMapPrice(station))
        feature.addStringProperty("name", station.nome)
        feature.addStringProperty("source", station.fonte.name)
        feature
    }
    return FeatureCollection.fromFeatures(features)
}

/** Marcador de posto de combustível: fundo Midnight Navy, anel tonal e ícone de bomba de combustível. */
private fun fuelStationBitmap(density: Float): Bitmap {
    val size = (36 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Fundo circular Midnight Navy
    paint.style = Paint.Style.FILL
    paint.color = 0xFF141C2B.toInt()
    c.drawCircle(cx, cx, size * 0.46f, paint)

    // Borda tonal Novare Orange
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2.5f * density
    paint.color = 0xFFFF7A1A.toInt()
    c.drawCircle(cx, cx, size * 0.44f, paint)

    // Corpo da bomba
    paint.style = Paint.Style.FILL
    paint.color = 0xFFFFFFFF.toInt()
    val left = cx - size * 0.16f
    val top = cx - size * 0.20f
    val right = cx + size * 0.08f
    val bottom = cx + size * 0.22f
    c.drawRect(left, top, right, bottom, paint)

    // Mangueira e bico
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.5f * density
    val hose = Path().apply {
        moveTo(right, top + size * 0.08f)
        lineTo(right + size * 0.10f, top + size * 0.08f)
        lineTo(right + size * 0.10f, bottom - size * 0.04f)
    }
    c.drawPath(hose, paint)

    return bmp
}

/** Marcador de amigo na estrada: halo ciano/azul, anel branco e seta apontando para o norte (gira com o rumo). */
private fun friendBikeBitmap(density: Float): Bitmap {
    val size = (64 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0x4000B4D8.toInt()
    c.drawCircle(cx, cx, size * 0.48f, paint)
    paint.color = 0xFFFFFFFF.toInt()
    c.drawCircle(cx, cx, size * 0.30f, paint)
    paint.color = 0xFF00B4D8.toInt()
    val arrow = Path().apply {
        moveTo(cx, size * 0.22f)
        lineTo(cx + size * 0.17f, size * 0.66f)
        lineTo(cx, size * 0.54f)
        lineTo(cx - size * 0.17f, size * 0.66f)
        close()
    }
    c.drawPath(arrow, paint)
    return bmp
}

/** Marcador do motociclista: halo laranja, anel branco e seta apontando para o norte (gira com o rumo). */
private fun meBitmap(density: Float): Bitmap {
    val size = (64 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0x40FF7A1A
    c.drawCircle(cx, cx, size * 0.48f, paint)
    paint.color = 0xFFFFFFFF.toInt()
    c.drawCircle(cx, cx, size * 0.30f, paint)
    paint.color = 0xFFFF7A1A.toInt()
    val arrow = Path().apply {
        moveTo(cx, size * 0.22f)
        lineTo(cx + size * 0.17f, size * 0.66f)
        lineTo(cx, size * 0.54f)
        lineTo(cx - size * 0.17f, size * 0.66f)
        close()
    }
    c.drawPath(arrow, paint)
    return bmp
}
