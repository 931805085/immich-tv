package com.zch.immich.tv.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

// ---------- DTO（字段按 Immich v3 精简，多余字段由 ignoreUnknownKeys 忽略） ----------

@Serializable
data class AssetDto(
    @SerialName("id") val id: String,
    /** "IMAGE" | "VIDEO" */
    @SerialName("type") val type: String = "IMAGE",
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
    @SerialName("duration") val duration: String? = null,
    /** 拍摄时间（本地时间，ISO 格式如 2026-08-30T12:43:47.942），时间线按天分组用 */
    @SerialName("fileCreatedAt") val fileCreatedAt: String? = null,
    /** 本地时区相对 UTC 的小时偏移 */
    @SerialName("localOffsetHours") val localOffsetHours: Int? = null,
)

/** GET /shared-links/me 返回体里的相册信息 */
@Serializable
data class SharedAlbumDto(
    @SerialName("id") val id: String = "",
    @SerialName("albumName") val albumName: String = "",
    @SerialName("albumThumbnailAssetId") val albumThumbnailAssetId: String? = null,
)

/** GET /shared-links/me 返回体（公开共享链接信息，无需登录） */
@Serializable
data class SharedLinkDto(
    @SerialName("id") val id: String = "",
    @SerialName("key") val key: String = "",
    /** "ALBUM" | "INDIVIDUAL" */
    @SerialName("type") val type: String = "ALBUM",
    @SerialName("album") val album: SharedAlbumDto? = null,
    /** 单资产类共享会带 assets；相册类共享在 v3 里为空，需走 timeline 接口枚举 */
    @SerialName("assets") val assets: List<AssetDto> = emptyList(),
    @SerialName("description") val description: String? = null,
    /** ISO 时间；非空说明共享链接有有效期 */
    @SerialName("expiresAt") val expiresAt: String? = null,
)

/** GET /timeline/buckets 返回体（时间桶，用于枚举相册里的照片） */
@Serializable
data class TimelineBucketDto(
    @SerialName("timeBucket") val timeBucket: String = "",
    @SerialName("count") val count: Int = 0,
    @SerialName("total") val total: Int = 0,
)

/**
 * GET /timeline/bucket 返回体（列式结构，每个数组下标对应一张照片）。
 *
 * 只声明真正会用到的字段，且类型必须和 Immich 返回的 JSON 严格一致：
 * 实测 ratio 是数字（[0.751, 0.563]）、localOffsetHours 是整数，
 * 之前把 ratio 声明成 List<String> 会导致整个 bucket 反序列化直接抛异常，
 * 相册永远停在「正在加载照片…」。不用的字段不声明，避免类型漂移再踩一次。
 */
@Serializable
data class TimelineBucketAssetsDto(
    @SerialName("id") val id: List<String> = emptyList(),
    @SerialName("isImage") val isImage: List<Boolean> = emptyList(),
    @SerialName("fileCreatedAt") val fileCreatedAt: List<String> = emptyList(),
    @SerialName("localOffsetHours") val localOffsetHours: List<Int> = emptyList(),
) {
    /** 把列式数据转成 [AssetDto] 列表 */
    fun toAssets(): List<AssetDto> =
        id.mapIndexed { i, assetId ->
            AssetDto(
                id = assetId,
                type = if (isImage.getOrElse(i) { true }) "IMAGE" else "VIDEO",
                fileCreatedAt = fileCreatedAt.getOrNull(i),
                localOffsetHours = localOffsetHours.getOrNull(i),
            )
        }
}

// ---------- Retrofit 接口（共享链接 = 公开访问，无需鉴权） ----------

interface ImmichApi {

    /** 获取共享链接信息（key 就是链接里 /share/ 后面的那一段） */
    @GET("shared-links/me")
    suspend fun getSharedLinkMe(@Query("key") key: String): SharedLinkDto

    /** 相册共享：列出照片的时间桶（按月分组） */
    @GET("timeline/buckets")
    suspend fun getTimelineBuckets(
        @Query("albumId") albumId: String,
        @Query("key") key: String,
    ): List<TimelineBucketDto>

    /** 相册共享：取某个时间桶里的照片（列式） */
    @GET("timeline/bucket")
    suspend fun getTimelineBucket(
        @Query("albumId") albumId: String,
        @Query("timeBucket") timeBucket: String,
        @Query("key") key: String,
    ): TimelineBucketAssetsDto
}