package com.android.rs.myapplication

import android.app.Application
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.rs.myapplication.Util.emitMediaFrames
import com.android.rs.myapplication.Util.getMp4DurationSecond
import com.android.rs.myapplication.Util.initMuxer
import com.tsinglink.android.recorder.RecorderOption
import com.tsinglink.android.recorder.listener.OnRecordStatusChangeListener
import org.hamcrest.number.IsCloseTo
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.File
import kotlin.concurrent.thread

/**
 * dir 表示录像文件所在目录.
 * fileNamePatten 表示文件按照日期的format规则.录像以这种形式来命名:
 *
 * directoryToDelete,表示存储不足时,删除文件以释放空间的根目录:即仅删除该目录下面的文件来释放空间,默认为dir.
 */
@RunWith(AndroidJUnit4::class)
class RecordTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    var recordPath:String = ""
    val listener = object:OnRecordStatusChangeListener{
        override fun onRecordStart(path: String) {
            Timber.i("record start:$path")
            recordPath = path
        }

        override fun onRecordStop(path: String, e: Throwable?, extra: Bundle?) {
            Timber.i("record stop:$path.has error =${e != null}, extra=${extra}")

        }
    }

    @Before
    fun setup(){
        Timber.plant(Timber.DebugTree())
    }

    @Test
    fun testRecord(){
        val ffmuxer = Util.initMuxer(app,RecorderOption(
            dir = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,
            showNotification = true
        ),listener)
        Util.emitMediaFrames(ffmuxer,8)
        ffmuxer.stop()

        // record time should be 7,8s
        val mp4DurationSecond = getMp4DurationSecond(recordPath)
        Assert.assertTrue(mp4DurationSecond == 8L || mp4DurationSecond == 7L)
    }

    @Test
    fun testHighBitrate(){
        val bitrate = 1024 * 1024 * 4       // set bitrate to 8Mbps
        val ffmuxer = initMuxer(app,RecorderOption(
            dir = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,
            bitsPerSecond = bitrate
        ),listener)
        emitMediaFrames(ffmuxer,8)
        ffmuxer.stop()
        // record time should be 7,8s
        val mp4DurationSecond = getMp4DurationSecond(recordPath)
        Assert.assertThat(mp4DurationSecond.toDouble(),IsCloseTo(8.0,1.0))
    }

    @Test
    fun testSegmentFile(){
        val ffmuxer = initMuxer(app,RecorderOption(
            dir = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,
            recordSpanMinutes = 1   // one file could last 60 second.
        ),listener)
        emitMediaFrames(ffmuxer,70)
        ffmuxer.stop()
        // record file will be two segments and the second segment last 7 ~ 10 seconds.
        val mp4DurationSecond = getMp4DurationSecond(recordPath)
        Assert.assertThat(mp4DurationSecond.toDouble(),IsCloseTo(9.0,2.0))
    }

    @Test
    fun testDeleteOldFile(){
        val path = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,
            "${SystemClock.elapsedRealtime()}")
        path.mkdir()
        val stat = StatFs(path.path)
        var availableBytes = stat.availableBytes
        // make a temporary file for test
        var thisFileWillBeDeleted = Util.makeABigFile(path.path)
        Assert.assertTrue(thisFileWillBeDeleted.exists())

        val ffmuxer = initMuxer(app,RecorderOption(
            dir = path.path,
            rootDirToFreeSpace = path.path, // when space insufficient,on which folder the files should be delete
            recordSpanMinutes = 1,   // one file could last 60 second.
            deleteFilesToFreeSpace = true,
            recordThresholdMB = (availableBytes/1024/1024).toInt()
        ),listener)
        emitMediaFrames(ffmuxer,10)
        ffmuxer.stop()
        // the temporary file will be deleted to free space.
        Assert.assertTrue(!thisFileWillBeDeleted.exists())
    }

    @Test
    fun testLowSpaceError(){
        val path = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,
            "${SystemClock.elapsedRealtime()}")
        path.mkdir()
        val stat = StatFs(path.path)
        var availableBytes = stat.availableBytes
        var file = Util.makeABigFile(path.path)
        Assert.assertTrue(file.exists())

        val ffmuxer = initMuxer(app,RecorderOption(
            dir = path.path,
            deleteFilesToFreeSpace = false,     // do not delete file.
            recordThresholdMB = (availableBytes/1024/1024).toInt()
        ),listener)
        var i = emitMediaFrames(ffmuxer,30)
        ffmuxer.stop()
        Assert.assertTrue(file.exists())
        Assert.assertTrue(i == -2)  // emitMediaFrames break with low space error.
    }



    fun doRecord(dir:String){
        val ffmuxer1 = initMuxer(app,RecorderOption(
            dir = dir,
            showNotification = true
        ),listener)
        emitMediaFrames(ffmuxer1,8)
        ffmuxer1.stop()
    }

    @Test
    fun testMultiInstance(){
        var dir1 = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,"dir1").apply {
            mkdirs()
        }
        var dir2 = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,"dir2").apply {
            mkdirs()
        }
        val thread1 = thread {
            doRecord(dir1.path)
        }
        val thread2 = thread {
            doRecord(dir2.path)
        }
        thread1.join()
        thread2.join()
    }
}