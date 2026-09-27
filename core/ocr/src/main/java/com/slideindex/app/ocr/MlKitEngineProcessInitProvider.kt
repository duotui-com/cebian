package com.slideindex.app.ocr

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.google.mlkit.common.sdkinternal.MlKitContext

/**
 * 让 `:engine` 进程也初始化 ML Kit。
 *
 * ML Kit 的自动初始化只有一个 ContentProvider（库里的 `MlKitInitProvider`），而 ContentProvider
 * **只会在它被分配到的进程里创建**——库自带的声明没有 `android:process`，所以只有主进程会初始化。
 * 偏偏 ML Kit 中文模型的下载（`OcrModelDownloadService`）、识别（`EngineOcrService`）、翻译模型
 * 都跑在 `:engine`，那边 `MlKitContext` 恒为空，一调用就抛
 * "MlKitContext has not been initialized"。
 *
 * 这里注册一个只属于 `:engine` 的 Provider，走 ML Kit 的公开入口 `initializeIfNeeded()`：
 * 反编译确认它与库自带 Provider 内部是同一条路径（initializeIfNeeded → zza → zzb(MAIN_THREAD)），
 * 幂等且线程安全；主进程仍由库自带的 Provider 负责，互不干扰。
 */
class MlKitEngineProcessInitProvider : ContentProvider() {

    @SuppressLint("RestrictedApi") // ML Kit 没有别的公开初始化入口可用
    override fun onCreate(): Boolean {
        val context = context ?: return false
        return runCatching {
            MlKitContext.initializeIfNeeded(context.applicationContext)
            Log.i(TAG, "ML Kit 已在 ${processName()} 初始化")
            true
        }.onFailure {
            // 失败要可见：否则又会退化成"只是页面报 MlKitContext 未初始化"的静默失灵。
            Log.w(TAG, "ML Kit 在 ${processName()} 初始化失败", it)
        }.getOrDefault(false)
    }

    private fun processName(): String =
        runCatching { Application.getProcessName() }.getOrNull().orEmpty()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "MlKitEngineInit"
    }
}
