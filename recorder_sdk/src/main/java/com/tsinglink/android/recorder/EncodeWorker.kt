package com.tsinglink.android.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import com.tsinglink.android.library.YuvLib
import com.tsinglink.android.recorder.codec.CodecInfo
import com.tsinglink.android.recorder.codec.selectCodec
import com.tsinglink.android.recorder.listener.OnRecordStatusChangeListener
import com.tsinglink.android.recorder.muxer.MediaMuxer2.Companion.ERROR_LOW_STORAGE_SPACE
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EncodeWorker(
    private val context: Context,
    private val option: RecorderOption,
    private val listener: OnRecordStatusChangeListener
) : Thread("EncodeWorker") {
    private var mColorFormat: Int = 0
    private var path: String = ""

    //        private MediaMuxer2 muxer;
    var isMuxerCreated = false
        private set
    private var mExit = false

    //        private int[] mSize = new int[2];
    //        private ByteBuffer[] inputBuffers;
    //        private ByteBuffer[] outputBuffers;
    @Volatile
    private var videoCodec: MediaCodec? = null
    private val videoCodecLock = ByteArray(0)
    var pcmBuf = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    private val mBufferInfo = MediaCodec.BufferInfo()
    private var mClipYUV: ByteArray = ByteArray(0)
    private var mConvertYUV: ByteArray = ByteArray(0)
    private var codecInfo: CodecInfo? = null

    var mWidth = 0
    var mHeight = 0
    val transId: Int
    private var taskDone = false
    private var recordLogMills = 0L


    @Volatile
    private var changeFileByExternal = false
    private var extra = ByteArray(0)
    private var extra2 = ByteArray(0)
    private var audioMeta: MediaFormat? = null
    private var videoMeta: MediaFormat? = null

    @Volatile
    private var audioCodec: MediaCodec? = null

    private val audioCodecLock = ByteArray(0)
    private var sample = 0
    private var channel = 0
    fun onStart(width: Int, height: Int, sample: Int, channel: Int): Int {
        mWidth = width
        mHeight = height
        this.sample = sample
        this.channel = channel
        mClipYUV = ByteArray(mWidth * mHeight * 3 / 2)
        mConvertYUV = ByteArray(mWidth * mHeight * 3 / 2)
        //            initMediaMuxer(mCodec.getOutputFormat());
        Timber.i("onVideoStart width=$width height=$height")
        if (width > 0 && height > 0) {
            start()
            Timber.i(
                "transId: %d, video start，Resolution：%dx%d, segment duration：%d min",
                transId,
                mWidth,
                mHeight,
                option.recordSpanMinutes
            )
        } else {
            Timber.w(
                "transId: %d, Recording does not start. Because the resolution is not normal...%dX%d",
                transId,
                width,
                height
            )
        }
        return 0
    }

    override fun run() {
        super.run()
        var throwable:Throwable? = null
        try {
            val spec = initService(transId, context,option)
            waitService(spec)
            Timber.i("getVideoCodecMIME(%d,%d)=%s", mWidth, mHeight, option.videoCodecMIME)
            createMediaCodecs(option.videoCodecMIME)
            var outputBuffers = videoCodec!!.outputBuffers
            val audioOutputBuffers = audioCodec!!.outputBuffers
            do {
                var begin = SystemClock.elapsedRealtime()
                var timeSpend: Long = 0
                val outputBufferIndex =
                    videoCodec!!.dequeueOutputBuffer(mBufferInfo, 0) //拿到输出缓冲区的索引
                timeSpend = SystemClock.elapsedRealtime() - begin
                if (timeSpend > 100) {
                    Timber.w("avc/hevc dequeueOutputBuffer spend too much time...%d ms", timeSpend)
                }
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = videoCodec!!.outputBuffers
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = videoCodec!!.outputFormat
                    var sps = outputFormat.getByteBuffer("csd-0")!!
                    if (option.videoCodecMIME == MediaFormat.MIMETYPE_VIDEO_AVC) {
                        var pps = outputFormat.getByteBuffer("csd-1")!!
                        var sps_size = 0
                        var pps_size = 0
                        if (sps != null) {
                            sps_size = sps.remaining()
                        } else {
                            sps = ByteBuffer.allocate(0)
                        }
                        if (pps != null) {
                            pps_size = pps.remaining()
                        } else {
                            pps = ByteBuffer.allocate(0)
                        }
                        extra = ByteArray(sps_size + pps_size)
                        sps[extra, 0, sps_size]
                        pps[extra, sps_size, pps_size]
                    } else if (option.videoCodecMIME == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                        val sps_size = sps.remaining()
                        extra = ByteArray(sps_size)
                        sps[extra]
                    }
                    videoMeta = outputFormat
                    if (audioMeta != null) startMediaMuxer()
                } else if (outputBufferIndex >= 0) {  //编码成功了.
                    try {
                        var outputBuffer: ByteBuffer?
                        outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            videoCodec!!.getOutputBuffer(outputBufferIndex)
                        } else {
                            outputBuffers[outputBufferIndex] //outputBuffer保存的就是H264数据了
                        }
                        if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { // sps
                            mBufferInfo.size = 0
                        }
                        if (mBufferInfo.size != 0) {
                            outputBuffer!!.position(mBufferInfo.offset)
                            outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size)
                            //                                int bps = calculator.getBitrate(mBufferInfo);
                            onVideoEncoded(outputBuffer, mBufferInfo)
                        }
                    } finally {
                        videoCodec!!.releaseOutputBuffer(outputBufferIndex, false) //释放资源
                    }
                }
                begin = SystemClock.elapsedRealtime()
                val aacOutputBufferIndex = audioCodec!!.dequeueOutputBuffer(mBufferInfo, 0)
                val timeSpend2 = SystemClock.elapsedRealtime() - begin
                if (timeSpend2 > 200) {
                    Timber.w("aac dequeueOutputBuffer spend too much time....%d ms", timeSpend2)
                }
                if (aacOutputBufferIndex >= 0) {
                    try {
                        var outputBuffer: ByteBuffer?
                        outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            audioCodec!!.getOutputBuffer(aacOutputBufferIndex)
                        } else {
                            audioOutputBuffers[aacOutputBufferIndex] //outputBuffer保存的就是H264数据了
                        }
                        if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { // sps
                            mBufferInfo.size = 0
                        }
                        if (mBufferInfo.size != 0) {
                            outputBuffer!!.position(mBufferInfo.offset)
                            outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size)
                            onAudioEncoded(outputBuffer, mBufferInfo)
                        }
                    } finally {
                        audioCodec!!.releaseOutputBuffer(aacOutputBufferIndex, false) //释放资源
                    }
                } else if (aacOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioMeta = audioCodec!!.outputFormat
                    val sps = audioMeta!!.getByteBuffer("csd-0")
                    val sps_size = sps!!.remaining()
                    extra2 = ByteArray(sps_size)
                    sps[extra2]
                    if (videoMeta != null) startMediaMuxer()
                }
                if (aacOutputBufferIndex < 0 && outputBufferIndex < 0) {
                    if (timeSpend2 + timeSpend < 10) {
                        sleep(5)
                        //                            Timber.d("sleep...");
                    } else {
//                            Timber.d("no sleep...");
                    }
                }
            } while (!mExit)
        } catch (ex: Throwable) {
            if (ex !is InterruptedException) {
                Timber.w(ex, "The producer stopped abnormally?exit=%s", mExit)
            }
            if (!mExit) {
                throwable = ex
                throw ex
            }
        } finally {
            listener?.onRecordStop(path,throwable)
            stopMediaMuxer()
            releaseService(transId, context)
            synchronized(videoCodecLock) {
                try {
                    if (videoCodec != null) {
                        videoCodec!!.release()
                    }
                } catch (e: Exception) {
                }
                videoCodec = null
            }
            synchronized(audioCodecLock) {
                try {
                    if (audioCodec != null) {
                        audioCodec!!.release()
                    }
                } catch (e: Exception) {
                }
                audioCodec = null
            }
            Timber.w("The producer thread is stopped and the resource is released")
        }
    }

    var lastLoggingMillis = 0L
    private var mLowSpaceAlert = false
    fun onVideo(data: ByteArray, length: Int, timestamp: Long): Int {
        if (!isAlive) {
            Timber.w("The consumer thread is not running or has exited. The producer is ready to stop...")
            return -1
        }
        if (mLowSpaceAlert) {
            mLowSpaceAlert = false
            //            if (recordReady)
//                AppContext.getInstance().bus.post(new RecordingStoppedByLowStorage());
            Timber.w("Video producer, storage space is too low. Ready to stop recording.")
            return ERROR_LOW_STORAGE_SPACE
        }
        synchronized(videoCodecLock) {
            try {
                if (videoCodec == null) return 0
                val beginMillis = SystemClock.elapsedRealtime()
                val inputBufferIndex = videoCodec!!.dequeueInputBuffer(0)
                if (inputBufferIndex >= 0) {
                    val inputBuffer: ByteBuffer?
                    inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        videoCodec!!.getInputBuffer(inputBufferIndex)
                    } else {
                        val inputBuffers = videoCodec!!.inputBuffers
                        inputBuffers[inputBufferIndex]
                    }
                    inputBuffer!!.clear()
                    var length = data.size
                    if (mColorFormat == CodecCapabilities.COLOR_FormatYUV420PackedPlanar ||
                        mColorFormat == CodecCapabilities.COLOR_FormatYUV420Planar) {
                        inputBuffer.put(data, 0, length)
                    } else {
                        YuvLib.ConvertFromI420(data, mConvertYUV, mWidth, mHeight, 3)
                        inputBuffer.put(mConvertYUV, 0, length)
                    }
                    videoCodec!!.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        length,
                        timestamp / 1000,
                        0
                    ) // , needKeyFrm
                } else {
                    if (SystemClock.elapsedRealtime() - lastLoggingMillis >= 10000) {
                        lastLoggingMillis = SystemClock.elapsedRealtime()
                        Timber.i(
                            "dequeueInputBuffer return %d after %dms",
                            inputBufferIndex,
                            SystemClock.elapsedRealtime() - beginMillis
                        )
                    }
                }
            } catch (e: Throwable) {
                Timber.w(
                    e,
                    "The video producer encountered an exception. The video is about to stop."
                )
                return -1
            }
            return 0
        }
    }

    fun onAudio(pcm: ShortArray?, length: Int, timeStampNano: Long): Int {
        if (!isAlive) {
            Timber.w("The consumer thread is not running or has exited. The producer is ready to stop...")
            return -1
        }
        if (videoMeta == null) {
            Timber.w("Video meta data not gotten.ignore audio.")
            return 0
        }
        synchronized(audioCodecLock) {
            try {
                val mCodec = audioCodec ?: return 0
                val inputBufferIndex = mCodec.dequeueInputBuffer(100)
                if (inputBufferIndex >= 0) {
                    if (pcmBuf.capacity() != length) {
                        pcmBuf = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
                    }
                    pcmBuf.asShortBuffer().put(pcm, 0, length)
                    pcmBuf.clear()
                    val inputBuffer: ByteBuffer?
                    inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mCodec.getInputBuffer(inputBufferIndex)
                    } else {
                        val inputBuffers = mCodec.inputBuffers
                        inputBuffers[inputBufferIndex]
                    }
                    inputBuffer!!.clear()
                    inputBuffer.put(pcmBuf)
                    mCodec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        length * 2,
                        timeStampNano / 1000,
                        0
                    ) // , needKeyFrm
                }
            } catch (e: Throwable) {
                Timber.w(
                    e,
                    "The video producer encountered an exception. The audio is about to be uninstalled."
                )
                return -1
            }
            return 0
        }
    }

    fun onStop() {
        Timber.i("call VideoStop。actively stop the consumer thread[%s]", this)
        val beginMillis = SystemClock.elapsedRealtime()
        mExit = true
        interrupt()
        while (true) {
            try {
                join()
                break
            } catch (e: InterruptedException) {
//                    e.printStackTrace();
            }
        }
        Timber.i(
            "consumer thread[%s] end (%dms).",
            this,
            SystemClock.elapsedRealtime() - beginMillis
        )
    }

    @Throws(IOException::class)
    private fun createMediaCodecs(mime: String) {
        val codecInfo = try {
            Timber.i("selectCodec video codec %s", mime)
            selectCodec(mime)
        } catch (ex: IOException) {
            Timber.e(ex)
            throw ex
        }
        run {
            Timber.i("initing default video codec:%s", codecInfo.name)
            val mediaCodec = MediaCodec.createByCodecName(codecInfo.name)
            val videoFormat = MediaFormat.createVideoFormat(mime, mWidth, mHeight)
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, option.bitsPerSecond)
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, option.frameRate)
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, codecInfo.colorFormat)
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, option.iFrameInterval)
            // 这个选项加上会导致 26 设备录像一闪一闪的.
//            videoFormat.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, 1);
            Timber.i("video codec:mediaFormat = $videoFormat")
            mediaCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()
            mColorFormat = codecInfo.colorFormat
            videoCodec = mediaCodec
            Timber.i("video codec:%s ok", codecInfo.name)
        }
        run {
            Timber.i("initing default audio codec:%s", MediaFormat.MIMETYPE_AUDIO_AAC)
            val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sample, channel)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 16000)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920) // 1920 = 16k的采样,60毫秒长度.
            Timber.i("audio codec:mediaFormat = $format")
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()
            Timber.i("audio codec:%s ok", MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec = mediaCodec
        }
    }

    fun stopMediaMuxer() {
        val beginMillis = SystemClock.elapsedRealtime()
        Timber.i("stopMediaMuxer from [%s]", currentThread())
        stopMuxer(transId)
        Timber.i("stopMediaMuxer spend:%dms", SystemClock.elapsedRealtime() - beginMillis)
    }

    fun setExit(bExit: Boolean) {
        mExit = bExit
    }

    var buf = ByteArray(0)
    var recordReady = false
    fun startMediaMuxer() {
        val opt = option
        val param = CreateParam(opt).also {
            it.width = mWidth
            it.height = mHeight
            it.channel = 1
            it.extra = extra
            it.extra2 = extra2
            it.sample = sample
        }
        val path = createMuxer(transId, param)
        isMuxerCreated = path != ""
        check(isMuxerCreated) { String.format("muxer create error! ") }
        this.path = path
        listener.onRecordStart(path)
        Timber.i(
            "RecordStat change.dir:%s,recording:true,time:%d",
            option.dir,
            SystemClock.elapsedRealtime()
        )
        //                                liveData.postValue(new RecordStat(path, true, SystemClock.elapsedRealtime()));
    }

    private fun onVideoEncoded(outputBuffer: ByteBuffer, bi: MediaCodec.BufferInfo) {
        outputBuffer.clear()
        outputBuffer.position(bi.offset)
        outputBuffer.limit(bi.offset + bi.size)
        val requestChangFile = changeFileByExternal
        val (r, newPath, segmentDone, taskDone1, lowSpace) = writeVideoFrame(
            transId,
            outputBuffer,
            bi,
            requestChangFile
        )
        if (requestChangFile) {
            changeFileByExternal = false
            Timber.i("Change files externally! The recording service will switch files in the future.")
        }
        if (r < 0) Timber.i("writeVideoFrame return:%d", r)
        if (System.currentTimeMillis() - recordLogMills > 10 * 1000) {
            recordLogMills = System.currentTimeMillis()
            Timber.i(
                "Writing VideoFrame of stamp:%d size:%d flags:%d return:%d...",
                bi.presentationTimeUs / 1000,
                bi.size,
                bi.flags,
                r
            )
        }
        if (segmentDone) {
            listener?.onRecordStop(path)
            path = newPath
            listener?.onRecordStart(newPath)
            Timber.i("Replacing files during recording！")
        }
        if (lowSpace) {
            mLowSpaceAlert = true
            Timber.w("A low storage capacity alarm is received. Recording is about to stop. . .")
        }
        if (taskDone1) {
            // 录像完成.
            taskDone = true
            Timber.w("Recording is received. About to stop recording。。。")
        }
    }

    private fun onAudioEncoded(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        writeAudioFrame(transId, buffer, info)
        if (System.currentTimeMillis() - recordLogMills > 10 * 1000) {
            recordLogMills = System.currentTimeMillis()
            Timber.i(
                "Writing AudioFrame of stamp:%d length: %d",
                info.presentationTimeUs / 1000,
                info.size
            )
        }
    }

    companion object {
        private const val MIME_TYPE_VIDEO_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_TYPE_VIDEO_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    }

    init {
        transId = hashCode()
    }
}