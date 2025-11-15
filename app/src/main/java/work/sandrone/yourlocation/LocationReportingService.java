package work.sandrone.yourlocation;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Binder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import android.content.SharedPreferences;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.os.Looper;
import android.util.Log;
import okhttp3.*;

// LocationReportingService.java
public class LocationReportingService extends Service {
    private static final int NOTIFICATION_ID = 100;
    private static final String TAG = "LocationReportingService";
    private LocationManager locationManager;
    private LocationListener locationListener;
    private ScheduledExecutorService scheduler;
    private OkHttpClient client = new OkHttpClient();

    private boolean running = false;
    private int successCount = 0, errorCount = 0;
    private String currentLocationStr = "未知";
    private String connectionStatus = "未连接";
    private Location latestLocation = null;
    private final IBinder binder = new LocationBinder();

    public class LocationBinder extends Binder {
        public LocationReportingService getService() {
            return LocationReportingService.this;
        }

        public String getCurrentLocation() { return currentLocationStr; }
        public String getConnectionStatus() { return connectionStatus; }
        public int getSuccessCount() { return successCount; }
        public int getErrorCount() { return errorCount; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        // ✅ 立即启动前台通知（必须在 5 秒内）
        startForeground(NOTIFICATION_ID, createNotification("服务启动中..."));

        // ✅ 在主线程中初始化（使用 Handler 或直接调用）
        new Handler(Looper.getMainLooper()).post(() -> {
            initializeReporting(); // 这个方法现在在主线程运行
        });

        return START_STICKY;
    }

    private void initializeReporting() {
        Log.d(TAG, "Initializing reporting (timer-based only)...");

        SharedPreferences prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enable_reporting", false);
        if (!enabled) {
            Log.d(TAG, "Reporting disabled in settings, stopping");
            stopSelf();
            return;
        }

        running = true;

        // === 1. 注册定位监听（仅用于获取最新位置，不上报）===
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                latestLocation = location;
                currentLocationStr = String.format("%.6f, %.6f", location.getLongitude(), location.getLatitude());
                Log.d(TAG, "Latest location updated: " + currentLocationStr);
                saveServiceState(); // 更新 UI 显示
            }
        };

        try {
            // 持续监听位置（但不上报）
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000,  // 至少5秒更新一次（省电）
                    10,    // 至少移动10米
                    locationListener
            );
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to request location updates", e);
        }

        // === 2. 启动心跳（每6秒）===
        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleAtFixedRate(() -> sendPing(prefs), 0, 6, TimeUnit.SECONDS);

        // === 3. 启动定时上报（按用户设置的间隔）===
        String intervalStr = prefs.getString("report_interval", "30");
        int intervalSec;
        try {
            intervalSec = Integer.parseInt(intervalStr);
            intervalSec = Math.max(5, intervalSec); // 最小5秒
        } catch (NumberFormatException e) {
            intervalSec = 30;
        }

        Log.d(TAG, "Starting timer-based upload every " + intervalSec + " seconds");
        scheduler.scheduleAtFixedRate(() -> {
            if (running && latestLocation != null) {
                Log.d(TAG, "Timer triggered: uploading latest location");
                uploadLatestLocation(prefs);
            } else {
                Log.d(TAG, "Skip upload: running=" + running + ", latestLocation=" + (latestLocation != null));
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);

        updateNotification("服务运行中");
    }
    private void uploadLatestLocation(SharedPreferences prefs) {
        // 在后台线程执行
        scheduler.execute(() -> {
            if (!running) return;

            Location loc = latestLocation;
            if (loc == null) {
                Log.w(TAG, "No location available for upload");
                return;
            }

            String baseUrl = prefs.getString("server_url", "").trim();
            String token = prefs.getString("token", "").trim();

            if (baseUrl.isEmpty() || !baseUrl.startsWith("https://")) {
                Log.w(TAG, "Invalid server URL");
                errorCount++;
                saveServiceState();
                return;
            }

            if (token.isEmpty()) {
                Log.w(TAG, "Token is empty");
                /*
                errorCount++;
                saveServiceState();
                return;
                // 允许 空的token
                 */
            }

            String url = baseUrl + "/api/position";
            url = url.replace("\n", "").replace("\r", "");
            Log.d(TAG, "Uploading position (timer-based): " + url);

            try {
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("model", Build.MODEL);
                deviceInfo.put("manufactual", Build.MANUFACTURER);
                deviceInfo.put("brand", Build.BRAND);

                JSONObject position = new JSONObject();
                position.put("Longitude", loc.getLongitude());
                position.put("Latitude", loc.getLatitude());

                JSONObject body = new JSONObject();
                body.put("Token", token);
                body.put("DeviceInfo", deviceInfo);
                body.put("position", position);
                body.put("timestamp", System.currentTimeMillis() / 1000);

                RequestBody requestBody = RequestBody.create(
                        body.toString(),
                        MediaType.get("application/json; charset=utf-8")
                );

                Request request = new Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        successCount++;
                        Log.d(TAG, "Position upload success");
                    } else {
                        errorCount++;
                        Log.w(TAG, "Upload failed, code: " + response.code());
                    }
                }
            } catch (Exception e) {
                errorCount++;
                Log.e(TAG, "Upload exception", e);
            } finally {
                saveServiceState();
            }
        });
    }


    private void handleLocationUpdate(Location loc) {
        if (loc == null) {
            Log.w(TAG, "Received null location");
            return;
        }

        // 1. 更新当前定位字符串（保留6位小数，符合常见精度）
        currentLocationStr = String.format("%.6f, %.6f", loc.getLongitude(), loc.getLatitude());
        Log.d(TAG, "Location updated: " + currentLocationStr);

        // 2. 立即保存状态（位置变更，即使未上报也应显示）
        saveServiceState();

        // 3. 异步上报（避免阻塞主线程，因为 onLocationChanged 在主线程回调）
        scheduler.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                boolean enabled = prefs.getBoolean("enable_reporting", false);
                if (!enabled) {
                    Log.d(TAG, "Reporting disabled during location update, skip upload");
                    return;
                }

                sendPosition(prefs, loc);
            } catch (Exception e) {
                Log.e(TAG, "Error in async location upload", e);
                errorCount++;
                saveServiceState();
            }
        });
    }



    // sendPing 方法
    private void sendPing(SharedPreferences prefs) {
        String baseUrl = prefs.getString("server_url", "").trim();
        baseUrl = baseUrl.replace("\n", "").replace("\r", "");
        if (baseUrl.isEmpty() || !baseUrl.startsWith("https://")) {
            Log.w(TAG, "Invalid server URL");
            connectionStatus = "服务器地址无效";
            saveServiceState();
            return;
        }
        String url = baseUrl + "/api/ping";
        Log.d(TAG, "Sending ping to: " + url);

        try {
            Request request = new Request.Builder().url(url).head().build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                connectionStatus = "连接成功";
            } else if (response.code() == 401) {
                connectionStatus = "鉴权失败";
            } else {
                connectionStatus = "服务返回错误: " + response.code();
            }
            Log.d(TAG, "Ping response: " + response.code());
        } catch (Exception e) {
            Log.e(TAG, "Ping failed", e);
            connectionStatus = "网络错误: " + e.getMessage();
        }
        saveServiceState();
    }

    private void sendPosition(SharedPreferences prefs, Location loc) {
        String url = prefs.getString("server_url", "") + "/api/position";
        String token = prefs.getString("token", "");
        int interval = Integer.parseInt(prefs.getString("report_interval", "30"));

        JSONObject body = new JSONObject();
        try {
            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("model", Build.MODEL);
            deviceInfo.put("manufactual", Build.MANUFACTURER);
            deviceInfo.put("brand", Build.BRAND);

            JSONObject position = new JSONObject();
            position.put("Longitude", loc.getLongitude());
            position.put("Latitude", loc.getLatitude());

            body.put("Token", token);
            body.put("DeviceInfo", deviceInfo);
            body.put("position", position);
            body.put("timestamp", System.currentTimeMillis() / 1000);

            RequestBody requestBody = RequestBody.create(body.toString(), MediaType.get("application/json"));
            Request request = new Request.Builder().url(url).post(requestBody).build();


            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    errorCount++;
                    saveServiceState();
                    updateNotification("位置上报失败: " + e.getMessage());
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) {
                    if (response.isSuccessful()) {
                        successCount++;
                    } else {
                        errorCount++;
                    }
                    Log.d(TAG, "Sending position: " + body.toString());
                    Log.d(TAG, "Position upload success: " + response.isSuccessful() + ", code=" + response.code());
                    saveServiceState();
                    updateNotification("位置上报完成，状态码: " + response.code());
                }
            });

        } catch (Exception e) {
            errorCount++;
        }
    }

    private Notification createNotification(String content) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "LOC_CHANNEL", "位置上报", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台位置上报服务");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, "LOC_CHANNEL")
                .setContentTitle("位置上报服务")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        Notification notification = createNotification(content);
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    private void saveServiceState() {
        getSharedPreferences("service_state", Context.MODE_PRIVATE).edit()
                .putString("current_location", currentLocationStr)
                .putString("connection_status", connectionStatus)
                .putInt("success_count", successCount)
                .putInt("error_count", errorCount)
                .apply();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationListener != null) {
            lm.removeUpdates(locationListener);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}