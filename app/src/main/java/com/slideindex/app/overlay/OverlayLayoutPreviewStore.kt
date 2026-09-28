package com.slideindex.app.overlay

import com.slideindex.app.gesture.TriggerHandleDesign

/**
 * 布局/触钮设置页拖动滑条时的临时预览值；不落盘，结束预览后应清空。
 */
object OverlayLayoutPreviewStore {
    @Volatile
    var indexHeightFraction: Float? = null

    /**
     * 已提交、但还没被设置回流确认的预览值。
     *
     * 滑条松手时"写设置"是异步落盘的；如果这时立刻丢掉临时预览值，浮层会先按**旧的**已保存值
     * 重画一遍，等新值落盘回流后再重画一次（用户看到的就是"松手先跳回原位、再跳到正确位置"）。
     * 因此提交时先把值挪到 [committedIndexHeightFraction] / [committedTriggerHandlePreview] 继续顶着，
     * 等回流过来的设置值和它一样（说明已经落盘）再由 [AppSettings.withOverlayLayoutPreview] 丢掉。
     */
    @Volatile
    var committedIndexHeightFraction: Float? = null

    @Volatile
    private var committedIndexHeightAtMs: Long = 0L

    data class TriggerHandlePreview(
        val side: PanelSide,
        val handleId: String,
        val edgeWidthDp: Float? = null,
        val topFraction: Float? = null,
        val bottomFraction: Float? = null,
        val shortSwipeDistanceDp: Float? = null,
        val longSwipeDistanceDp: Float? = null,
        val design: TriggerHandleDesign? = null
    )

    @Volatile
    var triggerHandlePreview: TriggerHandlePreview? = null

    @Volatile
    var committedTriggerHandlePreview: TriggerHandlePreview? = null

    @Volatile
    private var committedTriggerHandleAtMs: Long = 0L

    fun clear() {
        indexHeightFraction = null
        triggerHandlePreview = null
        committedIndexHeightFraction = null
        committedTriggerHandlePreview = null
    }

    fun clearIndexHeightPreview() {
        indexHeightFraction = null
        committedIndexHeightFraction = null
    }

    fun clearTriggerHandlePreview() {
        triggerHandlePreview = null
        committedTriggerHandlePreview = null
    }

    /** 松手提交：临时值转为"待确认"，继续生效直到设置回流追上。 */
    fun commitIndexHeightPreview() {
        val current = indexHeightFraction ?: return
        committedIndexHeightFraction = current
        committedIndexHeightAtMs = android.os.SystemClock.elapsedRealtime()
        indexHeightFraction = null
    }

    fun commitTriggerHandlePreview() {
        val current = triggerHandlePreview ?: return
        committedTriggerHandlePreview = current
        committedTriggerHandleAtMs = android.os.SystemClock.elapsedRealtime()
        triggerHandlePreview = null
    }

    /** 超时兜底：设置写入失败时不能永远顶着旧值。 */
    fun activeIndexHeightFraction(nowMs: Long): Float? =
        indexHeightFraction ?: committedIndexHeightFraction?.takeIf {
            nowMs - committedIndexHeightAtMs <= COMMIT_TTL_MS
        }

    fun activeTriggerHandlePreview(nowMs: Long): TriggerHandlePreview? =
        triggerHandlePreview ?: committedTriggerHandlePreview?.takeIf {
            nowMs - committedTriggerHandleAtMs <= COMMIT_TTL_MS
        }

    fun dropCommittedIndexHeight() {
        committedIndexHeightFraction = null
    }

    fun dropCommittedTriggerHandle() {
        committedTriggerHandlePreview = null
    }

    fun mergeTriggerHandlePreview(
        side: PanelSide,
        handleId: String,
        edgeWidthDp: Float? = null,
        topFraction: Float? = null,
        bottomFraction: Float? = null,
        shortSwipeDistanceDp: Float? = null,
        longSwipeDistanceDp: Float? = null,
        design: TriggerHandleDesign? = null
    ) {
        val existing = triggerHandlePreview?.takeIf { it.side == side && it.handleId == handleId }
        triggerHandlePreview = TriggerHandlePreview(
            side = side,
            handleId = handleId,
            edgeWidthDp = edgeWidthDp ?: existing?.edgeWidthDp,
            topFraction = topFraction ?: existing?.topFraction,
            bottomFraction = bottomFraction ?: existing?.bottomFraction,
            shortSwipeDistanceDp = shortSwipeDistanceDp ?: existing?.shortSwipeDistanceDp,
            longSwipeDistanceDp = longSwipeDistanceDp ?: existing?.longSwipeDistanceDp,
            design = design ?: existing?.design
        )
    }

    /** 提交值顶住的时限；超过它说明设置没能落盘，放手让浮层回到已保存值。 */
    private const val COMMIT_TTL_MS = 2_000L
}
