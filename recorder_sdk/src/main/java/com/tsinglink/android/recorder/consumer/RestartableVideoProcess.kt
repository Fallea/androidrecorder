package com.tsinglink.android.recorder.consumer

/**
 * Created by Administrator on 2015/9/6.
 */
interface RestartableVideoProcess : VideoProcess {
    /**
     * 告诉消费者,这是一次重启,而不是停止.
     * 注意:后续 onVideoStop 依然会调用
     */
}