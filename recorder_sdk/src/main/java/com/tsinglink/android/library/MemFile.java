package com.tsinglink.android.library;

import static android.os.Build.VERSION_CODES.O;

import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MemFile {
    private int fd;
    private int size;
    private long buf;

    public ParcelFileDescriptor getFd(ByteBuffer buf, int size){
        if (Build.VERSION.SDK_INT >= O){
            int fd = map(buf,size);
            if (fd == 0){
                throw new IllegalStateException("invalid fd!");
            }
            try {
                return ParcelFileDescriptor.fromFd(fd);
            } catch (IOException e) {
                e.printStackTrace();
                throw new IllegalStateException("fromFd failed.fd="+fd);
            }
        }else{
            throw new IllegalStateException("unsupported");
        }
    }

    private native int map(ByteBuffer buf,int size);
    public native void unmap();
    static {
        if (Build.VERSION.SDK_INT >= O){
            System.loadLibrary("MemFile");
        }
    }
}
