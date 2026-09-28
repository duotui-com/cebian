package com.slideindex.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher icons loaded during [AppRepository.loadApps] on a background thread.
 * Overlay code rasterizes to small bitmaps without calling PackageManager on the UI thread.
 */
@Singleton
class AppLaunchIconCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm get() = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 缓存按**字节**算，不再按条目数算。
     *
     * 原实现是 `LruCache<String, Drawable>(最多 384 条)`，而 Flyme/MIUI 的主题图标是
     * 每个约 288×288 的位图（≈330 KB），384 条上限意味着常驻上百 MB——堆快照实测
     * `BitmapDrawable$BitmapState` 一项就有 256 张 / 81 MB。现在统一栅格化成
     * [ICON_CACHE_PX] 的位图并按字节淘汰，两档合计 40 MB 封顶。
     */
    private val drawableCache = object : LruCache<String, Bitmap>(DRAWABLE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val pendingRequests = ConcurrentHashMap.newKeySet<String>()

    fun clear() {
        drawableCache.evictAll()
        bitmapCache.evictAll()
    }

    fun retainPackages(packageNames: Collection<String>) {
        val keep = packageNames.toSet()
        drawableCache.snapshot().keys.filter { it !in keep }.forEach { drawableCache.remove(it) }
        bitmapCache.evictAll()
    }

    fun loadDrawable(applicationInfo: ApplicationInfo) {
        val pkg = applicationInfo.packageName
        if (drawableCache.get(pkg) != null) return
        runCatching {
            val raw = pm.getApplicationIcon(applicationInfo)
            drawableCache.put(pkg, rasterize(raw, ICON_CACHE_PX))
        }
    }

    fun loadDrawable(packageName: String) {
        if (drawableCache.get(packageName) != null) return
        runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            loadDrawable(appInfo)
        }
    }

    /** Returns a cached launcher icon without touching PackageManager. */
    fun peekDrawable(packageName: String): Drawable? {
        val cached = drawableCache.get(packageName) ?: return null
        return BitmapDrawable(context.resources, cached)
    }

    /** Loads from PackageManager when missing, then returns a fresh drawable instance. */
    fun drawableFor(packageName: String): Drawable? {
        if (drawableCache.get(packageName) == null) {
            loadDrawable(packageName)
        }
        return peekDrawable(packageName)
    }

    /** Returns a warmed bitmap without loading from PackageManager. */
    fun peekBitmap(packageName: String, sizePx: Int): Bitmap? {
        val size = sizePx.coerceAtLeast(1)
        return bitmapCache.get(bitmapKey(packageName, size))
    }

    fun bitmapFor(packageName: String, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val key = bitmapKey(packageName, size)
        bitmapCache.get(key)?.let { return it }
        if (drawableCache.get(packageName) == null) {
            loadDrawable(packageName)
        }
        val source = drawableCache.get(packageName)
        val bitmap = if (source != null) rasterizeBitmap(source, size) else createTransparent(size)
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    fun warmBitmapsAsync(packageNames: Collection<String>, sizePx: Int) {
        if (packageNames.isEmpty()) return
        scope.launch {
            warmBitmaps(packageNames, sizePx)
        }
    }

    /**
     * 后台加载单个图标，完成后回到主线程通知调用方重绘。
     *
     * 供浮层绘制路径使用：绘制时只 [peekBitmap]，未命中就提交这里 ——
     * 主线程绝不能同步调 PackageManager（Flyme/MIUI 的主题图标重建会卡到 ANR）。
     * 同一个 size 的重复请求会被合并。
     */
    fun requestBitmapAsync(packageName: String, sizePx: Int, onReady: () -> Unit) {
        if (packageName.isBlank()) {
            onReady()
            return
        }
        val size = sizePx.coerceAtLeast(1)
        val key = bitmapKey(packageName, size)
        if (bitmapCache.get(key) != null) {
            onReady()
            return
        }
        if (!pendingRequests.add(key)) return
        scope.launch {
            withContext(Dispatchers.Default) {
                runCatching { bitmapFor(packageName, size) }
            }
            pendingRequests.remove(key)
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    suspend fun warmBitmaps(packageNames: Collection<String>, sizePx: Int) {
        val size = sizePx.coerceAtLeast(1)
        withContext(Dispatchers.Default) {
            packageNames.forEach { pkg ->
                bitmapFor(pkg, size)
            }
        }
    }

    private fun rasterize(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val d = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bitmap
    }

    private fun rasterizeBitmap(source: Bitmap, size: Int): Bitmap {
        if (source.width == size && source.height == size) {
            return source.copy(Bitmap.Config.ARGB_8888, false) ?: createTransparent(size)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(
            source,
            null,
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return bitmap
    }

    private fun createTransparent(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private fun bitmapKey(packageName: String, sizePx: Int): String = "$packageName\u0000$sizePx"

    private companion object {
        /** 缓存图标边长上限：所有绘制点都在 200px 以内，超过就没必要留原图。 */
        const val ICON_CACHE_PX = 192

        /** 两档缓存都以字节计，合计 40 MB 封顶（原先按条目数算，实测可到上百 MB）。 */
        const val DRAWABLE_CACHE_BYTES = 24 * 1024 * 1024
        const val BITMAP_CACHE_BYTES = 16 * 1024 * 1024
    }
}
