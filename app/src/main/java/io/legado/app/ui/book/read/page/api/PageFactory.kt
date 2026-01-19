package io.legado.app.ui.book.read.page.api

/**
 * 抽象类就像是一个"半成品模板" - 它定义了结构和规范，但不提供完整实现
 *
 * 不能直接创建对象：val factory = PageFactory()❌ 会报错
 *
 * 必须被子类继承：class TextPageFactory : PageFactory<TextPage>()✅
 *
 * 包含抽象方法：子类必须实现这些方法
 *
 * 可以包含具体方法：子类可以直接使用
 */
abstract class PageFactory<DATA>(protected val dataSource: DataSource) {

    abstract fun moveToFirst()

    abstract fun moveToLast()

    abstract fun moveToNext(upContent: Boolean): Boolean

    abstract fun moveToPrev(upContent: Boolean): Boolean

    abstract val nextPage: DATA

    abstract val prevPage: DATA

    abstract val curPage: DATA

    abstract val nextPlusPage: DATA

    abstract fun hasNext(): Boolean

    abstract fun hasPrev(): Boolean

    abstract fun hasNextPlus(): Boolean
}