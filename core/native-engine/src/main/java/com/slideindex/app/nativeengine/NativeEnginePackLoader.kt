package com.slideindex.app.nativeengine

import android.util.Log
import java.io.File

internal object NativeEnginePackLoader {
    private const val TAG = "NativeEnginePack"

    private val loadedLibraries = LinkedHashSet<String>()
    private var loadedOcrPackRevision: Int? = null

    fun loadedOcrPackRevision(): Int? = loadedOcrPackRevision

    fun requiresProcessRestartForRevision(packRevision: Int): Boolean {
        val loadedRevision = loadedOcrPackRevision ?: return false
        return loadedLibraries.isNotEmpty() && loadedRevision != packRevision
    }

    @Synchronized
    fun loadLibraries(libDir: File, libraryNames: List<String>, packRevision: Int? = null) {
        if (packRevision != null && requiresProcessRestartForRevision(packRevision)) {
            throw IllegalStateException(
                "native_engine_stale: loadedRevision=$loadedOcrPackRevision requestedRevision=$packRevision",
            )
        }
        check(libDir.isDirectory) { "native_lib_dir_missing" }
        for (name in libraryNames) {
            if (name in loadedLibraries) continue
            val file = File(libDir, name)
            check(file.isFile) { "native_library_missing:$name" }
            sealReadOnly(file)
            System.load(file.absolutePath)
            loadedLibraries.add(name)
        }
        if (packRevision != null) {
            loadedOcrPackRevision = packRevision
        }
    }

    @Synchronized
    fun isLibraryLoaded(libraryName: String): Boolean = libraryName in loadedLibraries

    /**
     * 把已解压的 native 库置为只读。
     *
     * Android 17（SDK 37）起 `System.load()` 不再接受可写文件：libcore `Runtime.load0` 在
     * `file.canWrite()` 为真时，经 compat change `THROW_ERROR_FOR_WRITABLE_DCL`(463348571)
     * 抛 `UnsatisfiedLinkError`（`targetSdk >= 37` 的应用默认启用）。日志里表现为一条
     * `Attempt to load writable file: …` 警告后立即失败，上层只能看到「OCR 运行库未就绪」。
     *
     * 平台的判定点是「文件是否可写」（不是目录），所以解压后立刻置只读即可正常加载。
     */
    fun sealReadOnly(file: File): Boolean {
        if (!file.isFile) return false
        if (!file.canWrite()) return true
        val sealed = file.setReadOnly()
        if (!sealed) {
            Log.w(TAG, "failed to mark native library read-only: ${file.absolutePath}")
        }
        return sealed
    }
}
