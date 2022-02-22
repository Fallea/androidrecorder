package com.tsinglink.android.recorder.consumer

/**
 * Created by Administrator on 2015/10/13.
 */
interface VideoProcess {
    fun onVideoStart(width: Int, height: Int): Int
    fun onVideo(data: ByteArray, length: Int, timestampNanos: Long): Int
}