package com.tsinglink.android.recorder.consumer;

/**
 * Created by Administrator on 2015/10/13.
 */
public interface AudioProcess {
    /**
     *
     * @param sample                sample in hz
     * @param channel               audio channel
     * @param bitPerSample
     */
    public void onAudioStart(int sample, int channel, int bitPerSample);
    /**
     * @param pcm
     * @param length
     * @param timeStampNano
     */
    public int onAudio(short[] pcm, int length, long timeStampNano);
}
