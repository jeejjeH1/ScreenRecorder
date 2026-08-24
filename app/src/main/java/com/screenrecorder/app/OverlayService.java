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

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;
    private boolean overlayViewInitialized = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Inflate overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null);
        overlayViewInitialized = true;

        // Layout params for floating button
        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 300;

        // Make the button draggable
        final int[] initialX = {0};
        final int[] initialY = {0};
        final float[] initialTouchX = {0f};
        final float[] initialTouchY = {0f};

        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX[0] = params.x;
                    initialY[0] = params.y;
                    initialTouchX[0] = event.getRawX();
                    initialTouchY[0] = event.getRawY();
                    return true;

                case MotionEvent.ACTION_UP:
                    // If it was just a tap (not a drag)
                    float dx = Math.abs(event.getRawX() - initialTouchX[0]);
                    float dy = Math.abs(event.getRawY() - initialTouchY[0]);
                    if (dx < 10 && dy < 10) {
                        // Stop recording
                        Intent intent = new Intent(OverlayService.this, RecordingService.class);
                        intent.setAction(RecordingService.ACTION_STOP);
                        startService(intent);

                        Intent mainIntent = new Intent(OverlayService.this, MainActivity.class);
                        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(mainIntent);

                        stopSelf();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    params.x = initialX[0] + (int) (event.getRawX() - initialTouchX[0]);
                    params.y = initialY[0] + (int) (event.getRawY() - initialTouchY[0]);
                    windowManager.updateViewLayout(overlayView, params);
                    return true;

                default:
                    return false;
            }
        });

        windowManager.addView(overlayView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayViewInitialized) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                // View already removed
            }
        }
    }
}
