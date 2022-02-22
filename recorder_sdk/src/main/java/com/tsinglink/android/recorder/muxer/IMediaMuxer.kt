package com.tsinglink.android.mpu.misc

import java.io.FileDescriptor

interface IMediaMuxer:AutoCloseable {
    fun writeVideoFrame(fd: FileDescriptor, length: Int, flags:Int, timeStampMillis: Long): Int
    fun writeAAC(fd: FileDescriptor, length: Int, timeStampMillis: Long): Int
    fun create(path: String, videoCodecType: Int, width: Int, height: Int, extra: ByteArray, sample: Int, channel: Int, extra2: ByteArray):Int
}
