package com.example.airgest;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

/** Gesture settings: tracking FPS, cursor, overlay, scroll direction, restart prompt, battery. */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.applyInsets(findViewById(R.id.root));
        Ui.header(this, R.string.settings_title);
        final SettingsManager s = new SettingsManager(this);

        // Tracking FPS
        RadioGroup fps = findViewById(R.id.fpsGroup);
        int cur = s.getFps();
        fps.check(cur == 24 ? R.id.fps24 : cur == 15 ? R.id.fps15 : R.id.fps30);
        fps.setOnCheckedChangeListener((g, id) ->
                s.setFps(id == R.id.fps24 ? 24 : id == R.id.fps15 ? 15 : 30));

        // Cursor
        MaterialSwitch cursor = findViewById(R.id.switchCursor);
        ViewGroup sizeBox = findViewById(R.id.cursorSizeContainer);
        Ui.addSlider(this, sizeBox, R.string.settings_cursor_size, 16, 64, 2,
                s.getCursorSizeDp(), 1f, "%.0f dp", s::setCursorSizeDp);
        cursor.setChecked(s.isCursorEnabled());
        sizeBox.setVisibility(s.isCursorEnabled() ? View.VISIBLE : View.GONE);
        cursor.setOnCheckedChangeListener((b, on) -> {
            s.setCursorEnabled(on);
            sizeBox.setVisibility(on ? View.VISIBLE : View.GONE);
        });

        MaterialSwitch hud = findViewById(R.id.switchHud);
        hud.setChecked(s.isHudEnabled());
        hud.setOnCheckedChangeListener((b, on) -> s.setHudEnabled(on));

        MaterialSwitch invert = findViewById(R.id.switchInvert);
        invert.setChecked(s.isInvertScroll());
        invert.setOnCheckedChangeListener((b, on) -> s.setInvertScroll(on));

        MaterialSwitch boot = findViewById(R.id.switchBoot);
        boot.setChecked(s.isBootPromptEnabled());
        boot.setOnCheckedChangeListener((b, on) -> s.setBootPromptEnabled(on));

        findViewById(R.id.btnBattery).setOnClickListener(v -> openBatterySettings());
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e2) {
                Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
