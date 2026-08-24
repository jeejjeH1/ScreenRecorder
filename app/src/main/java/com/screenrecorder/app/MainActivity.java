package com.screenrecorder.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;

public class MainActivity extends Activity {

    private TextView timerText, statusText, hintText;
    private LinearLayout livePill;
    private View glowRing, btnRecord;
    private ImageView btnIcon;

    private boolean isRecording = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int seconds = 0;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            seconds++;
            int h = seconds / 3600;
            int m = (seconds % 3600) / 60;
            int s = seconds % 60;
            timerText.setText(h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        timerText = findViewById(R.id.timerText);
        statusText = findViewById(R.id.statusText);
        hintText = findViewById(R.id.hintText);
        livePill = findViewById(R.id.livePill);
        glowRing = findViewById(R.id.glowRing);
        btnRecord = findViewById(R.id.btnRecord);
        btnIcon = findViewById(R.id.btnIcon);

        isRecording = RecordingService.isRunning();
        updateUI();

        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else checkPermissionsAndStart();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRecording = RecordingService.isRunning();
        updateUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(timerRunnable);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            startRecordingService(resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            boolean allGranted = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (allGranted) requestScreenCapture();
            else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkPermissionsAndStart() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 101);
        } else {
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), 100);
    }

    private void startRecordingService(int resultCode, Intent data) {
        Intent intent = new Intent(this, RecordingService.class);
        intent.putExtra("resultCode", resultCode);
        intent.putExtra("data", data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        startService(new Intent(this, OverlayService.class));
        isRecording = true;
        seconds = 0;
        updateUI();
    }

    private void stopRecording() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_STOP);
        startService(intent);
        stopService(new Intent(this, OverlayService.class));
        isRecording = false;
        handler.removeCallbacks(timerRunnable);
        updateUI();
        Toast.makeText(this, "Recording saved!", Toast.LENGTH_SHORT).show();
    }

    private void updateUI() {
        if (isRecording) {
            btnIcon.setImageResource(R.drawable.ic_stop_white);
            btnRecord.setBackgroundResource(R.drawable.circle_stop_btn);
            statusText.setText("Recording...");
            statusText.setTextColor(0xFFFCA5A5);
            hintText.setText("Tap to stop recording");
            livePill.setVisibility(View.VISIBLE);
            glowRing.setVisibility(View.VISIBLE);
            glowRing.setAlpha(0f);
            ObjectAnimator.ofFloat(glowRing, "alpha", 0f, 0.6f).setDuration(1200);
            ObjectAnimator anim = ObjectAnimator.ofFloat(glowRing, "alpha", 0f, 0.6f);
            anim.setDuration(1200);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            anim.setRepeatMode(ObjectAnimator.REVERSE);
            anim.setRepeatCount(ObjectAnimator.INFINITE);
            anim.start();
            btnRecord.setScaleX(0.8f); btnRecord.setScaleY(0.8f);
            btnRecord.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator()).start();
            handler.post(timerRunnable);
        } else {
            btnIcon.setImageResource(R.drawable.ic_record);
            btnRecord.setBackgroundResource(R.drawable.circle_record_btn);
            timerText.setText("00:00");
            statusText.setText("Ready to record");
            statusText.setTextColor(0xFF9898A8);
            hintText.setText("Tap to start recording");
            livePill.setVisibility(View.GONE);
            glowRing.setVisibility(View.GONE);
            glowRing.animate().cancel();
            btnRecord.animate().cancel();
            btnRecord.setScaleX(1f); btnRecord.setScaleY(1f);
        }
    }
}
