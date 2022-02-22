package com.android.rs.myapplication

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tsinglink.android.recorder.RecorderOption
import com.tsinglink.android.recorder.listener.OnRecordStatusChangeListener
import com.tsinglink.android.recorder.muxer.MediaMuxer2
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.lang.IllegalStateException
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

object Util {
    /* Prepare a dummy image. */
    fun fill_yuv_image(
        data: ByteArray, frame_index: Int,
        width: Int, height: Int
    ) {
        var x: Int
        var y: Int
        val i: Int
        i = frame_index

        /* Y */y = 0
        while (y < height) {
            x = 0
            while (x < width) {
                data[0 + y * width + x] = (x + y + i * 3).toByte()
                x++
            }
            y++
        }

        /* Cb and Cr */y = 0
        while (y < height / 2) {
            x = 0
            while (x < width / 2) {
                data[width * height + y * width / 2 + x] = (128 + y + i * 2).toByte()
                data[width * height + width * height / 4 + y * width / 2 + x] =
                    (64 + x + i * 5).toByte()
                x++
            }
            y++
        }
    }




    fun getMp4DurationSecond(path: String): Long {
        var duration: Long
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val s = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
            duration = s.toLong()/1000
            if (duration <= 0) {
                throw IllegalStateException("$path illegal duration:$duration")
            }
            duration
        } finally {
            mmr.release()
        }
    }

    fun makeABigFile(path: String):File {
        val buf = ByteArray(1024*1024)    // 1m
        var idx = 0
        val folder = File(path,"tmp").apply { mkdirs() }
        val file = File(folder, "thisFileWillBeDeleted")
        FileOutputStream(file).use {
            while (idx++ < 20) {
                it.write(buf)
            }
        }
        return file
    }


    fun initMuxer(app:Application, option: RecorderOption,listener: OnRecordStatusChangeListener): MediaMuxer2 {
        val ffmuxer = MediaMuxer2(app, option, listener)
        // video params.
        ffmuxer.onVideoStart(1920,1080)
        // audio params
        ffmuxer.onAudioStart(16000,1,16)
        return ffmuxer
    }

    fun emitMediaFrames(ffmuxer: MediaMuxer2, durationSeconds:Int, lifecycleOwner: LifecycleOwner? = null):Int{
        // video frame
        val begin = SystemClock.elapsedRealtime()
        var frameIndex = 0
        val thread = thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val data = ByteArray(1920*1080*3/2)
            while (SystemClock.elapsedRealtime() - begin < durationSeconds*1000) {
                if(lifecycleOwner != null && !lifecycleOwner.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)){
                    break
                }
                // make dummy yuv image.see:https://github.com/FFmpeg/FFmpeg/blob/master/doc/examples/muxing.c.
                Util.fill_yuv_image(data,frameIndex++, 1920,1080)
                var i = ffmuxer.onVideo(data, data.size, SystemClock.elapsedRealtimeNanos())
                if (i < 0){
                    break
                }
                Thread.sleep(30)
            }
        }
        val thread2 = thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val pcmGen = PCMGen(16000, 1)
            // audio frame,100 ms pcm data.  1ms=>16 short,100 * 16 = 1600 short
            val pcm = ShortArray(960)   // 16000hz,channel :1
            while (SystemClock.elapsedRealtime() - begin < durationSeconds*1000) {
                if(lifecycleOwner != null && !lifecycleOwner.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)){
                    break
                }
                // make dummy pcm buffer.see:https://github.com/FFmpeg/FFmpeg/blob/master/doc/examples/muxing.c.
                pcmGen.fill_pcm(pcm)
                var i = ffmuxer.onAudio(pcm,pcm.size, SystemClock.elapsedRealtimeNanos())
                if (i < 0){
                    break
                }
                val measureTimeMillis = measureTimeMillis {
                    Thread.sleep(60)
                }
//                Timber.i("sleep 100 spend:$measureTimeMillis")
            }
        }
        thread.join()
        thread2.join()
        return 0
    }
}