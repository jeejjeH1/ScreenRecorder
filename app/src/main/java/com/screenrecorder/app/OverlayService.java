package com.screenrecorder.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class OverlayService extends Service {
    private WindowManager wm;
    private View overlayView;
    private boolean isRunning = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) showOverlay();
        return START_STICKY;
    }

    private void showOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(180, 180, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0; params.y = 400;

        final int[] lastX = {0}, lastY = {0}, startX = {0}, startY = {0}, initialX = {0}, initialY = {0};
        final boolean[] moving = {false};

        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX[0] = params.x; initialY[0] = params.y;
                    startX[0] = (int) event.getRawX(); startY[0] = (int) event.getRawY();
                    moving[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - startX[0], dy = (int) event.getRawY() - startY[0];
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moving[0] = true;
                    params.x = initialX[0] + dx; params.y = initialY[0] + dy;
                    wm.updateViewLayout(overlayView, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moving[0]) {
                        Intent i = new Intent(OverlayService.this, RecordingService.class);
                        i.setAction(RecordingService.ACTION_STOP);
                        startService(i);
                        stopSelf();
                    }
                    return true;
            }
            return false;
        });

        wm.addView(overlayView, params);
        isRunning = true;
    }

    @Override public void onDestroy() {
        if (overlayView != null) { wm.removeView(overlayView); overlayView = null; }
        isRunning = false;
        super.onDestroy();
    }
}
