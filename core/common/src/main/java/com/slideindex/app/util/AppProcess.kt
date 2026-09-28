package com.slideindex.app.util

import android.app.Application

/**
 * 进程身份。
 *
 * 现架构（已从多进程回退）：
 * - 默认进程：设置 UI + 无障碍服务 + 全部浮层/面板/小组件宿主 + 剪贴板监听 + 录屏截屏 + 模块桥
 * - `:engine`：OCR / 翻译 / 公式 / 分词等引擎（OOM 隔离，独立保留）
 *
 * 所以这里只剩"默认进程"与"引擎进程"两个身份：历史上的 `:overlay` / `:clipboard`
 * 连同它们的分支已随合并一起删掉（Shizuku 用户服务 `:task_manager_v36` 这类旁支进程
 * 用 [isMain] 判定即可）。新代码不要再按进程身份分支，需要别的进程的数据就走显式接口。
 *
 * 注意：**静态状态在每个进程各有一份**，跨进程共享必须走显式的接口/快照通道，
 * 不要直接读另一个进程的 object 字段。本类只负责身份判定。
 *
 * 放在 `core:common` 是为了让 core 层模块（如 core:ocr）也能做进程判定。
 */
object AppProcess {
    const val ENGINE_PROCESS_SUFFIX = ":engine"

    @Volatile
    private var cachedName: String? = null

    /** 形如 `com.slideindex.app:engine`；取不到时返回空串。 */
    fun name(): String {
        cachedName?.let { return it }
        val detected = detect()
        cachedName = detected
        return detected
    }

    val isEngine: Boolean get() = name().endsWith(ENGINE_PROCESS_SUFFIX)

    /** 默认进程的进程名里没有 ':'（不依赖 BuildConfig，core 层也能用）。 */
    val isMain: Boolean get() = !name().contains(':')

    private fun detect(): String {
        runCatching { Application.getProcessName() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // 兜底：/proc/self/cmdline（进程名以 \0 结尾）
        return runCatching {
            java.io.File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        }.getOrDefault("")
    }
}
