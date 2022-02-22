package com.tsinglink.android.recorder.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import java.io.IOException

class CodecInfo {
    var name: String = ""
    var colorFormat = 0
    override fun toString(): String {
        return "CodecInfo[Name=$name,Color=$colorFormat]"
    }
}


fun listEncoders(mime: String): ArrayList<CodecInfo> {
    // 可能有多个编码库，都获取一下。。。
    val codecInfos = ArrayList<CodecInfo>()
    var numCodecs = MediaCodecList.getCodecCount()
    // int colorFormat = 0;
    // String name = null;
    for (i1 in 0 until numCodecs) {
        val codecInfo = MediaCodecList.getCodecInfoAt(i1)
        if (!codecInfo.isEncoder) {
            continue
        }
        if (codecMatch(mime, codecInfo)) {
            val name = codecInfo.name
            val colorFormat = getColorFormat(codecInfo, mime)
            if (colorFormat != 0) {
                val ci = CodecInfo()
                ci.name = name
                ci.colorFormat = colorFormat
                //                    ci.mWeight = 1;
//                    if ("OMX.IMG.TOPAZ.VIDEO.Encoder".equals(name)) {
//                        ci.mWeight = 0;
//                    }
                codecInfos.add(ci)
            }
        }
    }
    return codecInfos
}

fun codecMatch(mimeType: String, codecInfo: MediaCodecInfo): Boolean {
    val types = codecInfo.supportedTypes
    for (type in types) {
        if (type.equals(mimeType, ignoreCase = true)) {
            return true
        }
    }
    return false
}

fun getColorFormat(codecInfo: MediaCodecInfo, mimeType: String): Int {
    val capabilities = codecInfo.getCapabilitiesForType(mimeType)
    val cf = IntArray(capabilities.colorFormats.size)
    System.arraycopy(capabilities.colorFormats, 0, cf, 0, cf.size)
    val sets: MutableList<Int> = ArrayList()
    for (i in cf.indices) {
        sets.add(cf[i])
    }
    if (sets.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    } else if (sets.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    } else if (sets.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar)) {
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
    } else if (sets.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar)) {
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
    } else if (sets.contains(MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar)) {
        return MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar
    }
    return 0
}

// mate8 双码流，如果都用google硬编码的话，录像会出现绿屏。因此决定尝试录像用google的硬编，实时流用系统的:"OMX.IMG.TOPAZ.VIDEO.Encoder"
@Throws(IOException::class)
fun selectCodec(mimeType: String): CodecInfo {
    val codecInfos = listEncoders(mimeType)
    if (codecInfos.isEmpty()) {
        throw IOException("no encoder!")
    }
    return codecInfos[0]
}