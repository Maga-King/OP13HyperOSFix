package local.mio.op13hyperosfix.haptics;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.MediaPlayer;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.mio.op13hyperosfix.R;

/** Plays Xiaomi's signed 8-bit 24 kHz RTP as an Android haptic-A audio channel. */
final class AudioRtpPlayer {
    private static final String TAG = "OnePlus13HyperHaptics";
    private static final int SAMPLE_RATE = 24_000;
    private static final int HAPTIC_A = 0x20000000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO | HAPTIC_A;
    private static final float RTP_GAIN = 1.5f;
    private static final int STREAM_BUFFER_FRAMES = 8_192;
    private static final int[] FRAMEWORK_EFFECTS = {
            159, 162, 163, 167, 169, 171, 210
    };

    private static final Object LOCK = new Object();
    private static final AtomicBoolean CHANNEL_CHECK_HOOKED = new AtomicBoolean(false);
    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile String modulePath;
    private static volatile Handler releaseHandler;
    private static AudioTrack currentTrack;
    private static Thread currentWriter;
    private static MediaPlayer currentMediaPlayer;
    private static AssetFileDescriptor currentMediaFile;

    private AudioRtpPlayer() {}

    static void setModulePath(String path) {
        modulePath = path;
    }

    static boolean hasEffect(int effectId) {
        // Effect 192 is deliberately Settings-only. It is a 46 second stream,
        // never a normal framework prebaked effect.
        for (int candidate : FRAMEWORK_EFFECTS) {
            if (candidate == effectId) return true;
        }
        return false;
    }

    /**
     * Android's public AudioTrack builder rejects hidden haptic output bits even
     * when AudioFlinger advertises haptic-A. MediaPlayer is normally the only
     * framework caller allowed through this check. Scope the bypass to exactly
     * stereo + haptic-A PCM float tracks created by this module.
     */
    static void installHapticChannelCompatibility() {
        if (!CHANNEL_CHECK_HOOKED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(
                    AudioTrack.class,
                    "isMultichannelConfigSupported",
                    int.class,
                    int.class,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int mask = (int) param.args[0];
                            int encoding = (int) param.args[1];
                            if (mask == CHANNEL_MASK
                                    && encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                param.setResult(true);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": haptic-A AudioTrack compatibility active");
        } catch (Throwable throwable) {
            CHANNEL_CHECK_HOOKED.set(false);
            XposedBridge.log(TAG + ": failed to hook AudioTrack channel validation");
            XposedBridge.log(throwable);
        }
    }

    /** Returns the exact source duration, or zero for native fallback. */
    static long perform(int effectId) {
        try {
            byte[] pcm = load(effectId);
            if (pcm == null || pcm.length == 0) return 0L;
            float officialScale = RtpPlayer.consumeAudioScale(effectId);
            if (officialScale <= 0.0f) return 1L;

            float[] frames = new float[pcm.length * 3];
            for (int index = 0, output = 0; index < pcm.length; index++) {
                int unsigned = pcm[index] & 0xff;
                int signed = unsigned < 128 ? unsigned : unsigned - 256;
                float haptic = Math.max(-1.0f, Math.min(1.0f,
                        signed / 128.0f * RTP_GAIN * officialScale));
                frames[output++] = 0.0f;
                frames[output++] = 0.0f;
                frames[output++] = haptic;
            }

            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
            XposedHelpers.callMethod(
                    attributesBuilder, "setHapticChannelsMuted", false);
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(CHANNEL_MASK)
                    .build();
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(attributesBuilder.build())
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames.length * Float.BYTES)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return 0L;
            }
            int written = track.write(frames, 0, frames.length, AudioTrack.WRITE_BLOCKING);
            if (written != frames.length) {
                track.release();
                XposedBridge.log(TAG + ": haptic-A short write effect=" + effectId
                        + " written=" + written + "/" + frames.length);
                return 0L;
            }

            long duration = Math.max(
                    20L,
                    (pcm.length * 1000L + SAMPLE_RATE - 1L) / SAMPLE_RATE);
            long generation;
            synchronized (LOCK) {
                stopLocked();
                currentTrack = track;
                generation = GENERATION.incrementAndGet();
                track.play();
            }
            releaseHandler().postDelayed(
                    () -> releaseIfCurrent(generation), duration + 300L);
            XposedBridge.log(TAG + ": haptic-A RTP " + effectId
                    + " bytes=" + pcm.length + " duration=" + duration
                    + "ms gain=" + (RTP_GAIN * officialScale));
            return duration;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": haptic-A RTP failed for " + effectId);
            XposedBridge.log(throwable);
            return 0L;
        }
    }

    /**
     * Play the full Xiaomi haptic-video RTP through a streaming AudioTrack.
     * This path intentionally never materializes the 46 second file as a giant
     * float array and is only called by the hooked Settings video widget.
     */
    static boolean performVideo(Context context, int effectId) {
        if (effectId != 192) return false;
        if (context == null) return false;

        try {
            Context moduleContext = context.createPackageContext(
                    "local.mio.op13hyperosfix",
                    Context.CONTEXT_IGNORE_SECURITY);
            AssetFileDescriptor mediaFile = moduleContext.getResources()
                    .openRawResourceFd(R.raw.haptic_video_192);
            if (mediaFile == null) return false;

            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
            XposedHelpers.callMethod(attributesBuilder, "setHapticChannelsMuted", false);

            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(attributesBuilder.build());
            player.setDataSource(
                    mediaFile.getFileDescriptor(),
                    mediaFile.getStartOffset(),
                    mediaFile.getLength());
            player.setOnCompletionListener(ignored -> stop());
            player.setOnErrorListener((ignored, what, extra) -> {
                XposedBridge.log(TAG + ": Settings haptic video MediaPlayer error what="
                        + what + " extra=" + extra);
                stop();
                return true;
            });
            player.prepare();
            synchronized (LOCK) {
                stopLocked();
                GENERATION.incrementAndGet();
                currentMediaFile = mediaFile;
                currentMediaPlayer = player;
                player.start();
            }
            XposedBridge.log(TAG + ": Settings haptic video OGG started effect="
                    + effectId + " duration=" + player.getDuration() + "ms");
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": failed to start Settings haptic video OGG "
                    + effectId);
            XposedBridge.log(throwable);
            return false;
        }
    }

    /** Play a short Settings-only Xiaomi RTP cue without framework translation. */
    static boolean performSettingsCue(
            Context context, int effectId, float relativeGain) {
        try {
            byte[] pcm = load(effectId);
            if (pcm == null || pcm.length == 0 || pcm.length > SAMPLE_RATE * 2) {
                return false;
            }
            float officialScale = readOfficialStrength(context);
            if (officialScale <= 0.0f) {
                stop();
                return true;
            }
            return performStatic(
                    effectId, pcm, RTP_GAIN * relativeGain * officialScale) > 0L;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Settings RTP cue failed for " + effectId);
            XposedBridge.log(throwable);
            return false;
        }
    }

    static float readOfficialStrength(Context context) {
        if (context == null) return 1.0f;
        try {
            float stored = Settings.System.getFloat(
                    context.getContentResolver(),
                    "haptic_feedback_infinite_intensity",
                    1.0f);
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String slideVersion = (String) XposedHelpers.callStaticMethod(
                    properties, "get", "sys.haptic.slide_version", "");
            // Slider v2 stores 0..1. Legacy Settings stores 0..1.5 while
            // miui-services divides by 1.5 before calling the Xiaomi HAL.
            float normalized = "2.0".equals(slideVersion)
                    ? stored
                    : stored / 1.5f;
            return RtpPlayer.motorScaleForNormalized(normalized);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    static void stop() {
        synchronized (LOCK) {
            GENERATION.incrementAndGet();
            stopLocked();
        }
    }

    private static void releaseIfCurrent(long generation) {
        synchronized (LOCK) {
            if (GENERATION.get() != generation) return;
            GENERATION.incrementAndGet();
            stopLocked();
        }
    }

    private static void stopLocked() {
        MediaPlayer mediaPlayer = currentMediaPlayer;
        currentMediaPlayer = null;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Throwable ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {
            }
        }
        AssetFileDescriptor mediaFile = currentMediaFile;
        currentMediaFile = null;
        if (mediaFile != null) {
            try {
                mediaFile.close();
            } catch (Throwable ignored) {
            }
        }
        Thread writer = currentWriter;
        currentWriter = null;
        if (writer != null) writer.interrupt();
        AudioTrack track = currentTrack;
        currentTrack = null;
        if (track == null) return;
        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        try {
            track.release();
        } catch (Throwable ignored) {
        }
    }

    private static AudioTrack createTrack(int transferMode, int bufferBytes) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        XposedHelpers.callMethod(attributesBuilder, "setHapticChannelsMuted", false);
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(CHANNEL_MASK)
                .build();
        return new AudioTrack.Builder()
                .setAudioAttributes(attributesBuilder.build())
                .setAudioFormat(format)
                .setTransferMode(transferMode)
                .setBufferSizeInBytes(bufferBytes)
                .build();
    }

    private static long performStatic(int effectId, byte[] pcm, float gain) {
        float[] frames = toFrames(pcm, pcm.length, gain);
        AudioTrack track = createTrack(
                AudioTrack.MODE_STATIC,
                frames.length * Float.BYTES);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            return 0L;
        }
        int written = track.write(frames, 0, frames.length, AudioTrack.WRITE_BLOCKING);
        if (written != frames.length) {
            track.release();
            return 0L;
        }

        long duration = Math.max(
                20L,
                (pcm.length * 1000L + SAMPLE_RATE - 1L) / SAMPLE_RATE);
        long generation;
        synchronized (LOCK) {
            stopLocked();
            currentTrack = track;
            generation = GENERATION.incrementAndGet();
            track.play();
        }
        releaseHandler().postDelayed(
                () -> releaseIfCurrent(generation), duration + 300L);
        XposedBridge.log(TAG + ": Settings Xiaomi RTP cue " + effectId
                + " bytes=" + pcm.length + " duration=" + duration
                + "ms gain=" + gain);
        return duration;
    }

    private static Handler releaseHandler() {
        Handler handler = releaseHandler;
        if (handler != null) return handler;
        synchronized (AudioRtpPlayer.class) {
            handler = releaseHandler;
            if (handler == null) {
                handler = Handler.createAsync(Looper.getMainLooper());
                releaseHandler = handler;
            }
        }
        return handler;
    }

    private static void stream(int effectId, AudioTrack track, long generation) {
        String path = ModuleApkResolver.resolve(modulePath);
        if (path == null) {
            releaseIfCurrent(generation);
            return;
        }
        modulePath = path;
        try (ZipFile apk = new ZipFile(path)) {
            ZipEntry entry = apk.getEntry(assetName(effectId));
            if (entry == null) return;
            try (InputStream input = apk.getInputStream(entry)) {
                byte[] pcm = new byte[4_096];
                int read;
                while (!Thread.currentThread().isInterrupted()
                        && GENERATION.get() == generation
                        && (read = input.read(pcm)) != -1) {
                    float[] frames = toFrames(pcm, read, RTP_GAIN);
                    int written = track.write(
                            frames, 0, frames.length, AudioTrack.WRITE_BLOCKING);
                    if (written != frames.length) {
                        throw new IllegalStateException(
                                "haptic video short write " + written + "/" + frames.length);
                    }
                }
            }
        } catch (Throwable throwable) {
            if (GENERATION.get() == generation) {
                XposedBridge.log(TAG + ": haptic video stream failed effect=" + effectId);
                XposedBridge.log(throwable);
            }
        } finally {
            releaseIfCurrent(generation);
        }
    }

    private static float[] toFrames(byte[] pcm, int length, float gain) {
        float[] frames = new float[length * 3];
        for (int index = 0, output = 0; index < length; index++) {
            int unsigned = pcm[index] & 0xff;
            int signed = unsigned < 128 ? unsigned : unsigned - 256;
            float haptic = Math.max(-1.0f, Math.min(1.0f,
                    signed / 128.0f * gain));
            frames[output++] = 0.0f;
            frames[output++] = 0.0f;
            frames[output++] = haptic;
        }
        return frames;
    }

    private static byte[] load(int effectId) throws Exception {
        String path = ModuleApkResolver.resolve(modulePath);
        if (path == null || path.isEmpty()) return null;
        modulePath = path;
        try (ZipFile apk = new ZipFile(path)) {
            ZipEntry entry = apk.getEntry(assetName(effectId));
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

    private static String assetName(int effectId) {
        return "assets/rtp/effect_" + effectId + ".bin";
    }
}
