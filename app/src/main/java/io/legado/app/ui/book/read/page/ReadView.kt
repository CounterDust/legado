package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.activity
import io.legado.app.utils.invisible
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * 阅读页面核心视图，负责处理页面渲染、触摸交互和翻页动画
 * 实现FrameLayout作为基础容器，集成多种翻页效果和文本选择功能
 */
class ReadView(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs),
    DataSource, LayoutProgressListener {

    //region ==================== 视图组件声明 ====================
    // 获取关联Activity的回调接口，用于与Activity通信
    val callBack: CallBack get() = activity as CallBack
    // 页面工厂类，负责文本页面的生成和管理
    var pageFactory: TextPageFactory = TextPageFactory(this)
    /**
     * 翻页代理类，处理不同翻页动画效果,set 等同于 一个set方法
     * ```
     * public void setPageDelegate(PageDelegate newValue) {
     *     if (pageDelegate != null) {
     *         pageDelegate.onDestroy();
     *     }
     *     pageDelegate = null;
     *     pageDelegate = newValue;
     *     upContent();
     * }
     * ```
     */
    var pageDelegate: PageDelegate? = null
        private set(value) {
            field?.onDestroy()
            field = null
            field = value
            upContent()
        }
    // 标识当前翻页动画是否为滚动(用于区别滚动和左右)
    override var isScroll = false
    // 三个页面视图
    val prevPage by lazy { PageView(context) } // 上一页
    val curPage by lazy { PageView(context) }  // 当前页
    val nextPage by lazy { PageView(context) } // 下一页
    // 默认翻页动画速度（毫秒）
    val defaultAnimationSpeed = 300
    //endregion

    //region ==================== 触摸交互状态 ====================
    // 触摸按下状态标识
    private var pressDown = false
    // 触摸移动状态标识
    private var isMove = false
    // 触摸起始点坐标
    var startX: Float = 0f
    var startY: Float = 0f
    // 上一个触摸点坐标，用于计算移动距离
    var lastX: Float = 0f
    var lastY: Float = 0f
    // 当前触摸点坐标
    var touchX: Float = 0f
    var touchY: Float = 0f
    // 是否中止动画的标志
    var isAbortAnim = false
    // 长按相关状态
    private var longPressed = false     // 长按状态标识
    private val longPressTimeout = 600L // 长按触发时间阈值（毫秒）

    /**
     * 长按任务Runnable，延迟执行长按逻辑
     * Runnable 是一个接口，本质就是一段可被执行的代码任务
     */
    private val longPressRunnable = Runnable {
        longPressed = true // 设置长按状态
        onLongPress()      // 执行长按处理
    }
    // 文本选择状态
    var isTextSelected = false              // 是否处于文本选择模式
    private var pressOnTextSelected = false // 是否在已选文本上按下
    // 初始文本位置，用于文本选择范围计算
    private val initialTextPos = TextPos(0, 0, 0)
    //endregion

    //region ==================== 系统配置和几何计算 ====================
    // 系统触摸滑动阈值，用于判断是否触发滑动
    private val slopSquare by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    // 页面滑动阈值，可自定义配置
    private var pageSlopSquare: Int = slopSquare
    // 滑动阈值的平方，用于距离计算优化（避免开方运算）
    var pageSlopSquare2: Int = pageSlopSquare * pageSlopSquare

    // 9宫格区域定义，用于点击区域识别
    private val tlRect = RectF()  // 左上区域
    private val tcRect = RectF()  // 中上区域
    private val trRect = RectF()  // 右上区域
    private val mlRect = RectF()  // 左中区域
    private val mcRect = RectF()  // 中心区域
    private val mrRect = RectF()  // 右中区域
    private val blRect = RectF()  // 左下区域
    private val bcRect = RectF()  // 中下区域
    private val brRect = RectF()  // 右下区域

    // 文本边界迭代器，用于智能文本选择（按单词选择）
    private val boundary by lazy { BreakIterator.getWordInstance(Locale.getDefault()) }
    // 进度更新节流器，避免频繁更新进度（200ms间隔）
    private val upProgressThrottle = throttle(200) { post { upProgress() } }

    // 自动翻页控制器
    val autoPager = AutoPager(this)
    // 自动翻页状态检查
    val isAutoPage get() = autoPager.isRunning
    //endregion

    //region ==================== 初始化方法 ====================
    init {
        // 添加三个页面视图，注意添加顺序影响层级（后添加的在上层）
        addView(nextPage)
        addView(curPage)
        addView(prevPage)
        prevPage.invisible()
        nextPage.invisible()
        curPage.markAsMainView()
        curPage.markAsMainView()
        // 非设计模式下进行运行时初始化
        if (!isInEditMode) {
            upBg()
            setWillNotDraw(false)
            upPageAnim()
            upPageSlopSquare()
        }
    }

    /**
     * 设置9宫格区域坐标
     * 将屏幕划分为9个矩形区域用于点击识别
     */
    private fun setRect9x() {
        tlRect.set(0f, 0f, width * 0.33f, height * 0.33f)
        tcRect.set(width * 0.33f, 0f, width * 0.66f, height * 0.33f)
        trRect.set(width * 0.36f, 0f, width.toFloat(), height * 0.33f)
        mlRect.set(0f, height * 0.33f, width * 0.33f, height * 0.66f)
        mcRect.set(width * 0.33f, height * 0.33f, width * 0.66f, height * 0.66f)
        mrRect.set(width * 0.66f, height * 0.33f, width.toFloat(), height * 0.66f)
        blRect.set(0f, height * 0.66f, width * 0.33f, height.toFloat())
        bcRect.set(width * 0.33f, height * 0.66f, width * 0.66f, height.toFloat())
        brRect.set(width * 0.66f, height * 0.66f, width.toFloat(), height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setRect9x()
        prevPage.x = -w.toFloat()
        pageDelegate?.setViewSize(w, h)
        if (w > 0 && h > 0) {
            upBg()
            callBack.upSystemUiVisibility()
        }
    }
    //endregion

    //region ==================== 绘制和滚动处理 ====================

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pageDelegate?.onDraw(canvas)
        autoPager.onDraw(canvas)
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager.computeOffset()
    }
    //endregion

    //region ==================== 触摸事件处理 ====================

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * 触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = this.rootWindowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.mandatorySystemGestures()
            )
            val height = activity?.windowManager?.currentWindowMetrics?.bounds?.height()
            if (height != null) {
                if (event.y > height.minus(insets.bottom)
                    && event.action != MotionEvent.ACTION_UP
                    && event.action != MotionEvent.ACTION_CANCEL
                ) {
                    return true
                }
            }
        }

        //在多点触控时，事件不走ACTION_DOWN分支而产生的特殊事件处理
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            pageDelegate?.onTouch(event)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                callBack.screenOffTimerStart()
                if (isTextSelected) {
                    curPage.cancelSelect()
                    isTextSelected = false
                    pressOnTextSelected = true
                } else {
                    pressOnTextSelected = false
                }
                longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
                pressDown = true
                isMove = false
                pageDelegate?.onTouch(event)
                pageDelegate?.onDown()
                setStartPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressDown) return true
                val absX = abs(startX - event.x)
                val absY = abs(startY - event.y)
                if (!isMove) {
                    isMove = absX > slopSquare || absY > slopSquare
                }
                if (isMove) {
                    longPressed = false
                    removeCallbacks(longPressRunnable)
                    if (isTextSelected) {
                        selectText(event.x, event.y)
                    } else {
                        pageDelegate?.onTouch(event)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                callBack.screenOffTimerStart()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (!pageDelegate!!.isMoved && !isMove) {
                    if (!longPressed && !pressOnTextSelected) {
                        if (!curPage.onClick(startX, startY)) {
                            onSingleTapUp()
                        }
                        return true
                    }
                }
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                autoPager.resume()
            }
        }
        return true
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        if (isTextSelected) {
            curPage.cancelSelect(clearSearchResult)
            isTextSelected = false
        }
    }

    /**
     * 更新状态栏
     */
    fun upStatusBar() {
        curPage.upStatusBar()
        prevPage.upStatusBar()
        nextPage.upStatusBar()
    }

    /**
     * 保存开始位置
     */
    fun setStartPoint(x: Float, y: Float, invalidate: Boolean = true) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y

        if (invalidate) {
            invalidate()
        }
    }

    /**
     * 保存当前位置
     */
    fun setTouchPoint(x: Float, y: Float, invalidate: Boolean = true) {
        lastX = touchX
        lastY = touchY
        touchX = x
        touchY = y
        if (invalidate) {
            invalidate()
        }
        pageDelegate?.onScroll()
        val offset = touchY - lastY
        touchY -= offset - offset.toInt()
    }

    /**
     * 长按选择
     */
    private fun onLongPress() {
        kotlin.runCatching {
            curPage.longPress(startX, startY) { textPos: TextPos ->
                isTextSelected = true
                pressOnTextSelected = true
                initialTextPos.upData(textPos)
                val startPos = textPos.copy()
                val endPos = textPos.copy()
                val page = curPage.relativePage(textPos.relativePagePos)
                val stringBuilder = StringBuilder()
                var cIndex = textPos.columnIndex
                var lineStart = textPos.lineIndex
                var lineEnd = textPos.lineIndex
                for (index in textPos.lineIndex - 1 downTo 0) {
                    val textLine = page.getLine(index)
                    if (textLine.isParagraphEnd) {
                        break
                    } else {
                        stringBuilder.insert(0, textLine.text)
                        lineStart -= 1
                        cIndex += textLine.charSize
                    }
                }
                for (index in textPos.lineIndex until page.lineSize) {
                    val textLine = page.getLine(index)
                    stringBuilder.append(textLine.text)
                    lineEnd += 1
                    if (textLine.isParagraphEnd) {
                        break
                    }
                }
                var start: Int
                var end: Int
                boundary.setText(stringBuilder.toString())
                start = boundary.first()
                end = boundary.next()
                while (end != BreakIterator.DONE) {
                    if (cIndex in start until end) {
                        break
                    }
                    start = end
                    end = boundary.next()
                }
                kotlin.run {
                    var ci = 0
                    for (index in lineStart..lineEnd) {
                        val textLine = page.getLine(index)
                        for (j in textLine.columns.indices) {
                            if (ci == start) {
                                startPos.lineIndex = index
                                startPos.columnIndex = j
                            } else if (ci == end - 1) {
                                endPos.lineIndex = index
                                endPos.columnIndex = j
                                return@run
                            }
                            val column = textLine.getColumn(j)
                            if (column is TextColumn) {
                                ci += column.charData.length
                            } else {
                                ci++
                            }
                        }
                    }
                }
                curPage.selectStartMoveIndex(startPos)
                curPage.selectEndMoveIndex(endPos)
            }
        }
    }

    /**
     * 单击
     */
    private fun onSingleTapUp() {
        when {
            isTextSelected -> Unit
            mcRect.contains(startX, startY) -> if (!isAbortAnim) {
                click(AppConfig.clickActionMC)
            }

            bcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBC)
            }

            blRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBL)
            }

            brRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBR)
            }

            mlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionML)
            }

            mrRect.contains(startX, startY) -> {
                click(AppConfig.clickActionMR)
            }

            tlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTL)
            }

            tcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTC)
            }

            trRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTR)
            }
        }
    }

    /**
     * 点击
     */
    private fun click(action: Int) {
        when (action) {
            0 -> {
                pageDelegate?.dismissSnackBar()
                callBack.showActionMenu()
            }

            1 -> pageDelegate?.nextPageByAnim(defaultAnimationSpeed)
            2 -> pageDelegate?.prevPageByAnim(defaultAnimationSpeed)
            3 -> ReadBook.moveToNextChapter(true)
            4 -> ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            5 -> ReadAloud.prevParagraph(context)
            6 -> ReadAloud.nextParagraph(context)
            7 -> callBack.addBookmark()
            8 -> activity?.showDialogFragment(ContentEditDialog())
            9 -> callBack.changeReplaceRuleState()
            10 -> callBack.openChapterList()
            11 -> callBack.openSearchActivity(null)
            12 -> ReadBook.syncProgress(
                { progress -> callBack.sureNewProgress(progress) },
                { context.longToastOnUi(context.getString(R.string.upload_book_success)) },
                { context.longToastOnUi(context.getString(R.string.sync_book_progress_success)) })

            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(context)
                } else {
                    ReadAloud.resume(context)
                }
            }
        }
    }

    /**
     * 选择文本
     */
    private fun selectText(x: Float, y: Float) {
        curPage.selectText(x, y) { textPos ->
            val compare = initialTextPos.compare(textPos)
            when {
                compare > 0 -> {
                    curPage.selectStartMoveIndex(textPos)
                    curPage.selectEndMoveIndex(
                        initialTextPos.relativePagePos,
                        initialTextPos.lineIndex,
                        initialTextPos.columnIndex - 1
                    )
                }

                else -> {
                    curPage.selectStartMoveIndex(initialTextPos)
                    curPage.selectEndMoveIndex(textPos)
                }
            }
        }
    }

    /**
     * 销毁事件
     */
    fun onDestroy() {
        pageDelegate?.onDestroy()
        curPage.cancelSelect()
        invalidateTextPage()
    }

    /**
     * 翻页动画完成后事件
     * @param direction 翻页方向
     */
    fun fillPage(direction: PageDirection): Boolean {
        return when (direction) {
            PageDirection.PREV -> {
                pageFactory.moveToPrev(true)
            }

            PageDirection.NEXT -> {
                pageFactory.moveToNext(true)
            }

            else -> false
        }
    }

    /**
     * 更新翻页动画
     */
    fun upPageAnim(upRecorder: Boolean = false) {
        isScroll = ReadBook.pageAnim() == 3
        ChapterProvider.upLayout()
        // Kotlin的when()等于Java的switch()
        // 类型检查避免重复
        when (ReadBook.pageAnim()) {
            PageAnim.coverPageAnim -> if (pageDelegate !is CoverPageDelegate) {
                pageDelegate = CoverPageDelegate(this)
            }

            PageAnim.slidePageAnim -> if (pageDelegate !is SlidePageDelegate) {
                pageDelegate = SlidePageDelegate(this)
            }

            PageAnim.simulationPageAnim -> if (pageDelegate !is SimulationPageDelegate) {
                pageDelegate = SimulationPageDelegate(this)
            }

            PageAnim.scrollPageAnim -> if (pageDelegate !is ScrollPageDelegate) {
                pageDelegate = ScrollPageDelegate(this)
            }

            else -> if (pageDelegate !is NoAnimPageDelegate) {
                pageDelegate = NoAnimPageDelegate(this)
            }
        }
        // as? 安全类型转换操作符
        // 成功返回转换后的对象，失败返回null
        (pageDelegate as? ScrollPageDelegate)?.noAnim = AppConfig.noAnimScrollPage
        if (upRecorder) {
            (pageDelegate as? HorizontalPageDelegate)?.upRecorder()
            autoPager.upRecorder()
        }
        pageDelegate?.setViewSize(width, height)
        if (isScroll) {
            curPage.setAutoPager(autoPager)
        } else {
            curPage.setAutoPager(null)
        }
        curPage.setIsScroll(isScroll)
    }

    /**
     * 内容更新方法，实现多模式内容调度
     * @param relativePosition 相对位置（-1=上一页，0=当前页，1=下一页）
     * @param resetPageOffset 是否重置滚动偏移量（仅滚动模式有效）
     */
    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        // 无障碍功能
        post {
            curPage.setContentDescription(pageFactory.curPage.text)
        }
        // 判断是否为滚动模式且非自动翻页状态
        if (isScroll && !isAutoPage) {
            if (relativePosition == 0) {
                curPage.setContent(pageFactory.curPage, resetPageOffset)
            } else {
                curPage.invalidateContentView()
            }
        } else {
            when (relativePosition) {
                -1 -> prevPage.setContent(pageFactory.prevPage)
                1 -> nextPage.setContent(pageFactory.nextPage)
                else -> { //按需更新，章节切换、首次加载
                    curPage.setContent(pageFactory.curPage, resetPageOffset)
                    nextPage.setContent(pageFactory.nextPage)
                    prevPage.setContent(pageFactory.prevPage)
                }
            }
        }
        callBack.screenOffTimerStart()
    }

    private fun upProgress() {
        curPage.setProgress(pageFactory.curPage)
    }

    /**
     * 更新滑动距离
     */
    fun upPageSlopSquare() {
        val pageTouchSlop = AppConfig.pageTouchSlop
        this.pageSlopSquare = if (pageTouchSlop == 0) slopSquare else pageTouchSlop
        pageSlopSquare2 = this.pageSlopSquare * this.pageSlopSquare
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        ChapterProvider.upStyle()
        curPage.upStyle()
        prevPage.upStyle()
        nextPage.upStyle()
    }

    /**
     * 更新背景
     */
    fun upBg() {
        ReadBookConfig.upBg(width, height)
        curPage.upBg()
        prevPage.upBg()
        nextPage.upBg()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        curPage.upBgAlpha()
        prevPage.upBgAlpha()
        nextPage.upBgAlpha()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        curPage.upTime()
        prevPage.upTime()
        nextPage.upTime()
    }

    /**
     * 更新电量信息
     */
    fun upBattery(battery: Int) {
        curPage.upBattery(battery)
        prevPage.upBattery(battery)
        nextPage.upBattery(battery)
    }

    /**
     * 从选择位置开始朗读
     */
    suspend fun aloudStartSelect() {
        val selectStartPos = curPage.selectStartPos
        var pagePos = selectStartPos.relativePagePos
        val line = selectStartPos.lineIndex
        val column = selectStartPos.columnIndex
        while (pagePos > 0) {
            if (!ReadBook.moveToNextPage()) {
                ReadBook.moveToNextChapterAwait(false)
            }
            pagePos--
        }
        val startPos = curPage.textPage.getPosByLineColumn(line, column)
        ReadBook.readAloud(startPos = startPos)
    }

    /**
     * @return 选择的文本
     */
    fun getSelectText(): String {
        return curPage.selectedText
    }

    fun getCurVisiblePage(): TextPage {
        return curPage.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return curPage.getReadAloudPos()
    }

    fun invalidateTextPage() {
        if (!AppConfig.optimizeRender) {
            return
        }
        pageFactory.run {
            prevPage.invalidateAll()
            curPage.invalidateAll()
            nextPage.invalidateAll()
            nextPlusPage.invalidateAll()
        }
    }

    fun onScrollAnimStart() {
        autoPager.pause()
    }

    fun onScrollAnimStop() {
        autoPager.resume()
    }

    fun onPageChange() {
        autoPager.reset()
        submitRenderTask()
    }

    fun submitRenderTask() {
        if (!AppConfig.optimizeRender) {
            return
        }
        curPage.submitRenderTask()
    }

    fun isLongScreenShot(): Boolean {
        return curPage.isLongScreenShot()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upProgressThrottle.invoke()
    }

    override val currentChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(0) else null
        }

    override val nextChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(1) else null
        }

    override val prevChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(-1) else null
        }

    override fun hasNextChapter(): Boolean {
        return ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
    }

    override fun hasPrevChapter(): Boolean {
        return ReadBook.durChapterIndex > 0
    }

    interface CallBack {
        val isInitFinish: Boolean
        fun showActionMenu()
        fun screenOffTimerStart()
        fun showTextActionMenu()
        fun autoPageStop()
        fun openChapterList()
        fun addBookmark()
        fun changeReplaceRuleState()
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
        fun sureNewProgress(progress: BookProgress)
    }
}
