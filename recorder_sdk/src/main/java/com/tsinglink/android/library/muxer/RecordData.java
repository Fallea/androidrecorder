package com.tsinglink.android.library.muxer;


import com.tsinglink.android.library.YuvLib;

import java.nio.ByteBuffer;

public class RecordData {
    public final int type;
    public ByteBuffer data;
    public final int length;
    public final long timestampMillis;
    public final int flag;
    private int ref;

    public RecordData(int type, ByteBuffer data, long timestampMillis, int flag) {
        this.type = type;
        this.data = data;

        this.length = data.capacity();
        this.timestampMillis = timestampMillis;
        this.flag = flag;
    }

    public synchronized void ref(){
        ref++;
    }

    public synchronized void release() {
        ref--;
        if (ref <= 0)
        if (data != null) {
            YuvLib.freeByteBuffer(data);
            data = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }
}
