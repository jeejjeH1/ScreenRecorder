package com.screenrecorder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingService extends Service {

    // Static constants (replacing companion object)
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String NOTIFICATION_CHANNEL_ID = "screen_recording_channel";
    public static final int NOTIFICATION_ID = 1001;
    private static boolean sIsRunning = false;

    public static boolean isRunning() {
        return sIsRunning;
    }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private AudioRecord audioRecord;
    private MediaMuxer mediaMuxer;

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;

    private boolean isMuxerStarted = false;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();

    private int screenWidth = 1080;
    private int screenHeight = 1920;
    private int screenDpi = 320;

    private String outputPath = "";
    private Thread recordingThread;
    private Thread audioThread;
    private Thread muxerThread;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @SuppressWarnings("deprecation")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }

        int resultCode = intent != null ? intent.getIntExtra("resultCode", -1) : -1;
        Intent data = intent != null ? intent.getParcelableExtra("data") : null;

        if (resultCode == -1 || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start foreground immediately
        startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی..."));

        // Get screen metrics
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDpi = metrics.densityDpi;

        // Ensure dimensions are even (required by encoder)
        screenWidth = (screenWidth / 2) * 2;
        screenHeight = (screenHeight / 2) * 2;

        // Create output file
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "ScreenRecord_" + timestamp + ".mp4";

        File storageDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            storageDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenRecordings");
        } else {
            storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenRecordings");
        }
        storageDir.mkdirs();
        File file = new File(storageDir, fileName);
        outputPath = file.getAbsolutePath();

        // Start recording
        try {
            startRecording(resultCode, data);
            sIsRunning = true;
            updateNotification("در حال ضبط صفحه");
        } catch (Exception e) {
            Log.e("RecordingService", "Recording failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startRecording(int resultCode, Intent data) {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (sIsRunning) {
                    stopRecording();
                }
            }
        }, null);

        // Setup video encoder
        MediaFormat videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000); // 8 Mbps
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0); // REAL_TIME

        try {
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        } catch (Exception e) {
            Log.e("RecordingService", "Failed to create video encoder", e);
            return;
        }
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // Setup muxer
        try {
            mediaMuxer = new MediaMuxer(outputPath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Exception e) {
            Log.e("RecordingService", "Failed to create muxer", e);
            return;
        }

        // Setup audio encoder
        int sampleRate = 44100;
        int channelCount = 1;
        MediaFormat audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        try {
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        } catch (Exception e) {
            Log.e("RecordingService", "Failed to create audio encoder", e);
            return;
        }
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // Setup audio record
        int bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        // Start everything
        videoEncoder.start();
        audioEncoder.start();
        audioRecord.startRecording();

        // Create input surface for video
        android.view.Surface inputSurface = videoEncoder.createInputSurface();

        // Create virtual display
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth,
                screenHeight,
                screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
        );

        // Start encoding threads
        startVideoEncoding();
        startAudioEncoding();
    }

    private void startVideoEncoding() {
        recordingThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long timeoutUs = 10_000L;

            while (sIsRunning) {
                int index = videoEncoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (RecordingService.this) {
                        videoTrackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                        checkAndStartMuxer();
                    }
                } else if (index >= 0) {
                    ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(index);
                    if (outputBuffer == null) continue;
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0 && isMuxerStarted) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        synchronized (RecordingService.this) {
                            try {
                                mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                            } catch (Exception e) {
                                Log.e("RecordingService", "Error writing video data", e);
                            }
                        }
                    }
                    videoEncoder.releaseOutputBuffer(index, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        });
        recordingThread.start();
    }

    private void startAudioEncoding() {
        audioThread = new Thread(() -> {
            int bufferSize = 4096;
            byte[] buffer = new byte[bufferSize];
            long timeoutUs = 10_000L;

            while (sIsRunning) {
                // Read audio from microphone
                int readSize = audioRecord.read(buffer, 0, bufferSize);
                if (readSize <= 0) continue;

                // Feed to encoder
                int inputIndex = audioEncoder.dequeueInputBuffer(timeoutUs);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputIndex);
                    if (inputBuffer == null) continue;
                    inputBuffer.clear();
                    inputBuffer.put(buffer, 0, readSize);
                    audioEncoder.queueInputBuffer(inputIndex, 0, readSize,
                            System.nanoTime() / 1000, 0);
                }

                // Get encoded output
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
                while (outputIndex >= 0) {
                    ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputIndex);
                    if (outputBuffer == null) break;
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0 && isMuxerStarted) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        synchronized (RecordingService.this) {
                            try {
                                mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo);
                            } catch (Exception e) {
                                Log.e("RecordingService", "Error writing audio data", e);
                            }
                        }
                    }
                    audioEncoder.releaseOutputBuffer(outputIndex, false);
                    outputIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                }
            }
        });
        audioThread.start();
    }

    private synchronized void checkAndStartMuxer() {
        if (!isMuxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            mediaMuxer.start();
            isMuxerStarted = true;
        }
    }

    private void stopRecording() {
        sIsRunning = false;

        try {
            if (recordingThread != null) {
                recordingThread.join(3000);
            }
            if (audioThread != null) {
                audioThread.join(3000);
            }

            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }

            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
                audioEncoder = null;
            }

            if (videoEncoder != null) {
                videoEncoder.stop();
                videoEncoder.release();
                videoEncoder = null;
            }

            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }

            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }

            if (isMuxerStarted) {
                mediaMuxer.stop();
            }
            if (mediaMuxer != null) {
                mediaMuxer.release();
                mediaMuxer = null;
            }
            isMuxerStarted = false;
            videoTrackIndex = -1;
            audioTrackIndex = -1;

            // Notify gallery
            MediaScannerConnection.scanFile(
                    this,
                    new String[]{outputPath},
                    new String[]{"video/mp4"},
                    null
            );

        } catch (Exception e) {
            Log.e("RecordingService", "Error stopping recording", e);
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "ضبط صفحه",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("کنترل ضبط صفحه");
            channel.setShowBadge(false);
            NotificationManager notificationManager =
                    getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Screen Recorder")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_record)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(
                        new Notification.Action.Builder(
                                null, "توقف", stopPendingIntent
                        ).build()
                )
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sIsRunning) {
            stopRecording();
        }
    }
}
