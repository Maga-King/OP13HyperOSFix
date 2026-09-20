package local.mio.oneplus13hyperhaptics;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;

/** On-demand root player for the long Xiaomi Settings RTP video. */
final class RootRtpPlayer {
    private static final String TAG = "OnePlus13HyperHaptics";
    private static final String HELPER_ASSET = "assets/root/rtp_root_helper";
    private static final String VIDEO_ASSET = "assets/rtp/effect_192.bin";
    private static final String ROOT_DIR = "/data/local/tmp/oneplus13_hyper_haptics";
    private static final String ROOT_HELPER = ROOT_DIR + "/rtp_root_helper";
    private static final String ROOT_VIDEO = ROOT_DIR + "/effect_192.bin";
    // About 3.98 V on the OnePlus 13 driver. Full gain (128 / ~7.49 V)
    // audibly clips the long Xiaomi haptic-video waveform.
    private static final int VIDEO_GAIN_MAX = 68;

    private static final Object LOCK = new Object();
    private static final AtomicBoolean STOP_RUNNING = new AtomicBoolean(false);
    private static volatile String modulePath;
    private static volatile boolean rootFilesReady;
    private static volatile boolean playbackActive;
    private static volatile Process currentProcess;
    private static long generation;

    private RootRtpPlayer() {}

    static void setModulePath(String path) {
        modulePath = path;
    }

    /**
     * Checks root only at the moment the video requests effect 192.  A false
     * result deliberately leaves the stock Settings method untouched.
     */
    static boolean performVideo(Context context, int effectId) {
        if (effectId != 192 || context == null) return false;
        if (!hasRootNow()) {
            XposedBridge.log(TAG + ": Settings video 192 not intercepted: root unavailable");
            return false;
        }

        try {
            int videoGain = Math.round(
                    VIDEO_GAIN_MAX * AudioRtpPlayer.readOfficialStrength(context));
            if (videoGain <= 0) {
                stop();
                XposedBridge.log(TAG + ": root mmap video RTP suppressed by official slider");
                return true;
            }
            LocalAssets assets = extractAssets(context);
            if (assets == null || !installRootAssets(assets)) return false;

            Process oldProcess;
            boolean stopOld;
            long localGeneration;
            synchronized (LOCK) {
                oldProcess = currentProcess;
                currentProcess = null;
                stopOld = playbackActive;
                playbackActive = false;
                localGeneration = ++generation;
            }
            if (oldProcess != null) oldProcess.destroy();
            if (stopOld) runRootCommand(shellQuote(ROOT_HELPER) + " --stop", 2_000L);

            String command = "exec " + shellQuote(ROOT_HELPER) + " "
                    + shellQuote(ROOT_VIDEO) + " " + videoGain;
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            synchronized (LOCK) {
                if (generation != localGeneration) {
                    process.destroy();
                    return false;
                }
                currentProcess = process;
                playbackActive = true;
            }
            Thread monitor = new Thread(
                    () -> monitor(process, localGeneration),
                    "HyperHaptics-root-rtp-monitor");
            monitor.setDaemon(true);
            monitor.start();
            XposedBridge.log(TAG + ": root mmap video RTP 192 started gain=" + videoGain);
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": failed to start root mmap video RTP 192");
            XposedBridge.log(throwable);
            return false;
        }
    }

    static void stop() {
        Process process;
        boolean shouldStop;
        synchronized (LOCK) {
            ++generation;
            process = currentProcess;
            currentProcess = null;
            shouldStop = playbackActive;
            playbackActive = false;
        }
        if (process != null) process.destroy();
        if (!shouldStop || !rootFilesReady || !STOP_RUNNING.compareAndSet(false, true)) {
            return;
        }

        Thread stopper = new Thread(() -> {
            try {
                runRootCommand(shellQuote(ROOT_HELPER) + " --stop", 2_000L);
                XposedBridge.log(TAG + ": root mmap video RTP stopped");
            } finally {
                STOP_RUNNING.set(false);
            }
        }, "HyperHaptics-root-rtp-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private static void monitor(Process process, long localGeneration) {
        int exitCode = -1;
        String output = "";
        try {
            exitCode = process.waitFor();
            output = readOutput(process);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": root RTP monitor failed");
            XposedBridge.log(throwable);
        }
        synchronized (LOCK) {
            if (currentProcess == process) currentProcess = null;
            if (generation == localGeneration) playbackActive = false;
        }
        XposedBridge.log(TAG + ": root mmap video RTP exited code=" + exitCode
                + (output.isEmpty() ? "" : " output=" + output));
    }

    private static boolean hasRootNow() {
        CommandResult result = runRootCommand("id -u", 3_000L);
        return result.exitCode == 0 && "0".equals(result.output.trim());
    }

    private static boolean installRootAssets(LocalAssets assets) {
        if (rootFilesReady) return true;
        synchronized (RootRtpPlayer.class) {
            if (rootFilesReady) return true;
            String command = "umask 077; mkdir -p " + shellQuote(ROOT_DIR)
                    + " && cp " + shellQuote(assets.helper.getAbsolutePath())
                    + " " + shellQuote(ROOT_HELPER + ".new")
                    + " && chmod 0700 " + shellQuote(ROOT_HELPER + ".new")
                    + " && mv -f " + shellQuote(ROOT_HELPER + ".new")
                    + " " + shellQuote(ROOT_HELPER)
                    + " && cp " + shellQuote(assets.video.getAbsolutePath())
                    + " " + shellQuote(ROOT_VIDEO + ".new")
                    + " && chmod 0600 " + shellQuote(ROOT_VIDEO + ".new")
                    + " && mv -f " + shellQuote(ROOT_VIDEO + ".new")
                    + " " + shellQuote(ROOT_VIDEO);
            CommandResult result = runRootCommand(command, 8_000L);
            if (result.exitCode != 0) {
                XposedBridge.log(TAG + ": root RTP asset install failed code="
                        + result.exitCode + " output=" + result.output);
                return false;
            }
            rootFilesReady = true;
            XposedBridge.log(TAG + ": root RTP assets prepared on demand");
            return true;
        }
    }

    private static LocalAssets extractAssets(Context context) throws Exception {
        String apk = ModuleApkResolver.resolve(modulePath);
        if (apk == null || apk.isEmpty()) return null;
        modulePath = apk;
        File directory = new File(context.getCodeCacheDir(), "oneplus13_hyper_haptics");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("cannot create " + directory);
        }
        try (ZipFile zip = new ZipFile(apk)) {
            File helper = extract(zip, HELPER_ASSET, directory, "rtp_root_helper");
            File video = extract(zip, VIDEO_ASSET, directory, "effect_192.bin");
            if (!helper.setExecutable(true, true)) {
                XposedBridge.log(TAG + ": local RTP helper chmod was not accepted");
            }
            return new LocalAssets(helper, video);
        }
    }

    private static File extract(ZipFile zip, String entryName, File directory,
                                String baseName) throws Exception {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.getSize() <= 0) {
            throw new IllegalStateException("missing APK entry " + entryName);
        }
        String suffix = Long.toHexString(entry.getCrc());
        File destination = new File(directory, baseName + "-" + suffix);
        if (destination.isFile() && destination.length() == entry.getSize()) {
            return destination;
        }
        File temporary = new File(directory, destination.getName() + ".tmp");
        try (InputStream input = zip.getInputStream(entry);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (temporary.length() != entry.getSize()) {
            throw new IllegalStateException("short APK extraction " + entryName);
        }
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("cannot replace " + destination);
        }
        if (!temporary.renameTo(destination)) {
            throw new IllegalStateException("cannot commit " + destination);
        }
        return destination;
    }

    private static CommandResult runRootCommand(String command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-2, "timeout");
            }
            return new CommandResult(process.exitValue(), readOutput(process));
        } catch (Throwable throwable) {
            if (process != null) process.destroyForcibly();
            return new CommandResult(-1, throwable.toString());
        }
    }

    private static String readOutput(Process process) {
        try (InputStream input = process.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class LocalAssets {
        final File helper;
        final File video;

        LocalAssets(File helper, File video) {
            this.helper = helper;
            this.video = video;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
