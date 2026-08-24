package com.screenrecorder.app;

import android.Manifest;
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

public class MainActivity extends Activity {

    private TextView timerText, statusText, hintText;
    private LinearLayout livePill;
    private View glowRing, btnRecord;
    private ImageView btnIcon;
    private boolean isRecording = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int seconds = 0;

    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            seconds++;
            int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
            timerText.setText(h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
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
        btnRecord.setOnClickListener(v -> { if (isRecording) stopRecording(); else checkPerms(); });
    }

    @Override protected void onResume() { super.onResume(); isRecording = RecordingService.isRunning(); updateUI(); }
    @Override protected void onDestroy() { super.onDestroy(); handler.removeCallbacks(timerRunnable); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 100 && res == RESULT_OK && data != null) startRec(res, data);
    }

    private void checkPerms() {
        java.util.ArrayList<String> n = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) n.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) n.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!n.isEmpty()) requestPermissions(n.toArray(new String[0]), 101);
        else requestScreenCapture();
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (c == 101) { boolean ok = true; for (int i : r) if (i != PackageManager.PERMISSION_GRANTED) ok = false; if (ok) requestScreenCapture(); }
    }

    private void requestScreenCapture() {
        startActivityForResult(((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent(), 100);
    }

    private void startRec(int rc, Intent data) {
        Intent i = new Intent(this, RecordingService.class);
        i.putExtra("resultCode", rc); i.putExtra("data", data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        startService(new Intent(this, OverlayService.class));
        isRecording = true; seconds = 0; updateUI();
    }

    private void stopRecording() {
        Intent i = new Intent(this, RecordingService.class); i.setAction(RecordingService.ACTION_STOP);
        startService(i); stopService(new Intent(this, OverlayService.class));
        isRecording = false; handler.removeCallbacks(timerRunnable); updateUI();
        Toast.makeText(this, "Recording saved!", Toast.LENGTH_SHORT).show();
    }

    private void updateUI() {
        if (isRecording) {
            btnIcon.setImageResource(R.drawable.ic_stop_white);
            btnRecord.setBackgroundResource(R.drawable.circle_stop_btn);
            statusText.setText("Recording..."); statusText.setTextColor(0xFFFCA5A5);
            hintText.setText("Tap to stop recording");
            livePill.setVisibility(View.VISIBLE); glowRing.setVisibility(View.VISIBLE); glowRing.setAlpha(0f);
            ObjectAnimator a = ObjectAnimator.ofFloat(glowRing, "alpha", 0f, 0.6f);
            a.setDuration(1200); a.setInterpolator(new AccelerateDecelerateInterpolator());
            a.setRepeatMode(ObjectAnimator.REVERSE); a.setRepeatCount(ObjectAnimator.INFINITE); a.start();
            btnRecord.setScaleX(0.8f); btnRecord.setScaleY(0.8f);
            btnRecord.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator()).start();
            handler.post(timerRunnable);
        } else {
            btnIcon.setImageResource(R.drawable.ic_record);
            btnRecord.setBackgroundResource(R.drawable.circle_record_btn);
            timerText.setText("00:00"); statusText.setText("Ready to record"); statusText.setTextColor(0xFF9898A8);
            hintText.setText("Tap to start recording");
            livePill.setVisibility(View.GONE); glowRing.setVisibility(View.GONE);
            glowRing.animate().cancel(); btnRecord.animate().cancel();
            btnRecord.setScaleX(1f); btnRecord.setScaleY(1f);
        }
    }
}
