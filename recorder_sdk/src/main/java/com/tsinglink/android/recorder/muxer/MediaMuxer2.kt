package com.tsinglink.android.recorder.muxer


import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.tsinglink.android.recorder.BuildConfig
import com.tsinglink.android.recorder.CHANNEL_ID_RECORDING
import com.tsinglink.android.recorder.EncodeWorker
import com.tsinglink.android.recorder.RecorderOption
import com.tsinglink.android.recorder.battery.DefaultGetBatteryLevel
import com.tsinglink.android.recorder.battery.IGetBatteryLevel
import com.tsinglink.android.recorder.consumer.AudioProcess
import com.tsinglink.android.recorder.consumer.VideoProcess
import com.tsinglink.android.recorder.listener.OnRecordStatusChangeListener
import timber.log.Timber

class MediaMuxer2(
    private val context: Context,
    private val option: RecorderOption,
    var listener: OnRecordStatusChangeListener
) : VideoProcess, AudioProcess {
    private var videoWorker: EncodeWorker? = null

    //    private AACEncodeWorker audioWorker;
    private var mOriginalWidth = 0
    private var mOriginalHeight = 0
    private var stopped = false
    private var sample = 0
    private var channel = 0
    private var bitsPerSample = 0
    private var onAudioStartCalled = false
    private var bl = DefaultGetBatteryLevel(context)

    var stopByLowPower = false
    override fun onVideo(data: ByteArray, length: Int, timestampNanos: Long): Int {
        if (option.isStopByLowPowerEnable) {
            val currentBatteryLevel = bl!!.getBatteryLevel()
            if (currentBatteryLevel <= option.batteryThresholdStopRecording) {
                if (!stopByLowPower) {
                    stopByLowPower = true
                }
                Timber.w(
                    "video producer，battery is too low[%d]。ready to stop recording。",
                    currentBatteryLevel
                )
                return ERROR_LOW_POWER
            }
        }
        return videoWorker!!.onVideo(data, length, timestampNanos)
    }

    fun stop() {
        if (stopped) return
        Timber.i("Video producers ready to stop.")
        videoWorker!!.onStop()
        videoWorker = null
        stopped = true
    }

    override fun onVideoStart(width: Int, height: Int): Int {
        mOriginalWidth = width
        mOriginalHeight = height
        stopByLowPower = false
        Timber.i("Video producers ready to start：onVideoStart（%d,%d）", width, height)
        synchronized(this) {
            if (videoWorker == null) {
                if (onAudioStartCalled) {
                    initWorker(width, height, sample, channel, bitsPerSample)
                }
                return 0
            }
        }
        return -1
    }

    override fun onAudioStart(sample: Int, channel: Int, bitPerSample: Int) {
        this.sample = sample
        this.channel = channel
        bitsPerSample = bitPerSample
        onAudioStartCalled = true
        synchronized(this) {
            if (videoWorker == null) {
                if (mOriginalWidth != 0 && mOriginalHeight != 0) {
                    initWorker(mOriginalWidth, mOriginalHeight, sample, channel, bitsPerSample)
                }
            }
        }
    }

    override fun onAudio(data: ShortArray, length: Int, timestampNanos: Long): Int {
        val worker = videoWorker
        if (worker == null) {
            Timber.w("videoWorker is null.ignore to write Audio.")
            return 0
        }
        return worker.onAudio(data, length, timestampNanos)
    }

    private fun initWorker(width: Int, height: Int, sample: Int, channel: Int, bitPerSample: Int) {
        if (stopped) {
            stopped = false
        }
        if (videoWorker != null) {
            Timber.w("videoWorker re-init!")
            check(!BuildConfig.DEBUG) { "videoWorker re-init!" }
        }
        videoWorker = EncodeWorker(context, option, listener)
        videoWorker!!.onStart(width, height, sample, channel)
    }


    companion object {
        const val ERROR_LOW_STORAGE_SPACE = -2
        const val ERROR_LOW_POWER = -3
        private const val TAG = "MediaMuxer2"
    }
}