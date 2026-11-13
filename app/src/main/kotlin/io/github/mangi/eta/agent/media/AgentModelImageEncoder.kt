package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal const val MAX_AGENT_IMAGE_BYTES = 12 * 1024 * 1024
internal const val CHAT_PREVIEW_MAX_LONG_EDGE = 1_920

/** 用户附件视觉发送档的上限：长边与像素。超限即降级，不看字节数。 */
internal const val USER_ATTACHMENT_MAX_LONG_EDGE = 1_600
internal const val USER_ATTACHMENT_MAX_PIXELS = 1_500_000L

/**
 * 触发降级的像素门槛（约 400 万）。
 *
 * 定在 4MP 而不是 1.5MP：竖长截图（如 873×1920）像素低但长边高，按长边判会白白丢掉 17% 清晰度；
 * 而相机照片 4000×3000 = 1200 万像素远超此线，正是需要降级的那一类。
 */
internal const val USER_ATTACHMENT_DOWNSCALE_PIXELS = 4_000_000L

/**
 * 用户附件（相机/相册）的输入上限：40MiB。发送前经 userAttachmentProfile 降到 ≤12MiB，
 * 所以输入可以放宽——否则「一张 15MiB 的照片」在进入编码器前就被整张拒绝。
 */
internal const val MAX_USER_ATTACHMENT_INPUT_BYTES = 40 * 1024 * 1024

/** 仅负责为工具截图和聊天预览生成独立的图片副本。 */
internal object AgentModelImageEncoder {
    private const val MODEL_JPEG_QUALITY = 95
    private const val PREVIEW_JPEG_QUALITY = 88

    private data class EncodingProfile(
        val format: Bitmap.CompressFormat,
        val mimeType: String,
        val quality: Int,
        val maxLongEdge: Int? = null,
        val maxPixels: Long? = null,
    )

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class TargetSize(val width: Int, val height: Int)

    private val screenProfile = EncodingProfile(
        format = Bitmap.CompressFormat.PNG,
        mimeType = "image/png",
        quality = 100,
    )
    /**
     * 保持原始像素尺寸的附件档（已编码附件的跨进程传输用）。
     *
     * 有回归测试断言 2400×1600 不被缩放（[io.github.mangi.eta.agent.media.AgentImageCodecTest]）。
     * 用户附件不要走这里，见 [userAttachmentProfile]。
     */
    private val attachmentProfile = EncodingProfile(
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = MODEL_JPEG_QUALITY,
    )
    // 聊天图片支持点击全屏缩放查看文字细节（如聊天记录与终端长截图），保留高清边长上限。

    /**
     * 用户附件的视觉发送档：长边 ≤1600px、总像素 ≤150 万（超过就按需采样降级）。
     *
     * ★ 不能复用 [attachmentProfile] / [toolVisionProfile]：那些档要求保持原尺寸，而这里必须降级。
     * 历史 bug 就是复用导致的：scale 恒为 1.0、采样比恒为 1，文档写着「按需采样解码到 1600px」
     * 实际从不降级，超大附件整张原样发出，到 Provider 那里被拒或静默丢。
     */
    private val userAttachmentProfile = EncodingProfile(
        maxLongEdge = USER_ATTACHMENT_MAX_LONG_EDGE,
        maxPixels = USER_ATTACHMENT_MAX_PIXELS,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = MODEL_JPEG_QUALITY,
    )
    private val previewProfile = EncodingProfile(
        maxLongEdge = CHAT_PREVIEW_MAX_LONG_EDGE,
        maxPixels = 1_800_000L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = PREVIEW_JPEG_QUALITY,
    )
    private val toolVisionProfile = EncodingProfile(
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = MODEL_JPEG_QUALITY,
    )

    fun attachment(
        bytes: ByteArray,
        source: String,
        mimeHint: String,
    ): AgentModelClient.ModelImage? {
        if (!bytes.hasSupportedImageMagic()) return null
        val bounds = inspectBounds(bytes, mimeHint)
        val encoded = transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = bounds,
            profile = attachmentProfile,
        )
        // 普通附件保持 JPEG 兼容路径；截图不经过这里的解码与重编码。
        return encoded
    }

    fun screen(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        // Bitmap 尚无文件编码，仅序列化一次 PNG；不缩放、不铺底、不转换像素。
        encodeBitmap(bitmap, source, screenProfile, flattenAlpha = false).copy(preserveOriginal = true)

    /** 文件工具图片在发送模型前统一编码为 JPEG。 */
    fun toolVision(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage? = runCatching {
        if (!bytes.hasSupportedImageMagic()) return@runCatching null
        transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBounds(bytes, mimeHint),
            profile = toolVisionProfile,
        )
    }.getOrNull()

    /**
     * 用户附件是否超出视觉发送档，需要走降级编码。
     *
     * 只看字节数的判定漏掉了最常见的一种：手机相机 4000×3000 的 JPEG 通常只有 3–6MiB，
     * 字节数远低于 12MiB，却按原图整张发出（Provider 侧像素超限、请求体虚高）。
     * 这里按总像素兜住：4MP 以下原样保真，之上降到 [userAttachmentProfile]。
     * 不按长边判——竖长截图（873×1920）像素很低，按长边判会白丢清晰度。
     */
    fun exceedsUserAttachmentLimits(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.size > MAX_AGENT_IMAGE_BYTES) return true
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return false
        return options.outWidth.toLong() * options.outHeight > USER_ATTACHMENT_DOWNSCALE_PIXELS
    }

    /**
     * 用户附件（相机/相册）统一走视觉尺寸发送档：输入可能超过 12MiB，按需采样解码到 1600px，
     * 避免超大原图撑大请求体，也避免因单张超过 12MiB 而整张失败。
     */
    fun userAttachment(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage? = runCatching {
        if (bytes.isEmpty()) return@runCatching null
        if (!bytes.hasSupportedImageMagic()) return@runCatching null
        transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBoundsLoose(bytes, mimeHint),
            profile = userAttachmentProfile,
        )
    }.getOrNull()

    fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = runCatching {
        val width = image.width ?: return@runCatching null
        val height = image.height ?: return@runCatching null
        val target = targetSize(width, height, previewProfile)
        val openStream = referenceStreamFactory(context, image.reference) ?: return@runCatching null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        try {
            encodeBitmap(decoded, image.source, previewProfile, flattenAlpha = true)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }.getOrNull()

    private fun transcodeBytes(
        bytes: ByteArray,
        source: String,
        bounds: ImageBounds,
        profile: EncodingProfile,
    ): AgentModelClient.ModelImage? {
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val target = bounds.targetSize(profile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        // 解码不应用 EXIF 方向：竖拍照片原样重编码会躺着。降级路径必须自己转正。
        val oriented = applyOrientation(bitmap, readExifOrientation(bytes))
        return try {
            encodeBitmap(
                bitmap = oriented,
                source = source,
                profile = profile,
                flattenAlpha = profile.format == Bitmap.CompressFormat.JPEG &&
                    !bounds.mimeType.equals("image/jpeg", ignoreCase = true),
            )
        } finally {
            if (!oriented.isRecycled && oriented !== bitmap) oriented.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** EXIF 方向；读不到或格式不支持时按正常方向处理。 */
    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 按 EXIF 方向转正位图；已经是正常方向时原样返回同一个对象（不额外拷贝）。
     */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            // 5/7 是带镜像的 90°/270°，与 6/8 区分开，否则会左右颠倒。
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        source: String,
        profile: EncodingProfile,
        flattenAlpha: Boolean,
    ): AgentModelClient.ModelImage {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        val encodedBitmap = renderBitmap(
            bitmap = bitmap,
            target = targetSize(bitmap.width, bitmap.height, profile),
            flattenAlpha = flattenAlpha,
        )
        try {
            val initialCapacity = minOf(
                encodedBitmap.width * encodedBitmap.height / 4,
                2 * 1024 * 1024,
            ).coerceAtLeast(32 * 1024)
            val output = ByteArrayOutputStream(initialCapacity)
            check(encodedBitmap.compress(profile.format, profile.quality, output)) {
                "图片编码失败"
            }
            val encoded = output.toByteArray()
            require(encoded.isNotEmpty() && encoded.size <= MAX_AGENT_IMAGE_BYTES) {
                "图片数据过大：${encoded.size}"
            }
            return AgentModelClient.ModelImage(
                reference = "data:${profile.mimeType};base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
                mimeType = profile.mimeType,
                bytes = encoded.size,
                width = encodedBitmap.width,
                height = encodedBitmap.height,
                source = source,
            )
        } finally {
            if (encodedBitmap !== bitmap && !encodedBitmap.isRecycled) {
                encodedBitmap.recycle()
            }
        }
    }

    private fun renderBitmap(
        bitmap: Bitmap,
        target: TargetSize,
        flattenAlpha: Boolean,
    ): Bitmap {
        if (
            target.width == bitmap.width &&
            target.height == bitmap.height &&
            !flattenAlpha
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            if (flattenAlpha) canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, target.width, target.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            output.setHasAlpha(false)
        }
    }

    /** 用户附件的边界探测：输入上限放宽到 [MAX_USER_ATTACHMENT_INPUT_BYTES]。 */
    private fun inspectBoundsLoose(bytes: ByteArray, mimeHint: String): ImageBounds {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_USER_ATTACHMENT_INPUT_BYTES) { "图片数据过大：${bytes.size}" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun inspectBounds(bytes: ByteArray, mimeHint: String): ImageBounds {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "图片数据过大：${bytes.size}" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun ImageBounds.targetSize(profile: EncodingProfile): TargetSize =
        targetSize(width, height, profile)

    private fun targetSize(
        width: Int,
        height: Int,
        profile: EncodingProfile,
    ): TargetSize {
        if (width <= 0 || height <= 0) return TargetSize(width, height)
        var scale = profile.maxLongEdge
            ?.let { limit -> min(1.0, limit.toDouble() / maxOf(width, height)) }
            ?: 1.0
        val scaledPixels = width.toDouble() * height * scale * scale
        profile.maxPixels?.let { limit ->
            if (scaledPixels > limit) {
                scale *= sqrt(limit / scaledPixels)
            }
        }
        return TargetSize(
            width = (width * scale).toInt().coerceIn(1, width),
            height = (height * scale).toInt().coerceIn(1, height),
        )
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        target: TargetSize,
    ): Int {
        var sample = 1
        while (sample <= 64) {
            val next = sample * 2
            val nextWidth = width.toDouble() / next
            val nextHeight = height.toDouble() / next
            if (
                nextWidth < target.width * 0.9 ||
                nextHeight < target.height * 0.9
            ) {
                break
            }
            sample = next
        }
        return sample
    }

    private fun referenceStreamFactory(
        context: Context,
        reference: String,
    ): (() -> InputStream)? {
        val trimmed = reference.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            val decoded = Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            if (decoded.isEmpty() || decoded.size > MAX_AGENT_IMAGE_BYTES) return null
            return { ByteArrayInputStream(decoded) }
        }
        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return {
                context.contentResolver.openInputStream(uri)
                    ?: error("无法打开本地图片")
            }
        }
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            null, "" -> File(trimmed)
            else -> null
        } ?: return null
        if (!file.isFile) return null
        return { file.inputStream() }
    }
}

internal fun String.normalizedAgentImageMimeType(): String =
    substringBefore(';')
        .trim()
        .takeIf {
            it.startsWith("image/", ignoreCase = true) &&
                !it.equals("image/*", ignoreCase = true)
        }
        ?: "image/jpeg"

internal fun ByteArray.hasSupportedImageMagic(): Boolean {
    if (size < 8) return false
    if (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return true
    }
    if (
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII)
        return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    }
    return false
}
