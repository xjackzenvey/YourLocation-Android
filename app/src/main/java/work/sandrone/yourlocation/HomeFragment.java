package work.sandrone.yourlocation;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import android.content.SharedPreferences;
import android.util.Log;


public class HomeFragment extends Fragment {

    private TextView connectionStatus, locationText, reportCount;
    private ScheduledExecutorService uiUpdater;
    private SharedPreferences statePrefs;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        connectionStatus = view.findViewById(R.id.connectionStatus);
        locationText = view.findViewById(R.id.locationText);
        reportCount = view.findViewById(R.id.reportCount);

        statePrefs = requireActivity().getSharedPreferences("service_state", Context.MODE_PRIVATE);

        uiUpdater = Executors.newSingleThreadScheduledExecutor();
        uiUpdater.scheduleAtFixedRate(this::updateUIFromPrefs, 0, 2, TimeUnit.SECONDS);
    }

    private void updateUIFromPrefs() {
        String conn = statePrefs.getString("connection_status", "未连接");
        String loc = statePrefs.getString("current_location", "未知");
        int success = statePrefs.getInt("success_count", 0);
        int error = statePrefs.getInt("error_count", 0);

        requireActivity().runOnUiThread(() -> {
            connectionStatus.setText("连接状态: " + conn);
            locationText.setText("位置: " + loc);
            reportCount.setText(String.format("上报成功: %d / 错误: %d", success, error));
        });
        Log.d("HomeFragment", "UI updated: loc=" + loc + ", conn=" + conn);
    }

    @Override
    public void onDestroy() {
        if (uiUpdater != null) {
            uiUpdater.shutdown();
        }
        super.onDestroy();
    }
}