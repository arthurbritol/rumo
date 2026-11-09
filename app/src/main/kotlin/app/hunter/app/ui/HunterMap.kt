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
import app.hunter.model.TrackPoint
import app.hunter.nav.LatLon
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Como a câmera se comporta. */
sealed interface MapCamera {
    /** O usuário mexe livremente. */
    data object Free : MapCamera

    /** Segue a posição; [rotate] gira o mapa com o rumo (modo navegação), [tilt] inclina. */
    data class Follow(val zoom: Double = 16.0, val tilt: Double = 0.0, val rotate: Boolean = false) : MapCamera

    /** Enquadra todos os [points] (pré-visualização de rota, detalhe de viagem). */
    data class Fit(val points: List<LatLon>) : MapCamera
}

// "positron" é bem mais leve que o "liberty" (sem prédios 3D nem centenas de ícones): rola sem travar.
internal const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
internal const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val REASON_API_GESTURE = 1

private const val SRC_ALT = "alt-src"
private const val SRC_ROUTE = "route-src"
private const val SRC_TRAIL = "trail-src"
private const val SRC_DEST = "dest-src"
private const val SRC_ME = "me-src"

/**
 * Mapa (MapLibre + OpenFreeMap, sem chave de API). Desenha: rota principal, rotas alternativas, trilha
 * gravada colorida por velocidade, destino e a posição do motociclista. Tudo é redesenhado quando os
 * parâmetros mudam; a câmera obedece a [camera].
 */
@Composable
fun HunterMap(
    modifier: Modifier = Modifier,
    dark: Boolean = LocalDark.current,
    me: TrackPoint? = null,
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
            m.addOnCameraMoveStartedListener { reason -> if (reason == REASON_API_GESTURE) latestGesture() }
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(-8.05, -34.9)).zoom(11.0).build()))
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

private fun setupStyle(st: Style, density: Float, dark: Boolean) {
    st.addImage("me", meBitmap(density))
    listOf(SRC_ALT, SRC_ROUTE, SRC_TRAIL, SRC_DEST, SRC_ME).forEach { st.addSource(GeoJsonSource(it)) }

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
    st.addLayer(
        SymbolLayer("me-symbol", SRC_ME).withProperties(
            iconImage("me"), iconRotate(Expression.get("b")), iconRotationAlignment("map"),
            iconAllowOverlap(true), iconIgnorePlacement(true),
        ),
    )
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
