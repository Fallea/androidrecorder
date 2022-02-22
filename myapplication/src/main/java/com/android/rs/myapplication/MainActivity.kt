package com.android.rs.myapplication

import android.content.Context
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.preference.PreferenceManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.tsinglink.android.recorder.RecorderOption
import com.tsinglink.android.recorder.listener.OnRecordStatusChangeListener
import timber.log.Timber
import java.io.File
import java.lang.RuntimeException


class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    var recordPath:String = ""
    val listener = object: OnRecordStatusChangeListener {
        override fun onRecordStart(path: String) {
            Timber.i("record start:$path")
            recordPath = path
        }

        override fun onRecordStop(path: String, e: Throwable?, extra: Bundle?) {
            Timber.i("record stop:$path.has error =${e != null}, extra=${extra}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Timber.treeCount() < 1) Timber.plant(Timber.DebugTree())
        setContentView(R.layout.activity_main)
    }

    override fun onStart() {
        super.onStart()
        findViewById<View>(R.id.recording_container).visibility = View.VISIBLE
        val playerView = findViewById<StyledPlayerView>(R.id.player)
        playerView.visibility = View.GONE
        findViewById<TextView>(R.id.record_text).text = "Recording in progress,please wait for 30 seconds";
        if (recordPath == "") {
            recordPath =
                getPreferences(Context.MODE_PRIVATE).getString("last-recording-file", recordPath)!!
        }
        if (recordPath != ""){
            getPreferences(Context.MODE_PRIVATE).edit().putString("last-recording-file","").apply()
            startPlay()
        }else {
            AsyncTask.execute(Runnable {
                val ffmuxer = Util.initMuxer(
                    application,
                    RecorderOption(
                        dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path,
                        showNotification = true,
                        bitsPerSecond = 8 * 1024 * 1024
                    ),
                    listener
                )
                Util.emitMediaFrames(ffmuxer, 30, this)
                ffmuxer.stop()
                // record time should be 7,8s
                val mp4DurationSecond = Util.getMp4DurationSecond(recordPath)
                Timber.i("mp4DurationSecond is :$mp4DurationSecond")

                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    runOnUiThread {
                        findViewById<View>(R.id.progressBar).visibility = View.GONE
                        findViewById<TextView>(R.id.record_text).visibility = View.GONE
                        Toast.makeText(
                            this@MainActivity,
                            "Recording done,the file path is:\n$recordPath",
                            Toast.LENGTH_LONG
                        ).show()
                        startPlay()
                    }
                }
            })
        }
    }

    override fun onStop() {
        this.player?.stop()
        super.onStop()
    }

    private fun startPlay() {
        this.player?.stop()
        val player = ExoPlayer.Builder(this@MainActivity).build()
        val playerView = findViewById<StyledPlayerView>(R.id.player)
        playerView.visibility = View.VISIBLE
        findViewById<View>(R.id.recording_container).visibility = View.GONE
        playerView.setPlayer(player)
        // Build the media item.
        val mediaItem: MediaItem = MediaItem.fromUri(Uri.fromFile(File(recordPath)))
        // Set the media item to be played.
        player.setMediaItem(mediaItem)
        // Prepare the player.
        player.prepare()
        // Start the playback.
        player.play()
        this.player = player
    }

    fun makeACrash(view: View) {
        getPreferences(Context.MODE_PRIVATE).edit().putString("last-recording-file",recordPath).commit()
        throw RuntimeException("Crash by button")
    }

    override fun onDestroy() {
        try {
            File(recordPath).delete()
        }catch (e:Throwable){

        }
        super.onDestroy()
    }
}