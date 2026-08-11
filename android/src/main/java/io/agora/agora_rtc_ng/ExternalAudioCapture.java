package io.agora.agora_rtc_ng;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;

import java.nio.ByteBuffer;

public class ExternalAudioCapture {
    private static final String TAG = "ExternalAudioCapture";

    static {
        System.loadLibrary("iris_rendering_android");
    }

    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private Thread captureThread;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler platformAec;
    private NoiseSuppressor platformNs;
    private AutomaticGainControl platformAgc;
    private long engineHandle;
    private int trackId;
    private int sampleRate;
    private int channels;
    private int samplesPerChannel;
    private int frameBytes;
    private volatile long framesCaptured;
    private volatile long framesDropped;

    public boolean start(Context context, long engineHandle, int trackId,
                         int sampleRate, int channels, boolean usePlatformEffects) {
        synchronized (lifecycleLock) {
            if (running) {
                return true;
            }
            if (sampleRate <= 0 || channels < 1 || channels > 2) {
                Log.e(TAG, "start: invalid audio format");
                return false;
            }

            this.engineHandle = engineHandle;
            this.trackId = trackId;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.samplesPerChannel = sampleRate / 100;
            this.frameBytes = samplesPerChannel * channels * 2;

            if (!createAudioRecord(usePlatformEffects)) {
                return false;
            }

            framesCaptured = 0;
            framesDropped = 0;
            running = true;
            captureThread = new Thread(this::captureLoop, "AgoraExternalAudioCapture");
            captureThread.start();
            Log.i(TAG, "start: sampleRate=" + sampleRate
                    + " channels=" + channels
                    + " trackId=" + trackId
                    + " frameBytes=" + frameBytes);
            return true;
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            if (!running && captureThread == null && audioRecord == null) {
                return;
            }

            running = false;
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException ignored) {
                }
            }

            if (captureThread != null) {
                try {
                    captureThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                captureThread = null;
            }

            releaseEffects();

            if (audioRecord != null) {
                Log.i(TAG, "stop: " + getStatsLine());
                audioRecord.release();
                audioRecord = null;
            }
        }
    }

    public String getStatsLine() {
        return "framesCaptured=" + framesCaptured + " framesDropped=" + framesDropped;
    }

    private boolean createAudioRecord(boolean usePlatformEffects) {
        int channelMask = channels == 1
                ? AudioFormat.CHANNEL_IN_MONO
                : AudioFormat.CHANNEL_IN_STEREO;
        int minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            Log.e(TAG, "createAudioRecord: invalid minimum buffer size " + minBufferSize);
            return false;
        }

        int bufferSize = Math.max(minBufferSize, frameBytes * 4);
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "createAudioRecord: failed to build AudioRecord", e);
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "createAudioRecord: AudioRecord not initialized");
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        attachEffects(usePlatformEffects);

        try {
            audioRecord.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "createAudioRecord: failed to start recording", e);
            releaseEffects();
            audioRecord.release();
            audioRecord = null;
            return false;
        }
        return true;
    }

    private void attachEffects(boolean usePlatformEffects) {
        int sessionId = audioRecord.getAudioSessionId();
        Log.i(TAG, "effects: usePlatformEffects=" + usePlatformEffects
                + " aecAvailable=" + AcousticEchoCanceler.isAvailable()
                + " nsAvailable=" + NoiseSuppressor.isAvailable()
                + " agcAvailable=" + AutomaticGainControl.isAvailable());
        if (!usePlatformEffects) {
            return;
        }
        if (AcousticEchoCanceler.isAvailable()) {
            platformAec = AcousticEchoCanceler.create(sessionId);
            if (platformAec != null) {
                platformAec.setEnabled(true);
                Log.i(TAG, "effects: platform AEC enabled=" + platformAec.getEnabled());
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            platformNs = NoiseSuppressor.create(sessionId);
            if (platformNs != null) {
                platformNs.setEnabled(true);
                Log.i(TAG, "effects: platform NS enabled=" + platformNs.getEnabled());
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            platformAgc = AutomaticGainControl.create(sessionId);
            if (platformAgc != null) {
                platformAgc.setEnabled(false);
                Log.i(TAG, "effects: platform AGC enabled=" + platformAgc.getEnabled());
            }
        }
    }

    private void releaseEffects() {
        if (platformAec != null) {
            platformAec.release();
            platformAec = null;
        }
        if (platformNs != null) {
            platformNs.release();
            platformNs = null;
        }
        if (platformAgc != null) {
            platformAgc.release();
            platformAgc = null;
        }
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        ByteBuffer buffer = ByteBuffer.allocateDirect(frameBytes);

        while (running) {
            buffer.position(0);
            int read = audioRecord.read(buffer, frameBytes);
            if (read < 0) {
                Log.e(TAG, "captureLoop: AudioRecord.read failed: " + read);
                break;
            }
            if (read < frameBytes) {
                framesDropped++;
                continue;
            }

            buffer.position(0);
            int pushed = nativePushAudioFrame(
                    engineHandle, buffer, samplesPerChannel, channels, sampleRate,
                    trackId, System.currentTimeMillis());
            if (pushed < 0) {
                framesDropped++;
            } else {
                framesCaptured++;
            }
        }
    }

    private native int nativePushAudioFrame(long engineHandle, ByteBuffer buffer,
                                            int samplesPerChannel, int channels,
                                            int sampleRate, int trackId,
                                            long timestampMs);
}
