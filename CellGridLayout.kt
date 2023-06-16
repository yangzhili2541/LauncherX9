package com.iflytek.autofly.x9launcher.custom

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.*
import android.util.AttributeSet
import android.util.Log
import android.view.DragEvent
import android.view.View
import android.view.View.OnDragListener
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.GridLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.animation.doOnEnd
import androidx.core.view.marginEnd
import androidx.core.view.marginStart
import androidx.core.view.setMargins
import androidx.lifecycle.lifecycleScope
import com.iflytek.autofly.common.utilkt.*
import com.iflytek.autofly.x9launcher.R
import com.iflytek.autofly.x9launcher.X9LauncherConfig
import com.iflytek.autofly.x9launcher.database.LauncherDataBase
import com.iflytek.autofly.x9launcher.database.entity.WidgetEntity
import com.iflytek.autofly.x9launcher.ui.MainActivity
import com.iflytek.autofly.x9launcher.widget.*
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.*
import skin.support.widget.SkinCompatImageView
import java.lang.ref.WeakReference
import java.util.*
import kotlin.math.roundToInt


@RequiresApi(Build.VERSION_CODES.N)
@SuppressLint("UseCompatLoadingForDrawables")
class CellGridLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : GridLayout(context, attrs), OnDragListener {

    private var columnNum: Int = 12
    private var rowNum: Int = 4
    private var defaultWidth = px2dp(112)
    private var defaultHeight = px2dp(112)
    private val CLICK_DELAY_TIME = 1000
    private var lastLongClickTime = 0L
    private var firstClickTime = 0L
    private var isCanMove = true

    //Widget数据库
    private val mLauncherDao by lazy { LauncherDataBase.getInstance().getLauncherDao() }

    //存储基线
    private val nullCells = mutableListOf<Cell>()

    //是否可以拖拽
    private var isCanDrag = false

    //是否是底部拖动可放置
    private var isCanPlacement = false

    //是否是底部拖动可放置
    private var isCanMoves = true

    //当前正在拖拽的View
    private lateinit var dragView: Any

    //当前拖拽的view拖拽前占用的坐标
    private var dragPos = mutableListOf<String>()

    //WidgetRoot虚应
    private lateinit var tempWidgetRootPreview: Drawable

    //不可使用的坐标集合
    private val noPositions = arrayListOf<String>()

    //当前存在屏幕上的Widget以及坐标
    private val widgetList = arrayListOf<Pair<List<String>, IWidgetRootView>>()

//    private val widgetList1 = arrayListOf<Pair<List<String>, IWidgetRootView>>()

    //当前拖动的Widget的占用格数
    private var currentXSpan = -1
    private var currentYSpan = -1

    private var isOutView = false

    //存储删除按钮
    private var deleteView = mutableListOf<DeleteView>()

    //画笔类
    private lateinit var paint: Paint

    //回调给主页设置Widget
    private lateinit var onSetWidgetPos: (absX: Int, absY: Int, appWidgetInfo: AppWidgetProviderInfo, appWidgetId: Int, previewX: Float, previewY: Float, String) -> Unit

    //删除事件回调
    private lateinit var onDeleteWidgetID: (Int, AppWidgetProviderInfo) -> Unit

    //正常状态下widget长按回调
    private lateinit var joinEditModeListener: () -> Unit

    fun addJoinEditModeListener(e: () -> Unit) {
        this.joinEditModeListener = e
    }

    fun addDeleteWidgetIdListener(e: (Int, AppWidgetProviderInfo) -> Unit) {
        this.onDeleteWidgetID = e
    }

    fun onSetWidgetPos(e: (absX: Int, absY: Int, appWidgetInfo: AppWidgetProviderInfo, appWidgetId: Int, previewX: Float, previewY: Float, String) -> Unit) {
        this.onSetWidgetPos = e
    }

    private lateinit var activity: MainActivity

    fun setActivity(activity: MainActivity) {
        this.activity = activity
        LiveEventBus.get("endDrag").observe(activity){
            Log.i(TAG, "延迟事件，关闭拖拽状态")
            cancelDragAndDrop()
            endDrag()
            isCanPlacement = false
        }

        LiveEventBus.get("isPlacement").observe(activity) {
            Log.i(TAG, "放置状态更新")
            when (it) {
                MOVE_CANCEL -> isCanPlacement = false
                MOVE_PLACEMENT -> isCanPlacement = true
            }
        }
        LiveEventBus.get("isOutView").observe(activity) {
            isOutView = true
        }
    }

    fun getWidgetList(): ArrayList<Pair<List<String>, IWidgetRootView>> {
        return widgetList
    }

    //PAGE创建，上下回调
    private lateinit var onLocalPagerListener: (PageOperationType) -> Unit

    fun setLocalPagerListener(e: (PageOperationType) -> Unit) {
        this.onLocalPagerListener = e
    }

    //设置当前页面页码
    private var currentPager = -1
    private var maxPager = -1//目前最大页码

    //设置页码
    fun setPager(currentPager: Int, maxPager: Int) {
        this.currentPager = currentPager
        this.maxPager = maxPager
    }

    fun initCell() {
        val cellBg = context.resources.getDrawable(R.drawable.cell)
        this.columnCount = columnNum
        this.rowCount = rowNum
        paint = Paint()
        paint.color = Color.RED
        for (x in 0 until rowCount) {
            for (y in 0 until columnNum) {
                val nullView = Cell(context)
                nullView.posX = x
                nullView.posY = y
                nullView.tag = "[$x,$y]"
                nullView.background = cellBg
                nullView.alpha = 0f
                val layoutParams = LayoutParams(spec(x), spec(y))
                layoutParams.width = defaultWidth
                layoutParams.height = defaultHeight
                layoutParams.setMargins(dp2px(20))
                nullCells.add(nullView)
                this.addView(nullView, layoutParams)
            }
        }
        setOnDragListener(this)
    }

    private fun detectionIsSetWidget(): Boolean {
        val widgetSize = currentXSpan * currentYSpan
        return ((nullCells.size - noPositions.size) - widgetSize) < 0
    }

    init {}

    //重置cellLayout内部所有属性
    fun resetCellLayout() {
        currentXSpan = -1
        currentYSpan = -1
        noPositions.clear()
        widgetList.clear()
        deleteView.clear()
        removeAllViews()
        initCell()
    }

    //测量一个Widget占用几格
    private fun measureWidget(event: DragEvent) {
        var rowSpan = 0
        var columnSpan = 0
        var widgetName = ""
        if (event.localState is WidgetPreviewInfo) {
            dragView = event.localState as WidgetPreviewInfo
            widgetName = (dragView as WidgetPreviewInfo).widgetPreviewInfo.loadLabel(context.packageManager)
            if (widgetName.contains("com.iflytek.autofly")) {
                rowSpan = (dragView as WidgetPreviewInfo).widgetPreviewInfo.minResizeHeight / 112
                columnSpan = (dragView as WidgetPreviewInfo).widgetPreviewInfo.minResizeWidth / 112
            } else {
                val otherWidget = isOtherWidget((dragView as WidgetPreviewInfo).widgetPreviewInfo)
                rowSpan = otherWidget[0]
                columnSpan = otherWidget[1]
            }
        } else if (event.localState is IWidgetRootView) {
            //这是已经存在的Widget
            dragView = event.localState as IWidgetRootView
            tempWidgetRootPreview = (dragView as IWidgetRootView).view2Bitmap().toDrawable(context)
            widgetName = (dragView as IWidgetRootView).appWidgetInfo.loadLabel(context.packageManager)
            if (widgetName.contains("com.iflytek.autofly")) {
                rowSpan = (dragView as IWidgetRootView).appWidgetInfo.minResizeHeight / 112
                columnSpan = (dragView as IWidgetRootView).appWidgetInfo.minResizeWidth / 112
            } else {
                val otherWidget = isOtherWidget((dragView as IWidgetRootView).appWidgetInfo)
                rowSpan = otherWidget[0]
                columnSpan = otherWidget[1]
            }
        }
        currentXSpan = rowSpan
        currentYSpan = columnSpan
    }

    private fun addDeleteView(x: Int, y: Int, widget: IWidgetRootView) {
        val tempDeleteView = deleteView.find { it.widget == widget }
        var imageView: ImageView? = null
        val layoutParams = LayoutParams()
        if (tempDeleteView == null) {
            //该View没有删除按钮
            imageView = ImageView(context)
            layoutParams.width = 51
            layoutParams.height = 51
            layoutParams.rowSpec = spec(x)
            layoutParams.columnSpec = spec(y)
            layoutParams.setMargins(5)
            imageView.loadImage(context, context.resources.getDrawable(R.drawable.delete))
            addView(imageView, layoutParams)
            deleteView.add(DeleteView(imageView, widget))
        } else {
            //该View有删除按钮
            removeView(tempDeleteView.deleteView)
            layoutParams.rowSpec = spec(x)
            layoutParams.columnSpec = spec(y)
            addView(tempDeleteView.deleteView, layoutParams)
            tempDeleteView.deleteView?.visible()
        }
        if (isCanDrag) imageView?.alpha = 1f else imageView?.alpha = 0f

        imageView?.setOnClickListener { clickView ->
            val pair = widgetList.find { widget == it.second }
            val noPosList = pair?.first
            if (noPosList != null) {
                cellIsVisible(noPosList, false)
            }
            if (noPosList?.isNotEmpty() == true) {
                val array = IntArray(2)
                widget.getLocationOnScreen(array)
                val targetX = if (widget.previewX.roundToInt() == 0) 0F else -(array[0].toFloat() - widget.previewX)
                val targetY = if (widget.previewY.roundToInt() == 0) 0F else -(array[1].toFloat() - widget.previewY)
                "targetX = $targetX , targetY = $targetY".loge("widgetTarget")
                AnimatorUtil.deleteAnim(widget, clickView, targetX, targetY) {
                    deleteWidget(clickView, noPosList)
                }
            }
        }
    }

    private fun deleteWidget(clickView: View?, noPosList: List<String>) {
        var tempView = DeleteView(null, null)
        deleteView.forEach {
            if (it.deleteView == clickView) {
                tempView = it
                removeView(it.widget)
                removeView(it.deleteView)
            }
        }
        val pair = widgetList.find { tempView.widget == it.second }
        noPosList.forEach { pos ->
            if (noPositions.contains(pos)) {
                noPositions.remove(pos)
            }
        }
        try {
            deleteView.remove(tempView)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        onDeleteWidgetID(pair?.second?.appWidgetId!!, pair.second.appWidgetInfo)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val widgetEntity = mLauncherDao.paramsFindWidgetId(pair.second.appWidgetId)
            if (widgetEntity != null) {
                mLauncherDao.delete(widgetEntity)
                widgetList.remove(pair)
            }
        }
    }

    /**
     * 检测是否第三方Widget
     */
    private fun isOtherWidget(widgetInfo: AppWidgetProviderInfo): IntArray {
        var rowSpan = 0
        var columnSpan = 0
        val value = IntArray(2)
        val widgetName = widgetInfo.loadLabel(context.packageManager)
        if (widgetName.contains("com.iflytek.autofly")) {
            rowSpan = widgetInfo.minResizeHeight / 112
            columnSpan = widgetInfo.minResizeWidth / 112
        } else {
            val systemCell = context.getSystemCell(widgetInfo.minResizeWidth, widgetInfo.minResizeHeight)
            rowSpan = (systemCell[0] * 112) / 112
            columnSpan = (systemCell[1] * 112) / 112
        }
        //处理高德地图widget
        if (widgetInfo.provider.packageName == "com.autonavi.amapauto") {
            if (widgetInfo.label == "高德地图(2x3)") {
                value[0] = 4
                value[1] = 3
            } else if (widgetInfo.label == "高德地图(4x3)") {
                value[0] = 4
                value[1] = 6
            }
            return value
        }
        value[0] = rowSpan
        value[1] = columnSpan
        return value
    }

    //存储的临时位置
    private var tempWidgetX = -1
    private var tempWidgetY = -1
    private var isfinish = false

    fun addWidget(x: Int, y: Int, widget: IWidgetRootView) {
        if (x == -1 || y == -1) return
        val ints = isOtherWidget(widget.appWidgetInfo)
        val rowSpan = ints[0]
        val columnSpan = ints[1]
        val layoutParams = LayoutParams()
        layoutParams.width = 0
        layoutParams.height = 0
        //计算边界
        val resultX = if (x + rowSpan > rowCount) (rowCount - rowSpan) else x
        val resultY = if (y + columnSpan > columnCount) (columnCount - columnSpan) else y
        layoutParams.rowSpec = spec(resultX, rowSpan, 1f)
        layoutParams.columnSpec = spec(resultY, columnSpan, 1f)
//        layoutParams.setMargins(10)
        //调整widget坐标偏移量
        layoutParams.marginStart = 10
        layoutParams.marginEnd = 10
        //调整widget坐标偏移量
        layoutParams.topMargin = 16
        //添加Widget
        addView(widget, layoutParams)
        requestLayout()
        //配置widget的一些东西
        setWidgetConfig(widget, resultX, resultY, rowSpan, columnSpan)
        //更新Widget在数据库的位置
        updateWidget(x, y, widget, rowSpan, columnSpan)
        //添加删除按钮
        addDeleteView(resultX, resultY, widget)
    }

    private fun updateWidget(x: Int, y: Int, widget: IWidgetRootView, rowSpan: Int, columnSpan: Int) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            var widgetEntity = mLauncherDao.paramsFindWidgetId(widget.appWidgetId)
            if (widgetEntity == null) {
                //在此处去进行赋值
                widgetEntity = WidgetEntity(
                    widgetId = widget.appWidgetId,
                    packageName = widget.appWidgetInfo.provider.packageName,
                    className = widget.appWidgetInfo.provider.className,
                    startX = x,
                    startY = y,
                    xSpan = rowSpan,
                    ySpan = columnSpan,
                    userId = X9LauncherConfig.localUserId!!,
                    previewX = widget.previewX,
                    previewY = widget.previewY,
                    widgetSize = widget.widgetSize.toString(),
                    pageNum = currentPager
                )
                mLauncherDao.insert(widgetEntity)
                widgetEntity.loge("DataBaseWidget insert")
            } else {
                widgetEntity.widgetId = widget.appWidgetId
                widgetEntity.packageName = widget.appWidgetInfo.provider.packageName
                widgetEntity.className = widget.appWidgetInfo.provider.className
                widgetEntity.startX = x
                widgetEntity.startY = y
                widgetEntity.xSpan = rowSpan
                widgetEntity.ySpan = columnSpan
                widgetEntity.previewX = widget.previewX
                widgetEntity.previewY = widget.previewY
                widgetEntity.widgetSize = widget.widgetSize.toString()
                widgetEntity.pageNum = currentPager
                mLauncherDao.update(widgetEntity)
                widgetEntity.loge("DataBaseWidget update")
            }
        }
    }

    private fun setWidgetConfig(widget: IWidgetRootView, resultX: Int, resultY: Int, rowSpan: Int, columnSpan: Int) {
        val view = tempWidget?.get()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1.0f, 1.1f),
                ObjectAnimator.ofFloat(view, "scaleY", 1.0f, 1.1f),
                ObjectAnimator.ofFloat(view, "alpha", 1.0f, 0f),
            )
            duration = 600
            interpolator = AccelerateInterpolator()
            start()
            doOnEnd {
                //移除虚影
                clearTempWidget()
            }
        }
        //设置widget位置信息
        widget.startX = resultX
        widget.startY = resultY
        widget.cs = columnSpan
        widget.rs = rowSpan
        //设置当前Widget是否可点击状态
        widget.isOnDrag = !isCanDrag
        //长按widget进入编辑模式
        widget.addJoinEditModeListener {
            joinEditModeListener()
        }
        //创建长按事件
        widget.setOnLongClickListener { parent ->
            firstClickTime = System.currentTimeMillis()
            //防重复点击
            if (System.currentTimeMillis() - lastLongClickTime < CLICK_DELAY_TIME) {
                return@setOnLongClickListener false
            }
            lastLongClickTime = System.currentTimeMillis()
            if (!isCanDrag) return@setOnLongClickListener false //不可滑动时长按事件直接Return
            //创建Widget拖拽影像
            //查找当前集合内的Widget
            val pair = widgetList.find { parent == it.second }
            //上一次坐标位置
            var lastPos = ""
            //显示占用线框
            if (pair?.first?.isNotEmpty() == true) {
                //记录拖拽前的坐标
                dragPos = pair.first as MutableList<String>
                cellIsVisible(pair.first, false)
                //Widget抬起移除之前他所占用的位置
                pair.first.forEachIndexed { index, s ->
                    if (index == 0) lastPos = s
                    if (noPositions.contains(s)) {
                        noPositions.remove(s)
                    }
                }
            }
            widgetList.remove(pair)
            //删除图标逻辑
            val deleteImg = deleteView.find { it.widget == parent }
            deleteImg?.deleteView?.gone()
            if (lastPos.isNotEmpty()) {
                val list = lastPos.replace("[", "").replace("]", "").split(",")
                list.toString().loge("lastPos")
                //移除现在的WidgetID
                //onDeleteWidgetID(widget.appWidgetId, appwidgetInfo)
                //从数据库移除现在的对象
                //deleteWidget(widget, widget.appWidgetId)
            }
            parent as IWidgetRootView
            //根据自身转换成bitmap创建一个虚影
            dragView = parent
            //创建拖拽使用对象
            //val widgetPreviewInfo = WidgetPreviewInfo(
            //    previewImage = preview, widgetPreviewInfo = appwidgetInfo, widgetId = widget.appWidgetId
            //)
            //开始拖拽
            parent.startDragAndDrop(null, DragShadowBuilder(parent), parent, 0)
            //把自己从布局中删除
            removeView(parent)
            true
        }
        //添加落地坐标 落地后这些坐标将不可使用
        val tempList = mutableListOf<String>()
        for (x in resultX until (resultX + rowSpan)) {
            for (y in resultY until (resultY + columnSpan)) {
                noPositions.add("[$x,$y]")
                tempList.add("[$x,$y]")
            }
        }
        //隐藏背后线框
        cellIsVisible(tempList, true)
        //存出当前页面内Widget对象及他们的坐标
        widgetList.add(Pair(tempList, widget))
        "widgetLabel=${widget.appWidgetInfo.label},widgetId=${widget.appWidgetId},setWidget x=$resultX,y=$resultY,rowSpan=$currentXSpan,columnSpan=$currentYSpan".loge(
            "setWidget")
        noPositions.loge("noPosition")
        widgetList.loge("widgetList")
    }

    /**
     * noPosList ：坐标
     * isVisible ：true = 隐藏 ，false = 显示
     */
    private fun cellIsVisible(noPosList: List<String>, isVisible: Boolean) {
        noPosList.forEach { posTag ->
            nullCells.forEach { cell ->
                if (cell.tag == posTag) {
                    cell.isInvisible(isVisible)
                    cell.invalidate()
                }
            }
        }
    }

    private var lastWidgetId = -1
    private var lastTempX = -1
    private var lastTempY = -1
    private var first = 0

    //添加虚影
    private fun addTempWidget(absX: Int, absY: Int, widget: SkinCompatImageView) {
        if (absX == -1 || absY == -1) return
        //测量占用格数
        var rowSpan = widget.layoutParams.height / 112
        var columnSpan = widget.layoutParams.width / 112
        "Widget : rowSpan = $rowSpan,columnSpan = $columnSpan".loge("WidgetSpan-hjr")

        //TODO:此处写死，因为取不到地图widget的实时预览图(地图强制被拉长拉高)，导致布局在绘制的过程中会闪屏，变形；2022-09-13
        if(widget.layoutParams.height == 214 && widget.layoutParams.width == 286){
            rowSpan = 4
            columnSpan = 6
        }

        if(widget.layoutParams.height == 214 && widget.layoutParams.width == 142){
            rowSpan = 4
            columnSpan = 3
        }

        val layoutParams = LayoutParams()
        layoutParams.width = 0
        layoutParams.height = 0

        Log.d("yzlSize", "size: ${widgetList.size}")

        //检测当前位置是否可用 并进行边界判定
        if (checkPosition(absX, absY, rowSpan, columnSpan)) {
            //当前位置可用就记录当前位置
            val resultX = if (absX + rowSpan > rowCount) (rowCount - rowSpan) else absX
            val resultY = if (absY + columnSpan > columnCount) (columnCount - columnSpan) else absY
            layoutParams.rowSpec = spec(resultX, rowSpan, 1f)
            layoutParams.columnSpec = spec(resultY, columnSpan, 1f)
            //记录widget放置的位置
            tempWidgetX = resultX
            tempWidgetY = resultY

            lastTempX = resultX
            lastTempY = resultY
        } else {
            if (lastTempX == -1 || lastTempY == -1) return
            layoutParams.rowSpec = spec(lastTempX, rowSpan, 1f)
            layoutParams.columnSpec = spec(lastTempY, columnSpan, 1f)
        }
        Log.d("yzla", "addTempWidget: $tempWidgetX+      宽是$tempWidgetY")
        layoutParams.setMargins(20)
        try {
            addView(widget, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        //重新绘制布局
        requestLayout()
    }

    //拖拽时使用的虚影
    private var tempWidget: WeakReference<SkinCompatImageView?>? = null

    //是否创建页面
    private var isPageLock: Boolean = false
    //是否创建页面ACTION_DRAG_LOCATION
    private var isDragLocation: Boolean = false

    //停留坐标
    private var locationPoints = IntArray(2)

    override fun onDrag(v: View, event: DragEvent): Boolean {
        //搜索可以用的位置
        //val tempList = searchLocation(event)
        //tempList.loge("tempList")
        event.action.loge("onDrag")
        var widgetList1 = arrayListOf<Pair<List<String>, IWidgetRootView>>()
        when (event.action) {
            //1
            DragEvent.ACTION_DRAG_STARTED -> {
                widgetList1  = getWidgetList()
                isDragStarted = true
                measureWidget(event)
                if (dragView is IWidgetRootView) {
                    val widget = dragView as IWidgetRootView
                    createTempWidget()
                    addTempWidget(widget.startX, widget.startY, tempWidget?.get()!!)
                }
                //拖拽时隐藏删除按钮
                setAllDeleteViewAlpha(0f)
                isfinish =false

                val visibleRect = Rect()
                v.getGlobalVisibleRect(visibleRect)
                v.tag = visibleRect // 通过 tag 属性保存 visibleRect 对象


                val tempList = mLauncherDao.findAll()
                if (tempList.isNotEmpty()) {
                    tempList.map {

                    }
                }

            }
            //2
            DragEvent.ACTION_DRAG_LOCATION -> {
                isDragStarted = true
                if (detectionIsSetWidget()) {
                    if (!isPageLock) {
                        isPageLock = true
                        clearTempWidget()
                        if (dragView is IWidgetRootView) {
                            (dragView as IWidgetRootView).removeSelf()
                        }
                        if (currentPager == maxPager) {
                            pageHandler.sendEmptyMessage(CREATE_PAGE)
                        } else {
                            pageHandler.sendEmptyMessage(NEXT_PAGE)
                        }
                        if (event.x.roundToInt() < 90) {
                            //上一页
                            isPageLock = true
                            clearTempWidget()
                            if (dragView is IWidgetRootView) {
                                (dragView as IWidgetRootView).removeSelf()
                            }
                            pageHandler.sendEmptyMessage(PREVIOUS_PAGE)
                        }
                    }
                }
                if (isPageLock and (event.x.roundToInt() > 90) and (event.x.roundToInt() < 1700)) {
                    pageHandler.sendEmptyMessage(CANCEL_PAGE)
                }
                setTempWidget(event)
                val visibleRect = v.tag as? Rect ?: return false // 从 tag 属性中获取 visibleRect 对象
                val x = event.x.toInt()
                val y = event.y.toInt()
                if (!visibleRect.contains(x, y)) {
                    // 当前拖拽事件已经超出该 View 的范围
                    // 处理超出 View 范围的逻辑
                    Log.d("yzlNew", "拖拽事件超出当前View可见范围: ($x, $y)")
                    isOutView = true
                }else{
                    // 处理在 View 范围内的逻辑
                    isOutView = false
                    Log.d("yzlNew", "拖拽事件未超出当前View可见范围: ($x, $y)")
                }
                val visibleRect2 = Rect()
                if (x > 1800) {
                    isOutView = !visibleRect2.contains(x, y)
                }
            }
            //3
            DragEvent.ACTION_DROP -> {

            }
            //5
            DragEvent.ACTION_DRAG_ENTERED -> {
                if (isDragLocation){
                    return true
                }
            }
            //6
            DragEvent.ACTION_DRAG_EXITED -> {
                if (dragView is WidgetPreviewInfo) {
                    clearTempWidget()
                }
            }
            //4
            DragEvent.ACTION_DRAG_ENDED -> {
                isDragLocation = false
//                if (isOutView){


                    widgetList1.forEach { item ->
                        val list = item.first
                        val view = item.second
                        Log.d("yzlList","list = $list, view = $view")
                    }
//                    pushView(widgetList1)
//                }
                Log.d("yzlNews", "是否超出当前范围: ($isOutView)")
                endDrag()
            }
        }

        return true
    }

    private var isDragStarted:Boolean =false
    private var lastView :IWidgetRootView? = null
    private fun endDrag() {
        if (!isDragStarted){
            return
        }
        isDragStarted = false
        resetLocationPoints()
        //取消页面操作
        pageHandler.sendEmptyMessage(CANCEL_PAGE)
        createWidget()
        resetLocationPoints()
        //放下后现实


        setAllDeleteViewAlpha(1f)
        clearTempWidget()
    }

    private fun resetLocationPoints() {
        //清空坐标记录
        locationPoints[0] = 0
        locationPoints[1] = 0
        lastLocationPoints[0] = 0
        lastLocationPoints[1] = 0
    }

    private fun checkCreatePage(x: Int, y: Int) {
        if (nullCells.size == noPositions.size) {
            //当前页面填充已满
            isPageLock = true
        }
    }

    //创建Widget
    private fun createWidget() {
        //如果没有虚影证明该Widget放不下来
        Log.d("lys19871213","---->>>"+(tempWidget == null || tempWidget?.get() == null))
        Log.d("lys19871213","---->>"+tempWidgetX+":"+tempWidgetY)
        Log.d("yzla", "createWidget: $tempWidgetX+      宽是$tempWidgetY")
        //if (tempWidget == null || tempWidget?.get() == null) return
        //如果临时坐标也没有直接返回
        if (tempWidgetX == -1 || tempWidgetY == -1) return
        //移除之前的View
        //removeView(tempWidget?.get()!!)
        //创建一个新的Widget
        if (dragView is WidgetPreviewInfo) {
            onSetWidgetPos(
                tempWidgetX,
                tempWidgetY,
                (dragView as WidgetPreviewInfo).widgetPreviewInfo,
                (dragView as WidgetPreviewInfo).widgetId,
                (dragView as WidgetPreviewInfo).previewX,
                (dragView as WidgetPreviewInfo).previewY,
                (dragView as WidgetPreviewInfo).widgetSize.toString()
            )
        } else if (dragView is IWidgetRootView) {
            val iWidgetRootView = dragView as IWidgetRootView
            try {
                addWidget(tempWidgetX, tempWidgetY, iWidgetRootView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        isfinish = true
        Log.d("yzlSize", "放置完成后isfinish: $isfinish")
        //释放部分记录
        releaseWidget()
    }

    //清除Widget虚影
    private fun clearTempWidget() {
        if (tempWidget?.get()?.parent != null) {
            (tempWidget?.get()?.parent as ViewGroup).removeView(tempWidget?.get())
        }
        tempWidget?.clear()
        tempWidget = null
    }

    /**
     * 创建虚影
     */
    @Synchronized
    private fun setTempWidget(event: DragEvent) {
        val tempCellList = arrayListOf<Cell>()
        nullCells.map {
            val tempX = event.x.roundToInt() - it.x.roundToInt()
            val tempY = event.y.roundToInt() - it.y.roundToInt()
            "cell = $it , x=$tempX,y=$tempY".loge("cellLocation")
            if (currentXSpan <= 1) {
                if (tempX in 0..112 && tempY in 0..112) {
                    tempCellList.add(it)
                }
            } else {
                if (tempX in 0 until 112 * currentXSpan && tempY in 0 until 112 * currentYSpan) {
                    tempCellList.add(it)
                }
            }
        }
        val tempCell = tempCellList.find { checkPosition(it.posX, it.posY, currentXSpan, currentYSpan) }
        //找出可以放的位置
        tempCell?.loge("tempCell")
        Log.d("zylCd", "拖动的坐标 $tempCell")
        if (tempCell != null) {
            isCanMove = true
            //创建虚影
            if (dragView is WidgetPreviewInfo) {
                createTempWidget()
            } else if (dragView is IWidgetRootView) {
                if (::tempWidgetRootPreview.isInitialized) {
                    createTempWidget()
                } else {
                    createTempWidget()
                }
            }
            //添加虚影
            addTempWidget(tempCell.posX, tempCell.posY, tempWidget?.get()!!)
        } else {
            isCanMove = false
            tempCellList.forEach {
                checkCurrentWidget(it.posX, it.posY, currentXSpan, currentYSpan, event)
            }
        }
    }

    private fun createTempWidget() {
        //创建前先清除
        clearTempWidget()
        //创建虚影
        val imageView = SkinCompatImageView(context)
        imageView.layoutParams = LayoutParams().apply {
            if (dragView is WidgetPreviewInfo) {
                val className = (dragView as WidgetPreviewInfo).widgetPreviewInfo.provider.className
                if (className == "com.autonavi.autowidget.AutoWidgetProvider") {
                    width = 4 * 112
                    height = 6 * 112
                } else if (className == "com.autonavi.autowidget.AutoWidgetProviderTwoXThree") {
                    width = 4 * 112
                    height = 3 * 112
                }
                width = (dragView as WidgetPreviewInfo).widgetPreviewInfo.minResizeWidth
                height = (dragView as WidgetPreviewInfo).widgetPreviewInfo.minResizeHeight
            } else if (dragView is IWidgetRootView) {
                width = (dragView as IWidgetRootView).appWidgetInfo.minResizeWidth
                height = (dragView as IWidgetRootView).appWidgetInfo.minResizeHeight
            }
        }
        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.setImageResource(R.drawable.select)
        tempWidget = WeakReference(imageView)
        tempWidget?.get()?.isEnabled = false
    }

    private fun releaseWidget() {
        currentYSpan = -1
        currentXSpan = -1
        tempWidgetX = -1
        tempWidgetY = -1
    }

    //检查当前要放置的位置是否可以放置
    @Synchronized
    private fun checkPosition(absX: Int, absY: Int, rowSpan: Int, columnSpan: Int): Boolean {
        "rowSpan=$rowSpan,columnSpan=$columnSpan".loge("method widgetNum")
        "rowSpan=$currentXSpan,columnSpan=$currentYSpan".loge("all widgetNum + $rowCount,$columnCount")
        val tempList = mutableListOf<String>()
        val tempX = if (absX + rowSpan > rowCount) (rowCount - rowSpan) else absX
        val tempY = if (absY + columnSpan > columnCount) (columnCount - columnSpan) else absY
        for (x in tempX until (tempX + rowSpan)) {
            for (y in tempY until (tempY + columnSpan)) {
                tempList.add("[$x,$y]")
            }
        }
        tempList.loge("checkList")
        var successNum = 0
        //判断拖拽view是否处于松手状态

        Log.d("yzlSize", "size: $isCanMoves")
        if (!isCanPlacement && noPositions.isEmpty() && widgetList.size > 0 && !isCanMoves) {
            successNum++
        }
        Log.d("yzlRootMove", "$isCanPlacement ")
        val wList = mutableListOf<IWidgetRootView>()
        tempList.map {
            if (noPositions.contains(it)) {
                successNum++
                //存储当前位置下的控件
                widgetList.map { widget ->
                    if (widget.first.contains(it)) {
                        if (!wList.contains(widget.second)) {
                            wList.add(widget.second)
                        }
                    }
                }
            }
        }
        successNum.loge("successNum")
        return successNum == 0
    }

    @Synchronized
    private fun checkCurrentWidget(absX: Int, absY: Int, rowSpan: Int, columnSpan: Int, event: DragEvent) {
        val tempList = mutableListOf<String>()
        val tempX = if (absX + rowSpan > rowCount) (rowCount - rowSpan) else absX
        val tempY = if (absY + columnSpan > columnCount) (columnCount - columnSpan) else absY
        for (x in tempX until (tempX + rowSpan)) {
            for (y in tempY until (tempY + columnSpan)) {
                tempList.add("[$x,$y]")
            }
        }
        val wList = mutableListOf<Pair<List<String>, IWidgetRootView>>()
        var notPos = mutableListOf<String>()
        tempList.map {
            if (noPositions.contains(it)) {
                //存储当前位置下的控件
                widgetList.forEach { widget ->
                    if (widget.first.contains(it)) {
                        nullCells.forEach { cell ->
                            if (cell.tag == widget.first[widget.first.size - 1]) {
                                if (cell.x.roundToInt() > event.x.roundToInt()) {
                                    if (!wList.contains(widget)) {
                                        wList.add(widget)
                                    }
                                } else {
                                    notPos = widget.first as MutableList<String>
                                }
                            }
                        }
                    }
                }
            }
        }
        Log.d("zylCd", "集合数据 $tempList")
        //移动第一个widget
        val isUse = mutableSetOf<String>()
        isUse.addAll(notPos)
        isUse.addAll(tempList)
        if (isUse.size == (currentXSpan * currentYSpan)) {
            if (wList.isNotEmpty()) checkRight(tempList, wList[0])
        }
    }

    @Synchronized
    private fun checkRight(currentPos: MutableList<String>, widget: Pair<List<String>, IWidgetRootView>) {
        val startXY = currentPos[0].replace("[", "").replace("]", "").split(",")
        val tempList = mutableListOf<String>()
        //获取当前起点到终点的所有widget
        for (x in 0 until rowCount) {
            for (y in startXY[1].toInt() until columnCount) {
                tempList.add("[$x,$y]")
            }
        }
        //存储区域内的所有widget
        val wList = mutableListOf<Pair<List<String>, IWidgetRootView>>()
        widgetList.forEach {
            val repeatElements = tempList.getRepeatElements(it.first)
            if (repeatElements?.isNotEmpty() == true) {
                if (!wList.contains(it)) wList.add(it)
            }
        }

        /*判断是否可以移动的值*/
        //排序widget列表
        wList.sortBy { it.second.startY }
        //存储widget移动数据和移动后坐标的数据类
        val newWList = mutableListOf<WidgetOffsetInfo>()
        //上一个移动数据
        var lastWidgetOffsetInfo = WidgetOffsetInfo()

        //从第一个开始计算移动的距离
        var isMove = true;
        wList.forEachIndexed { index, widgetPair ->
            //移动多少距离
            var offset = 0
            //创建widget移动数据对象
            val widgetOffsetInfo = WidgetOffsetInfo()
            //绑定widget
            widgetOffsetInfo.widget = widgetPair.second
            //绑定原坐标
            widgetOffsetInfo.oldWidgetPos = widgetPair.first
            //先看跟放置位置是否有冲突
            val repeatElements = currentPos.getRepeatElements(widgetPair.first)
            Log.d("zylCd", "拖动的距离 $repeatElements")
            if (repeatElements?.isNotEmpty() == true) {
                //跟放置位置有碰撞
                offset = checkXOffset(repeatElements)
                Log.d("zylCd", "跟放置位置有碰撞")
            } else if (index > 0) {
                //计算跟上一个widget重叠要移动多少位置
                val repeatPos = widgetPair.first.getRepeatElements(lastWidgetOffsetInfo.newWidgetPos)
                if (repeatPos?.isNotEmpty() == true) {
                    offset = checkXOffset(repeatPos)
                }
            }
            Log.d("zylCd", "拖动的距离 $offset")
            //保存移动多少距离
            widgetOffsetInfo.offset = offset

            //新的坐标集
            val widgetNewPos = mutableListOf<String>()

            //计算widget新坐标
            widgetPair.first.forEach {
                val split = it.replace("[", "").replace("]", "").split(",")
                val newX = split[1].toInt() + offset
                "widget offset: = $offset".loge("LYS-DRAG")
                "widget x: = [${split[0]},$newX]".loge("LYS-DRAG")

                if(newX > 11){
                    isMove = false;
                }
                widgetNewPos.add("[${split[0]},$newX]")

            }
            //保存新的坐标集
            widgetOffsetInfo.newWidgetPos = widgetNewPos
            //记录上一个widgetOffsetInfo
            lastWidgetOffsetInfo = widgetOffsetInfo
            //存储widgetOffset
            if (!newWList.contains(widgetOffsetInfo)) {
                newWList.add(widgetOffsetInfo)
            }
        }


        //先判断是否有越界的坐标
        var isCanPush = true
        if(!isMove){
            isCanPush = false;
        }else {
            "被拖动 = $currentPos".loge("yzlCd")
            val posList = mutableListOf<String>();
            "被拖动 = $currentPos".loge("LYS-DRAG")
            posList.addAll(currentPos);
            if(isCanPush){
                newWList.forEachIndexed { index, widgetOffsetInfo ->
                    "序号 = $index".loge("LYS-DRAG")
                    "widget坐标 = ${widgetOffsetInfo.newWidgetPos}".loge("LYS-DRAG")
                    if (index == 0) {
                        val repeatElements = widgetOffsetInfo.newWidgetPos.getRepeatElements(currentPos)
                        if (repeatElements?.isNotEmpty() == true) {
                            isCanPush = false
                        }
                    } else {
                        val repeatElements = widgetOffsetInfo.newWidgetPos.getRepeatElements(newWList[index.dec()].newWidgetPos)
                        if (repeatElements?.isNotEmpty() == true) {
                            isCanPush = false
                        }
                    }

                    //TODO:2023-01-07 此处特别处理大widget右侧无空间时的右移问题导致被拖动的widget与big widget重叠问题；
                    widgetOffsetInfo.newWidgetPos.forEach{pos->
                        if(posList.contains(pos)){
                            isCanPush = false
                        }else{
                            posList.add(pos)
                        }
                    }

                }
            }

        }
        //计算结束 开始移动widget
        "是否可以移动 = $isCanPush".loge("liuyongsheng")
        if (!isCanPush) return
//        if (!isCanMove) return
        pushView(newWList)
        //动画��widget
    }


    //只要判断newWidgetPos集合中存在重复元素就表示不可以移动
    private fun checkCanPush(newWidgetPos: List<String>): Boolean{
        //
        val set = hashSetOf<String>();
        set.addAll(newWidgetPos);
        return set.size == newWidgetPos.size;
    }


    private fun checkXOffset(repeatElements: MutableList<String>): Int {
        val offsetSize = mutableSetOf<Int>()
        offsetSize.clear()
        repeatElements.forEach {
            val split = it.replace("[", "").replace("]", "").split(",")
            offsetSize.add(split[1].toInt())
        }
        return offsetSize.size
    }

    private fun pushView(wList: MutableList<WidgetOffsetInfo>) {
        wList.forEach { widgetInfo ->

            "循环 = ${widgetInfo.widget?.widgetSize}".loge("is-can-push")

            widgetInfo.oldWidgetPos.forEach { oldPos ->
                //删除旧坐
                if (noPositions.contains(oldPos)) {
                    noPositions.remove(oldPos)
                }
            }
            //删除集合的原widget
            val oldWidget = widgetList.find { it.second == widgetInfo.widget }
            widgetList.remove(oldWidget)
            //刷新格子
            cellIsVisible(widgetInfo.oldWidgetPos, false)
        }
        //执行动画
        wList.forEach { widgetInfo ->
            val startXY = widgetInfo.newWidgetPos[0].replace("[", "").replace("]", "").split(",")
            val translationXValue = getTranslationXValue(widgetInfo, widgetInfo.widget!!)
            translationAnim(widgetInfo.widget!!, translationXValue, startXY)
        }
    }

    private fun translationAnim(view: View, value: Float, startXY: List<String>) {
        val animator = ObjectAnimator.ofFloat(view, "translationX", value)
        Log.d("yzlSize", "isfinish: $isfinish")
        isCanMoves = false
        animator.start()
        animator.doOnEnd {
            if (view is IWidgetRootView) {
                removeView(view)
                view.translationX = 0f
                addWidget(startXY[0].toInt(), startXY[1].toInt(), view)
                Log.d("zylCd", "移动的坐标 $startXY")
                isCanMoves = true
                Log.d("yzlSize", "动画完毕移动: $isfinish")
            }
        }
    }

    private fun getTranslationXValue(widgetInfo: WidgetOffsetInfo, widget: IWidgetRootView): Float {
        val oldStart = widgetInfo.oldWidgetPos[0]
        val newStart = widgetInfo.newWidgetPos[0]
        val oldCell = nullCells.find { it.tag == oldStart }
        val newCell = nullCells.find { it.tag == newStart }
        val translationValue = oldCell?.x?.let { newCell?.x?.minus(it) }
        if (translationValue != null) {
            translationValue + widget.marginStart + widget.marginEnd
        }
        "widget移动距离 = $translationValue".loge("translation-value")
        return translationValue?.toFloat() ?: 0f

    }

    fun removeLongClickEvent() {
        widgetList.forEach {
            it.second.eventHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun checkPushWidget(sx: Int, sy: Int, rowSpan: Int, columnSpan: Int) {
        "checkPushWidget".loge("PUSH-WIDGET")
        val tempCellList = mutableListOf<Cell>()
        nullCells.map {
            val tempX = sx - it.x.roundToInt()
            val tempY = sy - it.y.roundToInt()
            if (currentXSpan <= 1) {
                if (tempX in 0..112 && tempY in 0..112) {
                    tempCellList.add(it)
                }
            } else {
                if (tempX in 0 until 112 * currentXSpan && tempY in 0 until 112 * currentYSpan) {
                    tempCellList.add(it)
                }
            }
        }
        if (tempCellList.isEmpty()) return
        val cell = tempCellList.random()
        val tempList = mutableListOf<String>()
        val tempX = if (cell.posX + rowSpan > rowCount) (rowCount - rowSpan) else cell.posX
        val tempY = if (cell.posY + columnSpan > columnCount) (columnCount - columnSpan) else cell.posY
        for (x in tempX until (tempX + rowSpan)) {
            for (y in tempY until (tempY + columnSpan)) {
                tempList.add("[$x,$y]")
            }
        }

        val wList = mutableListOf<Pair<List<String>, IWidgetRootView>>()
        tempList.map {
            if (noPositions.contains(it)) {
                //存储当前位置下的控件
                widgetList.map { widget ->
                    if (widget.first.contains(it)) {
                        if (!wList.contains(widget)) {
                            wList.add(widget)
                        }
                    }
                }
            }
        }
        wList.map { it.second.appWidgetInfo.label.loge("push-list") }
    }

    private fun setAllDeleteViewAlpha(alpha: Float) {
        deleteView.forEach {
            it.deleteView?.alpha = alpha
        }
    }

    fun editMode(isOpen: Boolean) {
        isCanDrag = isOpen
        nullCells.map {
            it.animate().apply {
                if (isOpen) alpha(1f) else alpha(0f)
                duration = 230
                interpolator = AccelerateInterpolator()
                start()
            }
        }
        widgetList.forEach {
            it.second.isOnDrag = !isOpen
        }
        deleteView.map {
            it.deleteView?.animate()?.apply {
                it.deleteView?.isClickable = isOpen
                if (isOpen) alpha(1f) else alpha(0f)
                duration = 230
                interpolator = AccelerateInterpolator()
                start()
            }
        }
    }

    class Cell @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        var posX = -1
        var posY = -1

        override fun toString(): String {
            return "{x=$posX,y=$posY}"
        }
    }

    data class DeleteView(
        var deleteView: ImageView?,
        var widget: AppWidgetHostView?,
    )

    companion object {
        const val CREATE_PAGE = 100001
        const val NEXT_PAGE = 100002
        const val PREVIOUS_PAGE = 100003
        const val CANCEL_PAGE = 200004
        const val PAGE_CREATE_END = 200005
        const val CLEAR_TEMP_WIDGET = 200006
        const val MOVE_PLACEMENT = 1
        const val MOVE_CANCEL = 2
        const val PUSH_TIME = 300001
        private const val TAG ="CellGridLayout"
    }

    private var lastLocationPoints = intArrayOf(locationPoints[0], locationPoints[1])

    val pageHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                PUSH_TIME -> {
                    if ((locationPoints[0] - lastLocationPoints[0] < 10) and (locationPoints[1] - lastLocationPoints[1] < 10)) {
                        checkPushWidget(lastLocationPoints[0], lastLocationPoints[1], currentXSpan, currentYSpan)
                    }
                }
                CREATE_PAGE -> {
                    //创建页面
                    onLocalPagerListener(PageOperationType.CREATE_PAGE)
                }
                CANCEL_PAGE -> {
                    //取消创建
                    isPageLock = false
                    removeCallbacksAndMessages(null)
                }
                NEXT_PAGE -> {
                    //下一页
                    onLocalPagerListener(PageOperationType.NEXT_PAGE)
                }
                PREVIOUS_PAGE -> {
                    //上一页
                    onLocalPagerListener(PageOperationType.PREVIOUS_PAGE)
                }
                PAGE_CREATE_END -> {

                }
                CLEAR_TEMP_WIDGET -> {
                    clearTempWidget()
                }
                else -> {}
            }
        }
    }
}