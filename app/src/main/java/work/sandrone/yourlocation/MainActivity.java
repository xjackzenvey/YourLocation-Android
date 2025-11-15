package work.sandrone.yourlocation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.content.pm.PackageManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.util.Log;

// MainActivity.java
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "MainActivity onCreate, hasPermissions=" + PermissionUtils.hasPermissions(this));
        createNotificationChannel();

        // ✅ 先检查权限，通过后再设置布局！
        if (PermissionUtils.hasPermissions(this)) {
            Log.d(TAG, "Permissions OK, inflating UI");
            resetReportingState();
            setContentView(R.layout.activity_main);
            setupViewPager();
        } else {
            Log.d(TAG, "Permissions missing, requesting...");
            PermissionUtils.requestPermissions(this);
            // 不要 setContentView！等权限通过后再设置
        }
    }

    private void resetReportingState() {
        SharedPreferences settings = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        // 默认关闭上报
        editor.putBoolean("enable_reporting", false);
        editor.apply();

        // 清空服务状态（成功/失败次数、位置、连接状态）
        SharedPreferences serviceState = getSharedPreferences("service_state", Context.MODE_PRIVATE);
        serviceState.edit()
                .putString("current_location", "未知")
                .putString("connection_status", "未连接")
                .putInt("success_count", 0)
                .putInt("error_count", 0)
                .apply();

        // 可选：停止可能残留的服务
        stopService(new Intent(this, LocationReportingService.class));

        Log.d(TAG, "Reporting state reset: reporting disabled, counters cleared");
    }

    private void setupViewPager() {
        ViewPager2 viewPager = findViewById(R.id.viewPager);
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);

        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                viewPager.setCurrentItem(0);
                return true;
            } else if (itemId == R.id.nav_settings) {
                viewPager.setCurrentItem(1);
                return true;
            }
            return false;
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                bottomNav.getMenu().getItem(position).setChecked(true);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.d(TAG, "Permissions granted, now setting content view");
                // ✅ 关键：在这里设置布局！
                setContentView(R.layout.activity_main);
                setupViewPager();
            } else {
                Log.d(TAG, "Permissions denied");
                showPermissionDeniedDialog();
            }
        }
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限被拒绝")
                .setMessage("请在设置中手动开启位置和网络权限，否则应用无法运行。")
                .setPositiveButton("去设置", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("退出", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "LOC_CHANNEL", "位置上报", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台位置上报服务");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }
}