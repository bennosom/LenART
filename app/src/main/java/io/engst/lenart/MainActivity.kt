package io.engst.lenart

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.engst.lenart.ui.theme.LenARTTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Canvas as AndroidCanvas

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Start in immersive mode (hide system bars, allow transient swipe)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).let { controller ->
      controller.hide(WindowInsetsCompat.Type.systemBars())
      controller.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    setContent { LenARTTheme { DrawingApp() } }
  }
}

private data class StrokePath(
    val color: Color,
    val widthPx: Float,
    // Use snapshot state list so updates during drag invalidate the Canvas and redraw lines
    val points: MutableList<Offset> = mutableStateListOf(),
)

@Composable
private fun DrawingApp() {
  val context = LocalContext.current
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()

  // Tools state
  val colors =
      listOf(
          Color(0xFFE91E63), // pink
          Color(0xFFFFC107), // amber
          Color(0xFF4CAF50), // green
          Color(0xFF2196F3), // blue
          Color(0xFFFF5722), // deep orange
      )
  var selectedColorIndex by remember { mutableIntStateOf(0) }
  val thicknessOptionsDp = listOf(4.dp, 8.dp, 14.dp)
  var selectedThicknessIndex by remember { mutableIntStateOf(1) }
  val thicknessOptionsPx = thicknessOptionsDp.map { with(density) { it.toPx() } }

  var isEraser by remember { mutableStateOf(false) }

  // Drawing state
  val strokes = remember { mutableStateListOf<StrokePath>() }
  var canvasWidth by remember { mutableIntStateOf(0) }
  var canvasHeight by remember { mutableIntStateOf(0) }
  var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }

  // SAF launchers
  val saveLauncher =
      rememberLauncherForActivityResult(
          contract = ActivityResultContracts.CreateDocument("image/png")
      ) { uri: Uri? ->
        if (uri != null && canvasWidth > 0 && canvasHeight > 0) {
          scope.launch(Dispatchers.IO) {
            saveDrawing(
                context.contentResolver,
                uri,
                canvasWidth,
                canvasHeight,
                strokes,
                backgroundBitmap,
            )
          }
        }
      }

  val openLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) {
          uri: Uri? ->
        if (uri != null) {
          strokes.clear()
          scope.launch(Dispatchers.IO) {
            val bmp = loadBitmap(context.contentResolver, uri)
            withContext(Dispatchers.Main) { backgroundBitmap = bmp }
          }
        }
      }

  val canvasColor = Color.White
  // UI
  Scaffold(
      containerColor = canvasColor,
      content = { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          DrawingCanvas(
              strokes = strokes,
              isEraser = isEraser,
              drawColor = if (isEraser) canvasColor else colors[selectedColorIndex],
              strokeWidthPx = thicknessOptionsPx[selectedThicknessIndex],
              background = backgroundBitmap?.asImageBitmap(),
              onSizeChanged = { w, h ->
                canvasWidth = w
                canvasHeight = h
              },
          )

          // Control overlay
          Column(
              modifier =
                  Modifier.align(Alignment.BottomCenter)
                      .fillMaxWidth()
                      .background(Color.Black.copy(alpha = 0.2f))
                      .padding(8.dp)
          ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              colors.forEachIndexed { idx, color ->
                ColorDot(
                    color = color,
                    selected = !isEraser && idx == selectedColorIndex,
                ) {
                  selectedColorIndex = idx
                  isEraser = false
                }
              }

              Spacer(Modifier.weight(1f))

              thicknessOptionsDp.forEachIndexed { idx, dpVal ->
                ThicknessDot(
                    size = dpVal,
                    selected = idx == selectedThicknessIndex,
                ) {
                  selectedThicknessIndex = idx
                }
              }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Row {
                IconButton(
                    onClick = { isEraser = !isEraser },
                ) {
                  if (isEraser) {
                    Icon(painterResource(R.drawable.rounded_ink_eraser_24), "Erase")
                  } else {
                    Icon(painterResource(R.drawable.rounded_draw_24), "Draw")
                  }
                }
              }

              Row {
                IconButton(
                    onClick = {
                      val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                      val name = "LenART_${sdf.format(Date())}.png"
                      saveLauncher.launch(name)
                    }
                ) {
                  Icon(Icons.Default.Save, "Save as image")
                }
                IconButton(onClick = { openLauncher.launch(arrayOf("image/*", "image/png")) }) {
                  Icon(Icons.Default.Upload, "Open image")
                }
                IconButton(
                    onClick = {
                      strokes.clear()
                      backgroundBitmap = null
                    }
                ) {
                  Icon(Icons.Default.Clear, "Clear canvas")
                }
              }
            }
          }
        }
      },
  )
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
  val outer = if (selected) 42.dp else 28.dp
  Box(modifier = Modifier.size(outer).clip(CircleShape).background(color).clickable { onClick() })
}

@Composable
private fun ThicknessDot(size: Dp, selected: Boolean, onClick: () -> Unit) {
  val outer = if (selected) 42.dp else 28.dp
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.size(outer)
              .clip(CircleShape)
              .background(if (selected) Color(0xFFE0E0E0) else Color(0xFFF0F0F0))
              .clickable { onClick() },
  ) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(Color.Black))
  }
}

@Composable
private fun DrawingCanvas(
    strokes: MutableList<StrokePath>,
    isEraser: Boolean,
    drawColor: Color,
    strokeWidthPx: Float,
    background: ImageBitmap?,
    onSizeChanged: (Int, Int) -> Unit,
) {
  Canvas(
      modifier =
          Modifier.fillMaxSize().pointerInput(
              drawColor,
              strokeWidthPx,
              isEraser,
          ) {
            awaitPointerEventScope {
              while (true) {
                val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                val stroke =
                    StrokePath(
                        color = if (isEraser) Color.White else drawColor,
                        widthPx = strokeWidthPx,
                    )
                stroke.points.add(down.position)
                strokes.add(stroke)
                // consume not required for basic drawing
                // Drag/move loop
                while (true) {
                  val event = awaitPointerEvent()
                  val change = event.changes.first()
                  if (change.pressed) {
                    stroke.points.add(change.position)
                    // consume not required for basic drawing
                  } else {
                    break
                  }
                }
              }
            }
          },
      onDraw = {
        // Report size
        onSizeChanged(size.width.toInt(), size.height.toInt())

        // Draw background (white or loaded image stretched to fit)
        if (background != null) {
          drawImage(
              image = background,
              dstSize = IntSize(size.width.toInt(), size.height.toInt()),
          )
        } else {
          // fill white (already via background)
        }

        // Draw all strokes
        strokes.forEach { s ->
          if (s.points.size > 1) {
            for (i in 1 until s.points.size) {
              drawLine(
                  color = s.color,
                  start = s.points[i - 1],
                  end = s.points[i],
                  strokeWidth = s.widthPx,
                  cap = StrokeCap.Round,
                  blendMode = BlendMode.SrcOver,
              )
            }
          } else if (s.points.size == 1) {
            // Dot
            drawCircle(color = s.color, radius = s.widthPx / 2, center = s.points[0])
          }
        }
      },
  )
}

private fun saveDrawing(
    resolver: ContentResolver,
    uri: Uri,
    width: Int,
    height: Int,
    strokes: List<StrokePath>,
    background: Bitmap?,
) {
  // Compose final bitmap with optional background and strokes (eraser acts as white paint)
  val bitmap = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
  val canvas = AndroidCanvas(bitmap)
  val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
      }

  // Background: image or white
  if (background != null) {
    val dstRect = android.graphics.Rect(0, 0, width, height)
    canvas.drawBitmap(background, null, dstRect, null)
  } else {
    canvas.drawColor(android.graphics.Color.WHITE)
  }

  // Draw strokes
  strokes.forEach { s ->
    paint.color = s.color.toArgb()
    paint.strokeWidth = s.widthPx
    if (s.points.size > 1) {
      for (i in 1 until s.points.size) {
        val p0 = s.points[i - 1]
        val p1 = s.points[i]
        canvas.drawLine(p0.x, p0.y, p1.x, p1.y, paint)
      }
    } else if (s.points.size == 1) {
      val p = s.points[0]
      val fill =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = s.color.toArgb()
          }
      canvas.drawCircle(p.x, p.y, s.widthPx / 2, fill)
    }
  }

  resolver.openOutputStream(uri)?.use { out ->
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    out.flush()
  }
}

private fun loadBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
  return try {
    resolver.openInputStream(uri)?.use { input: InputStream -> BitmapFactory.decodeStream(input) }
  } catch (_: Exception) {
    null
  }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  LenARTTheme { DrawingApp() }
}
