package org.telegram.messenger;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.telegram.ui.LaunchActivity;

import java.util.Locale;

public class UserLocationTracker extends Service implements LocationListener {

  public static final boolean ENABLED = false;

  private static final int NOTIFICATION_ID = 47;
  private static final long STATIONARY_LIMIT_MS = 3 * 60 * 1000L;
  private static final long BACKGROUND_BREAK_MS = 3 * 60 * 1000L;
  private static final long LOCATION_INTERVAL_MS = 1000L;
  private static final float SAME_PLACE_METERS = 0.5f;
  private static final int MAX_HISTORY_RECORDS = 360;

  private static final String PREFS_NAME = "user_location_tracker";
  private static final String KEY_LAST_LAT = "last_lat";
  private static final String KEY_LAST_LON = "last_lon";
  private static final String KEY_LAST_TIME = "last_time";
  private static final String KEY_LAST_ACCURACY = "last_accuracy";
  private static final String KEY_PLACE_LAT = "place_lat";
  private static final String KEY_PLACE_LON = "place_lon";
  private static final String KEY_PLACE_ACCURACY = "place_accuracy";
  private static final String KEY_PLACE_START = "place_start";
  private static final String KEY_BACKGROUND_SINCE = "background_since";
  private static final String KEY_WARNING_PENDING = "warning_pending";
  private static final String KEY_WARNING_SHOWN_FOR_START = "warning_shown_for_start";
  private static final String KEY_HISTORY = "history";

  private LocationManager locationManager;
  private Handler handler;
  private boolean updatesStarted;

  public static boolean hasLocationPermission() {
    Context context = ApplicationLoader.applicationContext;
    if (context == null) {
      return false;
    }
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  public static boolean hasBackgroundLocationPermission() {
    Context context = ApplicationLoader.applicationContext;
    if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return true;
    }
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  public static void startIfAllowed() {
    Context context = ApplicationLoader.applicationContext;
    if (!ENABLED || context == null || !hasLocationPermission()) {
      return;
    }
    try {
      Intent intent = new Intent(context, UserLocationTracker.class);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent);
      } else {
        context.startService(intent);
      }
    } catch (Throwable e) {
      FileLog.e(e);
    }
  }

  public static void markAppForeground() {
    if (!ENABLED) {
      return;
    }
    SharedPreferences preferences = getPreferences();
    long now = System.currentTimeMillis();
    long backgroundSince = preferences.getLong(KEY_BACKGROUND_SINCE, 0);
    SharedPreferences.Editor editor = preferences.edit().putLong(KEY_BACKGROUND_SINCE, 0);
    if (backgroundSince > 0 && now - backgroundSince >= BACKGROUND_BREAK_MS) {
      resetPlace(editor);
    }
    editor.apply();
    startIfAllowed();
  }

  public static void markAppBackground() {
    if (!ENABLED) {
      return;
    }
    getPreferences().edit().putLong(KEY_BACKGROUND_SINCE, System.currentTimeMillis()).apply();
  }

  public static boolean isBreakRequired() {
    if (!ENABLED) {
      return false;
    }
    SharedPreferences preferences = getPreferences();
    return preferences.getBoolean(KEY_WARNING_PENDING, false);
  }

  public static long getCountdownRemainingMs() {
    if (!ENABLED) {
      return -1;
    }
    SharedPreferences preferences = getPreferences();
    long now = System.currentTimeMillis();
    if (preferences.getBoolean(KEY_WARNING_PENDING, false)) {
      long backgroundSince = preferences.getLong(KEY_BACKGROUND_SINCE, 0);
      if (backgroundSince <= 0) {
        return BACKGROUND_BREAK_MS;
      }
      return Math.max(0, BACKGROUND_BREAK_MS - (now - backgroundSince));
    }
    long placeStart = preferences.getLong(KEY_PLACE_START, 0);
    if (placeStart <= 0 || ApplicationLoader.mainInterfacePaused) {
      return -1;
    }
    return Math.max(0, STATIONARY_LIMIT_MS - (now - placeStart));
  }

  private static SharedPreferences getPreferences() {
    return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private static void resetPlace(SharedPreferences.Editor editor) {
    editor.remove(KEY_PLACE_LAT);
    editor.remove(KEY_PLACE_LON);
    editor.remove(KEY_PLACE_ACCURACY);
    editor.remove(KEY_PLACE_START);
    editor.remove(KEY_WARNING_PENDING);
    editor.remove(KEY_WARNING_SHOWN_FOR_START);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    handler = new Handler();
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (!ENABLED) {
      stopSelf();
      return START_NOT_STICKY;
    }
    startForegroundNotification();
    requestUpdates();
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (locationManager != null) {
      try {
        locationManager.removeUpdates(this);
      } catch (Throwable e) {
        FileLog.e(e);
      }
    }
    NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
    stopForeground(true);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onLocationChanged(@NonNull Location location) {
    handler.post(() -> recordLocation(location));
  }

  @SuppressWarnings("MissingPermission")
  private void requestUpdates() {
    if (!ENABLED || locationManager == null || !hasLocationPermission()) {
      stopSelf();
      return;
    }
    if (updatesStarted) {
      return;
    }
    updatesStarted = true;
    try {
      locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0, this);
    } catch (Throwable e) {
      FileLog.e(e);
    }
    try {
      locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_INTERVAL_MS, 0, this);
    } catch (Throwable e) {
      FileLog.e(e);
    }
    try {
      locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, LOCATION_INTERVAL_MS, 0, this);
    } catch (Throwable e) {
      FileLog.e(e);
    }
  }

  private void startForegroundNotification() {
    try {
      Intent openIntent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
      openIntent.addCategory(Intent.CATEGORY_LAUNCHER);
      PendingIntent contentIntent = PendingIntent.getActivity(ApplicationLoader.applicationContext, 47, openIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      NotificationsController.checkOtherNotificationsChannel();
      NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
          .setWhen(System.currentTimeMillis())
          .setSmallIcon(R.drawable.live_loc)
          .setContentTitle(LocaleController.getString(R.string.AppName))
          .setContentText("Location usage tracking is active")
          .setContentIntent(contentIntent)
          .setOngoing(true);
      startForeground(NOTIFICATION_ID, builder.build());
    } catch (Throwable e) {
      FileLog.e(e);
    }
  }

  private void recordLocation(Location location) {
    SharedPreferences preferences = getPreferences();
    long now = System.currentTimeMillis();
    SharedPreferences.Editor editor = preferences.edit();
    appendHistory(preferences, editor, location, now);
    editor.putString(KEY_LAST_LAT, Double.toString(location.getLatitude()));
    editor.putString(KEY_LAST_LON, Double.toString(location.getLongitude()));
    editor.putLong(KEY_LAST_TIME, now);
    editor.putFloat(KEY_LAST_ACCURACY, getAccuracy(location));

    long backgroundSince = preferences.getLong(KEY_BACKGROUND_SINCE, 0);
    boolean appPaused = ApplicationLoader.mainInterfacePaused;
    boolean longBackgroundBreak = appPaused && backgroundSince > 0 && now - backgroundSince >= BACKGROUND_BREAK_MS;
    if (longBackgroundBreak) {
      editor.putString(KEY_PLACE_LAT, Double.toString(location.getLatitude()));
      editor.putString(KEY_PLACE_LON, Double.toString(location.getLongitude()));
      editor.putFloat(KEY_PLACE_ACCURACY, getAccuracy(location));
      editor.putLong(KEY_PLACE_START, now);
      editor.remove(KEY_WARNING_PENDING);
      editor.remove(KEY_WARNING_SHOWN_FOR_START);
      editor.apply();
      return;
    }

    double placeLat = parseDouble(preferences.getString(KEY_PLACE_LAT, null), location.getLatitude());
    double placeLon = parseDouble(preferences.getString(KEY_PLACE_LON, null), location.getLongitude());
    float placeAccuracy = preferences.getFloat(KEY_PLACE_ACCURACY, Float.MAX_VALUE);
    long placeStart = preferences.getLong(KEY_PLACE_START, 0);
    if (placeStart == 0) {
      editor.putString(KEY_PLACE_LAT, Double.toString(location.getLatitude()));
      editor.putString(KEY_PLACE_LON, Double.toString(location.getLongitude()));
      editor.putFloat(KEY_PLACE_ACCURACY, getAccuracy(location));
      editor.putLong(KEY_PLACE_START, now);
      editor.apply();
      return;
    }

    float[] distance = new float[1];
    Location.distanceBetween(placeLat, placeLon, location.getLatitude(), location.getLongitude(), distance);
    boolean breakRequired = preferences.getBoolean(KEY_WARNING_PENDING, false);
    float currentAccuracy = getAccuracy(location);
    float effectiveDistance = getEffectiveDistance(distance[0], placeAccuracy, currentAccuracy);
    if (!breakRequired && effectiveDistance > SAME_PLACE_METERS) {
      editor.putString(KEY_PLACE_LAT, Double.toString(location.getLatitude()));
      editor.putString(KEY_PLACE_LON, Double.toString(location.getLongitude()));
      editor.putFloat(KEY_PLACE_ACCURACY, currentAccuracy);
      editor.putLong(KEY_PLACE_START, now);
      editor.remove(KEY_WARNING_PENDING);
      editor.remove(KEY_WARNING_SHOWN_FOR_START);
    } else if (!breakRequired && currentAccuracy < placeAccuracy) {
      editor.putString(KEY_PLACE_LAT, Double.toString(location.getLatitude()));
      editor.putString(KEY_PLACE_LON, Double.toString(location.getLongitude()));
      editor.putFloat(KEY_PLACE_ACCURACY, currentAccuracy);
    } else if (!breakRequired && !appPaused && now - placeStart >= STATIONARY_LIMIT_MS && preferences.getLong(KEY_WARNING_SHOWN_FOR_START, 0) != placeStart) {
      editor.putBoolean(KEY_WARNING_PENDING, true);
      editor.putLong(KEY_WARNING_SHOWN_FOR_START, placeStart);
      editor.apply();
      AndroidUtilities.runOnUIThread(() -> {
        if (LaunchActivity.instance != null) {
          LaunchActivity.instance.showUserLocationTrackerWarningIfNeeded();
        }
      });
      return;
    }
    editor.apply();
  }

  private void appendHistory(SharedPreferences preferences, SharedPreferences.Editor editor, Location location, long now) {
    String item = String.format(Locale.US, "%d,%.7f,%.7f,%.1f", now, location.getLatitude(), location.getLongitude(), getAccuracy(location));
    String history = preferences.getString(KEY_HISTORY, "");
    String updated = history == null || history.length() == 0 ? item : history + "\n" + item;
    int lines = 1;
    for (int i = updated.length() - 1; i >= 0; i--) {
      if (updated.charAt(i) == '\n' && ++lines > MAX_HISTORY_RECORDS) {
        updated = updated.substring(i + 1);
        break;
      }
    }
    editor.putString(KEY_HISTORY, updated);
  }

  private static double parseDouble(String value, double fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      return Double.parseDouble(value);
    } catch (Exception ignore) {
      return fallback;
    }
  }

  private static float getAccuracy(Location location) {
    return location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
  }

  private static float getEffectiveDistance(float distance, float anchorAccuracy, float currentAccuracy) {
    float accuracyMargin = 0;
    if (anchorAccuracy != Float.MAX_VALUE) {
      accuracyMargin += anchorAccuracy;
    }
    if (currentAccuracy != Float.MAX_VALUE) {
      accuracyMargin += currentAccuracy;
    }
    return Math.max(0, distance - accuracyMargin);
  }

  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {
  }

  @Override
  public void onProviderEnabled(String provider) {
  }

  @Override
  public void onProviderDisabled(String provider) {
  }
}
