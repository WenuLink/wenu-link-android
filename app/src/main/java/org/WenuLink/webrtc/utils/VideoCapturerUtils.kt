package org.WenuLink.webrtc.utils

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.webrtc.JavaI420Buffer
import org.webrtc.NV12Buffer
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

fun getBufferNV12(
    mediaFormat: MediaFormat,
    videoBuffer: ByteBuffer,
    width: Int,
    height: Int
): NV12Buffer {
    // NV12 Buffer
    return NV12Buffer(
        width,
        height,
        mediaFormat.getInteger(MediaFormat.KEY_STRIDE),
        mediaFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT),
        videoBuffer,
        null
    )
}

fun getBufferI420(
    mediaFormat: MediaFormat,
    videoBuffer: ByteBuffer,
    width: Int,
    height: Int
): JavaI420Buffer? {
    // I420 Buffer
    val yStride = mediaFormat.getInteger(MediaFormat.KEY_STRIDE)
    val sliceHeight = mediaFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT)

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

fun VideoCapturer.videoBuffer2VideoFrame(
    mediaFormat: MediaFormat,
    videoBuffer: ByteBuffer,
    width: Int,
    height: Int
): VideoFrame? {
    val timestampNS = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
    // Check the color format. Could create more cases if needed
    val colorFormat = mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
    val procBuffer = when (colorFormat) {
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        -> getBufferNV12(mediaFormat, videoBuffer, width, height)

        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
        -> getBufferI420(mediaFormat, videoBuffer, width, height)

        else -> return null
    }
    return VideoFrame(procBuffer, 0, timestampNS)
}
