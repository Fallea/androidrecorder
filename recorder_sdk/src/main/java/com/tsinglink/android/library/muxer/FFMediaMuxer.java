package com.tsinglink.android.library.muxer;

import java.io.FileDescriptor;
import java.lang.annotation.Native;
import java.nio.ByteBuffer;

public class FFMediaMuxer {
    static {
        System.loadLibrary("AVMuxer");
    }
    public static final int AVMEDIA_TYPE_VIDEO  = 0;
    public static final int AVMEDIA_TYPE_AUDIO  = 1;


    public static final int VIDEO_CODEC_TYPE_H264  = 0;
    public static final int VIDEO_CODEC_TYPE_H265 = 1;

    @Native
    private long ctx;
    /**
     * 这个接口,创建出来的 muxer,可以传入 AAC 编码后的数据(writeAAC).
     *
     * @param path
     * @param videoCodecType    VIDEO_CODEC_TYPE_H264 or VIDEO_CODEC_TYPE_H265
     * @param width             video width
     * @param height            video height
     * @param extra             video extra-data (sps/pps), from MediaFormat
     * @param sample            audio sample
     * @param channel           audio channel
     * @param extra2            audio extra-data, from MediaFormat
     * @return
     */
    public native int create(String path, int videoCodecType, int width, int height, byte[] extra, int sample, int channel, byte[] extra2);

    /**
     *
     * @param fd
     * @param streamType
     * @param length
     * @param timeStampMillis
     * @param keyFrame
     *  write frame from fileDescriptor.
     * @return
     */
    public native int writeFrameFromFD(FileDescriptor fd, int streamType, int length, long timeStampMillis, int keyFrame);

    /**
     * 塞入 AAC 数据. 不包括ADTS头.
     *
     * @param frame
     * @param offset
     * @param length
     * @param timeStampMillis
     * @return
     */
    public native int writeAAC(byte[] frame, int offset, int length, long timeStampMillis);
    public native int writeAACFromFD(FileDescriptor fd, int length, long timeStampMillis);

    /**
     *
     * @param streamType
     * @param bf
     * @param timeStampMillis
     * @deprecated 使用带keyFrame的版本
     * @return
     */
    public int writeFrameBf(int streamType, ByteBuffer bf, long timeStampMillis) {
        return writeFrameBf(streamType, bf, bf.capacity(), timeStampMillis, 0);
    }

    public int writeFrameBf(int streamType, ByteBuffer bf, long timeStampMillis, int keyFrame) {
        return writeFrameBf(streamType, bf, bf.capacity(), timeStampMillis, keyFrame);
    }

    public native int writeFrameBf(int streamType, ByteBuffer bf, int length, long timeStampMillis, int keyFrame);

    public native void close();
}
