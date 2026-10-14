package com.jashith.stride.ui.map

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jashith.stride.core.model.Coord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/** One path on the map. Lines are drawn in list order, so put the one that should sit on top last. */
data class MapLine(
    val points: List<Coord>,
    val color: Color,
    val width: Float = 4f,
    val dashed: Boolean = false,
    val alpha: Float = 1f,
)

/** A round marker, such as a start or finish dot or a route waypoint. */
data class MapDot(val coord: Coord, val color: Color, val radius: Float = 7f, val stroke: Color = Color.White)

object MapStyles {
    /** Free vector tiles from OpenFreeMap, based on OpenStreetMap data. No API key. */
    const val POSITRON = "https://tiles.openfreemap.org/styles/positron"

    /** What the map shows when the tiles cannot be fetched: a plain page, with the routes still drawn on it. */
    const val OFFLINE = """{"version":8,"name":"offline","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#ECEEE8"}}]}"""
}

private const val LINES = "stride-lines"
private const val LINES_DASHED = "stride-lines-dashed"
private const val DOTS = "stride-dots"
private const val USER = "stride-user"

/**
 * An OpenStreetMap map (MapLibre with OpenFreeMap tiles). Replaces the iOS `RouteMap`, `HeatMap` and
 * `EditableMap` with one composable: you give it lines and dots and it draws them.
 *
 * @param fitKey the camera re-fits to the lines and dots whenever this changes.
 * @param follow when non-null the camera stays centred on it (the live recording map).
 * @param zoomScaledLines widen lines as the map zooms in; used by the heatmap, where thousands of
 *        translucent lines overlap and the busiest roads read as the brightest.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun StrideMap(
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    dots: List<MapDot> = emptyList(),
    interactive: Boolean = true,
    fitKey: Any? = lines.sumOf { it.points.size } to dots.size,
    fitPaddingPx: Int = 80,
    userLocation: Coord? = null,
    follow: Coord? = null,
    initialCenter: Coord? = null,
    zoomScaledLines: Boolean = false,
    onTap: ((Coord) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(0) }
    val currentOnTap by rememberUpdatedState(onTap)

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // The effect may start after the lifecycle did, so catch up.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
                setAttributionGravity(android.view.Gravity.BOTTOM or android.view.Gravity.START)
            }
            m.addOnMapClickListener { latLng ->
                val handler = currentOnTap
                if (handler != null) handler(Coord(latLng.latitude, latLng.longitude))
                handler != null
            }
            initialCenter?.let { m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), 13.0)) }
            loadStyle(mapView, m, MapStyles.POSITRON) { styleReady += 1 }
        }
        onDispose { }
    }

    LaunchedEffect(map, interactive) {
        map?.uiSettings?.apply {
            isScrollGesturesEnabled = interactive
            isZoomGesturesEnabled = interactive
            isDoubleTapGesturesEnabled = interactive
            isQuickZoomGesturesEnabled = interactive
        }
    }

    // GeoJSON is built off the main thread: a long ride is 10,000 points, a heatmap far more.
    val lineJson by produceGeoJson(lines, key = lines) { buildLinesJson(lines, dashed = false) }
    val dashedJson by produceGeoJson(lines, key = lines) { buildLinesJson(lines, dashed = true) }
    val dotJson = remember(dots) { buildDotsJson(dots) }
    val userJson = remember(userLocation) { buildDotsJson(userLocation?.let { listOf(MapDot(it, Color(0xFF2F7DF6), 8f)) } ?: emptyList()) }

    LaunchedEffect(map, styleReady, lineJson, dashedJson, dotJson, userJson, zoomScaledLines) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        ensureLayers(style, zoomScaledLines)
        (style.getSource(LINES) as? GeoJsonSource)?.setGeoJson(lineJson)
        (style.getSource(LINES_DASHED) as? GeoJsonSource)?.setGeoJson(dashedJson)
        (style.getSource(DOTS) as? GeoJsonSource)?.setGeoJson(dotJson)
        (style.getSource(USER) as? GeoJsonSource)?.setGeoJson(userJson)
    }

    LaunchedEffect(map, fitKey, styleReady) {
        val m = map ?: return@LaunchedEffect
        val all = lines.flatMap { it.points } + dots.map { it.coord } + listOfNotNull(userLocation.takeIf { lines.isEmpty() && dots.isEmpty() })
        val valid = all.filter { it.isValid }
        if (valid.isEmpty()) return@LaunchedEffect
        if (valid.size == 1 || valid.all { it == valid.first() }) {
            val only = valid.first()
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), 15.0))
            return@LaunchedEffect
        }
        val bounds = LatLngBounds.Builder().apply { valid.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, fitPaddingPx))
    }

    LaunchedEffect(map, follow) {
        val m = map ?: return@LaunchedEffect
        val target = follow ?: return@LaunchedEffect
        if (!target.isValid) return@LaunchedEffect
        val zoom = maxOf(m.cameraPosition.zoom, 15.5)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lon), zoom), 800)
    }

    Box(modifier) {
        AndroidView(
            factory = {
                if (interactive) {
                    // A map inside a scrolling column must keep its drags instead of handing them to the page.
                    mapView.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!interactive && onTap == null) {
            // A transparent layer that lets the page scroll when a finger starts on a decorative map.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {})
        }
    }
}

@Composable
private fun produceGeoJson(
    @Suppress("UNUSED_PARAMETER") lines: List<MapLine>,
    key: Any?,
    build: () -> String,
): androidx.compose.runtime.State<String> =
    androidx.compose.runtime.produceState(initialValue = EMPTY, key) {
        value = withContext(Dispatchers.Default) { build() }
    }

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

private fun loadStyle(view: MapView, map: MapLibreMap, uri: String, onLoaded: () -> Unit) {
    var failed = false
    view.addOnDidFailLoadingMapListener {
        // Offline, or the tile host is down: keep the routes on a plain page instead of an empty view.
        if (!failed) {
            failed = true
            map.setStyle(Style.Builder().fromJson(MapStyles.OFFLINE)) { onLoaded() }
        }
    }
    map.setStyle(Style.Builder().fromUri(uri)) { onLoaded() }
}

private fun ensureLayers(style: Style, zoomScaled: Boolean) {
    if (style.getSource(LINES) != null) return
    style.addSource(GeoJsonSource(LINES, EMPTY))
    style.addSource(GeoJsonSource(LINES_DASHED, EMPTY))
    style.addSource(GeoJsonSource(DOTS, EMPTY))
    style.addSource(GeoJsonSource(USER, EMPTY))

    val width: Expression = if (zoomScaled) {
        Expression.interpolate(
            Expression.exponential(2f), Expression.zoom(),
            Expression.stop(8, Expression.product(Expression.get("width"), Expression.literal(0.4f))),
            Expression.stop(14, Expression.get("width")),
            Expression.stop(18, Expression.product(Expression.get("width"), Expression.literal(6f))),
        )
    } else {
        Expression.get("width")
    }
    style.addLayer(
        LineLayer("$LINES-layer", LINES).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineOpacity(Expression.get("alpha")),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        LineLayer("$LINES_DASHED-layer", LINES_DASHED).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineOpacity(Expression.get("alpha")),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 2f)),
            PropertyFactory.lineCap("butt"),
        ),
    )
    style.addLayer(
        CircleLayer("$DOTS-layer", DOTS).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleRadius(Expression.get("radius")),
            PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("stroke"))),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        CircleLayer("$USER-layer", USER).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleRadius(Expression.get("radius")),
            PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("stroke"))),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

internal fun colorHex(c: Color): String =
    String.format(Locale.ROOT, "#%02X%02X%02X", (c.red * 255 + 0.5f).toInt(), (c.green * 255 + 0.5f).toInt(), (c.blue * 255 + 0.5f).toInt())

/** Long paths are thinned: past a few hundred points per line the eye cannot tell, but the map slows down. */
internal fun thin(points: List<Coord>, limit: Int): List<Coord> {
    val valid = points.filter { it.isValid }
    if (valid.size <= limit) return valid
    val step = valid.size.toDouble() / limit
    val out = ArrayList<Coord>(limit + 1)
    var i = 0.0
    while (i < valid.size) {
        out.add(valid[i.toInt()])
        i += step
    }
    out.add(valid.last())
    return out
}

internal fun buildLinesJson(lines: List<MapLine>, dashed: Boolean): String {
    val perLine = if (lines.size > 40) 300 else 2000
    val sb = StringBuilder(EMPTY.length + lines.size * 64)
    sb.append("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (line in lines) {
        if (line.dashed != dashed) continue
        val pts = thin(line.points, perLine)
        if (pts.size < 2) continue
        if (!first) sb.append(',')
        first = false
        sb.append("""{"type":"Feature","properties":{"color":"""").append(colorHex(line.color))
            .append("""","width":""").append(line.width)
            .append(""","alpha":""").append(line.alpha * line.color.alpha)
            .append("""},"geometry":{"type":"LineString","coordinates":[""")
        pts.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append('[').append(String.format(Locale.ROOT, "%.6f,%.6f", c.lon, c.lat)).append(']')
        }
        sb.append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

internal fun buildDotsJson(dots: List<MapDot>): String {
    val sb = StringBuilder()
    sb.append("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (dot in dots) {
        if (!dot.coord.isValid) continue
        if (!first) sb.append(',')
        first = false
        sb.append("""{"type":"Feature","properties":{"color":"""").append(colorHex(dot.color))
            .append("""","stroke":"""").append(colorHex(dot.stroke))
            .append("""","radius":""").append(dot.radius)
            .append("""},"geometry":{"type":"Point","coordinates":[""")
            .append(String.format(Locale.ROOT, "%.6f,%.6f", dot.coord.lon, dot.coord.lat))
            .append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}
