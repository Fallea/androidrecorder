package com.tsinglink.android.recorder

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.*
import android.os.IBinder.FIRST_CALL_TRANSACTION
import android.text.TextUtils
import android.util.SparseArray
import androidx.core.app.NotificationCompat
import androidx.core.util.valueIterator
import com.tsinglink.android.library.ErrorCodeException
import com.tsinglink.android.library.MemFile
import com.tsinglink.android.library.muxer.FFMediaMuxer.*
import com.tsinglink.android.mpu.misc.IMediaMuxer
import com.tsinglink.android.recorder.muxer.FFmpegIMediaMuxer
import com.tsinglink.android.recorder.notification.createNotificationChannel
import timber.log.Timber
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

val CODE_START_MUXER = FIRST_CALL_TRANSACTION + 10
val CODE_WRITE_FRAME = FIRST_CALL_TRANSACTION + 11
val CODE_STOP_MUXER = FIRST_CALL_TRANSACTION + 12

const val CHANNEL_ID_RECORDING = "recording"


class CreateParam(var option: RecorderOption? = null) : Parcelable {
    var width: Int = 0
    var height: Int = 0
    var sample: Int = 0
    var channel: Int = 0
    var extra: ByteArray = ByteArray(0)
    var extra2: ByteArray = ByteArray(0)

    constructor(parcel: Parcel) : this() {
        width = parcel.readInt()
        height = parcel.readInt()
        sample = parcel.readInt()
        channel = parcel.readInt()
        extra = parcel.createByteArray()!!
        extra2 = parcel.createByteArray()!!
        option = parcel.readParcelable(RecorderOption.javaClass.classLoader)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeInt(sample)
        parcel.writeInt(channel)
        parcel.writeByteArray(extra)
        parcel.writeByteArray(extra2)
        parcel.writeParcelable(option,flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun toString(): String {
        return "CreateParam(width=$width, height=$height, sample=$sample, channel=$channel, videoCodecMIME=${option!!.videoCodecMIME}, extra=${extra.contentToString()}, extra2=${extra2.contentToString()})"
    }

    companion object CREATOR : Parcelable.Creator<CreateParam> {
        override fun createFromParcel(parcel: Parcel): CreateParam {
            return CreateParam(parcel)
        }

        override fun newArray(size: Int): Array<CreateParam?> {
            return arrayOfNulls(size)
        }
    }
}

class WriteReplay() : Parcelable {
    var newPath: String = ""
    var writeCode: Int = 0
    var segmentDone: Boolean = false
    var taskDone:Boolean = false
    var lowStorage: Boolean = false

    constructor(parcel: Parcel) : this() {
        newPath = parcel.readString()!!
        writeCode = parcel.readInt()
        segmentDone = parcel.readByte() != 0.toByte()
        taskDone = parcel.readByte() != 0.toByte()
        lowStorage = parcel.readByte() != 0.toByte()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(newPath)
        parcel.writeInt(writeCode)
        parcel.writeByte(if (segmentDone) 1 else 0)
        parcel.writeByte(if (taskDone) 1 else 0)
        parcel.writeByte(if (lowStorage) 1 else 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<WriteReplay> {
        override fun createFromParcel(parcel: Parcel): WriteReplay {
            return WriteReplay(parcel)
        }

        override fun newArray(size: Int): Array<WriteReplay?> {
            return arrayOfNulls(size)
        }
    }
}

class WriteParam() : Parcelable {
    //    AVMEDIA_TYPE_VIDEO, buffer, 0, info.size, info.presentationTimeUs / 1000
    var frameType: Int = 0
    var fdUsed:Boolean = false
    lateinit var pfd: ParcelFileDescriptor
    var size: Int = 0
    var timeStampleUs: Long = 0L
    var frameFlags: Int = 0

    var requestToChangeFile: Boolean = false

    constructor(parcel: Parcel) : this() {
        frameType = parcel.readInt()
        fdUsed = parcel.readByte() != 0.toByte()
        pfd = parcel.readParcelable(ParcelFileDescriptor::class.java.classLoader)!!
        size = parcel.readInt()
        timeStampleUs = parcel.readLong()
        frameFlags = parcel.readInt()
        requestToChangeFile = parcel.readByte() != 0.toByte()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(frameType)
        parcel.writeByte(if (fdUsed) 1 else 0)
        parcel.writeParcelable(pfd, flags)
        parcel.writeInt(size)
        parcel.writeLong(timeStampleUs)
        parcel.writeInt(frameFlags)
        parcel.writeByte(if (requestToChangeFile) 1 else 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<WriteParam> {
        override fun createFromParcel(parcel: Parcel): WriteParam {
            return WriteParam(parcel)
        }

        override fun newArray(size: Int): Array<WriteParam?> {
            return arrayOfNulls(size)
        }
    }
}


data class WriteFrameResult(var code: Int = 0,
                            var newPath: String = "",
                            var segmentDone: Boolean = false,
                            var taskDone:Boolean = false,
                            var lowSpace: Boolean = false)

val muxerConn = SparseArray<FFMuxerConSpec>()
const val USE_SHARED_MEM = false

class FFMuxerConSpec(val transId:Int){

    internal var sGetFileDescriptorMethod: Method? = null

    @SuppressLint("SoonBlockedPrivateApi")
    internal fun getFD(): ParcelFileDescriptor {
        if (USE_SHARED_MEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            mMapping?.apply {
                SharedMemory.unmap(this)
            }
            mMapping = null

            if (sGetFileDescriptorMethod == null)   // TODO 通过native层获取fd.
                sGetFileDescriptorMethod = SharedMemory::class.java.getDeclaredMethod("getFileDescriptor")
            val fileDescriptor = sGetFileDescriptorMethod!!.invoke(mSharedMemory) as FileDescriptor
            // 序列化，才可传送
            return ParcelFileDescriptor.dup(fileDescriptor)
        }else {
            val mf: MemoryFile = memoryFile!!
            if (sGetFileDescriptorMethod == null)
                sGetFileDescriptorMethod = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            val fileDescriptor = sGetFileDescriptorMethod!!.invoke(mf) as FileDescriptor
            // 序列化，才可传送
            return ParcelFileDescriptor.dup(fileDescriptor)
        }
    }

    val lock = java.lang.Object()
    var binder: IBinder? = null
//    Build.VERSION_CODES.O_MR1以下.
    var memoryFile:MemoryFile? = null
//    Build.VERSION_CODES.O_MR1 以上
    var mSharedMemory:SharedMemory? = null
    var mMapping:ByteBuffer? = null

    var initing = false

    private var videoFolderPath:String = ""
    set(value) {
        if (field != value){
            Timber.i("VideoFolderPath set from $field to $value")
        }
        field = value
    }

    var lastGetVideoFolderPathMillis = 0L;

    fun closeShareMemory(){
        if (USE_SHARED_MEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            mMapping?.apply {
                SharedMemory.unmap(this)
            }
            mMapping = null
            mSharedMemory?.close()
        }else {
            memoryFile?.close()
        }
    }

    fun newShareMemory(size:Int){
        if (USE_SHARED_MEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            mSharedMemory = SharedMemory.create("ffmuxer",size)
            mMapping = mSharedMemory!!.mapReadWrite()
        }else {
            memoryFile = MemoryFile("ffmuxer", size)
        }
    }

    fun writeBuffer(buf:ByteBuffer, info:MediaCodec.BufferInfo):ParcelFileDescriptor{
        buf.limit(info.offset + info.size)
        buf.position(info.offset)
        if (USE_SHARED_MEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1){
            var sm = mSharedMemory!!
            if (sm.size < info.size) {
                Timber.w("re-init sharedMemory due short length:${sm.size} need:${info.size}")
                closeShareMemory()
                newShareMemory(info.size)
            }
            var maping = mMapping!!
            maping.position(0)
            maping.put(buf)
        }else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                return MemFile().getFd(buf, info.size)
            }
            var mf = memoryFile!!
            if (mf.length() < info.size) {
                Timber.w("re-init memoryFile due short length:${mf.length()} need:${info.size}")
                closeShareMemory()
                newShareMemory(info.size)
            }
            mf = memoryFile!!
            val tmp = ByteArray(info.size)
            buf.get(tmp)
            mf.writeBytes(tmp, 0, 0, info.size)
        }
        return getFD()
    }

    val conn: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (FFMuxerService::class.java.name == name.className) {
                Timber.i("FFMuxerService onServiceConnected.service:$service ComponentName:$name...")
                binder = service
                closeShareMemory()
                newShareMemory(20000)
                synchronized(lock) {
                    lock.notify()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (FFMuxerService::class.java.name == name.className) {
                Timber.i("FFMuxerService onServiceDisconnected ComponentName:$name...")
                memoryFile?.close()
                memoryFile = null
            }
        }
    }
}

fun waitService(muxCon:FFMuxerConSpec): Boolean {
    Timber.i("waitService...")
    val begin = SystemClock.elapsedRealtime()
    while (muxCon.binder == null && muxCon.initing) {
        synchronized(muxCon.lock) {
            muxCon.lock.wait(10)
        }
        if (SystemClock.elapsedRealtime() - begin > 5000) {
            throw TimeoutException("waiting service timeout.On some devices, the multi-process service startup is slower. Please try to modify to single process, by remove android:process line of FFMuxerService in AndroidManifest.xml")
        }
    }
    Timber.i("waitService done.binder:${muxCon.binder} initing:${muxCon.initing}...")
    return muxCon.initing
}

fun initService(transId: Int, ctx: Context,option: RecorderOption):FFMuxerConSpec{
    if (option.showNotification){
        createNotificationChannel(ctx)
    }
    val muxCon = synchronized(muxerConn){
        if (muxerConn[transId] != null){
            throw java.lang.IllegalStateException("muxer conn already create!")
        }
        FFMuxerConSpec(transId).apply {
            muxerConn.put(transId, this)
        }
    }
    val ffmuxerService = Intent(ctx, FFMuxerService::class.java)
    ffmuxerService.putExtra("showNotification",option.showNotification)
    if (option.showNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(ffmuxerService)
    } else {
        ctx.startService(ffmuxerService)
    }
    val bind = ctx.bindService(ffmuxerService, muxCon.conn, Service.BIND_AUTO_CREATE or
            Service.BIND_ABOVE_CLIENT or Service.BIND_IMPORTANT or Service.BIND_INCLUDE_CAPABILITIES)
    Timber.i("bindToService result:$bind")
    muxCon.initing = true
    return muxCon
}

fun releaseService(transId: Int, ctx: Context) {
    val muxCon = synchronized(muxerConn){
        muxerConn[transId]?.apply {
            muxerConn.remove(transId)
        }
    }?:return
    Timber.i("releaseService called. initing=${muxCon.initing}")
    if (!muxCon.initing) {
        Timber.w("releaseService already called without init called. ignore this call")
//        if (BuildConfig.DEBUG) throw IllegalStateException("releaseService already called without init called. ignore this call")
        return
    }
    ctx.unbindService(muxCon.conn)
    muxCon.binder = null
    muxCon.initing = false
    muxCon.memoryFile?.close()
    muxCon.memoryFile = null
    if (muxerConn.size() == 0){
        val ffmuxerService = Intent(ctx, FFMuxerService::class.java)
        ctx.stopService(ffmuxerService)
    }
}

fun createMuxer(transId: Int,createParam: CreateParam): String {
    Timber.i("createMuxer called. transId:$transId, createParam=$createParam")
    val muxCon = muxerConn[transId]?:throw java.lang.IllegalStateException("muxer conn not create!")
    val replay = Parcel.obtain()
    val create = Parcel.obtain()
    create.writeInt(transId)
    create.writeParcelable(createParam, 0)
    val ok = if (muxCon.binder != null) muxCon.binder!!.transact(CODE_START_MUXER, create, replay, 0)
    else false
    if (ok){
        return replay.readString()!!
    }else{
        throw java.lang.IllegalStateException("start muxer failed.transact return false.")
    }
}

fun stopMuxer(transId:Int) {
    Timber.i("stopMuxer called. transId:$transId")
    val muxCon = muxerConn[transId]?:throw java.lang.IllegalStateException("muxer conn not create!")
    val replay = Parcel.obtain()
    val obtain = Parcel.obtain()
    obtain.writeInt(transId)
    muxCon.binder?.transact(CODE_STOP_MUXER, obtain, replay, 0)
}

var lastPrintLogMillis = 0L
fun writeVideoFrame(transId: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo,
                    requestChangeFile: Boolean = false): WriteFrameResult {
    var imp: WriteFrameResult;
    val measureTimeMillis = measureTimeMillis {
        imp = writeVideoFrameImp(transId, info, buffer, requestChangeFile)
    }
    if (measureTimeMillis > 100) Timber.w("writeVideo[$transId] spend too much time:$measureTimeMillis")
    return imp
}

private fun writeVideoFrameImp(transId: Int, info: MediaCodec.BufferInfo, buffer: ByteBuffer,
                               requestChangeFile: Boolean): WriteFrameResult {
    val muxCon = muxerConn[transId]?:throw java.lang.IllegalStateException("muxer conn not create!")
    val param = WriteParam()
    var writeResult = WriteFrameResult()
    param.pfd = muxCon.writeBuffer(buffer,info)
    try {
        param.frameType = AVMEDIA_TYPE_VIDEO
        param.size = info.size
        param.timeStampleUs = info.presentationTimeUs
        param.frameFlags = info.flags
        param.requestToChangeFile = requestChangeFile

        val paramArg = Parcel.obtain()
        val replay = Parcel.obtain()

        paramArg.writeInt(transId)
        paramArg.writeParcelable(param, 0)
        val success = muxCon.binder!!.transact(CODE_WRITE_FRAME, paramArg, replay, 0)
        if (success) {
            val result = replay.readParcelable<WriteReplay>(WriteReplay::class.java.classLoader)!!
            val code = result.writeCode
            val newPath = result.newPath
            if (SystemClock.elapsedRealtime() - lastPrintLogMillis >= 10000) {
                lastPrintLogMillis = SystemClock.elapsedRealtime()
                Timber.i("write frame to binder code:$code, done:${result.segmentDone}")
            }
            if (code != 0) {
                Timber.w("ffmuxer:Failed to write video frame：$code")
                if (code == -5) {
                    // 可能 TF 卡被拔了.
                    Timber.w("write frame return -5. maybe TF card was removed.")
                    throw ErrorCodeException(-5)
                }
            }
            writeResult.code = code
            writeResult.newPath = newPath
            writeResult.segmentDone = result.segmentDone
            writeResult.taskDone = result.taskDone
            writeResult.lowSpace = result.lowStorage
            if (result.lowStorage) {
                Timber.w("Not enough storage space...")
            }
        } else {
            Timber.w("ffmuxer:Failed to write video frame：binder transact failed.")
            Timber.w("frameType=${param.frameType} size=${param.size} pfd=${param.pfd}")
            throw IllegalStateException("binder transact failed")
        }
        paramArg.recycle()
        replay.recycle()
    } finally {
        param.pfd.close()
    }
    return writeResult
}


fun writeAudioFrame(transId: Int, buffer: ByteBuffer, info:MediaCodec.BufferInfo) {
    val measureTimeMillis = measureTimeMillis {
        writeAudioFrameMillis(transId, buffer, info)
    }
    if (measureTimeMillis > 100) Timber.w("writeAudioFrame[$transId] spend too much time:$measureTimeMillis")
}

private fun writeAudioFrameMillis(transId:Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    val muxCon = muxerConn[transId]?:throw java.lang.IllegalStateException("muxer conn not create!")

    val param = WriteParam()
    param.pfd = muxCon.writeBuffer(buffer,info)
    try {
        param.frameType = AVMEDIA_TYPE_AUDIO
        param.size = info.size
        param.timeStampleUs = info.presentationTimeUs

        val paramArg = Parcel.obtain()
        val replay = Parcel.obtain()

        paramArg.writeInt(transId)
        paramArg.writeParcelable(param, 0)
        val success = muxCon.binder!!.transact(CODE_WRITE_FRAME, paramArg, replay, 0)
        if (success) {
            val result = replay.readParcelable<WriteReplay>(WriteReplay::class.java.classLoader)!!
            val code = result.writeCode
            val newPath = result.newPath
            if (!TextUtils.isEmpty(newPath)) {
            }
            if (code != 0) {
                Timber.w("ffmuxer:Failed to write audio frame：$code")
                if (code == -5) {
                    // 可能 TF 卡被拔了.
                    Timber.w("write frame return -5. maybe TF card was removed.")
                    throw ErrorCodeException(-5)
                }
            }
        } else {
            Timber.w("ffmuxer:Failed to write audio frame：binder transact failed.")
            throw IllegalStateException("binder transact failed")
        }
        paramArg.recycle()
        replay.recycle()
    } finally {
        param.pfd?.close()
    }
}


fun deleteOldVideo(path: String, needToAvailableBytes: Long) {
    // 1 get video folder
    var f = File(path)
    while (true) {
        if (f.name == "video") {
            break
        }
        if (f.parentFile == null) {
            break
        }
        f = f.parentFile
    }

    val files = f.listFiles()?: emptyArray()
    files.sortBy { it.name }

    val statFs = StatFs(path)
    var availableBytes = statFs.availableBytes
    if (availableBytes > needToAvailableBytes) {
        return
    }
    Timber.i("delete old video from ${f.path}  to free at least ${needToAvailableBytes / 1024 / 1024}M. now available ${availableBytes / 1024 / 1024}M")
    loop@ for (dateFolder in files) {
        val videos = dateFolder.listFiles { v ->
            v.isFile && v.path != path
        }?: emptyArray()
        videos.sortBy { v -> v.name }
        for (v in videos) {
            v.delete()
            statFs.restat(path)
            availableBytes = statFs.availableBytes
            Timber.i("delete one file[${v.name}].availableBytes come to ${availableBytes / 1024 / 1024}M need ${needToAvailableBytes / 1024 / 1024}M.")
            if (availableBytes > needToAvailableBytes) {
                break@loop
            }
        }
    }
    Timber.i("delete old video done")
}

fun deleteOldVideo2(path: String, option: RecorderOption) {
    try {
        val toBytes = option.recordThresholdMB * 2 * 1024 * 1024.toLong()
        val statFs = StatFs(option.rootDirToFreeSpace)
        var availableBytes = statFs.availableBytes
        if (availableBytes > toBytes) {
            return
        }
        // 1 get video folder
        var f = File(option.rootDirToFreeSpace)
        val fl = ArrayList<File>()
        f.walkBottomUp()
                .forEach {
                    if (it.isFile) {
                        when {
                            it == File(path) -> {
                                Timber.i("deleteFilesTo to $toBytes ignore itself $it")
                                // ignore recording file.
                            }
                            else -> {
                                fl.add(it)
                            }
                        }
                    }
                }
        fl.sortBy { it.lastModified() }
        var needToFreeBytes = toBytes - availableBytes
        Timber.i("need to free total:${needToFreeBytes / 1024 / 1024}MB")
        while (needToFreeBytes > 0 && fl.isNotEmpty()) {
            val f = fl[0]
            if (f.name.startsWith("IMP")){
                Timber.i("recover file, ignore imp file.${f.name}")
                fl.removeAt(0)
                continue
            }
            needToFreeBytes -= f.length()
            Timber.i("delete $f free ${f.length() / 1024 / 1024}MB need to free total:${needToFreeBytes / 1024 / 1024}MB")
            f.delete()
            fl.removeAt(0)
        }
    } catch (e: Throwable) {
        Timber.e(e)
    }
}

class FFMuxerService : Service() {

    val muxers:SparseArray<MuxerSpec> = SparseArray()
    val binder = MyBinder()
    @Override
    override fun onBind(intent: Intent): IBinder {
        Timber.i("service bind to:$intent")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.i("service unbind to:$intent")
//        muxer?.close()
        // 把时间存下来，查询的时候需要。
        muxers.valueIterator().forEach {
            it.apply {
                muxer?.close()
                Timber.i("muxer of transId:$transId,path:${path} closed.duration:${(SystemClock.elapsedRealtime() - mStartMillis) / 1000 / 60}min")
            }
        }
        muxers.clear()
        stopSelf()
        return false
    }

    val info: ActivityManager.RunningAppProcessInfo = ActivityManager.RunningAppProcessInfo()
    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount()<1) {
            Timber.plant(Timber.DebugTree())
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent.getBooleanExtra("showNotification",false)){
            startForeground(1, NotificationCompat.Builder(this, CHANNEL_ID_RECORDING)
                .setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle(resources.getString(R.string.recording)).build())
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    var lastPrintStorageMillis = 0L
    var lastPrintStorageMillis1 = 0L
    var lastCheckStorageMillis = 0L

    inner class MuxerSpec(val transId: Int){
        fun createMuxer(createParam: CreateParam) {
            this.createParam = createParam
            val muxer2 = FFmpegIMediaMuxer()

            val r = with(createParam) {
                val opt = option!!
                val path = File(opt.dir,"${SimpleDateFormat(opt.fileNamePatten).format(Date())}.mp4").path
                this@MuxerSpec.path = path
                val videoCodecType = if (opt.videoCodecMIME == MediaFormat.MIMETYPE_VIDEO_HEVC) VIDEO_CODEC_TYPE_H265 else VIDEO_CODEC_TYPE_H264
                muxer2.create(path, videoCodecType, width, height, extra, sample, channel, extra2)
            }
            if (r != 0) {
                Timber.e("muxer of transId:$transId create muxer2 $createParam error:$r")
                throw RemoteException(String.format("muxer create error! result= %d ", r))
            } else {
                Timber.i("muxer of transId:$transId create muxer2 $createParam ok:${path}")
            }
            mStartMillis = SystemClock.elapsedRealtime()
            frameIndex = 0
            writeBytes = 0L
            muxer = muxer2
        }

        var muxer: IMediaMuxer? = null
        var requestChangeFile: Boolean = false
        var mStartMillis: Long = SystemClock.elapsedRealtime()
        var path:String = ""
        var writeBytes: Long = 0L
        var frameIndex = 0
        lateinit var createParam: CreateParam
    }

    fun MuxerSpec.writeFrame(writeParam: WriteParam, writeReplay: WriteReplay) {
        val recordThresholdMB = createParam.option!!.recordThresholdMB
        if (!requestChangeFile) {
            requestChangeFile = writeParam.requestToChangeFile
            if (requestChangeFile) {
                Timber.i("The caller requests to switch the recording.")
            }
        }

        val muxer = muxer!!
        val recordSpanMillis = createParam.option!!.recordSpanMinutes * 60000
        val deleteOldToFreeSpace = createParam.option!!.deleteFilesToFreeSpace
        if (writeParam.frameFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && writeParam!!.frameType == AVMEDIA_TYPE_VIDEO) {
//                        Timber.d("write a key frame")
        }
        if (writeParam.fdUsed){
            throw java.lang.IllegalStateException("fd already used.")
        }
        val r = if (writeParam!!.frameType == AVMEDIA_TYPE_VIDEO){
            muxer.writeVideoFrame(writeParam.pfd.fileDescriptor, writeParam.size, writeParam.frameFlags, writeParam.timeStampleUs / 1000)
        }else{
            muxer.writeAAC(writeParam.pfd.fileDescriptor, writeParam.size, writeParam.timeStampleUs / 1000)
        }
        writeParam.fdUsed = true
        if (r < 0) {
            Timber.w("muxer of transId:$transId write Frame:${writeParam!!.frameType}.keyFrame=${writeParam!!.frameType} return:$r")
            writeReplay.writeCode = r
        } else if (r == 0) {
            writeBytes += writeParam.size
            frameIndex++
        }

        if (lastCheckStorageMillis == 0L ||
            SystemClock.elapsedRealtime() - lastCheckStorageMillis >= 15000) {      // 15秒检查一下存储状态
            lastCheckStorageMillis = SystemClock.elapsedRealtime()
            val statFs = StatFs(path)
            var availableBytes = statFs.availableBytes
//            StorageSpaceLiveData.postValue(Pair(availableBytes, statFs.totalBytes))
            if (availableBytes < (recordThresholdMB * 1024 * 1024L)) {
                Timber.i("muxer of transId:$transId available bytes of $path is $availableBytes.very less...")
                if (deleteOldToFreeSpace) {
                    Timber.i("muxer of transId:$transId available bytes of $path is $availableBytes.very less... delete old video...")
                    deleteOldVideo2(path, createParam.option!!)
                    Timber.i("muxer of transId:$transId available bytes of $path is $availableBytes.very less... delete old video... done. try restat")
                    statFs.restat(path)
                    Timber.i("muxer of transId:$transId after restat.available bytes of $path is $availableBytes.")
                    availableBytes = statFs.availableBytes
//                    StorageSpaceLiveData.postValue(Pair(availableBytes, statFs.totalBytes))
                } else {
                    Timber.i("muxer of transId:$transId cover old file not enable post low storage to caller。。。")
                }
            }
            if (availableBytes < (recordThresholdMB * 1024 * 1024L)) {
                Timber.i("muxer of transId:$transId available bytes of $path is $availableBytes.very less... post lowStorage. will soon stopped by the caller.")
                writeReplay.lowStorage = true
            }

            if (SystemClock.elapsedRealtime() - lastPrintStorageMillis >= 30000) {
                lastPrintStorageMillis = SystemClock.elapsedRealtime()
                Timber.i("Remaining space capacity：$availableBytes byte，${availableBytes / 1024 / 1024}M,${availableBytes * 1.0f / 1024 / 1024 / 1024}G")
            }
            Timber.i("number of frames written:$frameIndex, total size$writeBytes,Time from creation to present:${(SystemClock.elapsedRealtime() - mStartMillis) / 1000}s")
        }
        if (r == -27) {
            Timber.i("muxer of transId:$transId see -27 File too large. will switch file ASAP")
        }
        if (writeBytes >= 4290000000) {
            Timber.w("muxer of transId:$transId file too large. will switch file ASAP.")
        }
        if (writeParam.frameType == AVMEDIA_TYPE_VIDEO &&
                        (writeBytes >= 4290000000/*4294967295*/ || r == -27 || requestChangeFile
                        || SystemClock.elapsedRealtime() - mStartMillis >= recordSpanMillis)) { // 换文件时，等到一个关键帧的时候再换。
            //  new file...
            if (writeParam.frameFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                Timber.i("muxer of transId:$transId key frame.switching...")
                writeReplay.segmentDone = true
                muxer.close()
                requestChangeFile = false

                Timber.w("Recorder switch to new file. oldPath:$path,duration:${(SystemClock.elapsedRealtime() - mStartMillis)/1000} seconds")
                createMuxer(createParam)
                Timber.w("Recorder switch to new file. newPath:$path")

                writeReplay.newPath = path
                var r = muxer.writeVideoFrame(writeParam.pfd.fileDescriptor, writeParam.size, writeParam.frameFlags, writeParam.timeStampleUs / 1000)
                if (r < 0) {
                    Timber.i("muxer of transId:$transId,path:${path} write Frame:${writeParam!!.frameType} return:$r")
                    if (r == -28) {
                        val statFs = StatFs(path)
                        var availableBytes = statFs.availableBytes
                        Timber.e("Recording encountered -28 error！NOT ENOUGH STORAGE SPACE. availableBytes=$availableBytes, bufferSize=${writeParam.size}。POSSIBLE FILE IS CORRUPTED？$path close muxer and prompt the low storage")
                        muxer.close()
                        writeReplay.lowStorage = true
                    }
                    writeReplay.writeCode = r
                } else {
                    writeBytes += writeParam.size
                    frameIndex++
                }
            }
        }
    }


    inner class MyBinder : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val transId = data.readInt()
            return try {
                doTransact(transId, code, data, reply, flags)
            } catch (e: Throwable) {
                muxers[transId]?.muxer?.close()
                muxers.remove(transId)
                Timber.e(e, "muxer of transId:$transId Recording process is abnormal...")
                false
            }
        }

        fun doTransact(transId: Int, code: Int, data: Parcel?, reply: Parcel?, flags: Int): Boolean {
            if (SystemClock.elapsedRealtime() - lastPrintStorageMillis1 >= 30000) {
                ActivityManager.getMyMemoryState(info)
                Timber.i("muxer of transId:$transId app info.lastTrimLevel = ${info.lastTrimLevel};importance =${info.importance};importanceReason=${info.importanceReasonCode}; importanceReasonComponent = ${info.importanceReasonComponent}")
                lastPrintStorageMillis1 = SystemClock.elapsedRealtime();
            }
            when (code) {
                CODE_START_MUXER -> {
                    if (muxers[transId] != null) throw java.lang.IllegalStateException("spec of $transId already created!")
                    val spec = MuxerSpec(transId)
                    val createParam: CreateParam = data?.readParcelable(CreateParam::class.java.classLoader)?: throw RemoteException("create param was null.")
                    spec.createMuxer(createParam)
                    reply?.writeString(spec.path!!)
                    muxers.put(transId, spec)
                }
                CODE_WRITE_FRAME -> {
                    val spec = muxers[transId]?:throw java.lang.IllegalStateException("spec of $transId not created!")
                    var writeReplay = WriteReplay()
                    val writeParam = data!!.readParcelable<WriteParam>(WriteParam::class.java.classLoader)!!
                    try {
                        spec.writeFrame(writeParam,writeReplay)
                    }finally {
                        writeParam.pfd.close()
                    }
                    reply?.writeParcelable(writeReplay, 0)
                }
                CODE_STOP_MUXER -> {
                    muxers[transId].apply {
                        muxer?.close()
                        muxers.remove(transId)
                        reply?.writeString(path)
                        Timber.i("muxer of transId:$transId,path:${path} closed.duration:${(SystemClock.elapsedRealtime() - mStartMillis) / 1000} seconds")
                    } ?: Timber.i("muxer of transId:got null")
                }
            }
            return true
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Timber.i("onTrimMemory of level:$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Timber.i("onTrimMemory of level:lowMemory")
    }
}
