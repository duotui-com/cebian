package com.slideindex.app.overlay.history

import androidx.compose.runtime.mutableStateOf
import com.slideindex.app.stash.StashCategoryFilter

/**
 * 暂存夹页**选中的分类筛选**。
 *
 * 和 [StashTagFilterState] 同一个理由放在**进程级单例**里：面板是 overlay 窗，被系统摘掉（比如 Flyme 的 detach）
 * 之后重新打开是全新的 ViewModelStore + 全新的 ViewModel，放在 ViewModel 里的选择就跟着丢了，
 * 用户的感受是“我选的分类自己跳回「全部」”。
 *
 * 存的是 [StashCategoryFilter.encode] 的字符串；选中的分类被删掉时由 `HistoryPanelViewModel` 回落到「全部」。
 * 只保存**筛选条件**，不保存任何条目数据。
 */
internal object StashCategoryFilterState {
    val encoded = mutableStateOf(StashCategoryFilter.All.encode())

    fun set(filter: StashCategoryFilter) {
        encoded.value = filter.encode()
    }
}
