package local.mio.op13hyperosfix;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ConfigWriter {
    private static final long TIMEOUT_SECONDS = 8L;

    private ConfigWriter() {
    }

    static boolean read(Context context, String key, boolean defaultValue) {
        return ModuleConfig.isEnabled(context, key, defaultValue);
    }

    static int readInt(Context context, String key, int defaultValue) {
        return ModuleConfig.getInt(context, key, defaultValue);
    }

    static Result write(String key, boolean value) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put(key, value);
        return write(values);
    }

    static Result writeInt(String key, int value) {
        String script = "[ \"$(id -u 2>/dev/null)\" = 0 ] || exit 90\n"
                + "settings put global " + key + " " + value + " || exit 91\n"
                + "exit 0\n";
        return runScript(script);
    }

    static Result writeString(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        return writeStrings(values);
    }

    static Result writeStrings(Map<String, String> values) {
        StringBuilder script = new StringBuilder();
        script.append("[ \"$(id -u 2>/dev/null)\" = 0 ] || exit 90\n");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String safe = entry.getValue() == null ? "" : entry.getValue();
            if (!entry.getKey().matches("[A-Za-z0-9._-]+")
                    || !safe.matches("[A-Za-z0-9._,-]*")) {
                return new Result(false, "配置包含非法字符");
            }
            script.append("settings put global ")
                    .append(entry.getKey())
                    .append(" '")
                    .append(safe)
                    .append("' || exit 91\n");
        }
        script.append("exit 0\n");
        return runScript(script.toString());
    }

    static Result write(Map<String, Boolean> values) {
        StringBuilder script = new StringBuilder();
        script.append("[ \"$(id -u 2>/dev/null)\" = 0 ] || exit 90\n");
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            script.append("settings put global ")
                    .append(entry.getKey())
                    .append(' ')
                    .append(entry.getValue() ? '1' : '0')
                    .append(" || exit 91\n");
        }
        script.append("exit 0\n");
        return runScript(script.toString());
    }

    private static Result runScript(String script) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, "ROOT 配置写入超时");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int code = process.exitValue();
            if (code == 0) {
                return new Result(true, "");
            }
            String message = code == 90 ? "未获得 ROOT 授权"
                    : "配置写入失败，退出码 " + code;
            if (output.length() > 0) {
                message += "\n" + output.toString().trim();
            }
            return new Result(false, message);
        } catch (Throwable error) {
            return new Result(false, "ROOT 配置写入失败：" + error.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
