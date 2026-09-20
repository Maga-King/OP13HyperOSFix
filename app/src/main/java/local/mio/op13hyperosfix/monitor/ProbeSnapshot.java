package local.mio.op13hyperosfix.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One immutable-by-public-contract, read-only snapshot returned by the Root sampler. */
public final class ProbeSnapshot {
    private static final Pattern NUMBER_FIELD = Pattern.compile("(?:^|\\s)%s=(-?\\d+)");
    private static final Pattern TEXT_FIELD = Pattern.compile("(?:^|\\s)%s=([^\\s]+)");

    public boolean rootGranted;
    public boolean moduleReady;
    public String error = "";
    public int requestMode = -1;
    public int effectiveMode = -1;
    public int dailyMode = -1;
    public int requestProfile = -1;
    public int effectiveProfile = -1;
    public int saver;
    public int extreme;
    public int screenActive = 1;
    public int tempDecic = -1;
    public int thermalState;
    public int fpsCap;
    public String owner = "不可用";
    public String mainPackage = "";
    public String visiblePackages = "";
    public final Cluster policy0 = new Cluster("CPU0-5");
    public final Cluster policy6 = new Cluster("CPU6-7");
    public final Gpu gpu = new Gpu();
    public long systemTicks;
    public long systemIdleTicks;
    public double systemCpuPercent;
    public long adapterTicks;
    public int adapterPid = -1;
    public long adapterRssKb;
    public int onlineCores = 8;
    public double adapterCpuCorePercent;
    public double adapterCpuDevicePercent;
    public long memoryTotalKb;
    public long memoryAvailableKb;
    public long swapTotalKb;
    public long swapFreeKb;
    public long batteryCurrentUa;
    public long batteryVoltageUv;
    public long batteryPackVoltageUv;
    public int batteryCellCount = 2;
    public long batteryChargeCounterUah;
    public boolean usbOnline;
    public int batteryCapacity = -1;
    public String batteryStatus = "Unknown";
    public double batteryPowerW = -1;
    public double averagePowerW;
    public double sessionEnergyWh;
    public long sessionElapsedMs;
    public double displayFps = -1;
    public long sessionSamples;
    public double sessionAverageCpuPercent;
    public double sessionAverageGpuPercent;
    public double sessionMinimumPowerW = -1;
    public double sessionMaximumPowerW = -1;
    public int sessionMaximumTempDecic = -1;
    public int sessionBatteryDeltaPercent;
    public int traces;
    public int tracked;
    public int records;
    public long threadEvents;
    public long threadDrops;
    public long threadErrors;
    public long applyRequests;
    public long applyChanges;
    public long applyErrors;
    public long restoreErrors;
    public int guardActive;
    public int guardRecords;
    public long guardEvents;
    public long guardRejected;
    public long guardRegisterErrors;
    public String samplerBackend = "?";
    public long samplerSequence;
    public long samplerElapsedUs;
    public final Map<Integer, Integer> slotPriority = new HashMap<>();
    public List<TaskAffinity> tasks = new ArrayList<>();
    public List<ProcessStat> processes = new ArrayList<>();
    public String rulesJson = "";

    public static ProbeSnapshot parse(List<String> lines, ProbeSnapshot previous) {
        ProbeSnapshot out = new ProbeSnapshot();
        if (previous != null) {
            out.rulesJson = previous.rulesJson;
            out.tasks = previous.tasks;
            out.processes = previous.processes;
        }
        StringBuilder rules = new StringBuilder();
        boolean receivedRules = false;
        boolean receivedTasks = false;
        List<TaskAffinity> nextTasks = new ArrayList<>();
        List<ProcessStat> nextProcesses = new ArrayList<>();
        boolean receivedProcesses = false;

        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (line.startsWith("rules|")) {
                if (rules.length() > 0) rules.append('\n');
                rules.append(line.substring(6));
                receivedRules = true;
                continue;
            }
            if (line.startsWith("task|")) {
                String[] fields = line.split("\\|", -1);
                if (fields.length >= 6) {
                    int tgid = parseInt(fields[1], -1);
                    int tid = parseInt(fields[2], -1);
                    if (tgid > 0 && tid > 0) {
                        nextTasks.add(new TaskAffinity(tgid, tid, fields[3],
                                fields[4], fields[5]));
                        receivedTasks = true;
                    }
                }
                continue;
            }
            if (line.startsWith("process|")) {
                String[] fields = line.split("\\|", 5);
                if (fields.length == 5) {
                    int pid = parseInt(fields[1], -1);
                    if (pid > 0) {
                        nextProcesses.add(new ProcessStat(pid,
                                parseLong(fields[2], 0), parseLong(fields[3], 0),
                                fields[4]));
                        receivedProcesses = true;
                    }
                }
                continue;
            }
            if (line.startsWith("sched|")) {
                parseSchedulerLine(out, line.substring(6));
                continue;
            }
            if (line.startsWith("threads|")) {
                parseThreadLine(out, line.substring(8));
                continue;
            }
            if (line.startsWith("visible|")) {
                parseVisibleLine(out, line.substring(8));
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1).trim();
            switch (key) {
                case "root.uid" -> out.rootGranted = "0".equals(value);
                case "error" -> out.error = value;
                case "cpu0.cur" -> out.policy0.current = parseLong(value, -1);
                case "cpu0.min" -> out.policy0.actualMin = parseLong(value, -1);
                case "cpu0.max" -> out.policy0.actualMax = parseLong(value, -1);
                case "cpu0.hwmin" -> out.policy0.hardwareMin = parseLong(value, -1);
                case "cpu0.hwmax" -> out.policy0.hardwareMax = parseLong(value, -1);
                case "cpu0.gov" -> out.policy0.governor = value;
                case "cpu6.cur" -> out.policy6.current = parseLong(value, -1);
                case "cpu6.min" -> out.policy6.actualMin = parseLong(value, -1);
                case "cpu6.max" -> out.policy6.actualMax = parseLong(value, -1);
                case "cpu6.hwmin" -> out.policy6.hardwareMin = parseLong(value, -1);
                case "cpu6.hwmax" -> out.policy6.hardwareMax = parseLong(value, -1);
                case "cpu6.gov" -> out.policy6.governor = value;
                case "gpu.cur" -> out.gpu.current = parseLong(value, -1);
                case "gpu.min" -> out.gpu.minimum = parseLong(value, -1);
                case "gpu.max" -> out.gpu.maximum = parseLong(value, -1);
                case "gpu.busy" -> out.gpu.busy = parseDouble(value, -1);
                case "gpu.gov" -> out.gpu.governor = value;
                case "system.ticks" -> out.systemTicks = parseLong(value, 0);
                case "system.idle_ticks" -> out.systemIdleTicks = parseLong(value, 0);
                case "system.cores" -> out.onlineCores = Math.max(1, parseInt(value, 8));
                case "adapter.pid" -> out.adapterPid = parseInt(value, -1);
                case "adapter.ticks" -> out.adapterTicks = parseLong(value, 0);
                case "adapter.rss_kb" -> out.adapterRssKb = parseLong(value, 0);
                case "battery.temp_decic" -> {
                    if (out.tempDecic < 0) out.tempDecic = parseInt(value, -1);
                }
                case "battery.current_ua" -> out.batteryCurrentUa = parseLong(value, 0);
                case "battery.voltage_uv" -> out.batteryVoltageUv = parseLong(value, 0);
                case "battery.pack_voltage_uv" -> out.batteryPackVoltageUv =
                        parseLong(value, 0);
                case "battery.cell_count" -> out.batteryCellCount = Math.max(1,
                        parseInt(value, 2));
                case "battery.charge_counter_uah" -> out.batteryChargeCounterUah =
                        parseLong(value, 0);
                case "power.usb_online" -> out.usbOnline = parseInt(value, 0) != 0;
                case "battery.capacity" -> out.batteryCapacity = parseInt(value, -1);
                case "battery.status" -> out.batteryStatus = value;
                case "battery.power_w" -> out.batteryPowerW = parseDouble(value, -1);
                case "memory.total_kb" -> out.memoryTotalKb = parseLong(value, 0);
                case "memory.available_kb" -> out.memoryAvailableKb = parseLong(value, 0);
                case "memory.swap_total_kb" -> out.swapTotalKb = parseLong(value, 0);
                case "memory.swap_free_kb" -> out.swapFreeKb = parseLong(value, 0);
                case "display.fps" -> out.displayFps = parseDouble(value, -1);
                case "sampler.backend" -> out.samplerBackend = value;
                case "sampler.sequence" -> out.samplerSequence = parseLong(value, 0);
                case "sampler.elapsed_us" -> out.samplerElapsedUs = parseLong(value, 0);
            }
        }
        if (receivedRules) out.rulesJson = rules.toString();
        if (receivedTasks) out.tasks = nextTasks;
        if (receivedProcesses) out.processes = nextProcesses;
        out.moduleReady = out.moduleReady || out.policy0.expectedMin > 0;
        return out;
    }

    private static void parseSchedulerLine(ProbeSnapshot out, String line) {
        if (line.startsWith("ready=")) {
            out.moduleReady = fieldInt(line, "ready", 0) == 1;
            out.requestMode = fieldInt(line, "request", out.requestMode);
            out.requestProfile = fieldInt(line, "profile", out.requestProfile);
            out.dailyMode = fieldInt(line, "daily", out.dailyMode);
            out.effectiveMode = fieldInt(line, "effective", out.effectiveMode);
            out.effectiveProfile = fieldInt(line, "effective_profile", out.effectiveProfile);
        } else if (line.startsWith("saver=")) {
            out.saver = fieldInt(line, "saver", 0);
            out.extreme = fieldInt(line, "extreme", 0);
            out.screenActive = fieldInt(line, "active", 1);
            out.tempDecic = fieldInt(line, "temp_decic", -1);
            out.thermalState = fieldInt(line, "thermal", 0);
            out.fpsCap = fieldInt(line, "fps_cap", 0);
        } else if (line.startsWith("owner=")) {
            out.owner = line.substring(6).trim();
        } else if (line.startsWith("policy0 ")) {
            out.policy0.expectedMin = fieldLong(line, "min", -1);
            out.policy0.expectedMax = fieldLong(line, "max", -1);
        } else if (line.startsWith("policy6 ")) {
            out.policy6.expectedMin = fieldLong(line, "min", -1);
            out.policy6.expectedMax = fieldLong(line, "max", -1);
        }
    }

    private static void parseThreadLine(ProbeSnapshot out, String line) {
        if (line.startsWith("ready=")) {
            out.traces = fieldInt(line, "traces", 0);
            out.tracked = fieldInt(line, "tracked", 0);
        } else if (line.startsWith("slot")) {
            int tgid = fieldInt(line, "tgid", -1);
            int priority = fieldInt(line, "priority", -1);
            if (tgid > 0) out.slotPriority.put(tgid, priority);
        } else if (line.startsWith("events=")) {
            out.threadEvents = fieldLong(line, "events", 0);
            out.threadDrops = fieldLong(line, "drops", 0);
            out.threadErrors = fieldLong(line, "uevent_errors", 0);
            out.records = fieldInt(line, "records", 0);
        } else if (line.startsWith("apply ")) {
            out.applyRequests = fieldLong(line, "requests", 0);
            out.applyChanges = fieldLong(line, "changes", 0);
            out.applyErrors = fieldLong(line, "errors", 0);
            out.restoreErrors = fieldLong(line, "restore_errors", 0);
        } else if (line.startsWith("guard ")) {
            out.guardActive = fieldInt(line, "active", 0);
            out.guardRecords = fieldInt(line, "records", 0);
            out.guardEvents = fieldLong(line, "events", 0);
            out.guardRejected = fieldLong(line, "rejected", 0);
            out.guardRegisterErrors = fieldLong(line, "register_errors", 0);
        }
    }

    private static void parseVisibleLine(ProbeSnapshot out, String line) {
        int split = line.indexOf('=');
        if (split <= 0) return;
        String key = line.substring(0, split);
        String value = line.substring(split + 1).trim();
        if ("main".equals(key)) out.mainPackage = value;
        else if ("visible".equals(key)) out.visiblePackages = value;
    }

    private static int fieldInt(String line, String name, int fallback) {
        long value = fieldLong(line, name, fallback);
        return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
                ? fallback : (int) value;
    }

    private static long fieldLong(String line, String name, long fallback) {
        Pattern pattern = Pattern.compile(String.format(Locale.ROOT,
                NUMBER_FIELD.pattern(), Pattern.quote(name)));
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? parseLong(matcher.group(1), fallback) : fallback;
    }

    private static String fieldText(String line, String name, String fallback) {
        Pattern pattern = Pattern.compile(String.format(Locale.ROOT,
                TEXT_FIELD.pattern(), Pattern.quote(name)));
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static double parseDouble(String value, double fallback) {
        try { return Double.parseDouble(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    public String compactReport(ThreadPolicyVerifier.Result binding, float stressFps) {
        if (!rootGranted) return "Root：未授权\n" + (error.isEmpty() ? "等待授权或启动服务" : error);
        StringBuilder out = new StringBuilder();
        if (!moduleReady) out.append("内置调度未运行 · 仅监视硬件\n");
        else out.append("档位 ").append(modeName(effectiveMode)).append(" · ")
                .append(profileName(effectiveProfile)).append("\n")
                .append(shortOwner()).append("\n");
        out.append(String.format(Locale.CHINA,
                        "CPU %.1f%% · RAM %.1f/%.1fGB\n",
                        systemCpuPercent, memoryUsedGb(), memoryTotalGb()))
                .append(policy0.shortLine()).append("\n")
                .append(policy6.shortLine()).append("\n")
                .append(gpu.shortLine()).append("\n")
                .append(powerLine()).append("\n");
        if (moduleReady) out.append(binding.oneLine());
        if (guardActive != 0 || guardRejected != 0)
            out.append(" · 护栏拒绝 ").append(guardRejected);
        out.append("\n")
                .append(String.format(Locale.CHINA,
                        "调度进程 PID %s · CPU %.3f%%整机/%.2f%%单核 · RSS %.1fMB",
                        adapterPid > 0 ? Integer.toString(adapterPid) : "无",
                        adapterCpuDevicePercent, adapterCpuCorePercent,
                        adapterRssKb / 1024.0));
        if (stressFps > 0) out.append(String.format(Locale.CHINA, "\n3D %.1f FPS", stressFps));
        else if (displayFps >= 0) out.append(String.format(Locale.CHINA,
                "\n屏幕实测 %.1f Hz", displayFps));
        out.append(String.format(Locale.CHINA, "\n采样 %s %.2fms · #%d",
                samplerBackend, samplerElapsedUs / 1000.0, samplerSequence));
        return out.toString();
    }

    public String detailedReport(ThreadPolicyVerifier.Result binding, float stressFps) {
        if (!rootGranted) return compactReport(binding, stressFps);
        StringBuilder out = new StringBuilder();
        out.append("【内置调度】\n")
                .append(moduleReady ? "KO 与桥接器已运行\n" : "未运行；以下硬件统计仍然有效\n")
                .append("请求：").append(modeName(requestMode)).append("/")
                .append(profileName(requestProfile)).append("　生效：")
                .append(modeName(effectiveMode)).append("/")
                .append(profileName(effectiveProfile)).append("\n")
                .append("所有者：").append(owner).append("\n")
                .append("省电=").append(saver).append("　超级省电=").append(extreme)
                .append("　温控=").append(thermalState).append("　FPS上限=")
                .append(fpsCap).append("\n\n")
                .append("【性能与功率】\n")
                .append(String.format(Locale.CHINA,
                        "CPU 总负载 %.1f%%　RAM %.2f/%.2fGB　Swap %.2f/%.2fGB\n",
                        systemCpuPercent, memoryUsedGb(), memoryTotalGb(),
                        swapUsedGb(), swapTotalGb()))
                .append(powerLine()).append("\n")
                .append(String.format(Locale.CHINA,
                        "监视时长 %s　累计 %.4fWh　平均 %.2fW",
                        durationText(sessionElapsedMs), sessionEnergyWh,
                        averagePowerW)).append("\n")
                .append(String.format(Locale.CHINA,
                        "样本 %d　平均 CPU %.1f%% / GPU %.1f%%　功率 %.2f~%.2fW　最高 %.1f°C　电量变化 %+d%%",
                        sessionSamples, sessionAverageCpuPercent,
                        sessionAverageGpuPercent,
                        Math.max(0, sessionMinimumPowerW),
                        Math.max(0, sessionMaximumPowerW),
                        sessionMaximumTempDecic < 0 ? 0.0
                                : sessionMaximumTempDecic / 10.0,
                        sessionBatteryDeltaPercent)).append("\n\n")
                .append("【CPU/GPU 实际值与 KO 投票】\n")
                .append(policy0.detailedLine()).append("\n")
                .append(policy6.detailedLine()).append("\n")
                .append(gpu.detailedLine()).append("\n\n")
                .append("【线程放置】\n").append(binding.details()).append("\n")
                .append("trace=").append(traces).append(" tracked=").append(tracked)
                .append(" records=").append(records).append(" events=").append(threadEvents)
                .append(" drops=").append(threadDrops).append(" errors=")
                .append(threadErrors + applyErrors).append(" restore_error=")
                .append(restoreErrors).append("\n")
                .append("guard=").append(guardActive).append(" records=")
                .append(guardRecords).append(" events=").append(guardEvents)
                .append(" rejected=").append(guardRejected)
                .append(" register_error=").append(guardRegisterErrors)
                .append("\n\n")
                .append("【调度进程自身开销】\n")
                .append(String.format(Locale.CHINA,
                        "PID=%s　CPU=%.3f%%整机 / %.2f%%单核　RSS=%.1fMB",
                        adapterPid > 0 ? Integer.toString(adapterPid) : "未运行",
                        adapterCpuDevicePercent, adapterCpuCorePercent,
                        adapterRssKb / 1024.0));
        if (stressFps > 0) out.append(String.format(Locale.CHINA,
                "\n3D 压测=%.1f FPS", stressFps));
        if (tempDecic >= 0) out.append(String.format(Locale.CHINA,
                "　电池=%.1f°C", tempDecic / 10.0));
        out.append(String.format(Locale.CHINA,
                "\n采样器=%s　序号=%d　耗时=%.2fms",
                samplerBackend, samplerSequence, samplerElapsedUs / 1000.0));
        return out.toString();
    }

    private String shortOwner() {
        String value = owner == null || owner.isEmpty() ? "不可用" : owner;
        return value.length() > 46 ? value.substring(0, 43) + "…" : value;
    }

    private String powerLine() {
        if (batteryPowerW < 0 || batteryPowerW > 250)
            return "电池功率不可用";
        return String.format(Locale.CHINA,
                "功率 %.2fW · %.0fmA @ %.3fV · %d%% · %.1f°C",
                batteryPowerW, batteryCurrentUa / 1000.0,
                batteryVoltageUv / 1_000_000.0, Math.max(0, batteryCapacity),
                tempDecic < 0 ? 0.0 : tempDecic / 10.0);
    }

    private double memoryUsedGb() {
        return Math.max(0, memoryTotalKb - memoryAvailableKb) / 1048576.0;
    }

    private double memoryTotalGb() { return memoryTotalKb / 1048576.0; }

    private double swapUsedGb() {
        return Math.max(0, swapTotalKb - swapFreeKb) / 1048576.0;
    }

    private double swapTotalGb() { return swapTotalKb / 1048576.0; }

    private static String durationText(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.CHINA, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    public static String modeName(int mode) {
        return switch (mode) {
            case 0 -> "省电";
            case 1 -> "均衡";
            case 2 -> "性能";
            case 3 -> "极速";
            case -1 -> "日常自动";
            default -> "未知";
        };
    }

    public static String profileName(int profile) {
        return profile == 1 ? "游戏表" : profile == 0 ? "应用表" : "未知表";
    }

    public static final class Cluster {
        public final String name;
        public long current = -1;
        public long actualMin = -1;
        public long actualMax = -1;
        public long expectedMin = -1;
        public long expectedMax = -1;
        public long hardwareMin = -1;
        public long hardwareMax = -1;
        public String governor = "?";

        Cluster(String name) { this.name = name; }

        public boolean exact() {
            return expectedMin > 0 && actualMin == expectedMin && actualMax == expectedMax;
        }

        public String verdict() {
            if (expectedMin <= 0 || actualMin <= 0) return "无法核验";
            if (exact()) return "✓ 与内置投票一致";
            if (actualMin >= expectedMin && actualMax <= expectedMax)
                return "⚠ 存在更严格的其它投票";
            return "✗ 实际范围未包含内置投票";
        }

        String shortLine() {
            return String.format(Locale.CHINA, "%s %.0f [%.0f~%.0f]MHz %s",
                    name, mhzKhz(current), mhzKhz(actualMin), mhzKhz(actualMax),
                    exact() ? "✓" : "⚠");
        }

        String detailedLine() {
            return String.format(Locale.CHINA,
                    "%s 当前 %.1fMHz　实际 %.1f~%.1f　KO %.1f~%.1f　%s　%s",
                    name, mhzKhz(current), mhzKhz(actualMin), mhzKhz(actualMax),
                    mhzKhz(expectedMin), mhzKhz(expectedMax), governor, verdict());
        }

        private static double mhzKhz(long value) { return value < 0 ? 0 : value / 1000.0; }
    }

    public static final class Gpu {
        public long current = -1;
        public long minimum = -1;
        public long maximum = -1;
        public double busy = -1;
        public String governor = "?";

        String shortLine() {
            return String.format(Locale.CHINA, "GPU %.0f [%.0f~%.0f]MHz · %.0f%%",
                    mhz(current), mhz(minimum), mhz(maximum), Math.max(0, busy));
        }

        String detailedLine() {
            return String.format(Locale.CHINA,
                    "GPU 当前 %.1fMHz　范围 %.1f~%.1f　忙碌 %.1f%%　%s",
                    mhz(current), mhz(minimum), mhz(maximum), Math.max(0, busy), governor);
        }

        private static double mhz(long value) { return value < 0 ? 0 : value / 1_000_000.0; }
    }

    public static final class TaskAffinity {
        public final int tgid;
        public final int tid;
        public final String packageName;
        public final String comm;
        public final String allowedList;
        public final int allowedMask;

        TaskAffinity(int tgid, int tid, String packageName, String comm, String allowedList) {
            this.tgid = tgid;
            this.tid = tid;
            this.packageName = packageName;
            this.comm = comm;
            this.allowedList = allowedList;
            this.allowedMask = cpuListMask(allowedList);
        }
    }

    public static final class ProcessStat {
        public final int pid;
        public final long ticks;
        public final long rssKb;
        public final String processName;
        public double cpuPercent;

        ProcessStat(int pid, long ticks, long rssKb, String processName) {
            this.pid = pid;
            this.ticks = ticks;
            this.rssKb = rssKb;
            this.processName = processName;
        }
    }

    static int cpuListMask(String cpus) {
        if (cpus == null || cpus.trim().isEmpty()) return 0;
        int mask = 0;
        try {
            for (String part : cpus.trim().split(",")) {
                int dash = part.indexOf('-');
                int first = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
                int last = Integer.parseInt(dash < 0 ? part : part.substring(dash + 1));
                if (first < 0 || last < first || last > 7) return 0;
                for (int cpu = first; cpu <= last; cpu++) mask |= 1 << cpu;
            }
        } catch (Exception ignored) { return 0; }
        return mask;
    }
}
