package org.telegram.ui.Components;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public class FloatingShortcutOverlayService extends Service {

    private static final String CHANNEL_ID = "floating_shortcuts";
    private static final int NOTIFICATION_ID = 9401;
    private static FloatingShortcutOverlayService instance;

    private WindowManager windowManager;
    private ImageView button;
    private WindowManager.LayoutParams params;
    private final ArrayList<OverlayShortcut> shortcutViews = new ArrayList<>();
    private boolean expanded;

    public static void start(Context context) {
        if (ChannelFloatingButton.getChannelUrls(context).isEmpty()
                || Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            return;
        }
        Intent intent = new Intent(context, FloatingShortcutOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void refresh(Context context) {
        if (instance != null) {
            instance.updateVisibility();
        } else if (ChannelFloatingButton.isSystemOverlayEnabled(context)) {
            start(context);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ApplicationLoader.postInitApplication();
        createNotification();
        showButton();
    }

    private void createNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Floating shortcuts", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the floating shortcut available over other apps");
            manager.createNotificationChannel(channel);
        }
        Intent launchIntent = new Intent(this, LaunchActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        startForeground(NOTIFICATION_ID, builder
                .setSmallIcon(R.drawable.notification)
                .setContentTitle("Floating shortcuts")
                .setContentText("Tap to open Nogram")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build());
    }

    private void showButton() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        button = new ImageView(this);
        button.setImageResource(R.drawable.msg_channel);
        button.setColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.SRC_IN);
        button.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        button.setBackground(Theme.createSimpleSelectorCircleDrawable(
                AndroidUtilities.dp(56),
                Theme.getColor(Theme.key_chats_actionBackground),
                Theme.getColor(Theme.key_chats_actionPressedBackground)));
        button.setContentDescription("Open floating shortcut");

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                AndroidUtilities.dp(56), AndroidUtilities.dp(56), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = getSharedPreferences("channel_floating_button", MODE_PRIVATE).getInt("overlay_x", 0);
        params.y = getSharedPreferences("channel_floating_button", MODE_PRIVATE).getInt("overlay_y", AndroidUtilities.dp(240));
        button.setOnTouchListener(new DragTouchListener());
        windowManager.addView(button, params);
        updateVisibility();
    }

    private void updateVisibility() {
        if (button == null) {
            return;
        }
        boolean visible = ChannelFloatingButton.isSystemOverlayEnabled(this)
                && !ChannelFloatingButton.getChannelUrls(this).isEmpty();
        button.setVisibility(visible ? ImageView.VISIBLE : ImageView.GONE);
        if (!visible) {
            collapseShortcuts();
            stopSelf();
        }
    }

    private WindowManager.LayoutParams createShortcutParams(int x, int y) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams childParams = new WindowManager.LayoutParams(
                AndroidUtilities.dp(48), AndroidUtilities.dp(48), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        childParams.gravity = Gravity.TOP | Gravity.LEFT;
        childParams.x = x;
        childParams.y = y;
        return childParams;
    }

    private void toggleShortcuts() {
        if (expanded) {
            collapseShortcuts();
            return;
        }
        ArrayList<String> links = ChannelFloatingButton.getChannelUrls(this);
        if (links.size() == 1) {
            openShortcut(links.get(0));
            return;
        }
        expanded = true;
        android.graphics.Point screen = new android.graphics.Point();
        windowManager.getDefaultDisplay().getSize(screen);
        float mainCenterX = params.x + AndroidUtilities.dp(28);
        float mainCenterY = params.y + AndroidUtilities.dp(28);
        double centerAngle = Math.atan2(screen.y / 2f - mainCenterY, screen.x / 2f - mainCenterX);
        double span = Math.toRadians(30 + links.size() * 18);
        float radius = AndroidUtilities.dp(108);

        for (int i = 0; i < links.size(); i++) {
            String link = links.get(i);
            double angle = centerAngle - span / 2d + span * i / (links.size() - 1d);
            int x = Math.round(mainCenterX + (float) Math.cos(angle) * radius - AndroidUtilities.dp(24));
            int y = Math.round(mainCenterY + (float) Math.sin(angle) * radius - AndroidUtilities.dp(24));
            x = Math.max(0, Math.min(screen.x - AndroidUtilities.dp(48), x));
            y = Math.max(0, Math.min(screen.y - AndroidUtilities.dp(48), y));

            BackupImageView shortcut = new BackupImageView(this);
            shortcut.setRoundRadius(AndroidUtilities.dp(24));
            shortcut.setSize(AndroidUtilities.dp(22), AndroidUtilities.dp(22));
            shortcut.setImageResource(R.drawable.msg_channel, Theme.getColor(Theme.key_chats_actionIcon));
            shortcut.setBackground(Theme.createSimpleSelectorCircleDrawable(
                    AndroidUtilities.dp(48),
                    Theme.getColor(Theme.key_chats_actionBackground),
                    Theme.getColor(Theme.key_chats_actionPressedBackground)));
            shortcut.setContentDescription(ChannelFloatingButton.getDisplayLink(link));
            shortcut.setOnClickListener(view -> {
                collapseShortcuts();
                openShortcut(link);
            });
            WindowManager.LayoutParams childParams = createShortcutParams(x, y);
            windowManager.addView(shortcut, childParams);
            shortcutViews.add(new OverlayShortcut(shortcut, childParams));
            ChannelFloatingButton.bindConfiguredAvatar(shortcut, link);
            shortcut.setScaleX(0.35f);
            shortcut.setScaleY(0.35f);
            shortcut.setAlpha(0f);
            shortcut.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setStartDelay(i * 35L)
                    .setDuration(220)
                    .start();
        }
        button.animate().rotation(90f).setDuration(200).start();
    }

    private void openShortcut(String link) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(ChannelFloatingButton.getConfiguredOpenUrl(link)), this, LaunchActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int requestCode = link.hashCode();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            pendingIntent.send();
        } catch (PendingIntent.CanceledException ignore) {
            ChannelFloatingButton.openConfiguredLink(this, link);
        }
    }

    private void collapseShortcuts() {
        expanded = false;
        if (button != null) {
            button.animate().rotation(0f).setDuration(180).start();
        }
        for (OverlayShortcut shortcut : shortcutViews) {
            try {
                if (windowManager != null) {
                    windowManager.removeView(shortcut.view);
                }
            } catch (Exception ignore) {
            }
        }
        shortcutViews.clear();
    }

    private static class OverlayShortcut {
        final BackupImageView view;
        final WindowManager.LayoutParams params;

        OverlayShortcut(BackupImageView view, WindowManager.LayoutParams params) {
            this.view = view;
            this.params = params;
        }
    }

    private class DragTouchListener implements android.view.View.OnTouchListener {
        private final int slop = ViewConfiguration.get(FloatingShortcutOverlayService.this).getScaledTouchSlop();
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;

        @Override
        public boolean onTouch(android.view.View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true;
                        collapseShortcuts();
                    }
                    if (dragging) {
                        params.x = startX + Math.round(dx);
                        params.y = startY + Math.round(dy);
                        windowManager.updateViewLayout(button, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        getSharedPreferences("channel_floating_button", MODE_PRIVATE).edit()
                                .putInt("overlay_x", params.x)
                                .putInt("overlay_y", params.y)
                                .apply();
                    } else {
                        toggleShortcuts();
                    }
                    return true;
            }
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateVisibility();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        collapseShortcuts();
        if (button != null && windowManager != null) {
            windowManager.removeView(button);
            button = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
