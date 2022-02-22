package com.tsinglink.android.recorder

import android.media.MediaFormat
import android.os.Parcel
import android.os.Parcelable

class RecorderOption constructor(
    val dir: String,
    val fileNamePatten:String = "yy-MM-dd_HH.mm.ss",

    /**
     * 单个文件的录像时长 单位：分钟，默认：15。
     */
    val recordSpanMinutes: Int = 15,
    /**
     * 预录 默认：false
     */
    val isPreRecordEnable: Boolean = false,
    /**
     * 预录时长 单位：秒， 默认：10
     */
    val preRecordDurationSecond: Int = 10,
    /**
     * 停止录像的存储阈值 单位：MB， 默认：5000
     */
    val recordThresholdMB: Int = 5000,
    /**
     * 低电量停止录像 默认: false
     */
    val isStopByLowPowerEnable: Boolean = false,
    /**
     * If delete old files to free space when storage space is insufficient
     */
    val deleteFilesToFreeSpace:Boolean = false,
    /**
     * If deleteFilesToFreeSpace is true,the files in this directory could be deleted.
     * on default,it's the recording dir.
     */
    val rootDirToFreeSpace:String = dir,
    /**
     * 停止录像的电量阈值 单位：百分比， 默认：5
     */
    val batteryThresholdStopRecording: Int = 5,
    val videoCodecMIME:String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val showNotification:Boolean = true,
    val bitsPerSecond:Int = 1024 * 1024 * 2,
    val frameRate:Int = 30,
    val iFrameInterval:Int = 1,
):Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readString()!!,
        parcel.readInt(),
        parcel.readString()!!,
        parcel.readByte() != 0.toByte(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(dir)
        parcel.writeString(fileNamePatten)
        parcel.writeInt(recordSpanMinutes)
        parcel.writeByte(if (isPreRecordEnable) 1 else 0)
        parcel.writeInt(preRecordDurationSecond)
        parcel.writeInt(recordThresholdMB)
        parcel.writeByte(if (isStopByLowPowerEnable) 1 else 0)
        parcel.writeByte(if (deleteFilesToFreeSpace) 1 else 0)
        parcel.writeString(rootDirToFreeSpace)
        parcel.writeInt(batteryThresholdStopRecording)
        parcel.writeString(videoCodecMIME)
        parcel.writeByte(if (showNotification) 1 else 0)
        parcel.writeInt(bitsPerSecond)
        parcel.writeInt(frameRate)
        parcel.writeInt(iFrameInterval)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<RecorderOption> {
        override fun createFromParcel(parcel: Parcel): RecorderOption {
            return RecorderOption(parcel)
        }

        override fun newArray(size: Int): Array<RecorderOption?> {
            return arrayOfNulls(size)
        }
    }
}