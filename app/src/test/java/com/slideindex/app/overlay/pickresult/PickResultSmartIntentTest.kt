package com.slideindex.app.overlay.pickresult

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 取词面板的智能 chip：一段中文简介里的多条链接都要出现，
 * 且同域名（例如三条 t.co 短链）不能被去重成一条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PickResultSmartIntentTest {

    @Test
    fun `three short links on the same host all become chips`() {
        val text = "调查记者，主持人。油管频道地址：https://t.co/WfO3vZ0ygY?amp=1，" +
            "Facebook地址：https://t.co/QO4mprdzUL，TikTok地址：https://t.co/chl5sh7Cfa"

        val urls = PickResultSmartParser.parseSmartEntities(text)
            .filterIsInstance<PickResultSmartEntity.UrlEntity>()

        assertEquals(
            listOf(
                "https://t.co/WfO3vZ0ygY?amp=1",
                "https://t.co/QO4mprdzUL",
                "https://t.co/chl5sh7Cfa",
            ),
            urls.map { it.url },
        )
        assertEquals(
            listOf("t.co/WfO3vZ0ygY", "t.co/QO4mprdzUL", "t.co/chl5sh7Cfa"),
            urls.map { it.label },
        )
        assertEquals(urls.size, urls.map { it.entityKey }.distinct().size)
    }

    @Test
    fun `single url keeps host-only label`() {
        val urls = PickResultSmartParser.parseSmartEntities("官网 https://example.com/path")
            .filterIsInstance<PickResultSmartEntity.UrlEntity>()

        assertEquals(listOf("example.com"), urls.map { it.label })
    }
}
