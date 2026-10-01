package com.example.airgest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Configuration + start/stop only. The actual gesture control lives in
 * AirControlForegroundService (camera + MediaPipe) and AirControlAccessibilityService (actions).
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_AUTO_START = "auto_start";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText, statusDetail, stateCamera, stateNotifications, stateAccessibility;
    private AppCompatButton btnStartStop, btnGrantCamera, btnGrantNotifications, btnAccessibility;

    private boolean notificationAsked;

    private final ActivityResultLauncher<String> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                refresh();
                if (granted) {
                    startFlow();
                } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    // "Don't ask again": the only way left is the system settings page
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.dialog_camera_title)
                            .setMessage(R.string.msg_camera_denied_settings)
                            .setPositiveButton(R.string.dialog_open_settings, (d, w) -> openAppSettings())
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .show();
                } else {
                    Toast.makeText(this, R.string.msg_need_camera, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> notificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                refresh();
                startFlow(); // notifications are optional for the service to run; continue either way
            });

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.applyInsets(findViewById(R.id.root));

        statusText = findViewById(R.id.statusText);
        statusDetail = findViewById(R.id.statusDetail);
        stateCamera = findViewById(R.id.stateCamera);
        stateNotifications = findViewById(R.id.stateNotifications);
        stateAccessibility = findViewById(R.id.stateAccessibility);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnGrantCamera = findViewById(R.id.btnGrantCamera);
        btnGrantNotifications = findViewById(R.id.btnGrantNotifications);
        btnAccessibility = findViewById(R.id.btnAccessibility);

        btnStartStop.setOnClickListener(v -> {
            if (AirControlForegroundService.isRunning()) {
                AirControlForegroundService.stop(this);
                Toast.makeText(this, R.string.msg_stopped, Toast.LENGTH_SHORT).show();
                handler.postDelayed(this::refresh, 300);
            } else {
                startFlow();
            }
        });
        btnGrantCamera.setOnClickListener(v -> askCamera());
        btnGrantNotifications.setOnClickListener(v -> {
            notificationAsked = true;
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        });
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        findViewById(R.id.menuSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.menuProfiles).setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));
        findViewById(R.id.menuCalibration).setOnClickListener(v -> startActivity(new Intent(this, CalibrationActivity.class)));
        findViewById(R.id.menuDebug).setOnClickListener(v -> startActivity(new Intent(this, DebugActivity.class)));
        findViewById(R.id.menuGuide).setOnClickListener(v -> startActivity(
                new Intent(this, TextActivity.class)
                        .putExtra(TextActivity.EXTRA_TITLE, R.string.guide_title)
                        .putExtra(TextActivity.EXTRA_BODY, R.string.guide_text)));
        findViewById(R.id.menuPrivacy).setOnClickListener(v -> startActivity(
                new Intent(this, TextActivity.class)
                        .putExtra(TextActivity.EXTRA_TITLE, R.string.privacy_title)
                        .putExtra(TextActivity.EXTRA_BODY, R.string.privacy_text)));

        handleAutoStart(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoStart(intent);
    }

    /** Tapped the "Start Air Control?" notification that is shown after a device restart. */
    private void handleAutoStart(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            intent.removeExtra(EXTRA_AUTO_START);
            handler.post(this::startFlow);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    // =========================================================================================
    // permission state
    // =========================================================================================
    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33;
    }

    private boolean hasNotifications() {
        return !needsNotificationPermission()
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean accessibilityEnabled() {
        return AirControlAccessibilityService.isConnected()
                || AirControlAccessibilityService.isEnabledInSettings(this);
    }

    private void refresh() {
        boolean cam = hasCamera();
        boolean notif = hasNotifications();
        boolean a11y = accessibilityEnabled();
        boolean running = AirControlForegroundService.isRunning();

        stateCamera.setText(cam ? R.string.state_granted : R.string.state_denied);
        stateNotifications.setText(!needsNotificationPermission() ? R.string.state_granted
                : (notif ? R.string.state_granted : R.string.state_denied));
        stateAccessibility.setText(a11y ? R.string.state_ok : R.string.state_missing);

        btnGrantCamera.setVisibility(cam ? View.GONE : View.VISIBLE);
        btnGrantNotifications.setVisibility(needsNotificationPermission() && !notif ? View.VISIBLE : View.GONE);
        btnAccessibility.setText(R.string.btn_enable_accessibility);
        btnAccessibility.setVisibility(View.VISIBLE);

        if (running) {
            statusText.setText(AirControlState.paused ? R.string.status_paused : R.string.status_active);
            statusDetail.setText(a11y ? getString(R.string.msg_started)
                    : getString(R.string.msg_need_accessibility));
            btnStartStop.setText(R.string.btn_stop);
            btnStartStop.setBackgroundResource(R.drawable.btn_stop);
            btnStartStop.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        } else {
            statusText.setText(R.string.status_stopped);
            String detail;
            if (!AirControlState.lastError.isEmpty()) {
                detail = AirControlState.lastError;
            } else if (cam && a11y) {
                detail = getString(R.string.status_ready);
            } else {
                detail = getString(R.string.status_setup) + (cam ? "" : "  • camera")
                        + (a11y ? "" : "  • accessibility");
            }
            statusDetail.setText(detail);
            btnStartStop.setText(R.string.btn_start);
            btnStartStop.setBackgroundResource(R.drawable.btn_start);
            btnStartStop.setTextColor(ContextCompat.getColor(this, R.color.black));
        }
    }

    // =========================================================================================
    // start flow: camera -> accessibility -> (notifications) -> service
    // =========================================================================================
    private void startFlow() {
        if (AirControlForegroundService.isRunning()) {
            refresh();
            return;
        }
        if (!hasCamera()) {
            askCamera();
            return;
        }
        if (!accessibilityEnabled()) {
            Toast.makeText(this, R.string.msg_need_accessibility, Toast.LENGTH_LONG).show();
            showAccessibilityHelp();
            return;
        }
        if (needsNotificationPermission() && !hasNotifications() && !notificationAsked) {
            notificationAsked = true;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.row_notifications)
                    .setMessage(R.string.notif_explain)
                    .setPositiveButton(R.string.dialog_continue, (d, w) -> {
                        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    })
                    .setNegativeButton(R.string.dialog_cancel, (d, w) -> startFlow())
                    .show();
            return;
        }
        try {
            AirControlState.lastError = "";
            AirControlForegroundService.start(this);
            Toast.makeText(this, R.string.msg_started, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            AirControlState.lastError = getString(R.string.msg_start_failed, e.getMessage());
            Toast.makeText(this, AirControlState.lastError, Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::refresh, 400);
    }

    private void askCamera() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_camera_title)
                .setMessage(R.string.camera_explain)
                .setPositiveButton(R.string.dialog_continue,
                        (d, w) -> cameraLauncher.launch(Manifest.permission.CAMERA))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showAccessibilityHelp() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.row_accessibility)
                .setMessage(R.string.accessibility_explain)
                .setPositiveButton(R.string.dialog_open_settings, (d, w) -> openAccessibilitySettings())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** The user must switch the service on themselves; apps can not do it silently. */
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_a11y_settings_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }
}
