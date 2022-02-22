package com.tsinglink.android.recorder.listener

import android.os.Bundle


interface OnRecordStatusChangeListener {
    fun onRecordStart(path:String)
    fun onRecordStop(path:String,e:Throwable? = null,extra:Bundle? = null)
}