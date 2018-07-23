/*
	Copyright (c) 2012-2017 EasyDarwin.ORG.  All rights reserved.
	Github: https://github.com/EasyDarwin
	WEChat: EasyDarwin
	Website: http://www.easydarwin.org
*/

package org.easydarwin.easyipcamera.camera;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;

/**
 * Created by Kim on 8/8/2016.
 */
public class AudioStream {
    private int mAudioEncCodec = EasyIPCamera.AudioCodec.EASY_SDK_AUDIO_CODEC_AAC;
    private int mSamplingRate = 8000;
    private int mBitsPerSample = AudioFormat.ENCODING_PCM_16BIT;
    private int mChannelNum = 1;
    private int bitRate = 16000;
    private int BUFFER_SIZE = 1600;
    int mSamplingRateIndex=0;
    private AudioRecord mAudioRecord;
    private MediaCodec mMediaCodec;
    private EasyIPCamera mEasyIPCamera;
    private int mChannelId;
    private Thread mThread = null;
    private Thread encodeThread = null;
    String TAG = "AudioStream";
    //final String path = Environment.getExternalStorageDirectory() + "/123450001.aac";
    private boolean stoped = false;
    private boolean mPushAudio = false;
    private int mChannelState = 0;

    protected MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    protected ByteBuffer[] mBuffers = null;
    protected ByteBuffer mBuffer = null;
    protected int mIndex = -1;

    /** There are 13 supported frequencies by ADTS. **/
    public static final int[] AUDIO_SAMPLING_RATES = { 96000, // 0
            88200, // 1
            64000, // 2
            48000, // 3
            44100, // 4
            32000, // 5
            24000, // 6
            22050, // 7
            16000, // 8
            12000, // 9
            11025, // 10
            8000, // 11
            7350, // 12
            -1, // 13
            -1, // 14
            -1, // 15
    };

    public AudioStream(EasyIPCamera easyIPCamera) {
        this.mEasyIPCamera = easyIPCamera;
        int i = 0;
        for (; i < AUDIO_SAMPLING_RATES.length; i++) {
            if (AUDIO_SAMPLING_RATES[i] == mSamplingRate) {
                mSamplingRateIndex = i;
                break;
            }
        }
    }

    private void init() {
        try {
            stoped=false;
            mPushAudio = false;
            mAudioEncCodec = EasyIPCamera.AudioCodec.EASY_SDK_AUDIO_CODEC_AAC;
            mBitsPerSample = 16;//AudioFormat.ENCODING_PCM_16BIT;
            mChannelNum = 1;
            int bufferSize = AudioRecord.getMinBufferSize(mSamplingRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 16;
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    mSamplingRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            mMediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            MediaFormat format = new MediaFormat();
            format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, mSamplingRate);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE);
            mMediaCodec.configure(format, null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {

        }
    }

    private boolean AACStreamingSupported() {
        if (Build.VERSION.SDK_INT < 14)
            return false;
        try {
            MediaRecorder.OutputFormat.class.getField("AAC_ADTS");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 编码
     */
    private synchronized void startEncode() {
        mBuffers = mMediaCodec.getOutputBuffers();
        mBuffer=null;
        encodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted() && !stoped) {
                    try {
                        if (mBuffer == null) {
                            mBuffer = ByteBuffer.allocate(10240);
                            while (!Thread.currentThread().isInterrupted() && !stoped) {
                                mIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 50000);
                                if (mIndex >= 0) {
                                    if (mBufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                                        continue;
                                    }
                                    mBuffer.clear();
                                    mBuffer.position(7);
                                    mBuffers[mIndex].get(mBuffer.array(), 7, mBufferInfo.size);
                                    mBuffers[mIndex].clear();
                                    mBuffer.position(mBuffer.position() + mBufferInfo.size);
                                    addADTStoPacket(mBuffer.array(), mBufferInfo.size + 7);
                                    mBuffer.flip();
                                    break;
                                } else if (mIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                    mBuffers = mMediaCodec.getOutputBuffers();
                                } else if (mIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    Log.v(TAG, "output format changed...");
                                } else if (mIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                    Log.v(TAG, "No buffer available...");
                                } else {
                                    Log.e(TAG, "Message: " + mIndex);
                                }
                            }
                        }

                        int size = mBufferInfo.size + 7;
                        //Log.d(TAG, "kim mChannelState="+mChannelState+", mChannelId="+mChannelId+", length="+buffer.length);
                        if(mPushAudio && mChannelState == EasyIPCamera.ChannelState.EASY_IPCAMERA_STATE_REQUEST_PLAY_STREAM) {
//                            byte[] buffer = new byte[size];
//                            mBuffer.get(buffer, 0, size);
                            mEasyIPCamera.pushFrame(mChannelId, EasyIPCamera.FrameFlag.EASY_SDK_AUDIO_FRAME_FLAG, System.currentTimeMillis(), mBuffer.array(), 0, size);
                        }

                        if (mBuffer.position() >= size) { // read complete
                            mMediaCodec.releaseOutputBuffer(mIndex, false);
                            mBuffer = null;
                        }
                    } catch (Exception e) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        e.printStackTrace(pw);
                        String stack = sw.toString();
                        Log.i(TAG, "record" + stack);
                    } finally {
                        if(stoped){
                            release();
                        }
                    }
                }
            }
        }, "AACEncoder");
        encodeThread.start();
    }


    public synchronized void startRecord() {
        try {
            init();
            mAudioRecord.startRecording();
            mMediaCodec.start();
            final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                    int len = 0, bufferIndex = 0;
                    try {
                        while (!Thread.interrupted() && !stoped) {

                            bufferIndex = mMediaCodec.dequeueInputBuffer(50000);
                            if (bufferIndex >= 0) {
                                inputBuffers[bufferIndex].clear();
                                len = mAudioRecord.read(inputBuffers[bufferIndex], BUFFER_SIZE);
                                if (len == AudioRecord.ERROR_INVALID_OPERATION || len == AudioRecord.ERROR_BAD_VALUE) {
                                    mMediaCodec.queueInputBuffer(bufferIndex, 0, 0, 0, 0);
                                } else {
                                    mMediaCodec.queueInputBuffer(bufferIndex, 0, len, 0, 0);
                                }
                            }
                        }
                    } catch (Exception e) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        e.printStackTrace(pw);
                        String stack = sw.toString();
                        Log.i(TAG, "record" + stack);

                    }
                }
            }, "AACRecoder");
            mThread.start();
            startEncode();
        } catch (Exception e) {
            Log.e(TAG, "Record___Error!!!!!");
        }
    }

    private void addADTStoPacket(byte[] packet, int packetLen) {
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF1;
        packet[2] = (byte) (((2 - 1) << 6) + (mSamplingRateIndex << 2) + (1 >> 2));
        packet[3] = (byte) (((1 & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    public void release() {
        try {
            if (mThread != null) {
                mThread.interrupt();
            }
            if (encodeThread != null) {
                encodeThread.interrupt();
            }

            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
            }
            if (mMediaCodec != null) {
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop___Error!!!!!");
        }
    }

    public void stop() {
        stoped = true;
    }

    public void startPush(){
        mPushAudio = true;
    }

    public void stopPush(){
        mPushAudio = false;
    }

    public int getAudioEncCodec(){
        return mAudioEncCodec;
    }

    public int getSamplingRate(){
        return mSamplingRate;
    }

    public int getBitsPerSample(){
        return mBitsPerSample;
    }

    public int getChannelNum(){
        return mChannelNum;
    }

    public void setChannelId(int channelId){
        this.mChannelId = channelId;
    }

    public void setChannelState(int state){
        mChannelState = state;
    }

}
