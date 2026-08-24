package com.screenrecorder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";
    public static final String ACTION_STOP = "com.screenrecorder.app.STOP";
    public static final String CHANNEL_ID = "screen_recording_channel";
    public static final int NOTIFICATION_ID = 1001;
    private static boolean sRunning = false;
    public static boolean isRunning() { return sRunning; }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaMuxer mediaMuxer;
    private MediaCodec videoEncoder, audioEncoder;
    private AudioRecord audioRecord;
    private Surface inputSurface;
    private boolean muxerStarted = false;
    private int videoTrackIndex = -1, audioTrackIndex = -1;
    private int screenWidth, screenHeight, screenDpi;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private Thread audioThread, videoThread;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");
        if (resultCode == -1 || data == null) { stopSelf(); return START_NOT_STICKY; }

        startForeground(NOTIFICATION_ID, buildNotification("Recording..."));
        startRecording(resultCode, data);
        return START_STICKY;
    }

    private void startRecording(int resultCode, Intent data) {
        try {
            screenWidth = 1080; screenHeight = 1920; screenDpi = 320;
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mgr.getMediaProjection(resultCode, data);
            if (mediaProjection == null) { stopSelf(); return; }

            sRunning = true;
            isRecording.set(true);

            // Video encoder
            MediaFormat vidFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight);
            vidFmt.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
            vidFmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            vidFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            vidFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoEncoder.configure(vidFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            // Audio encoder
            int sampleRate = 44100, channelCount = 1;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, audioFormat) * 2;
            audioRecord = new AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, audioFormat, bufferSize);
            MediaFormat audFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
            audFmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            audFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioRecord.startRecording();

            // Virtual display
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenRecorder", screenWidth, screenHeight, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);

            // Muxer
            String path = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
                    + "/ScreenRecorder_" + System.currentTimeMillis() + ".mp4";
            mediaMuxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopRecording(); }
            }, null);

            // Video thread
            videoThread = new Thread(() -> {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (isRecording.get()) {
                    int idx = videoEncoder.dequeueOutputBuffer(info, 10000);
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                        checkMuxer();
                    } else if (idx >= 0) {
                        ByteBuffer buf = videoEncoder.getOutputBuffer(idx);
                        if (buf != null && info.size > 0 && muxerStarted) {
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            mediaMuxer.writeSampleData(videoTrackIndex, buf, info);
                        }
                        videoEncoder.releaseOutputBuffer(idx, false);
                    }
                }
            }, "VideoEncoder");

            // Audio thread
            audioThread = new Thread(() -> {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                byte[] buf = new byte[bufferSize];
                while (isRecording.get()) {
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0) {
                        int inIdx = audioEncoder.dequeueInputBuffer(10000);
                        if (inIdx >= 0) {
                            ByteBuffer inBuf = audioEncoder.getInputBuffer(inIdx);
                            inBuf.clear();
                            inBuf.put(buf, 0, read);
                            audioEncoder.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0);
                        }
                    }
                    int outIdx = audioEncoder.dequeueOutputBuffer(info, 10000);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioTrackIndex = mediaMuxer.addTrack(audioEncoder.getOutputFormat());
                        checkMuxer();
                    } else if (outIdx >= 0) {
                        ByteBuffer outBuf = audioEncoder.getOutputBuffer(outIdx);
                        if (outBuf != null && info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            mediaMuxer.writeSampleData(audioTrackIndex, outBuf, info);
                        }
                        audioEncoder.releaseOutputBuffer(outIdx, false);
                    }
                }
            }, "AudioEncoder");

            videoThread.start();
            audioThread.start();
            updateNotification("Recording screen");
        } catch (Exception e) {
            Log.e(TAG, "Start failed", e);
            stopSelf();
        }
    }

    private void checkMuxer() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            mediaMuxer.start();
            muxerStarted = true;
        }
    }

    private void stopRecording() {
        if (!sRunning) { stopSelf(); return; }
        isRecording.set(false);
        sRunning = false;
        try { if (audioRecord != null) audioRecord.stop(); } catch (Exception ignored) {}
        try { if (videoEncoder != null) videoEncoder.stop(); } catch (Exception ignored) {}
        try { if (audioEncoder != null) audioEncoder.stop(); } catch (Exception ignored) {}
        try { if (videoEncoder != null) videoEncoder.release(); } catch (Exception ignored) {}
        try { if (audioEncoder != null) audioEncoder.release(); } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        try { if (mediaMuxer != null) { if (muxerStarted) mediaMuxer.stop(); mediaMuxer.release(); } } catch (Exception ignored) {}
        try { if (mediaProjection != null) mediaProjection.stop(); } catch (Exception ignored) {}
        try { if (audioThread != null) audioThread.join(2000); } catch (Exception ignored) {}
        try { if (videoThread != null) videoThread.join(2000); } catch (Exception ignored) {}
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Screen Recording", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, RecordingService.class); stopIntent.setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        return b.setContentTitle("Screen Recorder").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .addAction(android.R.drawable.ic_media_pause, "Stop", pi)
                .setOngoing(true).build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override public void onDestroy() { stopRecording(); super.onDestroy(); }
}
