package org.WenuLink.webrtc.utils

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.webrtc.JavaI420Buffer
import org.webrtc.NV12Buffer
import org.webrtc.VideoFrame

private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

fun getBufferNV12(
    mediaFormat: MediaFormat,
    videoBuffer: ByteBuffer,
    width: Int,
    height: Int
): NV12Buffer = // NV12 Buffer
    NV12Buffer(
        width,
        height,
        mediaFormat.getIntegerOrDefault(MediaFormat.KEY_STRIDE, width),
        mediaFormat.getIntegerOrDefault(MediaFormat.KEY_SLICE_HEIGHT, height),
        videoBuffer,
        null
    )

fun getBufferI420(
    mediaFormat: MediaFormat,
    videoBuffer: ByteBuffer,
    width: Int,
    height: Int
): JavaI420Buffer {
    // I420 Buffer
    val yStride = mediaFormat.getIntegerOrDefault(MediaFormat.KEY_STRIDE, width)
    val sliceHeight = mediaFormat.getIntegerOrDefault(MediaFormat.KEY_SLICE_HEIGHT, height)

    // Y plane size in memory
    val yPlaneSize = yStride * sliceHeight

    // U/V plane stride is typically half
    val uvStride = yStride / 2
    val uvSliceHeight = sliceHeight / 2
    val uvPlaneSize = uvStride * uvSliceHeight

    // Reset position before slicing
    videoBuffer.position(0)
    val yPlane = videoBuffer.slice()
    yPlane.limit(yPlaneSize)

    videoBuffer.position(yPlaneSize)
    val uPlane = videoBuffer.slice()
    uPlane.limit(uvPlaneSize)

    videoBuffer.position(yPlaneSize + uvPlaneSize)
    val vPlane = videoBuffer.slice()
    vPlane.limit(uvPlaneSize)

    // Create JavaI420Buffer directly from slices (zero-copy)
    return JavaI420Buffer.wrap(
        width, height,
        yPlane, yStride,
        uPlane, uvStride,
        vPlane, uvStride,
        null
    )
}

/**
 * Converts a YUV [ByteBuffer] received from [DJICodecManager.YuvDataCallback] into a WebRTC
 * [VideoFrame]. The specific YUV420 color-format constants (Planar / SemiPlanar and their Packed
 * variants) are deprecated in favour of [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible],
 * but real-world codecs, including the one inside DJICodecManager, still report them, so we must
 * keep the branches. Suppressing the warning at function scope rather than per-case to keep the
 * `when`-expression readable.
 */
@Suppress("DEPRECATION")
fun videoBuffer2VideoFrame(
    mediaFormat: MediaFormat,
    videoBuffer: ByteBuffer,
    width: Int,
    height: Int
): VideoFrame? {
    val timestampNS = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
    // Check the color format. Could create more cases if needed
    val colorFormat = mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
    val procBuffer = when (colorFormat) {
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
        -> getBufferNV12(mediaFormat, videoBuffer, width, height)

        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
        -> getBufferI420(mediaFormat, videoBuffer, width, height)

        else -> return null
    }
    return VideoFrame(procBuffer, 0, timestampNS)
}
