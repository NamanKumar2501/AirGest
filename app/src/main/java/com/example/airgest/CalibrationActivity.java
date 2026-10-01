package com.example.airgest;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/** Sliders for every tunable gesture threshold. Changes apply live to a running controller. */
public class CalibrationActivity extends AppCompatActivity {

    private SettingsManager settings;
    private ViewGroup container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);
        Ui.applyInsets(findViewById(R.id.root));
        Ui.header(this, R.string.calib_title);
        settings = new SettingsManager(this);
        container = findViewById(R.id.sliderContainer);
        build();
        findViewById(R.id.btnReset).setOnClickListener(v -> {
            settings.resetCalibration();
            build();
            Toast.makeText(this, R.string.calib_reset_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void build() {
        container.removeAllViews();
        Ui.addSlider(this, container, R.string.calib_sensitivity, 10, 30, 1,
                settings.getSensitivity(), 10f, "%.1fx", v -> settings.setSensitivity(v / 10f));
        Ui.addSlider(this, container, R.string.calib_threshold, 15, 50, 1,
                settings.getPinchThreshold(), 100f, "%.2f", v -> settings.setPinchThreshold(v / 100f));
        Ui.addSlider(this, container, R.string.calib_swipe_distance, 15, 60, 1,
                settings.getSwipeDistance(), 100f, "%.2f", v -> settings.setSwipeDistance(v / 100f));
        Ui.addSlider(this, container, R.string.calib_swipe_speed, 4, 25, 1,
                settings.getSwipeSpeed(), 10f, "%.1f /s", v -> settings.setSwipeSpeed(v / 10f));
        Ui.addSlider(this, container, R.string.calib_smoothing, 0, 90, 1,
                settings.getSmoothing(), 100f, "%.2f", v -> settings.setSmoothing(v / 100f));
        Ui.addSlider(this, container, R.string.calib_cooldown, 400, 1500, 50,
                settings.getCooldownMs(), 1f, "%.0f ms", settings::setCooldownMs);
    }
}
