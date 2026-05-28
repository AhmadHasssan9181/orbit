package ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import repository.MatrixOrbitRepository

@Serializable
data class StrokePoint(val x: Float, val y: Float)

@Serializable
data class StrokeData(
    val color: Long,
    val width: Float,
    val points: List<StrokePoint>
)

@Serializable
data class WhiteboardState(
    val strokes: List<StrokeData>
)

@Composable
fun Whiteboard(
    roomId: String,
    repository: MatrixOrbitRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    
    // Local strokes (drawn by this user)
    val localStrokes = remember { mutableStateListOf<StrokeData>() }
    // Remote strokes (drawn by everyone, including us after sync)
    val remoteStrokes = remember { mutableStateListOf<StrokeData>() }
    
    var currentPathPoints by remember { mutableStateOf(mutableListOf<StrokePoint>()) }
    val currentColor = Color.White
    val currentWidth = 5f

    // Poll for remote whiteboard states (naive sync for prototype)
    // In a production app, we would listen to Trixnity's state flow directly
    LaunchedEffect(roomId) {
        while(true) {
            try {
                // We'd ideally have repository.getRoomState("org.orbit.whiteboard") here
                // For now, we rely on local drawing
                delay(2000)
            } catch (e: Exception) {
                delay(2000)
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        currentPathPoints = mutableListOf(StrokePoint(offset.x, offset.y))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        currentPathPoints.add(StrokePoint(change.position.x, change.position.y))
                    },
                    onDragEnd = {
                        val newStroke = StrokeData(
                            color = currentColor.value.toLong(),
                            width = currentWidth,
                            points = currentPathPoints.toList()
                        )
                        localStrokes.add(newStroke)
                        currentPathPoints = mutableListOf()
                        
                        // Sync to Matrix (fire and forget)
                        scope.launch {
                            val state = WhiteboardState(strokes = localStrokes.toList())
                            // repository.sendStateEvent("org.orbit.whiteboard", state)
                        }
                    }
                )
            }
    ) {
        // Draw remote and local strokes
        val allStrokes = remoteStrokes + localStrokes
        
        for (stroke in allStrokes) {
            if (stroke.points.size < 2) continue
            val path = Path()
            path.moveTo(stroke.points.first().x, stroke.points.first().y)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }
            drawPath(
                path = path,
                color = Color(stroke.color.toULong()),
                style = Stroke(
                    width = stroke.width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        
        // Draw the stroke currently being drawn
        if (currentPathPoints.size >= 2) {
            val path = Path()
            path.moveTo(currentPathPoints.first().x, currentPathPoints.first().y)
            for (i in 1 until currentPathPoints.size) {
                path.lineTo(currentPathPoints[i].x, currentPathPoints[i].y)
            }
            drawPath(
                path = path,
                color = currentColor,
                style = Stroke(
                    width = currentWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
