package com.example.airgest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.Size;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera + MediaPipe Hand Landmarker.
 *
 * Uses the FRONT camera through CameraX ImageAnalysis, turns every frame upright and MIRRORED
 * (so moving your hand to your right moves x to the right, like a mirror), and hands 21 landmarks
 * per frame to the listener. Everything runs on-device; no frame ever leaves the phone.
 *
 * The frame rate is throttled (15 / 24 / 30 fps) to save battery: frames arriving faster than the
 * chosen rate are dropped before any expensive work is done.
 */
public class HandTrackingManager {

    public interface Listener {
        /** Called on an arbitrary MediaPipe thread. */
        void onHandFrame(HandFrame frame);
        /** Called on an arbitrary thread. */
        void onTrackingError(String message);
    }

    private final Context context;
    private final LifecycleOwner owner;
    private final Listener listener;

    private HandLandmarker landmarker;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis analysis;
    private ExecutorService analysisExecutor;
    private DisplayManager displayManager;

    private volatile boolean running;      // camera should be delivering frames
    private volatile long minIntervalMs = 33;
    private volatile boolean mirror = true;
    private long lastProcessedMs;
    private int lastRotation = -1;
    private boolean started;

    public HandTrackingManager(Context context, LifecycleOwner owner, Listener listener) {
        this.context = context.getApplicationContext();
        this.owner = owner;
        this.listener = listener;
    }

    public void setFps(int fps) {
        minIntervalMs = Math.max(1, 1000 / Math.max(5, fps)) - 2; // small slack for timer jitter
    }

    /** Creates the landmarker and opens the camera. Main thread. Returns false on failure. */
    public boolean start(int fps) {
        setFps(fps);
        if (started) return true;
        try {
            initLandmarker();
        } catch (Throwable t) {
            listener.onTrackingError("Hand tracking could not start: " + t.getMessage()
                    + " (is app/src/main/assets/hand_landmarker.task present?)");
            return false;
        }
        started = true;
        analysisExecutor = Executors.newSingleThreadExecutor();
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        bindCamera();
        return true;
    }

    /** Stops delivering frames and releases the camera (screen off). The landmarker is kept. */
    public void pause() {
        running = false;
        unbind();
    }

    public void resume() {
        if (!started || running) return;
        bindCamera();
    }

    /** Releases everything. */
    public void stop() {
        running = false;
        started = false;
        unbind();
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
        if (landmarker != null) {
            try { landmarker.close(); } catch (Exception ignored) { }
            landmarker = null;
        }
    }

    // =========================================================================================
    private void initLandmarker() {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build();
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1) // one hand first = more reliable
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener((HandLandmarkerResult result, MPImage input) -> onResult(result, input))
                .setErrorListener(e -> listener.onTrackingError("MediaPipe error: " + e.getMessage()))
                .build();
        landmarker = HandLandmarker.createFromOptions(context, options);
    }

    private void bindCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                CameraSelector selector;
                if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                    mirror = true;
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    selector = CameraSelector.DEFAULT_BACK_CAMERA;
                    mirror = false;
                } else {
                    listener.onTrackingError("No camera available on this device.");
                    return;
                }

                ResolutionSelector resolution = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);

                cameraProvider.unbindAll();
                running = true;
                lastRotation = -1;
                cameraProvider.bindToLifecycle(owner, selector, analysis);
            } catch (Exception e) {
                running = false;
                listener.onTrackingError("Camera unavailable: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void unbind() {
        try {
            if (analysis != null) analysis.clearAnalyzer();
            if (cameraProvider != null) cameraProvider.unbindAll();
        } catch (Exception ignored) { }
        analysis = null;
    }

    // =========================================================================================
    private void analyze(@NonNull ImageProxy image) {
        try {
            long now = SystemClock.uptimeMillis();
            if (!running || landmarker == null || now - lastProcessedMs < minIntervalMs) {
                return; // dropped before doing any work (battery)
            }
            lastProcessedMs = now;

            // keep the frame upright when the phone is rotated
            if (displayManager != null && analysis != null) {
                Display d = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null && d.getRotation() != lastRotation) {
                    lastRotation = d.getRotation();
                    analysis.setTargetRotation(lastRotation);
                }
            }

            Bitmap raw = image.toBitmap();
            Matrix m = new Matrix();
            m.postRotate(image.getImageInfo().getRotationDegrees());
            if (mirror) m.postScale(-1f, 1f, raw.getWidth() / 2f, raw.getHeight() / 2f);
            Bitmap upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (upright != raw) raw.recycle();

            MPImage mp = new BitmapImageBuilder(upright).build();
            landmarker.detectAsync(mp, now);
        } catch (Throwable t) {
            listener.onTrackingError("Frame processing failed: " + t.getMessage());
        } finally {
            image.close();
        }
    }

    private void onResult(HandLandmarkerResult result, MPImage input) {
        long ts = result.timestampMs();
        float aspect = input.getHeight() == 0 ? 0.75f : (float) input.getWidth() / input.getHeight();
        List<List<NormalizedLandmark>> hands = result.landmarks();
        if (hands == null || hands.isEmpty() || hands.get(0).size() < HandFrame.LANDMARK_COUNT) {
            listener.onHandFrame(HandFrame.none(ts));
            return;
        }
        List<NormalizedLandmark> lm = hands.get(0);
        float[] x = new float[HandFrame.LANDMARK_COUNT];
        float[] y = new float[HandFrame.LANDMARK_COUNT];
        for (int i = 0; i < HandFrame.LANDMARK_COUNT; i++) {
            x[i] = lm.get(i).x();
            y[i] = lm.get(i).y();
        }
        float conf = 0.9f;
        try {
            List<List<Category>> handed = result.handedness();
            if (handed != null && !handed.isEmpty() && !handed.get(0).isEmpty()) {
                conf = handed.get(0).get(0).score();
            }
        } catch (Exception ignored) { }
        listener.onHandFrame(new HandFrame(true, x, y, conf, ts, aspect));
    }
}
