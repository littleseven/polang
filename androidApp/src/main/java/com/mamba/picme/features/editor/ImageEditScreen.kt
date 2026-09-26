package com.mamba.picme.features.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.editor.components.EditorChip
import com.mamba.picme.features.gallery.MediaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

enum class EditMode { DOODLE, MOSAIC }

data class DrawAction(
    val path: Path,
    val mode: EditMode,
    val color: Int = android.graphics.Color.RED,
    val strokeWidth: Float = 60f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditScreen(
    asset: MediaAsset,
    viewModel: MediaViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mosaicShader by remember { mutableStateOf<BitmapShader?>(null) }

    val actions = remember { mutableStateListOf<DrawAction>() }
    var currentMode by remember { mutableStateOf(EditMode.DOODLE) }
    var currentColor by remember { mutableIntStateOf(android.graphics.Color.RED) }

    val saveSuccessMsg = stringResource(R.string.save_success)
    val saveFailedMsg = stringResource(R.string.save_failed)
    val loadFailedMsg = stringResource(R.string.load_failed)

    LaunchedEffect(asset.uri) {
        withContext(Dispatchers.IO) {
            val uri = asset.uri.toUri()
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val options = BitmapFactory.Options().apply { inMutable = true }
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    bitmap?.let {
                        originalBitmap = it
                        val blockSize = (it.width / 40f).coerceAtLeast(10f)
                        val smallW = (it.width / blockSize).toInt().coerceAtLeast(1)
                        val smallH = (it.height / blockSize).toInt().coerceAtLeast(1)
                        val small = Bitmap.createScaledBitmap(it, smallW, smallH, false)
                        val shader =
                            BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        val matrix = Matrix()
                        matrix.postScale(it.width.toFloat() / smallW, it.height.toFloat() / smallH)
                        shader.setLocalMatrix(matrix)
                        mosaicShader = shader
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, loadFailedMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    ImageEditContent(
        originalBitmap = originalBitmap,
        mosaicShader = mosaicShader,
        actions = actions,
        currentMode = currentMode,
        currentColor = currentColor,
        onModeChange = { currentMode = it },
        onColorChange = { currentColor = it },
        onUndo = {
            if (actions.isNotEmpty()) {
                actions.removeAt(actions.size - 1)
            }
        },
        onSave = {
            originalBitmap?.let { base ->
                val result = applyActions(base, actions, mosaicShader)
                saveEditedImage(context, result, viewModel, saveSuccessMsg, saveFailedMsg)
                onDismiss()
            }
        },
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageEditContent(
    originalBitmap: Bitmap?,
    mosaicShader: BitmapShader?,
    actions: MutableList<DrawAction>,
    currentMode: EditMode,
    currentColor: Int,
    onModeChange: (EditMode) -> Unit,
    onColorChange: (Int) -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var drawIteration by remember { mutableLongStateOf(0L) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.edit)) },
                navigationIcon = {
                    AppTopBarAction(
                        icon = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cancel),
                        onClick = onDismiss
                    )
                },
                actions = {
                    AppTopBarAction(
                        icon = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = stringResource(R.string.undo),
                        onClick = {
                            onUndo()
                            drawIteration++
                        },
                        enabled = actions.isNotEmpty()
                    )
                    AppTopBarAction(
                        icon = Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.save),
                        onClick = onSave,
                        enabled = originalBitmap != null
                    )
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                if (currentMode == EditMode.DOODLE) {
                    ColorSelector(selectedColor = currentColor) {
                        onColorChange(it)
                    }
                }
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        EditorChip(
                            selected = currentMode == EditMode.DOODLE,
                            onClick = { onModeChange(EditMode.DOODLE) },
                            label = { Text(stringResource(R.string.doodle)) },
                            leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null) }
                        )
                        EditorChip(
                            selected = currentMode == EditMode.MOSAIC,
                            onClick = { onModeChange(EditMode.MOSAIC) },
                            label = { Text(stringResource(R.string.mosaic)) },
                            leadingIcon = { Icon(Icons.Default.BlurOn, contentDescription = null) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            originalBitmap?.let { bitmap ->
                var viewWidth by remember { mutableFloatStateOf(0f) }
                var viewHeight by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            viewWidth = it.size.width.toFloat()
                            viewHeight = it.size.height.toFloat()
                        }
                        .pointerInput(currentMode, viewWidth, viewHeight) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val layout =
                                        calculateBitmapLayout(bitmap, viewWidth, viewHeight)
                                    val drawW = layout[0]
                                    val offsetX = layout[2]
                                    val offsetY = layout[3]
                                    val scale = bitmap.width / drawW
                                    val path = Path()
                                    path.moveTo(
                                        (offset.x - offsetX) * scale,
                                        (offset.y - offsetY) * scale
                                    )
                                    currentPath = path
                                    drawIteration++
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val layout =
                                        calculateBitmapLayout(bitmap, viewWidth, viewHeight)
                                    val drawW = layout[0]
                                    val offsetX = layout[2]
                                    val offsetY = layout[3]
                                    val scale = bitmap.width / drawW
                                    currentPath?.lineTo(
                                        (change.position.x - offsetX) * scale,
                                        (change.position.y - offsetY) * scale
                                    )
                                    drawIteration++
                                },
                                onDragEnd = {
                                    currentPath?.let {
                                        actions.add(
                                            DrawAction(
                                                it,
                                                currentMode,
                                                currentColor,
                                                if (currentMode == EditMode.MOSAIC) 120f else 60f
                                            )
                                        )
                                    }
                                    currentPath = null
                                    drawIteration++
                                }
                            )
                        }
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        @Suppress("UNUSED_VARIABLE")
                        val iteration = drawIteration
                        drawContext.canvas.nativeCanvas.apply {
                            val layout =
                                calculateBitmapLayout(bitmap, size.width, size.height)
                            val drawW = layout[0]
                            val offsetX = layout[2]
                            val offsetY = layout[3]
                            val scale = drawW / bitmap.width
                            val saveCount = save()
                            translate(offsetX, offsetY)
                            scale(scale, scale)
                            drawBitmap(bitmap, 0f, 0f, null)
                            actions.forEach {
                                drawActionOnCanvas(this, it, mosaicShader)
                            }
                            currentPath?.let {
                                drawActionOnCanvas(
                                    this,
                                    DrawAction(
                                        it,
                                        currentMode,
                                        currentColor,
                                        if (currentMode == EditMode.MOSAIC) 120f else 60f
                                    ),
                                    mosaicShader
                                )
                            }
                            restoreToCount(saveCount)
                        }
                    }
                }
            }
        }
    }
}

private fun calculateBitmapLayout(bitmap: Bitmap, viewW: Float, viewH: Float) : FloatArray {
    val bitmapRatio = bitmap.width.toFloat() / bitmap.height
    val viewRatio = viewW / viewH
    return if (bitmapRatio > viewRatio) {
        val h = viewW / bitmapRatio
        floatArrayOf(viewW, h, 0f, (viewH - h) / 2)
    } else {
        val w = viewH * bitmapRatio
        floatArrayOf(w, viewH, (viewW - w) / 2, 0f)
    }
}

@Composable
fun ColorSelector(selectedColor: Int, onColorSelected: (Int) -> Unit) {
    val colors = listOf(
        Color.Red, Color.Green, Color.Blue, Color.Yellow,
        Color.White, Color.Black, Color.Magenta, Color.Cyan
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(colors) { color ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = 2.dp,
                        color = if (selectedColor == color.toArgb()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(color.toArgb()) }
            )
        }
    }
}

private fun drawActionOnCanvas(canvas: Canvas, action: DrawAction, mosaicShader: BitmapShader?) {
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = action.strokeWidth
    }
    if (action.mode == EditMode.DOODLE) {
        paint.color = action.color
        paint.isAntiAlias = true
        canvas.drawPath(action.path, paint)
    } else if (action.mode == EditMode.MOSAIC && mosaicShader != null) {
        paint.shader = mosaicShader
        paint.isAntiAlias = false
        paint.isFilterBitmap = false
        canvas.drawPath(action.path, paint)
    }
}

private fun applyActions(
    base: Bitmap,
    actions: List<DrawAction>,
    mosaicShader: BitmapShader?
) : Bitmap {
    val result = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    actions.forEach {
        drawActionOnCanvas(canvas, it, mosaicShader)
    }
    return result
}

private fun saveEditedImage(
    context: Context,
    bitmap: Bitmap,
    viewModel: MediaViewModel,
    successMsg: String,
    failedMsg: String
) {
    val name =
        "EDIT_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
        }
    }
    val uri =
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        try {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            val asset = MediaAsset(
                uri = it.toString(),
                type = MediaType.PHOTO,
                captureDate = System.currentTimeMillis(),
                fileName = name
            )
            viewModel.insertMedia(asset)
            Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, failedMsg, Toast.LENGTH_SHORT).show()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ImageEditScreenPreview() {
    val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint().apply { color = android.graphics.Color.LTGRAY }
        canvas.drawRect(0f, 0f, 800f, 1200f, paint)
        paint.color = android.graphics.Color.BLUE
        canvas.drawCircle(400f, 600f, 200f, paint)
    }
    PoLangTheme {
        ImageEditContent(
            originalBitmap = bitmap,
            mosaicShader = null,
            actions = mutableListOf(),
            currentMode = EditMode.DOODLE,
            currentColor = android.graphics.Color.RED,
            onModeChange = {},
            onColorChange = {},
            onUndo = {},
            onSave = {},
            onDismiss = {}
        )
    }
}
