package com.tsinglink.android.library;

import java.nio.ByteBuffer;

public class YuvLib {
//    JNIEXPORT void JNICALL Java_com_tsinglink_android_library_YuvLib_Android420ToARGB
//            (JNIEnv *env, jclass, jbyteArray yuv, jbyteArray argb, jint width, jint height, jint mode)

    static {
        System.loadLibrary("AVMuxer");
    }

    /**
     * @param yuv
     * @param argb
     * @param width
     * @param height
     * @param mode   0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void Android420ToARGB(byte[] yuv, byte[] argb, int width, int height, int mode);

    public static native void Android420ToARGBBf(ByteBuffer yuv, ByteBuffer argb, int width, int height, int mode);


    /**
     * @param yuv
     * @param argb
     * @param width
     * @param height
     * @param mode   0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void Android420ToABGR(byte[] yuv, byte[] argb, int width, int height, int mode);

    public static native void Android420ToABGRBf(ByteBuffer yuv, ByteBuffer argb, int width, int height, int mode);

    /**
     * @param argb
     * @param i420
     * @param width
     * @param height
     */
    public static native void ARGBToI420(byte[] argb, byte[] i420, int width, int height);

    public static native void ARGBToI420Bf(ByteBuffer argb, ByteBuffer i420, int width, int height);


    public static native void ARGBToRGB24(byte[] argb, byte[] rgb, int width, int height);

    public static native void ARGBToRGB24Bf(ByteBuffer argb, ByteBuffer rgb, int width, int height);

    /**
     * Convert camera sample to I420 with cropping, rotation and vertical flip.
     *
     * @param src
     * @param dst
     * @param width
     * @param height
     * @param cropX      "crop_x" and "crop_y" are starting position for cropping.
     *                   To center, crop_x = (src_width - dst_width) / 2
     *                   crop_y = (src_height - dst_height) / 2
     * @param cropY      "crop_x" and "crop_y" are starting position for cropping.
     *                   To center, crop_x = (src_width - dst_width) / 2
     *                   crop_y = (src_height - dst_height) / 2
     * @param cropWidth
     * @param cropHeight
     * @param rotation   "rotation" can be 0, 90, 180 or 270.
     * @param mode       0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void ConvertToI420
    (byte[] src, byte[] dst, int width, int height, int cropX, int cropY,
     int cropWidth, int cropHeight, int rotation, int mode);

    public static native void ConvertToI420Bf
            (ByteBuffer src, ByteBuffer dst, int width, int height, int cropX, int cropY,
             int cropWidth, int cropHeight, int rotation, int mode);


    /**
     * Convert camera sample to I420 with cropping, rotation and vertical flip.
     *
     * @param src
     * @param dst
     * @param width
     * @param height
     * @param mode   0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void ConvertFromI420
    (byte[] src, byte[] dst, int width, int height, int mode);

    public static native void ConvertFromI420Bf
            (ByteBuffer src, ByteBuffer dst, int width, int height, int mode);

    public static native void I422ToI420
            (ByteBuffer src, ByteBuffer dst, int width, int height);

    /**
     * I420压缩.
     *
     * @param src
     * @param dst
     * @param width
     * @param height
     * @param dstWidth
     * @param dstHeight
     * @param mode      0:Point sample; Fastest.<p>1:Filter horizontally only.<p>2:Faster than box, but lower quality scaling down.<p>3:Highest quality.
     */
    public static native void I420Scale
    (byte[] src, byte[] dst, int width, int height, int dstWidth, int dstHeight, int mode);

    public static native void I420ScaleBf
            (ByteBuffer src, ByteBuffer dst, int width, int height, int dstWidth, int dstHeight, int mode);

    public static native ByteBuffer newByteBuffer(int capacity);

    public static native void freeByteBuffer(ByteBuffer bf);
}
