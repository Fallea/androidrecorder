Android video/audio recording library.

基于安卓的一个音视频录像库.

features

-----

- Based on ffmpeg avformat
- Support video format yuv(I420)
- Support audio format pcm
- Recording was isolated by process(FFMuxerService),the mp4 file was not corrupted when main process crash
- Automatic segment when recording too much time
- Stop recording when battery is low
- Multiple instance recording
- Delete old files or stop recording when storage space is low


特性

------

- 基于ffmpeg avformat库实现
- 输入视频格式为yuv(I420)
- 输入音频格式为pcm
- 录像进程隔离,即便主进程崩溃录像文件也不会损坏
- 录像时间过长时,文件自动分段
- 电量过低时自动停止录像
- 支持多实例同时录像
- 存储空间不足时,删除旧文件或者自动停止录像

usage 用法
-----
```kotlin
// create instance
val ffmuxer = MediaMuxer2(app, option, listener)
ffmuxer.onVideoStart(1920,1080)// set video params. width/height
ffmuxer.onAudioStart(16000,1,16)// set audio params. sample, channel, bitPerSample

// write frames
ffmuxer.onVideo(data, data.size, millis)// write video frame, frame,size,timestamp
ffmuxer.onAudio(pcm,pcm.size,millis)// write audio frame

// stop ffmuxer
ffmuxer.stop()
```
test 测试
--------
- Run the test case on Android device:

    `myapplication/src/androidTest/java/com/android/rs/myapplication/RecordTest.kt`
    
License
-------

    Copyright 2022 Tsinglink

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
