package io.agora.agora_rtc_ng;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ExternalAudioRender {
    private static final String TAG = "ExternalAudioRender";
    private static final int FRAME_MS = 10;
    private static final int SAMPLE_RATE = 48000;

    static {
        System.loadLibrary("iris_rendering_android");
    }

    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private Thread renderThread;
    private AudioTrack audioTrack;
    private long engineHandle;
    private int sampleRate;
    private int channels;
    private int samplesPerChannel;
    private int frameBytes;
    private volatile long framesPulled;
    private volatile long silenceInserted;
    private static final boolean DEBUG_DUMP_PCM = false;
    private FileOutputStream farEndDump;

    public static boolean hasUsbOutput(Context context) {
        return findUsbOutput(context) != null;
    }

    public boolean start(Context context, long engineHandle, int sampleRate, int channels) {
        synchronized (lifecycleLock) {
            if (running) {
                return true;
            }
            if (sampleRate <= 0 || channels < 1 || channels > 2) {
                Log.e(TAG, "start: invalid audio format");
                return false;
            }

            AudioDeviceInfo usbDevice = findUsbOutput(context);
            if (usbDevice == null) {
                Log.w(TAG, "start: no USB output device found");
                return false;
            }

            this.engineHandle = engineHandle;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.samplesPerChannel = sampleRate / 100;
            this.frameBytes = samplesPerChannel * channels * 2;

            if (!createAudioTrack(usbDevice)) {
                return false;
            }

            framesPulled = 0;
            silenceInserted = 0;
            running = true;
            if (DEBUG_DUMP_PCM) {
                try {
                    File dir = context.getExternalFilesDir(null);
                    farEndDump = new FileOutputStream(new File(dir, "farend.pcm"));
                } catch (IOException e) {
                    Log.e(TAG, "start: cannot open farend dump", e);
                    farEndDump = null;
                }
            }
            renderThread = new Thread(this::renderLoop, "AgoraExternalAudioRender");
            renderThread.start();
            nativeAecCreate(sampleRate, channels);
            Log.i(TAG, "start: sampleRate=" + sampleRate
                    + " channels=" + channels
                    + " frameBytes=" + frameBytes);
            return true;
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            if (!running && renderThread == null && audioTrack == null) {
                return;
            }

            running = false;
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                } catch (IllegalStateException ignored) {
                }
            }

            if (renderThread != null) {
                try {
                    renderThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                renderThread = null;
            }

            if (farEndDump != null) {
                try {
                    farEndDump.close();
                } catch (IOException ignored) {
                }
                farEndDump = null;
            }

            if (audioTrack != null) {
                Log.i(TAG, "stop: " + getStatsLine());
                audioTrack.release();
                audioTrack = null;
            }
            nativeAecDestroy();
            nativeResetCache();
        }
    }

    public String getStatsLine() {
        int underruns = 0;
        if (audioTrack != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            underruns = audioTrack.getUnderrunCount();
        }
        return "framesPulled=" + framesPulled
                + " silenceInserted=" + silenceInserted
                + " underruns=" + underruns;
    }

    private boolean createAudioTrack(AudioDeviceInfo usbDevice) {
        int channelMask = channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        int minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            Log.e(TAG, "createAudioTrack: invalid minimum buffer size " + minBufferSize);
            return false;
        }

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        int bufferSize = Math.max(minBufferSize, frameBytes * 2);
        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }

        try {
            audioTrack = builder.build();
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "createAudioTrack: failed to build AudioTrack", e);
            return false;
        }

        boolean routed = audioTrack.setPreferredDevice(usbDevice);
        Log.i(TAG, "setPreferredDevice(" + usbDevice.getProductName() + ") -> " + routed);
        if (!routed) {
            audioTrack.release();
            audioTrack = null;
            return false;
        }

        try {
            audioTrack.play();
        } catch (IllegalStateException e) {
            Log.e(TAG, "createAudioTrack: failed to start AudioTrack", e);
            audioTrack.release();
            audioTrack = null;
            return false;
        }
        return true;
    }

    private void renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        ByteBuffer buffer = ByteBuffer.allocateDirect(frameBytes);

        while (running) {
            int bytes = nativePullAudioFrame(
                    engineHandle, buffer, samplesPerChannel, channels, sampleRate);
            if (bytes < 0) {
                zeroBuffer(buffer);
                bytes = frameBytes;
                silenceInserted++;
            } else {
                framesPulled++;
            }

            buffer.position(0);
            if (farEndDump != null) {
                try {
                    byte[] copy = new byte[bytes];
                    buffer.get(copy, 0, bytes);
                    farEndDump.write(copy);
                    buffer.position(0);
                } catch (IOException e) {
                    Log.e(TAG, "renderLoop: farend dump write failed", e);
                }
            }
            nativeAecPlayback(buffer, samplesPerChannel);
            if (audioTrack != null) {
                int written = audioTrack.write(buffer, bytes, AudioTrack.WRITE_BLOCKING);
                if (written < 0) {
                    Log.e(TAG, "renderLoop: AudioTrack.write failed: " + written);
                    break;
                }
                if (framesPulled > 0 && framesPulled % 500 == 0
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Log.i(TAG, "latency: headPos=" + audioTrack.getPlaybackHeadPosition()
                            + " bufferSizeFrames=" + audioTrack.getBufferSizeInFrames()
                            + " " + getStatsLine());
                }
            }
        }
    }

    private static void zeroBuffer(ByteBuffer buffer) {
        buffer.clear();
        while (buffer.hasRemaining()) {
            buffer.put((byte) 0);
        }
        buffer.clear();
    }

    private static AudioDeviceInfo findUsbOutput(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            Log.w(TAG, "findUsbOutput: AudioManager unavailable");
            return null;
        }

        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : outputs) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                Log.i(TAG, "findUsbOutput: product=" + device.getProductName()
                        + " type=" + type + " address=" + device.getAddress());
                return device;
            }
        }

        StringBuilder available = new StringBuilder();
        for (AudioDeviceInfo device : outputs) {
            available.append(device.getProductName())
                    .append("(type=")
                    .append(device.getType())
                    .append(") ");
        }
        Log.w(TAG, "findUsbOutput: no USB output among " + outputs.length
                + " outputs: " + available);
        return null;
    }

    private native int nativePullAudioFrame(long engineHandle, ByteBuffer buffer,
                                             int samplesPerChannel, int channels,
                                             int sampleRate);

    private native void nativeResetCache();

    private native void nativeAecCreate(int sampleRate, int channels);

    private native void nativeAecDestroy();

    private native void nativeAecPlayback(ByteBuffer buffer, int samplesPerChannel);

    private native void nativeAecSetDelay(int delayMs);

    public void setAecDelay(int delayMs) {
        nativeAecSetDelay(delayMs);
    }
}
