package com.example.airgest;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.slider.Slider;

import java.util.Locale;

/** Small UI helpers shared by the activities. */
final class Ui {
    private Ui() { }

    interface IntSetter {
        void set(int value);
    }

    /** Android 15+ draws edge-to-edge; keep content clear of the system bars. */
    static void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** Wires the shared back-arrow + title header (layout/view_header.xml). */
    static void header(Activity a, int titleRes) {
        ((TextView) a.findViewById(R.id.headerTitle)).setText(titleRes);
        a.findViewById(R.id.btnBack).setOnClickListener(v -> a.finish());
    }

    /**
     * Adds a labelled slider. The slider works on integers (exact steps); {@code divisor} turns the
     * integer into the displayed / stored value, e.g. 18 / 10 = 1.8.
     */
    static void addSlider(Activity a, ViewGroup container, int labelRes, int min, int max, int step,
                          float currentValue, float divisor, String format, IntSetter setter) {
        View row = LayoutInflater.from(a).inflate(R.layout.item_slider, container, false);
        TextView label = row.findViewById(R.id.sliderLabel);
        TextView value = row.findViewById(R.id.sliderValue);
        Slider slider = row.findViewById(R.id.slider);
        label.setText(labelRes);

        int current = Math.round(currentValue * divisor);
        current = Math.max(min, Math.min(max, current));
        // snap onto the step grid
        current = min + Math.round((current - min) / (float) step) * step;
        current = Math.max(min, Math.min(max, current));

        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);
        slider.setValue(current);
        value.setText(String.format(Locale.US, format, current / divisor));
        slider.addOnChangeListener((s, v, fromUser) -> {
            int iv = Math.round(v);
            value.setText(String.format(Locale.US, format, iv / divisor));
            if (fromUser) setter.set(iv);
        });
        container.addView(row);
    }
}
