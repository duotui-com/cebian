package com.slideindex.app.segmentation

import android.content.Context
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.nativeengine.NativeEnginePackCoordinator
import com.slideindex.app.nativeengine.NativeEnginePackIds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class SegmentationEngineProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: NativeEnginePackCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var requested = false

    fun requestIfNeeded() {
        if (coordinator.isPackInstalled(NativeEnginePackIds.SEGMENTATION)) return
        // 用户删过就尊重删除：不自动装回、也不弹"正在准备"这种会误导的提示，
        // 取词自动回落到 ICU 分词；想装请在设置 → 引擎包里显式安装。
        if (coordinator.isUserRemoved(NativeEnginePackIds.SEGMENTATION)) return
        synchronized(this) {
            if (requested) return
            requested = true
        }
        scope.launch {
            Toast.makeText(context, R.string.segmentation_engine_preparing, Toast.LENGTH_SHORT).show()
            withContext(Dispatchers.IO) {
                coordinator.ensurePackReady(NativeEnginePackIds.SEGMENTATION)
            }
        }
    }
}
