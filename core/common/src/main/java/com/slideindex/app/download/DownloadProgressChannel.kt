package com.slideindex.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 下载进度跨进程通道。
 *
 * 下载 Service 跑在 `:engine`（见 AndroidManifest 里"下载下沉"的决定），而设置页在主进程。
 * 进程内的单例（`OcrModelDownloadController` / `NativeEnginePackDownloadController`）在主进程
 * 永远是初始值，这正是"只有通知有进度、页面空白"的根因。
 *
 * 通道由两部分组成，缺一不可：
 * 1. **快照文件**：发布方每次写 `files/download_progress/<channel>.bin`（临时文件 + rename 原子替换）。
 *    订阅时先读一次，保证"下载开始后才打开页面"也能立刻看到进度。用文件而不是 SharedPreferences，
 *    因为 SharedPreferences 是每进程各自缓存的，跨进程读到旧值/空值正是要避免的坑。
 * 2. **载荷广播**：发布方 `sendBroadcast` 时**把载荷直接带在 Intent 里**，订阅方不需要回读文件。
 *    这一点是必须的：终态（READY/FAILED）发完经常马上收尾，谁也无法保证订阅方执行时快照文件还在。
 *    （教训：曾经改成"只发文件变更通知、订阅方回读文件"，结果终态在读取前就被清掉，页面拿到 null
 *    → 下载完成不自动选中。）
 *
 * 订阅方不存在（页面没开）时广播自然没人收，不会拉起进程。
 */
object DownloadProgressChannel {

    private const val TAG = "DownloadProgress"
    // 发布/订阅之间的约定，internal 是为了让单测能直接断言"载荷确实在广播里"。
    internal const val ACTION = "com.slideindex.app.action.DOWNLOAD_PROGRESS"
    internal const val EXTRA_CHANNEL = "channel"
    internal const val EXTRA_PAYLOAD = "payload"
    private const val KEY_PUBLISHED_AT = "publishedAtElapsedMs"
    private const val DIR_NAME = "download_progress"

    /** 发布进度：写快照 + 广播。payload 必须是只含基础类型的 [Bundle]。 */
    fun publish(context: Context, channel: String, payload: Bundle) {
        val stamped = Bundle(payload).apply { putLong(KEY_PUBLISHED_AT, SystemClock.elapsedRealtime()) }
        val encoded = encode(stamped) ?: return
        val appContext = context.applicationContext
        val file = snapshotFile(appContext, channel)
        runCatching { file.writeAtomically(encoded) }
            .onFailure { Log.w(TAG, "write snapshot failed channel=$channel", it) }
        // 快照负责"晚到的订阅者"，广播负责"此刻正在看的订阅者"，两者都带上载荷。
        val intent = Intent(ACTION)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_CHANNEL, channel)
            .putExtra(EXTRA_PAYLOAD, encoded)
        runCatching { appContext.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "broadcast failed channel=$channel", it) }
    }

    /** 清理该通道的快照（下载结束、或状态已无意义时调用）。 */
    fun clear(context: Context, channel: String) {
        runCatching { snapshotFile(context.applicationContext, channel).delete() }
            .onFailure { Log.w(TAG, "clear snapshot failed channel=$channel", it) }
    }

    /**
     * 快照是否仍然"新鲜"。进程被硬杀（OEM 清后台）时来不及 [clear]，
     * 留下的"下载中 43%"会一直挂在页面上；订阅方对"进行中"的快照做时效判断，
     * 超过 [maxAgeMs] 就当它已经死了（真正的下载有前台服务，进度会持续刷新时间戳）。
     */
    fun isFresh(payload: Bundle?, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean {
        val publishedAt = payload?.getLong(KEY_PUBLISHED_AT, 0L) ?: 0L
        if (publishedAt <= 0L) return true
        return SystemClock.elapsedRealtime() - publishedAt <= maxAgeMs
    }

    /** 读一次快照；没有或解析失败返回 null。 */
    fun snapshot(context: Context, channel: String): Bundle? {
        val file = snapshotFile(context.applicationContext, channel)
        if (!file.isFile) return null
        return runCatching { decode(file.readText()) }
            .onFailure { Log.w(TAG, "read snapshot failed channel=$channel", it) }
            .getOrNull()
    }

    /**
     * 先发一次快照，再持续发实时更新（载荷直接来自广播，不需要回读文件）。
     * **实时更新永远不会发 null**：快照可能因为清理/竞争而读不到，那不该抹掉"最后已知状态"。
     * 取消收集时自动注销 receiver；页面没打开时不会有人订阅，也就不会有开销。
     */
    fun observe(context: Context, channel: String): Flow<Bundle?> = callbackFlow {
        val appContext = context.applicationContext
        trySend(snapshot(appContext, channel))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ACTION) return
                if (intent.getStringExtra(EXTRA_CHANNEL) != channel) return
                val raw = intent.getStringExtra(EXTRA_PAYLOAD) ?: return
                val bundle = runCatching { decode(raw) }
                    .onFailure { Log.w(TAG, "decode broadcast failed channel=$channel", it) }
                    .getOrNull() ?: return
                trySend(bundle)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            // 注册失败（极少见）：至少保留快照那一次发射，并留下可见日志而不是静默失效。
            Log.w(TAG, "register receiver failed channel=$channel", it)
        }
        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "unregister receiver failed channel=$channel", it) }
        }
    }

    private fun snapshotFile(context: Context, channel: String): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "${channel.replace('/', '_')}.bin")
    }

    private fun File.writeAtomically(text: String) {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(this)) {
            writeText(text)
            tmp.delete()
        }
    }

    private fun encode(bundle: Bundle): String? = runCatching {
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }.onFailure { Log.w(TAG, "encode payload failed", it) }.getOrNull()

    private fun decode(raw: String): Bundle? {
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    const val DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L
}

/**
 * 发布侧的节流器：下载回调按网络块触发（每秒可能几十次），
 * 直接每次都广播 + 落盘会自己烧 CPU，所以这里做一层合并：
 * 阶段变化立即发，进度每涨 1% 发一次，其余情况最多 1 秒一次。
 */
class DownloadProgressRelay(
    private val context: Context,
    private val channel: String,
) {
    private var lastPhase: String? = null
    private var lastPercent: Int = -1
    private var lastPublishElapsedMs = 0L

    /**
     * @param force 终态补发时用 true：跳过节流，保证"这一条一定发出去"。
     */
    fun publish(phase: String, percent: Int?, payload: Bundle, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val phaseChanged = phase != lastPhase
        val percent = percent ?: -1
        val percentStepped = percent >= 0 && (lastPercent < 0 || percent - lastPercent >= 1 || percent >= 100)
        val stale = now - lastPublishElapsedMs >= MIN_INTERVAL_MS
        if (!force && !phaseChanged && !percentStepped && !stale) return
        lastPhase = phase
        lastPercent = percent
        lastPublishElapsedMs = now
        DownloadProgressChannel.publish(context, channel, payload)
    }

    fun clear() {
        lastPhase = null
        lastPercent = -1
        lastPublishElapsedMs = 0L
        DownloadProgressChannel.clear(context, channel)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1000L
    }
}
