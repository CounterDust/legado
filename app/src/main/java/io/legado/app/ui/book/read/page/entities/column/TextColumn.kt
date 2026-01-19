package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 文字列
 */
@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    val charData: String,
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    // 当用户选择文字时，这个属性会变为true，然后文字背景会显示选中颜色
    var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }

    // 当用户搜索文字时，匹配的文字会高亮显示。
    var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        // 根据情况选择画笔
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        // 根据情况选择颜色
        val textColor = if (textLine.isReadAloud || isSearchResult) {
            ThemeStore.accentColor
        } else {
            ReadBookConfig.textColor
        }
        // 颜色发生变化才设置
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        // 计算绘制位置
        val y = textLine.lineBase - textLine.lineTop
        // 处理字间距
        // 兼容性设计
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            canvas.drawText(charData, start + letterSpacingHalf, y, textPaint)
        } else {
            canvas.drawText(charData, start, y, textPaint)
        }
        // 绘制选中背景
        if (selected) {
            canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
        }
    }

}
