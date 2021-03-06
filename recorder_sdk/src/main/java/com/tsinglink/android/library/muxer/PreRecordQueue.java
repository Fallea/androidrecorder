package com.tsinglink.android.library.muxer;


import static com.tsinglink.android.library.muxer.FFMediaMuxer.AVMEDIA_TYPE_VIDEO;

import android.media.MediaCodec;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.PriorityBlockingQueue;

import io.reactivex.subjects.PublishSubject;
import timber.log.Timber;

public class PreRecordQueue {
    private PriorityBlockingQueue<RecordData> mediaList;
    private ArrayList<RecordData> keyFrameList;
    private final Object mLock = new Object();
    private long lastVideoStampMillis,lastAudioStampMillis;
    private final long preRecordDurationMillis;

    public PreRecordQueue(int preRecordDurationSecond) {
        this.preRecordDurationMillis = preRecordDurationSecond * 1000;
        mediaList = new PriorityBlockingQueue<>(500, (o1, o2) -> Long.compare(o1.timestampMillis, o2.timestampMillis));
        keyFrameList = new ArrayList<>();
    }

    public void add(RecordData e) {
        synchronized (mLock) {
            if (e.data == null) {
                throw new IllegalStateException("error null data");
            }
            if (e.type == AVMEDIA_TYPE_VIDEO) {
                if (lastVideoStampMillis != 0L){
                    if (e.timestampMillis < lastVideoStampMillis){
                        Timber.w("Video stamp backward.last<-this=%d<-%d diff:%d", e.timestampMillis, lastVideoStampMillis, e.timestampMillis - lastVideoStampMillis);
                    }
                }
                lastVideoStampMillis = e.timestampMillis;
                if ((e.flag & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) keyFrameList.add(e);
            }else{
                if (lastAudioStampMillis != 0L){
                    if (e.timestampMillis < lastAudioStampMillis){
                        Timber.w("Audio stamp backward.last<-this=%d<-%d diff:%d", e.timestampMillis, lastAudioStampMillis, e.timestampMillis - lastAudioStampMillis);
                    }
                }
                lastAudioStampMillis = e.timestampMillis;
            }
            if (keyFrameList.isEmpty()) {
                Timber.i("?????????????????????????????????????????????????????????????????????");
                return;
            }
            Timber.v("add to queue ,type == %d; flag == %d", e.type, e.flag);

            try {
                if (e.type == AVMEDIA_TYPE_VIDEO) {
                    // ???????????????
                    RecordData targetKeyVideo = null;
                    Timber.v("?????????????????????????????????????????????list size:%d", mediaList.size());
                    for (int i = keyFrameList.size() - 1; i >= 0; i--) {
                        RecordData keyVideo = keyFrameList.get(i);
                        if (e.timestampMillis - keyVideo.timestampMillis >= preRecordDurationMillis) {
                            targetKeyVideo = keyVideo;
                            break;
                        }
                    }
                    if (targetKeyVideo == null) {
                        Timber.v("??????????????????");
                        return;
                    }

                    while (mediaList.size() > 0) {
                        RecordData media = mediaList.peek();
                        if (media == targetKeyVideo) {
                            Timber.v("???????????????????????????????????????list size:%d", mediaList.size());
                            break;
                        }
                        mediaList.poll().release();
                    }
                    while (keyFrameList.indexOf(targetKeyVideo) > 0) {
                        keyFrameList.remove(0);
                    }
                }
            }finally {
                if (!mediaList.add(e)) {
                    Timber.e("add failed!");
                    Timber.i("???????????????????!?!????????????,??????????????????...");
                    keyFrameList.clear();
                    mediaList.clear();
                    return;
                }
                e.ref();
            }
        }
    }

    public void clear() {
        synchronized (mLock) {
            while (true) {
                RecordData myMedia = mediaList.poll();
                if (myMedia == null) break;
                myMedia.release();
            }
            keyFrameList.clear();
        }
    }


    public void writeCacheToMuxer(PublishSubject<RecordData> recordSubject) {
        synchronized (mLock) {
            Timber.i("?????????Cache??????Muxer.size:%d", mediaList.size());
            while (true) {
                RecordData myMedia = mediaList.poll();
                if (myMedia == null) break;
                Log.i("PreRecordQueue", String.format("???????????? type???%d, flags:%d, stamp:%d, data:%d", myMedia.type, myMedia.flag, myMedia.timestampMillis, myMedia.data.capacity()));
                recordSubject.onNext(myMedia);
            }
            keyFrameList.clear();
            Timber.i("???Cache??????Muxer,?????????");
        }
    }


}
