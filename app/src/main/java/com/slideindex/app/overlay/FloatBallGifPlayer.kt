package com.slideindex.app.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * FV-style GIF playback: Handler tick + lightweight View invalidate.
 */
internal class FloatBallGifPlayer(
    looper: Looper = Looper.getMainLooper()
) {
    private val handler = Handler(looper)
    private var gifView: FloatBallGifView? = null
    private var sequence: FloatBallGifFrameDecoder.Sequence? = null
    private var frameIndex = 0
    private var paused = false
    private var streamingStartUptimeMs = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (paused) return
            val view = gifView ?: return
            val seq = sequence ?: return
            if (!view.canAnimateFrame()) {
                // 球不可见 / 熄屏：没必要逐帧重绘，1 秒探一次即可。
                view.setAnimating(false)
                handler.postDelayed(this, HIDDEN_RECHECK_MS)
                return
            }
            view.setAnimating(true)
            when (seq) {
                is FloatBallGifFrameDecoder.Sequence.Cached -> {
                    if (seq.frames.isEmpty()) return
                    val frame = seq.frames[frameIndex]
                    view.showCachedFrame(frame.bitmap)
                    val delayMs = frame.delayMs
                    frameIndex = (frameIndex + 1) % seq.frames.size
                    handler.postDelayed(this, delayMs.toLong())
                }
                is FloatBallGifFrameDecoder.Sequence.Streaming -> {
                    val elapsed = ((SystemClock.uptimeMillis() - streamingStartUptimeMs) % seq.durationMs)
                        .toInt()
                    view.showStreamingFrame(
                        movie = seq.movie,
                        elapsedMs = elapsed,
                        outW = seq.width,
                        outH = seq.height
                    )
                    handler.postDelayed(this, STREAMING_TICK_MS.toLong())
                }
            }
        }
    }

    fun attach(view: FloatBallGifView) {
        gifView = view
    }

    fun setSequence(seq: FloatBallGifFrameDecoder.Sequence?) {
        stop()
        // 位图归 FloatBallGifSequenceCache 所有，可能被别处共享，这里只能松手不能 recycle。
        sequence = seq
        frameIndex = 0
        streamingStartUptimeMs = SystemClock.uptimeMillis()
        seq?.let { showFirstFrame(it) }
    }

    fun setPaused(pause: Boolean) {
        if (paused == pause) return
        paused = pause
        gifView?.setAnimating(!pause && sequence != null)
        if (pause) {
            handler.removeCallbacks(tickRunnable)
        } else {
            streamingStartUptimeMs = SystemClock.uptimeMillis()
            start()
        }
    }

    fun start() {
        if (paused || sequence == null) return
        gifView?.setAnimating(true)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
        gifView?.setAnimating(false)
    }

    fun release() {
        stop()
        sequence = null
        gifView?.clearFrame()
        gifView = null
    }

    private fun showFirstFrame(seq: FloatBallGifFrameDecoder.Sequence) {
        val view = gifView ?: return
        when (seq) {
            is FloatBallGifFrameDecoder.Sequence.Cached -> {
                val first = seq.frames.firstOrNull()?.bitmap ?: return
                view.showCachedFrame(first)
            }
            is FloatBallGifFrameDecoder.Sequence.Streaming -> {
                view.showStreamingFrame(
                    movie = seq.movie,
                    elapsedMs = 0,
                    outW = seq.width,
                    outH = seq.height
                )
            }
        }
    }

    companion object {
        internal const val STREAMING_TICK_MS = 66

        /** 球不可见时的探测周期；恢复可见后由下一次探测自动继续播放。 */
        private const val HIDDEN_RECHECK_MS = 1_000L
    }
}
