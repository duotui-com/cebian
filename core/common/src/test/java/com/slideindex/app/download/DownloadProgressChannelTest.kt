package com.slideindex.app.download

import android.os.Bundle
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * 下载进度跨进程通道。
 *
 * 进程间那一半（广播）没法在单进程单测里造出来——[com.slideindex.app.util.CrossProcessStore]
 * 会主动忽略"自己进程发出的"通知，这是有意的。所以这里锁住两件真正会坏的事：
 * 1. 快照能落盘再读回来（"下载开始了才打开页面"就靠它）；
 * 2. 时效判定与节流不会把该发的更新吞掉、也不会被网络块刷爆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DownloadProgressChannelTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `snapshot round-trips through the channel file`() {
        val payload = Bundle().apply {
            putString("modelId", "ppocrv6-medium")
            putLong("bytesDownloaded", 123L)
            putLong("totalBytes", 456L)
        }

        DownloadProgressChannel.publish(context, "round_trip", payload)

        val read = DownloadProgressChannel.snapshot(context, "round_trip")
        assertNotNull(read)
        assertEquals("ppocrv6-medium", read!!.getString("modelId"))
        assertEquals(123L, read.getLong("bytesDownloaded"))
        assertEquals(456L, read.getLong("totalBytes"))
    }

    @Test
    fun `channels do not see each other`() {
        DownloadProgressChannel.publish(context, "channel_a", Bundle().apply { putString("k", "a") })
        DownloadProgressChannel.publish(context, "channel_b", Bundle().apply { putString("k", "b") })

        assertEquals("a", DownloadProgressChannel.snapshot(context, "channel_a")!!.getString("k"))
        assertEquals("b", DownloadProgressChannel.snapshot(context, "channel_b")!!.getString("k"))
    }

    @Test
    fun `clear removes the snapshot`() {
        DownloadProgressChannel.publish(context, "cleared", Bundle().apply { putString("k", "v") })
        assertNotNull(DownloadProgressChannel.snapshot(context, "cleared"))

        DownloadProgressChannel.clear(context, "cleared")

        assertNull(DownloadProgressChannel.snapshot(context, "cleared"))
    }

    @Test
    fun `freshness uses the publish timestamp`() {
        DownloadProgressChannel.publish(context, "freshness", Bundle())
        val stored = DownloadProgressChannel.snapshot(context, "freshness")!!

        assertTrue(DownloadProgressChannel.isFresh(stored))
        // 发布方进程被硬杀后留下的旧快照必须能判出来（默认窗口 5 分钟）。
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        assertFalse(DownloadProgressChannel.isFresh(stored))
        assertTrue(DownloadProgressChannel.isFresh(null))
    }

    @Test
    fun `relay publishes new percentages and phase changes but drops repeats`() {
        val relay = DownloadProgressRelay(context, "relay")

        relay.publish("DOWNLOADING", 10, Bundle().apply { putLong("bytes", 10L) })
        assertEquals(10L, snapshotBytes("relay"))

        // 同一秒、同一百分比：网络回调可能一秒几十次，这里必须被吞掉。
        relay.publish("DOWNLOADING", 10, Bundle().apply { putLong("bytes", 11L) })
        assertEquals(10L, snapshotBytes("relay"))

        // 百分比前进 1%：必须发。
        relay.publish("DOWNLOADING", 11, Bundle().apply { putLong("bytes", 12L) })
        assertEquals(12L, snapshotBytes("relay"))

        // 阶段变化：即使百分比没动也必须发。
        relay.publish("VERIFYING", null, Bundle().apply { putLong("bytes", 13L) })
        assertEquals(13L, snapshotBytes("relay"))

        relay.clear()
        assertNull(DownloadProgressChannel.snapshot(context, "relay"))
    }

    private fun snapshotBytes(channel: String): Long =
        DownloadProgressChannel.snapshot(context, channel)!!.getLong("bytes")
}
