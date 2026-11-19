package com.mamba.picme.features.gallery.swipe

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.mamba.picme.R
import com.mamba.picme.core.common.FormatBytes
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * 清理战报分享卡（P0-2 获客钩子）：1080×1350 PNG——深色底 + 品牌渐变「释放空间」大数字
 * + 保留/删除/跳过三桶计数 + 日期 + 应用署名；写出 cacheDir/share/cleanup_report.png
 * 经既有 FileProvider（${applicationId}.ota.provider，share/ 路径）分享。
 */
object CleanupReportCard {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    // 版式坐标/字号（px，1080×1350 基准）
    private const val MARGIN = 96f
    private const val GRADIENT_BAR_HEIGHT = 12f
    private const val APP_NAME_BASELINE = 150f
    private const val APP_NAME_TEXT_SIZE = 34f
    private const val APP_NAME_LETTER_SPACING = 0.2f
    private const val TITLE_BASELINE = 430f
    private const val TITLE_TEXT_SIZE = 56f
    private const val FREED_LABEL_BASELINE = 560f
    private const val FREED_LABEL_TEXT_SIZE = 44f
    private const val FREED_NUMBER_TOP = 620f
    private const val FREED_NUMBER_BASELINE = 780f
    private const val FREED_NUMBER_TEXT_SIZE = 190f
    private const val STAT_VALUE_BASELINE = 1030f
    private const val STAT_VALUE_TEXT_SIZE = 72f
    private const val STAT_LABEL_BASELINE = 1090f
    private const val STAT_LABEL_TEXT_SIZE = 34f
    private const val CAPTION_BASELINE_OFFSET = 50f

    private val BG_COLOR = 0xFF121417.toInt()
    private val TEXT_COLOR_PRIMARY = 0xFFFFFFFF.toInt()
    private val TEXT_COLOR_SECONDARY = 0xFF9AA0A6.toInt()

    fun render(
        context: Context,
        kept: Int,
        deleted: Int,
        skipped: Int,
        freedBytes: Long,
        dateMs: Long = System.currentTimeMillis(),
    ): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 深色底 + 顶部品牌渐变条
        canvas.drawColor(BG_COLOR)
        paint.shader = LinearGradient(
            0f, 0f, WIDTH.toFloat(), 0f,
            ChatBubbleTokens.brandGradientStart.toArgb(),
            ChatBubbleTokens.brandGradientEnd.toArgb(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(RectF(0f, 0f, WIDTH.toFloat(), GRADIENT_BAR_HEIGHT), paint)
        paint.shader = null

        // 顶部应用名
        paint.color = TEXT_COLOR_SECONDARY
        paint.textSize = APP_NAME_TEXT_SIZE
        paint.letterSpacing = APP_NAME_LETTER_SPACING
        canvas.drawText(
            context.getString(R.string.app_name).uppercase(Locale.ROOT),
            MARGIN,
            APP_NAME_BASELINE,
            paint
        )
        paint.letterSpacing = 0f

        // 标题
        paint.color = TEXT_COLOR_PRIMARY
        paint.textSize = TITLE_TEXT_SIZE
        paint.isFakeBoldText = true
        canvas.drawText(context.getString(R.string.report_title), MARGIN, TITLE_BASELINE, paint)
        paint.isFakeBoldText = false

        // 中部：释放空间标签 + 渐变大数字
        paint.color = TEXT_COLOR_SECONDARY
        paint.textSize = FREED_LABEL_TEXT_SIZE
        canvas.drawText(context.getString(R.string.report_freed_label), MARGIN, FREED_LABEL_BASELINE, paint)

        val freedText = FormatBytes.format(freedBytes)
        paint.textSize = FREED_NUMBER_TEXT_SIZE
        paint.isFakeBoldText = true
        paint.shader = LinearGradient(
            MARGIN, FREED_NUMBER_TOP, MARGIN + paint.measureText(freedText), FREED_NUMBER_TOP,
            ChatBubbleTokens.brandGradientStart.toArgb(),
            ChatBubbleTokens.brandGradientEnd.toArgb(),
            Shader.TileMode.CLAMP
        )
        canvas.drawText(freedText, MARGIN, FREED_NUMBER_BASELINE, paint)
        paint.shader = null
        paint.isFakeBoldText = false

        // 三桶计数（保留 / 删除 / 跳过）
        val stats = listOf(
            kept to context.getString(R.string.swipe_done_kept),
            deleted to context.getString(R.string.swipe_done_deleted),
            skipped to context.getString(R.string.swipe_done_skipped),
        )
        val columnWidth = (WIDTH - MARGIN * 2) / stats.size
        stats.forEachIndexed { index, (value, label) ->
            val x = MARGIN + columnWidth * index
            paint.color = TEXT_COLOR_PRIMARY
            paint.textSize = STAT_VALUE_TEXT_SIZE
            paint.isFakeBoldText = true
            canvas.drawText(value.toString(), x, STAT_VALUE_BASELINE, paint)
            paint.isFakeBoldText = false
            paint.color = TEXT_COLOR_SECONDARY
            paint.textSize = STAT_LABEL_TEXT_SIZE
            canvas.drawText(label, x, STAT_LABEL_BASELINE, paint)
        }

        // 底部日期 + 署名
        val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dateMs))
        canvas.drawText(dateText, MARGIN, HEIGHT - MARGIN, paint)
        canvas.drawText(
            context.getString(R.string.report_app_caption),
            MARGIN,
            HEIGHT - MARGIN + CAPTION_BASELINE_OFFSET,
            paint
        )

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "cleanup_report.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.ota.provider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.report_app_caption))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
