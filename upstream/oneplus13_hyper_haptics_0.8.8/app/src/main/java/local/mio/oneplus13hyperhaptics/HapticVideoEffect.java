package local.mio.oneplus13hyperhaptics;

import android.os.VibrationEffect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;

/** Converts Xiaomi's long raw 24 kHz haptic-video RTP into a timed envelope. */
final class HapticVideoEffect {
    private static final String TAG = "OnePlus13HyperHaptics";
    private static final String ASSET = "assets/rtp/effect_192.bin";
    private static final int SAMPLE_RATE = 24_000;
    private static final int WINDOW_SAMPLES = 240; // 10 ms
    private static final double RMS_GAIN = 6.0;
    private static final int MIN_ACTIVE_AMPLITUDE = 24;
    private static final int AMPLITUDE_QUANTUM = 8;

    private static volatile String modulePath;
    private static volatile VibrationEffect cached;

    private HapticVideoEffect() {}

    static void setModulePath(String path) {
        modulePath = path;
    }

    static VibrationEffect get() {
        VibrationEffect effect = cached;
        if (effect != null) return effect;
        synchronized (HapticVideoEffect.class) {
            effect = cached;
            if (effect != null) return effect;
            try {
                byte[] pcm = load();
                if (pcm == null || pcm.length == 0) return null;
                effect = buildEnvelope(pcm);
                cached = effect;
                return effect;
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": failed to build haptic-video 192 envelope");
                XposedBridge.log(throwable);
                return null;
            }
        }
    }

    private static VibrationEffect buildEnvelope(byte[] pcm) {
        ArrayList<Long> timings = new ArrayList<>();
        ArrayList<Integer> amplitudes = new ArrayList<>();

        for (int start = 0; start < pcm.length; start += WINDOW_SAMPLES) {
            int end = Math.min(start + WINDOW_SAMPLES, pcm.length);
            double sumSquares = 0.0;
            for (int index = start; index < end; index++) {
                int unsigned = pcm[index] & 0xff;
                int signed = unsigned < 128 ? unsigned : unsigned - 256;
                sumSquares += (double) signed * signed;
            }

            double rms = Math.sqrt(sumSquares / (end - start));
            int amplitude;
            if (rms < 1.5) {
                amplitude = 0;
            } else {
                amplitude = (int) Math.round(rms * RMS_GAIN);
                amplitude = Math.max(MIN_ACTIVE_AMPLITUDE, Math.min(255, amplitude));
                amplitude = Math.min(
                        255,
                        ((amplitude + AMPLITUDE_QUANTUM / 2) / AMPLITUDE_QUANTUM)
                                * AMPLITUDE_QUANTUM);
            }

            long duration = Math.max(
                    1L,
                    Math.round((end - start) * 1000.0 / SAMPLE_RATE));
            int last = amplitudes.size() - 1;
            if (last >= 0 && amplitudes.get(last) == amplitude) {
                timings.set(last, timings.get(last) + duration);
            } else {
                timings.add(duration);
                amplitudes.add(amplitude);
            }
        }

        long[] timingArray = new long[timings.size()];
        int[] amplitudeArray = new int[amplitudes.size()];
        long totalDuration = 0L;
        for (int index = 0; index < timings.size(); index++) {
            timingArray[index] = timings.get(index);
            amplitudeArray[index] = amplitudes.get(index);
            totalDuration += timingArray[index];
        }
        XposedBridge.log(TAG + ": haptic-video 192 envelope ready segments="
                + timingArray.length + " duration=" + totalDuration + "ms gain=" + RMS_GAIN);
        return VibrationEffect.createWaveform(timingArray, amplitudeArray, -1);
    }

    private static byte[] load() throws Exception {
        String path = modulePath;
        if (path == null || path.isEmpty()) return null;
        try (ZipFile apk = new ZipFile(path)) {
            ZipEntry entry = apk.getEntry(ASSET);
            if (entry == null) return null;
            try (InputStream input = apk.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         (int) Math.max(32L, entry.getSize()))) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }
}
