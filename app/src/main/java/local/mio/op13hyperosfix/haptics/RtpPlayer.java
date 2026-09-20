package local.mio.op13hyperosfix.haptics;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Sends APK-packaged RTP bytes to the stock OnePlus vibrator extension. */
final class RtpPlayer {
    private static final String TAG = "OnePlus13HyperHaptics";
    // Keep the production hot path free of LSPosed file I/O. Errors and
    // one-time transport discovery remain logged below.
    private static final boolean TRACE_HOT_PATH = false;
    private static final String VIBRATOR_SERVICE =
            "android.hardware.vibrator.IVibrator/default";
    private static final String VIBRATOR_DESCRIPTOR =
            "android.hardware.vibrator.IVibrator";
    private static final String OPLUS_DESCRIPTOR =
            "vendor.oplus.hardware.oplusvibrator.IOplusVibrator";
    private static final String RICHTAP_DESCRIPTOR =
            "vendor.aac.hardware.richtap.vibrator.IRichtapVibrator";
    private static final String CALLBACK_DESCRIPTOR =
            "vendor.aac.hardware.richtap.vibrator.IRichtapCallback";
    private static final int TRANSACTION_PERFORM_RTP = 11;
    private static final int TRANSACTION_SET_AMPLITUDE = 5;
    private static final int TRANSACTION_STOP = 4;
    private static final int TRANSACTION_BASE_SET_AMPLITUDE = 6;
    // ColorOS converts effect strength to a raw OnePlus motor value before it
    // invokes the standard AIDL perform() call. Values below 800 are not part
    // of the vendor's supported prebaked range; 2400 is the calibrated maximum.
    private static final int OPLUS_PREBAKED_STRENGTH_MIN = 800;
    private static final int OPLUS_PREBAKED_STRENGTH_MAX = 2400;
    // DIRECT and per-effect values may reach 1.5, but Xiaomi normalizes every
    // official slider generation to USER 0..1 before it reaches the HAL.
    private static final float XIAOMI_STRENGTH_MAX = 1.5f;
    private static final float XIAOMI_BCL_REPLACE_THRESHOLD = 0.4f;
    // The OnePlus qcom motor also has a calibrated non-zero floor. Preserve
    // Xiaomi's minimum-to-maximum slider behavior instead of treating zero as
    // a request to disable haptics; Android's haptic-enable setting owns that.
    private static final float OPLUS_MOTOR_SCALE_MIN =
            (float) OPLUS_PREBAKED_STRENGTH_MIN / OPLUS_PREBAKED_STRENGTH_MAX;
    // Keep the OnePlus prebaked transaction inside its calibrated 800..2400
    // range, but allow framework envelopes and Xiaomi RTP to reach 130% at
    // the top of the official HyperOS slider.
    private static final float OPLUS_MOTOR_SCALE_MAX = 1.30f;
    // 100% short-RTP gain, continuously scaled by HyperOS's official slider.
    private static final int RTP_AMPLITUDE = 176;
    // The slider preview needs more headroom than ordinary short RTP after the
    // earlier 176 ceiling made it feel weaker than real framework feedback.
    private static final int SLIDER_PREVIEW_AMPLITUDE = 255;
    private static final int SLIDER_PREVIEW_EFFECT = 162;
    // effect 162 crosses zero at byte 6 and again just before byte 179. Keeping
    // exactly that interval gives one complete 0916-retargeted cycle (~7.2 ms)
    // without the 18 ms tail that overlapped successive seekbar callbacks.
    private static final int SLIDER_PREVIEW_START_BYTE = 6;
    private static final int SLIDER_PREVIEW_END_BYTE = 179;
    // RichTap needs about 20 ms to open/fill the FIFO on this OS4 HAL.  This is
    // only the framework ownership window; the actual waveform stays 7.2 ms.
    private static final int SLIDER_PREVIEW_TRANSPORT_DURATION_MS = 32;
    // Short 0916-tuned notification pulse. This is deliberately independent
    // from OnePlus predefined effects, and leaves headroom below the clipping
    // level found while calibrating the long video RTP.
    private static final int NOTIFICATION_LONG_PRESS_AMPLITUDE = 176;
    // GestureBackPull/Release are clean Xiaomi originals whose waveform RMS is
    // lower than the OnePlus actuator expects. Raise only their Binder gain by
    // 30%; this preserves the waveform and avoids the clipping caused by raw
    // sample scaling.
    private static final int GESTURE_BACK_AMPLITUDE = 229;
    // Binder gain reaches its 255 ceiling near the top of the new 130% range.
    // Use the remaining waveform headroom so effects 162/163 are about 60%
    // stronger than the previous maximum without replaying or lengthening them.
    private static final float GESTURE_BACK_WAVEFORM_GAIN = 1.44f;
    private static final float SLIDER_PREVIEW_WAVEFORM_GAIN_MAX = 1.30f;
    // Oplus halves the Binder value before RICHTAP_SETTING_GAIN. 136 becomes
    // driver gain 68, measured at about 3.98 V on this OnePlus 13.
    private static final int VIDEO_RTP_AMPLITUDE = 136;
    private static final int RTP_BYTES_PER_MILLISECOND = 24;
    private static final int SHORT_RTP_MAX_BYTES = 24_000;
    private static final int LONG_RTP_LEAD_COMPENSATION_BYTES =
            60 * RTP_BYTES_PER_MILLISECOND;
    // OS4's bundled demo video is about 100 ms ahead of the K80U effect-192
    // timeline.  The first real sample in that RTP is at 862 ms, so removing
    // 160 ms (the normal 60 ms plus the measured offset) still cuts digital
    // silence only and leaves every haptic event intact.
    private static final int VIDEO_RTP_LEAD_COMPENSATION_BYTES =
            160 * RTP_BYTES_PER_MILLISECOND;

    private static final int[] PACKAGED_EFFECTS = {
            23, 24, 25, 26, 27, 28, 30, 31, 32, 33, 35, 36, 38, 39, 40, 41,
            42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57,
            58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73,
            74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 90,
            91, 92, 93, 96, 98, 99, 101, 102, 103, 104, 106, 107, 108, 109,
            110, 111, 112, 113, 114, 115, 116, 118, 119, 120, 121, 122, 123,
            124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 137,
            140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152,
            153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165,
            166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178,
            179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191,
            192, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212,
            213, 214, 215, 216, 217, 301, 302, 401, 402, 403, 404, 405, 406,
            407, 408, 410, 521, 10001, 10002, 10003
    };

    private static final Map<Integer, byte[]> CACHE = new ConcurrentHashMap<>();
    private static final Set<Integer> LONG_STEREO_EFFECTS =
            ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Float> EFFECT_STRENGTHS =
            new ConcurrentHashMap<>();
    // A non-null callback binder is safer with vendor implementations that retain it.
    private static final Binder CALLBACK = new RtpCallback();
    private static volatile String modulePath;
    private static volatile IBinder vibrator;
    private static volatile IBinder extension;
    private static volatile String extensionDescriptor;
    private static volatile boolean playing;
    private static volatile float userStrength = 1.0f;
    private static volatile float directStrength = -1.0f;
    private static volatile float bclStrength = -1.0f;

    private RtpPlayer() {}

    static void setModulePath(String path) {
        modulePath = path;
    }

    static boolean hasEffect(int effectId) {
        for (int candidate : PACKAGED_EFFECTS) {
            if (candidate == effectId) return true;
        }
        return false;
    }

    static int[] packagedEffects() {
        return PACKAGED_EFFECTS.clone();
    }

    static void setXiaomiAmplitude(float amplitude, int flag) {
        // Xiaomi flags: 0=USER slider, 1=DIRECT one-shot override,
        // 2=stop current output, 3=BCL state. DIRECT intentionally does not
        // rewrite the persistent UI slider state.
        if (flag == 0) {
            userStrength = clamp01(amplitude);
            if (TRACE_HOT_PATH) {
                XposedBridge.log(TAG + ": Xiaomi USER strength=" + userStrength);
            }
        } else if (flag == 1) {
            directStrength = Float.isFinite(amplitude)
                    && amplitude >= 0.0f
                    && amplitude <= XIAOMI_STRENGTH_MAX
                    ? amplitude
                    : -1.0f;
            if (TRACE_HOT_PATH) {
                XposedBridge.log(TAG + ": Xiaomi DIRECT strength=" + directStrength);
            }
        } else if (flag == 2) {
            // Xiaomi's vendor HAL does not persist flag 2 as a second global
            // disable switch. Framework settings suppress subsequent requests.
            if (amplitude <= 0.0f) stop();
            if (TRACE_HOT_PATH) {
                XposedBridge.log(TAG + ": Xiaomi stop strength=" + amplitude);
            }
        } else if (flag == 3) {
            bclStrength = amplitude <= 0.0f
                    ? -1.0f
                    : clampXiaomiStrength(amplitude);
            if (TRACE_HOT_PATH) {
                XposedBridge.log(TAG + ": Xiaomi BCL strength=" + bclStrength);
            }
        }
    }

    static void setEffectStrength(int effectId, float strength) {
        EFFECT_STRENGTHS.put(effectId, clampXiaomiStrength(strength));
    }

    static boolean hasPendingEffectStrength(int effectId) {
        return EFFECT_STRENGTHS.containsKey(effectId);
    }

    static boolean consumeEffectStrengthToken(int effectId) {
        return EFFECT_STRENGTHS.remove(effectId) != null;
    }

    static float currentUserStrength() {
        return clamp01(userStrength);
    }

    /** Continuous feedback gain corresponding to OnePlus's usable motor range. */
    static float currentMotorScale() {
        return toMotorScale(currentUserStrength());
    }

    static float motorScaleForNormalized(float normalizedStrength) {
        return toMotorScale(normalizedStrength);
    }

    static int officialStrengthGrade() {
        float value = currentUserStrength();
        return value >= 0.67f ? 2 : (value >= 0.34f ? 1 : 0);
    }

    static boolean hasOnePlusTransport() {
        try {
            return getExtension() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ColorOS encodes its private prebaked strength in a public setAmplitude
     * transaction, stores the value, and deliberately returns an AIDL error
     * for values above 1. Calling the framework wrapper would log that expected
     * reply for every haptic. Send the identical transaction directly and only
     * require that Binder accepted it; the reply payload is intentionally not
     * decoded.
     */
    static boolean setNativePrebakedStrength(int rawStrength) {
        if (rawStrength < OPLUS_PREBAKED_STRENGTH_MIN
                || rawStrength > OPLUS_PREBAKED_STRENGTH_MAX) {
            return false;
        }

        Parcel data = null;
        Parcel reply = null;
        try {
            IBinder target = getVibrator();
            if (target == null) return false;

            float encoded = 1.0f
                    + ((float) rawStrength / OPLUS_PREBAKED_STRENGTH_MAX);
            data = Parcel.obtain();
            reply = Parcel.obtain();
            data.writeInterfaceToken(VIBRATOR_DESCRIPTOR);
            data.writeFloat(encoded);
            return target.transact(
                    TRANSACTION_BASE_SET_AMPLITUDE, data, reply, 0);
        } catch (Throwable throwable) {
            vibrator = null;
            XposedBridge.log(TAG + ": native Oplus strength transaction failed");
            XposedBridge.log(throwable);
            return false;
        } finally {
            if (reply != null) reply.recycle();
            if (data != null) data.recycle();
        }
    }

    /**
     * Consume Xiaomi's global/direct/per-effect strength state and translate it
     * to the raw range used by ColorOS's OplusPrebakedSegment path.
     */
    static synchronized int consumeNativePrebakedStrength(int effectId) {
        float scale = consumeConfiguredScale(effectId, true);
        return Math.max(OPLUS_PREBAKED_STRENGTH_MIN,
                Math.min(OPLUS_PREBAKED_STRENGTH_MAX,
                        OPLUS_PREBAKED_STRENGTH_MIN
                                + Math.round((OPLUS_PREBAKED_STRENGTH_MAX
                                        - OPLUS_PREBAKED_STRENGTH_MIN) * scale)));
    }

    /** Returns an estimated duration, or zero when the native fallback should run. */
    static long perform(int effectId) {
        return performInternal(effectId, effectId, true, false);
    }

    /** Official seekbar preview with Xiaomi's normal per-effect calibration. */
    static long performSliderPreview(int assetEffectId, int configuredEffectId) {
        return performInternal(assetEffectId, configuredEffectId, true, true);
    }

    private static long performInternal(
            int effectId,
            int strengthEffectId,
            boolean applyEffectCalibration,
            boolean sliderPreview) {
        SharedMemory sharedMemory = null;
        ParcelFileDescriptor descriptor = null;
        Parcel data = null;
        try {
            byte[] bytes = load(effectId);
            if (bytes == null || bytes.length == 0) return 0L;
            if (sliderPreview && bytes.length >= SLIDER_PREVIEW_END_BYTE) {
                bytes = Arrays.copyOfRange(
                        bytes,
                        SLIDER_PREVIEW_START_BYTE,
                        SLIDER_PREVIEW_END_BYTE);
            }

            IBinder target = getExtension();
            String targetDescriptor = extensionDescriptor;
            if (target == null || targetDescriptor == null) return 0L;

            float configuredScale = consumeConfiguredScale(
                    strengthEffectId, applyEffectCalibration);
            bytes = applyWaveformGain(
                    bytes, effectId, sliderPreview, configuredScale);

            // A normal /data/system file is rejected by Binder when its FD
            // crosses into the vendor domain. SharedMemory provides a regular
            // seekable memfd/ashmem object that Binder is allowed to transfer.
            sharedMemory = SharedMemory.create(
                    "xiaomi-rtp-" + effectId, bytes.length);
            ByteBuffer buffer = sharedMemory.mapReadWrite();
            try {
                buffer.put(bytes);
            } finally {
                SharedMemory.unmap(buffer);
            }
            sharedMemory.setProtect(OsConstants.PROT_READ);
            descriptor = (ParcelFileDescriptor) XposedHelpers.callMethod(
                    sharedMemory, "getFdDup");
            Os.lseek(descriptor.getFileDescriptor(), 0L, OsConstants.SEEK_SET);

            int amplitude = amplitudeFor(
                    effectId,
                    strengthEffectId,
                    applyEffectCalibration,
                    sliderPreview,
                    configuredScale);
            if (!setAmplitude(target, targetDescriptor, amplitude)) {
                return 0L;
            }

            data = Parcel.obtain();
            data.writeInterfaceToken(targetDescriptor);
            data.writeTypedObject(descriptor, 0);
            data.writeStrongBinder(CALLBACK);
            boolean accepted = target.transact(
                    TRANSACTION_PERFORM_RTP, data, null, IBinder.FLAG_ONEWAY);
            if (!accepted) return 0L;
            playing = true;

            long rawDuration = (bytes.length + RTP_BYTES_PER_MILLISECOND - 1L)
                    / RTP_BYTES_PER_MILLISECOND;
            long duration = sliderPreview
                    ? Math.max(SLIDER_PREVIEW_TRANSPORT_DURATION_MS, rawDuration)
                    : Math.max(20L, rawDuration);
            if (TRACE_HOT_PATH) {
                XposedBridge.log(TAG + ": pure LSPosed RTP " + effectId
                        + " bytes=" + bytes.length + " duration=" + duration
                        + "ms amplitude=" + amplitude);
            }
            return duration;
        } catch (Throwable throwable) {
            extension = null;
            extensionDescriptor = null;
            XposedBridge.log(TAG + ": pure LSPosed RTP failed for " + effectId);
            XposedBridge.log(throwable);
            return 0L;
        } finally {
            if (data != null) data.recycle();
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (Throwable ignored) {
                    }
            }
            if (sharedMemory != null) sharedMemory.close();
        }
    }

    private static synchronized int amplitudeFor(
            int effectId,
            int strengthEffectId,
            boolean applyEffectCalibration,
            boolean sliderPreview,
            float configuredScale) {
        int maximum;
        if (sliderPreview
                && strengthEffectId == 1
                && effectId == SLIDER_PREVIEW_EFFECT) {
            maximum = SLIDER_PREVIEW_AMPLITUDE;
        } else if (effectId == 192) {
            maximum = VIDEO_RTP_AMPLITUDE;
        } else if (effectId == 10001) {
            maximum = NOTIFICATION_LONG_PRESS_AMPLITUDE;
        } else if (effectId == 162 || effectId == 163) {
            maximum = GESTURE_BACK_AMPLITUDE;
        } else {
            maximum = RTP_AMPLITUDE;
        }

        return Math.max(1, Math.min(255,
                Math.round(maximum * toMotorScale(configuredScale))));
    }

    private static byte[] applyWaveformGain(
            byte[] source,
            int effectId,
            boolean sliderPreview,
            float configuredScale) {
        float gain = 1.0f;
        if (sliderPreview) {
            gain = 1.0f + (SLIDER_PREVIEW_WAVEFORM_GAIN_MAX - 1.0f)
                    * clamp01(configuredScale);
        } else if (effectId == 162 || effectId == 163) {
            gain = GESTURE_BACK_WAVEFORM_GAIN;
        }
        if (gain <= 1.001f) return source;

        byte[] scaled = source.clone();
        for (int index = 0; index < scaled.length; index++) {
            int sample = source[index];
            int value = Math.round(sample * gain);
            value = Math.max(-127, Math.min(127, value));
            scaled[index] = (byte) value;
        }
        return scaled;
    }

    /** AudioTrack fallback consumes the same one-shot calibration as the HAL. */
    static synchronized float consumeAudioScale(int effectId) {
        return toMotorScale(consumeConfiguredScale(effectId, true));
    }

    // Xiaomi's native priority, reconstructed from its OS4 HAL: DIRECT is a
    // one-shot override; otherwise USER is multiplied by per-effect tuning.
    // BCL is the final device-state override. One-shot values are consumed here.
    private static float consumeConfiguredScale(
            int effectId, boolean applyEffectCalibration) {
        float scale;
        if (directStrength >= 0.0f) {
            scale = directStrength;
            directStrength = -1.0f;
        } else {
            scale = userStrength;
            Float calibrated = EFFECT_STRENGTHS.remove(effectId);
            if (applyEffectCalibration && calibrated != null && calibrated > 0.0f) {
                scale *= calibrated;
            }
        }
        scale = clamp01(scale);
        if (bclStrength >= 0.0f && scale > XIAOMI_BCL_REPLACE_THRESHOLD) {
            scale = clamp01(bclStrength);
        }
        return scale;
    }

    private static float toMotorScale(float xiaomiScale) {
        float normalized = clamp01(xiaomiScale);
        return OPLUS_MOTOR_SCALE_MIN
                + (OPLUS_MOTOR_SCALE_MAX - OPLUS_MOTOR_SCALE_MIN) * normalized;
    }

    private static float clampXiaomiStrength(float value) {
        if (!Float.isFinite(value)) return 1.0f;
        return Math.max(0.0f, Math.min(XIAOMI_STRENGTH_MAX, value));
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 1.0f;
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    static void stop() {
        if (!playing) return;
        Parcel data = null;
        try {
            IBinder target = getExtension();
            String targetDescriptor = extensionDescriptor;
            if (target == null || targetDescriptor == null) return;
            data = Parcel.obtain();
            data.writeInterfaceToken(targetDescriptor);
            data.writeStrongBinder(CALLBACK);
            target.transact(TRANSACTION_STOP, data, null, IBinder.FLAG_ONEWAY);
            if (TRACE_HOT_PATH) {
                XposedBridge.log(TAG + ": RTP stop requested");
            }
        } catch (Throwable throwable) {
            extension = null;
            extensionDescriptor = null;
            XposedBridge.log(TAG + ": RTP stop failed");
            XposedBridge.log(throwable);
        } finally {
            playing = false;
            if (data != null) data.recycle();
        }
    }

    private static boolean setAmplitude(
            IBinder target, String targetDescriptor, int amplitude)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(targetDescriptor);
            data.writeInt(amplitude);
            data.writeStrongBinder(CALLBACK);
            return target.transact(
                    TRANSACTION_SET_AMPLITUDE, data, null, IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    /** Minimal stable-AIDL callback used by the vendor RTP implementation. */
    private static final class RtpCallback extends Binder {
        private static final int TRANSACTION_ON_CALLBACK = 1;
        private static final int TRANSACTION_GET_HASH = 0xfffffe;
        private static final int TRANSACTION_GET_VERSION = 0xffffff;
        private static final int INTERFACE_TRANSACTION = 0x5f4e5446;
        private static final String HASH =
                "298dd3bb711fa1f23baaf23ba7ba03997fef4459";

        RtpCallback() {
            attachInterface(null, CALLBACK_DESCRIPTOR);
            try {
                // Vendor-stable services reject callbacks created as ordinary
                // system-stability Binder objects.
                XposedHelpers.callMethod(this, "markVintfStability");
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": unable to mark RTP callback VINTF-stable");
                XposedBridge.log(throwable);
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code >= 1 && code <= TRANSACTION_GET_VERSION) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
            }
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_GET_VERSION) {
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeInt(2);
                }
                return true;
            }
            if (code == TRANSACTION_GET_HASH) {
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeString(HASH);
                }
                return true;
            }
            if (code == TRANSACTION_ON_CALLBACK) {
                int status = data.readInt();
                // Oplus sends status=1 immediately after accepting/starting an
                // RTP stream (often more than once). It is an acknowledgement,
                // not an end-of-playback notification. Clearing the session
                // here used to make the later framework NativeWrapper.off()
                // skip TRANSACTION_STOP, so long video/ringtone RTP continued
                // until the whole file had drained. Only stop() owns the
                // framework session state.
                if (TRACE_HOT_PATH) {
                    XposedBridge.log(TAG + ": RTP callback ack status=" + status);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static byte[] load(int effectId) throws Exception {
        byte[] cached = CACHE.get(effectId);
        if (cached != null) return cached;

        String path = ModuleApkResolver.resolve(modulePath);
        if (path == null || path.isEmpty()) return null;
        modulePath = path;
        String entryName = "assets/rtp/effect_" + effectId + ".bin";
        try (ZipFile apk = new ZipFile(path)) {
            ZipEntry entry = apk.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream input = apk.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         (int) Math.max(32L, entry.getSize()))) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                byte[] loaded = output.toByteArray();
                if (loaded.length > SHORT_RTP_MAX_BYTES) {
                    int stereoBytes = loaded.length;
                    loaded = downmixStereo8ToMono8(loaded);
                    int maximumLead = effectId == 192
                            ? VIDEO_RTP_LEAD_COMPENSATION_BYTES
                            : LONG_RTP_LEAD_COMPENSATION_BYTES;
                    int silentLead = findSilentLead(loaded, maximumLead);
                    if (silentLead > 0) {
                        byte[] compensated = new byte[loaded.length - silentLead];
                        System.arraycopy(
                                loaded, silentLead,
                                compensated, 0,
                                compensated.length);
                        loaded = compensated;
                    }
                    LONG_STEREO_EFFECTS.add(effectId);
                    XposedBridge.log(TAG + ": downmix Xiaomi long RTP "
                            + effectId + " stereoBytes=" + stereoBytes
                            + " monoBytes=" + loaded.length
                            + " silentLeadCompensation="
                            + (silentLead / RTP_BYTES_PER_MILLISECOND) + "ms");
                }
                CACHE.put(effectId, loaded);
                return loaded;
            }
        }
    }

    /** Xiaomi's long haptic-A RTP is interleaved signed 8-bit L/R data. */
    private static byte[] downmixStereo8ToMono8(byte[] stereo) {
        int frames = (stereo.length + 1) / 2;
        byte[] mono = new byte[frames];
        for (int frame = 0; frame < frames; frame++) {
            int left = stereo[frame * 2];
            int right = frame * 2 + 1 < stereo.length
                    ? stereo[frame * 2 + 1]
                    : left;
            mono[frame] = (byte) ((left + right) / 2);
        }
        return mono;
    }

    /**
     * Compensate RichTap/FIFO startup latency without touching real waveform
     * content. Xiaomi long RTP files carry a leading digital-silence region
     * (small +/-1 dither); remove at most the per-video compensation window
     * and stop at the first sample
     * whose magnitude is perceptible. Files that start immediately therefore
     * receive little or no shift.
     */
    private static int findSilentLead(byte[] mono, int maximum) {
        int limit = Math.min(maximum, mono.length);
        int index = 0;
        while (index < limit && Math.abs((int) mono[index]) <= 1) {
            index++;
        }
        return index;
    }

    private static IBinder getExtension() throws Throwable {
        IBinder cached = extension;
        if (cached != null && cached.isBinderAlive()) return cached;

        synchronized (RtpPlayer.class) {
            cached = extension;
            if (cached != null && cached.isBinderAlive()) return cached;

            IBinder base = getVibrator();
            if (base == null) return null;
            IBinder found = (IBinder) XposedHelpers.callMethod(base, "getExtension");
            if (found == null) return null;

            String descriptor = found.getInterfaceDescriptor();
            if (!OPLUS_DESCRIPTOR.equals(descriptor)
                    && !RICHTAP_DESCRIPTOR.equals(descriptor)) {
                XposedBridge.log(TAG + ": unknown vibrator extension " + descriptor);
                return null;
            }

            extensionDescriptor = descriptor;
            extension = found;
            XposedBridge.log(TAG + ": vibrator RTP extension " + descriptor);
            return found;
        }
    }

    private static IBinder getVibrator() throws Throwable {
        IBinder cached = vibrator;
        if (cached != null && cached.isBinderAlive()) return cached;

        synchronized (RtpPlayer.class) {
            cached = vibrator;
            if (cached != null && cached.isBinderAlive()) return cached;

            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            cached = (IBinder) XposedHelpers.callStaticMethod(
                    serviceManager, "getService", VIBRATOR_SERVICE);
            vibrator = cached;
            return cached;
        }
    }
}
