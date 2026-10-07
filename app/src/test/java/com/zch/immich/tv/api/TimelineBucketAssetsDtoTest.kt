package com.zch.immich.tv.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 回归测试：/timeline/bucket 是「列式」响应，字段类型必须和 Immich 实际返回的严格一致。
 *
 * 踩过一次坑：ratio 在 JSON 里是数字（[0.751, 0.563]），
 * 之前误声明成 List<String>，导致整个 bucket 反序列化抛异常，
 * 相册永远停在「正在加载照片…」。
 */
class TimelineBucketAssetsDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `columnar payload with numeric ratio and integer offsets deserializes`() {
        // 下面这段是按 Immich v3 真实返回抄的（已去掉 city / country / thumbhash 等无关列）
        val raw = """
            {
              "duration":[null,"00:00:54.702"],
              "id":["a8ed97d4-f6cb-4255-bd75-fe3adce44e16","7ce57c1e-b57d-4361-9797-58072654f8bb"],
              "visibility":["timeline","timeline"],
              "isFavorite":[false,false],
              "isImage":[true,false],
              "isTrashed":[false,false],
              "livePhotoVideoId":[null,null],
              "fileCreatedAt":["2026-09-03T11:37:37.936","2026-09-03T11:35:51"],
              "localOffsetHours":[8,8],
              "ownerId":["d74268a2-c83d-40f5-80d0-b93f62a78dc0","d74268a2-c83d-40f5-80d0-b93f62a78dc0"],
              "projectionType":[null,null],
              "ratio":[0.751,0.563],
              "status":["active","active"],
              "thumbhash":["aQgKDQKohomX+Ga4ZoVXdxRjYEAG","YBgODAK5B3tZmYZ4mKYIYWFwFg=="]
            }
        """.trimIndent()

        val assets = json.decodeFromString(TimelineBucketAssetsDto.serializer(), raw).toAssets()

        assertEquals(2, assets.size)
        assertEquals("a8ed97d4-f6cb-4255-bd75-fe3adce44e16", assets[0].id)
        assertEquals("IMAGE", assets[0].type)
        assertEquals("VIDEO", assets[1].type)
    }

    @Test
    fun `missing optional columns fall back to the declared defaults`() {
        val assets = json
            .decodeFromString(TimelineBucketAssetsDto.serializer(), """{"id":["only"]}""")
            .toAssets()
        assertEquals(1, assets.size)
        // isImage 缺失时当作图片处理
        assertEquals("IMAGE", assets[0].type)
    }

    @Test
    fun `asset ids are preserved in order`() {
        val raw = """{"id":["3","1","2"],"isImage":[true,true,true]}"""
        val assets = json.decodeFromString(TimelineBucketAssetsDto.serializer(), raw).toAssets()
        assertTrue(assets.map { it.id } == listOf("3", "1", "2"))
    }

    @Test
    fun `declaring ratio as ListOfString fails because the server sends numbers`() {
        // 复现并固化那次崩溃：ratio 在服务器返回里是数字，声明成 List<String> 会让
        // 整个 bucket 反序列化抛异常。正确的做法就是不要声明用不到的列。
        val raw = """{"id":["a8ed97d4"],"ratio":[0.751]}"""
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(WrongRatioDto.serializer(), raw)
        }
    }
}

/** 故意的错误写法，只用来守住上面那条用例（复现历史坑） */
@Serializable
private data class WrongRatioDto(
    @SerialName("id") val id: List<String> = emptyList(),
    @SerialName("ratio") val ratio: List<String> = emptyList(),
)
