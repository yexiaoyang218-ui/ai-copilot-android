package com.aisocial.copilot.capture

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.aisocial.copilot.MainActivity
import com.aisocial.copilot.core.BitmapUtils
import com.aisocial.copilot.core.NormalizedRect
import com.aisocial.copilot.domain.model.ThemeMode
import com.aisocial.copilot.overlay.OverlayService
import com.aisocial.copilot.ui.theme.AiCopilotTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Transparent activity that (1) asks for MediaProjection consent, (2) waits for the
 * single captured frame, and (3) shows a region selector so the user can crop exactly
 * the chat area they care about.
 *
 * While waiting, the activity draws nothing at all — anything visible here would end up
 * inside the screenshot.
 */
class ScreenCaptureActivity : ComponentActivity() {

    private var captured by mutableStateOf<Bitmap?>(null)
    private var failure by mutableStateOf<String?>(null)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                MediaProjectionService.start(this, result.resultCode, data)
            } else {
                failure = "已取消屏幕捕获授权"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Keep the floating ball out of the screenshot.
        OverlayService.setBallVisible(false)
        CaptureBus.reset()

        lifecycleScope.launch {
            CaptureBus.frame.collect { bitmap ->
                if (bitmap != null) {
                    captured = bitmap
                    failure = null
                    CaptureBus.consumeFrame()
                }
            }
        }
        lifecycleScope.launch {
            CaptureBus.error.collect { message ->
                if (message != null) {
                    failure = message
                    CaptureBus.consumeError()
                }
            }
        }

        if (savedInstanceState == null) requestProjection()

        setContent {
            AiCopilotTheme(themeMode = ThemeMode.DARK) {
                val bitmap = captured
                val error = failure
                Box(Modifier.fillMaxSize()) {
                    when {
                        bitmap != null -> CropEditor(
                            bitmap = bitmap,
                            onCancel = { finish() },
                            onConfirm = { rect -> confirmCrop(bitmap, rect) },
                        )

                        error != null -> ErrorPane(
                            message = error,
                            onRetry = {
                                failure = null
                                captured = null
                                requestProjection()
                            },
                            onCancel = { finish() },
                        )

                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        OverlayService.setBallVisible(true)
        captured = null
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            failure = "当前系统不支持屏幕捕获"
            return
        }
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ defaults to "a single app" and even shows an app picker,
                // which defeats the whole point (cropping a chat window from another app).
                // Ask explicitly for the whole default display instead.
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        }.onFailure { failure = "无法发起屏幕捕获授权：${it.message ?: "未知错误"}" }
    }

    private fun confirmCrop(bitmap: Bitmap, rect: NormalizedRect) {
        val cropped = runCatching { BitmapUtils.crop(bitmap, rect) }.getOrNull()
        if (cropped == null) {
            failure = "裁剪失败，请重新框选。"
            captured = null
            return
        }
        CaptureBus.publishDraft(PendingDraft(bitmap = cropped, source = "screen"))
        // In overlay mode the floating panel picks the crop up and runs OCR + generation
        // itself, so the app must not come to the foreground.
        if (mode != MODE_OVERLAY) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(MainActivity.EXTRA_OPEN_COMPOSER, true)
                }
            )
        }
        finish()
    }

    private val mode: String
        get() = intent?.getStringExtra(EXTRA_MODE) ?: MODE_APP

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_APP = "app"
        const val MODE_OVERLAY = "overlay"
    }
}

private enum class DragMode { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE, NEW }

@Composable
private fun CropEditor(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (NormalizedRect) -> Unit,
) {
    var selection by remember(bitmap) { mutableStateOf(NormalizedRect(0.06f, 0.12f, 0.94f, 0.88f)) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var dragAnchorPoint by remember { mutableStateOf(Offset.Zero) }
    var dragAnchorRect by remember { mutableStateOf(selection) }
    var showPreview by remember { mutableStateOf(false) }

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val handleRadius = with(density) { 16.dp.toPx() }

    fun imageRect(size: IntSize): Rect {
        if (size.width == 0 || size.height == 0) return Rect(0f, 0f, 0f, 0f)
        val scale = min(
            size.width.toFloat() / bitmap.width.toFloat(),
            size.height.toFloat() / bitmap.height.toFloat()
        )
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f
        return Rect(left, top, left + drawWidth, top + drawHeight)
    }

    fun toNormalized(position: Offset, rect: Rect): Offset {
        if (rect.width <= 0f || rect.height <= 0f) return Offset.Zero
        return Offset(
            ((position.x - rect.left) / rect.width).coerceIn(0f, 1f),
            ((position.y - rect.top) / rect.height).coerceIn(0f, 1f),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ComposeColor.Black)
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "屏幕截图",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val rect = imageRect(canvasSize)
                            val point = toNormalized(offset, rect)
                            val sel = selection.normalized()
                            val corners = listOf(
                                DragMode.TOP_LEFT to Offset(sel.left, sel.top),
                                DragMode.TOP_RIGHT to Offset(sel.right, sel.top),
                                DragMode.BOTTOM_LEFT to Offset(sel.left, sel.bottom),
                                DragMode.BOTTOM_RIGHT to Offset(sel.right, sel.bottom),
                            )
                            val touchSlopNorm = handleRadius / rect.width.coerceAtLeast(1f) * 1.4f
                            val nearest = corners.minByOrNull { (_, corner) ->
                                abs(corner.x - point.x) + abs(corner.y - point.y)
                            }
                            dragAnchorPoint = point
                            dragAnchorRect = sel
                            dragMode = when {
                                nearest != null &&
                                    abs(nearest.second.x - point.x) < touchSlopNorm &&
                                    abs(nearest.second.y - point.y) < touchSlopNorm * 1.6f -> nearest.first

                                point.x in sel.left..sel.right && point.y in sel.top..sel.bottom -> DragMode.MOVE
                                else -> DragMode.NEW
                            }
                            if (dragMode == DragMode.NEW) {
                                selection = NormalizedRect(point.x, point.y, point.x, point.y)
                            }
                        },
                        onDragEnd = { dragMode = DragMode.NONE },
                        onDragCancel = { dragMode = DragMode.NONE },
                    ) { change, _ ->
                        change.consume()
                        val rect = imageRect(canvasSize)
                        val point = toNormalized(change.position, rect)
                        val anchor = dragAnchorRect
                        selection = when (dragMode) {
                            DragMode.MOVE -> {
                                val dx = point.x - dragAnchorPoint.x
                                val dy = point.y - dragAnchorPoint.y
                                val w = anchor.width
                                val h = anchor.height
                                val left = (anchor.left + dx).coerceIn(0f, (1f - w).coerceAtLeast(0f))
                                val top = (anchor.top + dy).coerceIn(0f, (1f - h).coerceAtLeast(0f))
                                NormalizedRect(left, top, left + w, top + h)
                            }

                            DragMode.TOP_LEFT -> NormalizedRect(point.x, point.y, anchor.right, anchor.bottom)
                            DragMode.TOP_RIGHT -> NormalizedRect(anchor.left, point.y, point.x, anchor.bottom)
                            DragMode.BOTTOM_LEFT -> NormalizedRect(point.x, anchor.top, anchor.right, point.y)
                            DragMode.BOTTOM_RIGHT -> NormalizedRect(anchor.left, anchor.top, point.x, point.y)
                            DragMode.NEW -> NormalizedRect(dragAnchorPoint.x, dragAnchorPoint.y, point.x, point.y)
                            DragMode.NONE -> selection
                        }.normalized()
                    }
                }
        ) {
            val rect = imageRect(canvasSize)
            if (rect.width <= 0f) return@Canvas

            val sel = selection.normalized()
            val left = rect.left + sel.left * rect.width
            val top = rect.top + sel.top * rect.height
            val right = rect.left + sel.right * rect.width
            val bottom = rect.top + sel.bottom * rect.height

            val scrim = ComposeColor.Black.copy(alpha = 0.58f)
            drawRect(scrim, topLeft = rect.topLeft, size = Size(rect.width, top - rect.top))
            drawRect(scrim, topLeft = Offset(rect.left, bottom), size = Size(rect.width, rect.bottom - bottom))
            drawRect(scrim, topLeft = Offset(rect.left, top), size = Size(left - rect.left, bottom - top))
            drawRect(scrim, topLeft = Offset(right, top), size = Size(rect.right - right, bottom - top))

            drawRect(
                color = ComposeColor.White,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2.dp.toPx()),
            )

            // rule-of-thirds guides
            val guide = ComposeColor.White.copy(alpha = 0.35f)
            for (index in 1..2) {
                val x = left + (right - left) * index / 3f
                val y = top + (bottom - top) * index / 3f
                drawLine(guide, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                drawLine(guide, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }

            val handleRadiusPx = 7.dp.toPx()
            listOf(
                Offset(left, top), Offset(right, top),
                Offset(left, bottom), Offset(right, bottom),
            ).forEach { corner ->
                drawCircle(ComposeColor.Black.copy(alpha = 0.4f), handleRadiusPx + 2.dp.toPx(), corner)
                drawCircle(ComposeColor.White, handleRadiusPx, corner)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .background(ComposeColor.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "拖边角改范围；可以整段框选，AI 会分辨谁说了什么",
                color = ComposeColor.White,
                fontSize = 13.sp,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            OutlinedButton(
                onClick = { selection = NormalizedRect(0f, 0f, 1f, 1f) },
                modifier = Modifier.weight(1f),
            ) {
                Text("全屏")
            }
            OutlinedButton(
                onClick = { showPreview = true },
                modifier = Modifier.weight(1f),
            ) {
                Text("预览")
            }
            Button(
                onClick = { onConfirm(selection.normalized()) },
                enabled = selection.normalized().isValid,
                modifier = Modifier.weight(1f),
            ) {
                Text("确定")
            }
        }
    }

    if (showPreview) {
        val preview = remember(selection) { BitmapUtils.crop(bitmap, selection.normalized()) }
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("预览裁剪结果") },
            text = {
                Column {
                    Image(
                        bitmap = remember(preview) { preview.asImageBitmap() },
                        contentDescription = "裁剪预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(preview.width.toFloat() / preview.height.toFloat())
                            .background(ComposeColor.Black, RoundedCornerShape(8.dp)),
                    )
                    Text(
                        text = "只会识别并处理这一块区域。",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPreview = false
                    onConfirm(selection.normalized())
                }) { Text("用这张") }
            },
            dismissButton = {
                TextButton(onClick = { showPreview = false }) { Text("继续调整") }
            },
        )
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ComposeColor.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "截图没有完成",
                color = ComposeColor.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Text(
                text = message,
                color = ComposeColor.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onCancel) { Text("关闭") }
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

/** Tiny value holder so the crop maths stays readable. */
private data class Rect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val topLeft: Offset get() = Offset(left, top)
}
