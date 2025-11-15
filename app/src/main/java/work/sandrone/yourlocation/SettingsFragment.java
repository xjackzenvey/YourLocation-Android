package work.sandrone.yourlocation;

import android.os.Bundle;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import android.os.Build;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;


// SettingsFragment.java
public class SettingsFragment extends Fragment {

    private SwitchMaterial switchEnableReporting;
    private TextInputEditText editInterval, editServerUrl, editToken;
    private Button btnSave;

    private static final String TAG = "SettingsFragment";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        switchEnableReporting = view.findViewById(R.id.switchEnableReporting);
        editInterval = view.findViewById(R.id.editInterval);
        editServerUrl = view.findViewById(R.id.editServerUrl);
        editToken = view.findViewById(R.id.editToken);
        btnSave = view.findViewById(R.id.btnSave);

        loadSettings();

        btnSave.setOnClickListener(v -> saveSettings());
        switchEnableReporting.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "Reporting switched: " + isChecked);

            // ✅ 立即保存开关状态到 SharedPreferences
            SharedPreferences prefs = requireActivity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("enable_reporting", isChecked).apply();

            // 再启停服务
            toggleService(isChecked);
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = requireActivity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enable_reporting", false);
        String interval = prefs.getString("report_interval", "30");
        String url = prefs.getString("server_url", "https://yourserver.com");
        String token = prefs.getString("token", "");

        switchEnableReporting.setChecked(enabled);
        editInterval.setText(interval);
        editServerUrl.setText(url);
        editToken.setText(token);

        Log.d(TAG, "Loaded settings: enabled=" + enabled + ", interval=" + interval + ", url=" + url);
    }

    private void saveSettings() {
        String intervalStr = editInterval.getText().toString().trim();
        String url = editServerUrl.getText().toString().trim();
        String token = editToken.getText().toString().trim();

        if (intervalStr.isEmpty() || !intervalStr.matches("\\d+") || Integer.parseInt(intervalStr) < 5) {
            Toast.makeText(getContext(), "上报间隔必须是 ≥5 的数字", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!url.startsWith("https://")) {
            Toast.makeText(getContext(), "服务器地址必须以 https:// 开头", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = requireActivity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("enable_reporting", switchEnableReporting.isChecked()) // ✅ 确保保存
                .putString("report_interval", intervalStr)
                .putString("server_url", url)
                .putString("token", token)
                .apply();

        Log.d(TAG, "Settings saved with enable=" + switchEnableReporting.isChecked());

        Toast.makeText(getContext(), "设置已保存", Toast.LENGTH_SHORT).show();
    }

    private void toggleService(boolean enable) {
        Context ctx = requireContext();
        Intent serviceIntent = new Intent(ctx, LocationReportingService.class);
        if (enable) {
            Log.d(TAG, "Starting foreground service...");
            // ✅ 兼容所有版本
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent);
            } else {
                ctx.startService(serviceIntent);
            }
        } else {
            ctx.stopService(serviceIntent);
        }
    }
}