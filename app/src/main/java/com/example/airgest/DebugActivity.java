package com.example.airgest;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** Live view of what the tracker and the gesture state machine are doing. */
public class DebugActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView text;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);
        Ui.applyInsets(findViewById(R.id.root));
        Ui.header(this, R.string.debug_title);
        text = findViewById(R.id.debugText);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void render() {
        boolean a11y = AirControlAccessibilityService.isConnected();
        String controller = !AirControlState.controllerActive ? "STOPPED"
                : (AirControlState.paused ? "PAUSED (screen off)" : "ACTIVE");
        StringBuilder sb = new StringBuilder();
        sb.append("Hand Detected: ").append(AirControlState.handDetected ? "YES" : "NO").append('\n');
        sb.append("Index X: ").append(AirControlState.indexXpx).append('\n');
        sb.append("Index Y: ").append(AirControlState.indexYpx).append("\n\n");
        sb.append("Pose:\n").append(AirControlState.pose).append("\n\n");
        sb.append("Gesture:\n").append(AirControlState.gesture).append("\n\n");
        sb.append("Confidence:\n").append(String.format(Locale.US, "%.2f", AirControlState.confidence)).append("\n\n");
        sb.append("State machine:\n").append(AirControlState.gestureState)
                .append("  (mode ").append(AirControlState.mode).append(")\n\n");
        sb.append("Last action:\n").append(AirControlState.lastAction).append("\n\n");
        sb.append("Tracking FPS:\n").append(AirControlState.fps).append("\n\n");
        sb.append("Accessibility:\n").append(a11y ? "CONNECTED" : "NOT CONNECTED").append("\n\n");
        sb.append("Foreground app:\n").append(AirControlState.foregroundPackage)
                .append("  [").append(AppProfile.resolve(AirControlState.foregroundPackage).displayName).append("]\n\n");
        sb.append("Controller:\n").append(controller);
        if (!AirControlState.lastError.isEmpty()) {
            sb.append("\n\nLast error:\n").append(AirControlState.lastError);
        }
        text.setText(sb.toString());
    }
}
