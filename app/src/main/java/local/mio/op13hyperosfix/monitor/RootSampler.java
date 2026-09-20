package local.mio.op13hyperosfix.monitor;

import local.mio.op13hyperosfix.R;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Persistent native Root sampler with framed request/response I/O.
 *
 * <p>The one-shot shell only copies and execs the bundled PIE.  The PIE
 * immediately unlinks its temporary pathname, then performs all reads in one
 * process without spawning cat/awk/sed for every frame.  A blocking reader is
 * used instead of ready()+sleep polling.  A broken pipe is restarted once
 * transparently before the UI is told that sampling failed.</p>
 */
public final class RootSampler implements Closeable {
    private static final String READY = "__MAMBA_PROBE_READY__";
    private static final String BEGIN = "__MAMBA_PROBE_BEGIN__";
    private static final String END = "__MAMBA_PROBE_END__";
    private static final long START_TIMEOUT_MS = 6_000L;
    private static final long LIGHT_TIMEOUT_MS = 2_500L;
    private static final long FULL_TIMEOUT_MS = 8_000L;

    private final Context context;
    private final ExecutorService readerExecutor;
    private Process rootProcess;
    private BufferedWriter writer;
    private BufferedReader reader;
    private File stagedBinary;
    private boolean closed;

    public RootSampler(Context context) {
        this.context = context.getApplicationContext();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "MambaProbeNativeReader");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        readerExecutor = Executors.newSingleThreadExecutor(factory);
    }

    public synchronized List<String> sample(boolean full) throws IOException {
        if (closed) throw new IOException("采样器已经关闭");
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureStarted();
                writer.write(full ? "FULL\n" : "SAMPLE\n");
                writer.flush();
                return await(readerExecutor.submit(this::readFrameBlocking),
                        full ? FULL_TIMEOUT_MS : LIGHT_TIMEOUT_MS,
                        full ? "完整线程采样超时" : "快速采样超时");
            } catch (IOException error) {
                last = error;
                closeProcessOnly();
                if (closed || Thread.currentThread().isInterrupted()) break;
            }
        }
        throw last == null ? new IOException("Root 原生采样器启动失败") : last;
    }

    public synchronized List<String> samplePower() throws IOException {
        if (closed) throw new IOException("采样器已经关闭");
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureStarted();
                writer.write("POWER\n");
                writer.flush();
                return await(readerExecutor.submit(this::readFrameBlocking),
                        LIGHT_TIMEOUT_MS, "功耗采样超时");
            } catch (IOException error) {
                last = error;
                closeProcessOnly();
                if (closed || Thread.currentThread().isInterrupted()) break;
            }
        }
        throw last == null ? new IOException("功耗采样器启动失败") : last;
    }

    public synchronized List<String> sampleMonitor() throws IOException {
        if (closed) throw new IOException("采样器已经关闭");
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureStarted();
                writer.write("MONITOR\n");
                writer.flush();
                return await(readerExecutor.submit(this::readFrameBlocking),
                        FULL_TIMEOUT_MS, "进程监视采样超时");
            } catch (IOException error) {
                last = error;
                closeProcessOnly();
                if (closed || Thread.currentThread().isInterrupted()) break;
            }
        }
        throw last == null ? new IOException("进程监视采样器启动失败") : last;
    }

    private void ensureStarted() throws IOException {
        if (rootProcess != null && rootProcess.isAlive()) return;
        closeProcessOnly();
        File source = stageBinary();
        String destination = "/data/local/tmp/.op13_probe_sampler_"
                + android.os.Process.myUid() + "_" + android.os.Process.myPid();
        String command = "umask 077; /system/bin/cp " + shellQuote(source.getAbsolutePath())
                + " " + shellQuote(destination)
                + " && /system/bin/chmod 0700 " + shellQuote(destination)
                + " && exec " + shellQuote(destination) + " --unlink";
        rootProcess = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        writer = new BufferedWriter(new OutputStreamWriter(
                rootProcess.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(
                rootProcess.getInputStream(), StandardCharsets.UTF_8), 32 * 1024);
        await(readerExecutor.submit(this::readReadyBlocking), START_TIMEOUT_MS,
                "等待 Root 原生采样器启动超时");
    }

    private File stageBinary() throws IOException {
        if (stagedBinary != null && stagedBinary.isFile() && stagedBinary.length() > 0)
            return stagedBinary;
        File output = new File(context.getCodeCacheDir(), "op13_probe_sampler_v3");
        File temporary = new File(context.getCodeCacheDir(),
                "op13_probe_sampler_v3.tmp");
        try (InputStream input = context.getResources().openRawResource(
                R.raw.op13_probe_sampler);
             FileOutputStream sink = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) sink.write(buffer, 0, count);
            }
            sink.getFD().sync();
        }
        if (output.exists() && !output.delete())
            throw new IOException("无法替换原生采样器缓存");
        if (!temporary.renameTo(output))
            throw new IOException("无法提交原生采样器缓存");
        if (!output.setReadable(true, true))
            throw new IOException("无法设置原生采样器读取权限");
        stagedBinary = output;
        return output;
    }

    private Boolean readReadyBlocking() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (READY.equals(line)) return Boolean.TRUE;
        }
        throw new IOException("Root 被拒绝或原生采样器提前退出");
    }

    private List<String> readFrameBlocking() throws IOException {
        boolean begun = false;
        List<String> output = new ArrayList<>(64);
        String line;
        while ((line = reader.readLine()) != null) {
            if (BEGIN.equals(line)) {
                begun = true;
                output.clear();
            } else if (END.equals(line) && begun) {
                return output;
            } else if (begun) {
                output.add(line);
            }
        }
        throw new IOException("Root 原生采样器连接已关闭");
    }

    private static <T> T await(Future<T> future, long timeoutMs,
                               String timeoutMessage) throws IOException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IOException(timeoutMessage, timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("采样服务正在停止", interrupted);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("原生采样器读取失败", cause);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override public synchronized void close() {
        closed = true;
        closeProcessOnly();
        readerExecutor.shutdownNow();
    }

    private void closeProcessOnly() {
        if (writer != null) {
            try { writer.write("EXIT\n"); writer.flush(); }
            catch (Exception ignored) {}
        }
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        if (rootProcess != null) {
            rootProcess.destroy();
            try {
                if (!rootProcess.waitFor(150, TimeUnit.MILLISECONDS))
                    rootProcess.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                rootProcess.destroyForcibly();
            }
        }
        writer = null;
        reader = null;
        rootProcess = null;
    }
}
