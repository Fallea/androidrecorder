package com.tsinglink.android.recorder.muxer

import com.tsinglink.android.library.muxer.FFMediaMuxer
import com.tsinglink.android.mpu.misc.IMediaMuxer
import com.tsinglink.android.recorder.muxer.MediaMuxer2
import java.io.FileDescriptor
import java.lang.IllegalArgumentException

class FFmpegIMediaMuxer : IMediaMuxer {

    val muxer: FFMediaMuxer = FFMediaMuxer();

    override fun writeVideoFrame(fd: FileDescriptor, length: Int, flags:Int, timeStampMillis: Long): Int {
        if (fd == null) throw IllegalArgumentException("fd is null")
        return muxer.writeFrameFromFD(fd, 0, length, timeStampMillis, 0)
    }

    override fun writeAAC(fd: FileDescriptor, length: Int, timeStampMillis: Long): Int {
        if (fd == null) throw IllegalArgumentException("fd is null")
        return muxer.writeAACFromFD(fd, length, timeStampMillis)
    }

    override fun create(path: String, videoCodecType: Int, width: Int, height: Int, extra: ByteArray, sample: Int, channel: Int, extra2: ByteArray):Int {
        if (extra.isEmpty()){
            throw IllegalArgumentException("video extra invalid.")
        }
        if (sample != 0){
            if (extra2.isEmpty()){
                throw IllegalArgumentException("audio extra invalid.")
            }
        }
        return muxer.create(path, videoCodecType, width, height, extra, sample, channel, extra2)
    }

    override fun close() {
        muxer.close()
    }
}