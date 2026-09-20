package local.mio.op13hyperosfix.monitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Replays MambaSched's documented mask selection without ever writing affinity. */
public final class ThreadPolicyVerifier {
    private ThreadPolicyVerifier() {}

    public static Result verify(ProbeSnapshot snapshot) {
        if (!snapshot.moduleReady) return new Result(0, 0, 0,
                "KO 未加载，不能核验线程放置", List.of());
        if (snapshot.tracked == 0) {
            String state = snapshot.traces == 0
                    ? "空载正确：tracked=0、trace=0" : "空载异常：仍注册 trace";
            return new Result(0, 0, snapshot.traces == 0 ? 0 : 1,
                    state, List.of());
        }
        if (snapshot.tasks.isEmpty()) return new Result(0, 0, 0,
                "等待下一次线程明细采样", List.of());

        Map<String, Rule> rules;
        try {
            rules = parseRules(snapshot.rulesJson);
        } catch (Exception error) {
            return new Result(0, 0, 1,
                    "当前 thread.json 无法解析：" + error.getClass().getSimpleName(),
                    List.of());
        }

        boolean game = snapshot.requestProfile == 1;
        int checked = 0;
        int matched = 0;
        List<String> mismatches = new ArrayList<>();
        for (ProbeSnapshot.TaskAffinity task : snapshot.tasks) {
            String pkg = basePackage(task.packageName);
            Rule rule = rules.get(pkg);
            boolean primary = snapshot.slotPriority.getOrDefault(task.tgid, 1) == 0;
            Expected expected = expectedFor(task, rule, game, primary);
            if (expected.mask == 0) continue;
            checked++;
            if (task.allowedMask == expected.mask) {
                matched++;
            } else if (mismatches.size() < 4) {
                mismatches.add(task.comm + " T" + task.tid + " 实际="
                        + task.allowedList + " 应为=" + maskList(expected.mask)
                        + " (" + expected.role + ")");
            }
        }
        int mismatch = checked - matched;
        String status;
        if (checked == 0) status = "当前线程未命中可核验规则";
        else if (mismatch == 0) status = "已核验 " + matched + "/" + checked + " 条，全部一致";
        else status = "已核验 " + checked + " 条，" + mismatch + " 条不一致";
        if (snapshot.applyErrors + snapshot.threadErrors > 0)
            status += "；KO累计错误=" + (snapshot.applyErrors + snapshot.threadErrors);
        return new Result(checked, matched, mismatch, status, mismatches);
    }

    static Map<String, Rule> parseRules(String json) throws Exception {
        Map<String, Rule> out = new HashMap<>();
        if (json == null || json.trim().isEmpty()) return out;
        JSONArray root = new JSONArray(json);
        for (int i = 0; i < root.length(); i++) {
            JSONObject object = root.optJSONObject(i);
            if (object == null) continue;
            Rule rule = new Rule();
            JSONObject app = object.optJSONObject("app_cpuset");
            if (app != null) {
                rule.appMain = app.optString("main", "");
                rule.appRender = app.optString("render", "");
                rule.appOther = app.optString("other", "");
            }
            JSONObject cpuset = object.optJSONObject("cpuset");
            if (cpuset != null) {
                rule.mainThread = cpuset.optString("main_thread", "");
                rule.unityMain = cpuset.optString("unity_main", "");
                rule.heavyThread = cpuset.optString("heavy_thread", "");
                rule.heavyCores = cpuset.optString("heavy_cores", "");
                rule.other = cpuset.optString("other", "");
                JSONObject comm = cpuset.optJSONObject("comm");
                if (comm != null) {
                    Iterator<String> keys = comm.keys();
                    while (keys.hasNext()) {
                        String mask = keys.next();
                        JSONArray names = comm.optJSONArray(mask);
                        List<String> prefixes = new ArrayList<>();
                        if (names != null) for (int j = 0; j < names.length(); j++)
                            prefixes.add(names.optString(j, ""));
                        rule.comm.put(mask, prefixes);
                    }
                }
            }
            JSONArray packages = object.optJSONArray("packages");
            if (packages != null) for (int j = 0; j < packages.length(); j++) {
                String pkg = packages.optString(j, "");
                if (!pkg.isEmpty()) out.put(pkg, rule);
            }
        }
        return out;
    }

    private static Expected expectedFor(ProbeSnapshot.TaskAffinity task, Rule rule,
                                        boolean game, boolean primary) {
        String cpus = "";
        String role = "other";
        if (!game && rule != null && rule.hasAppRule()) {
            if (task.tid == task.tgid) {
                cpus = rule.appMain;
                role = "app-main";
            } else if (genericRender(task.comm)) {
                cpus = rule.appRender;
                role = "app-render";
            } else {
                cpus = rule.appOther;
                role = "app-other";
            }
        } else if (rule != null && !rule.hasAppRule()) {
            if (task.tid == task.tgid && !rule.mainThread.isEmpty()) {
                cpus = rule.mainThread;
                role = "main";
            } else if (!rule.unityMain.isEmpty() && task.comm.startsWith("UnityMain")) {
                cpus = rule.unityMain;
                role = "unity-main";
            } else {
                for (Map.Entry<String, List<String>> entry : rule.comm.entrySet()) {
                    if (startsWithAny(task.comm, entry.getValue())) {
                        cpus = entry.getKey();
                        role = "rule-comm";
                        break;
                    }
                }
                if (cpus.isEmpty() && !rule.heavyCores.isEmpty()
                        && startsWithSeparated(task.comm, rule.heavyThread)) {
                    cpus = rule.heavyCores;
                    role = "rule-heavy";
                }
                if (cpus.isEmpty()) {
                    cpus = rule.other;
                    role = "rule-other";
                }
            }
        }
        if (cpus.isEmpty() && game) {
            if (task.tid == task.tgid || genericPrimary(task.comm)) {
                cpus = "6-7";
                role = task.tid == task.tgid ? "generic-main" : "generic-primary";
            } else if (genericRender(task.comm)) {
                cpus = "6";
                role = "generic-render";
            }
        }
        int mask = ProbeSnapshot.cpuListMask(cpus);
        if (!primary) mask = companionMask(mask);
        return new Expected(mask, role);
    }

    private static boolean genericPrimary(String comm) {
        return comm.startsWith("UnityMain") || comm.startsWith("UEGameThread")
                || comm.startsWith("GameThread") || comm.startsWith("CrRendererMain");
    }

    private static boolean genericRender(String comm) {
        return comm.startsWith("RenderThread") || comm.startsWith("UnityGfx")
                || comm.startsWith("UnityMultiRende") || comm.startsWith("RHIThread")
                || comm.startsWith("1.ui") || comm.startsWith("1.raster")
                || comm.startsWith("2.ui") || comm.startsWith("rtr.raster")
                || comm.startsWith("Compositor");
    }

    private static int companionMask(int mask) {
        if (mask == 0x80) return 0x40;
        if (mask == 0xc0 || mask == 0xfe) return 0x7e;
        if (mask == 0xff) return 0x7f;
        return mask;
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes)
            if (!prefix.isEmpty() && value.startsWith(prefix)) return true;
        return false;
    }

    private static boolean startsWithSeparated(String value, String prefixes) {
        for (String prefix : prefixes.split(";")) {
            prefix = prefix.trim();
            if (!prefix.isEmpty() && value.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String basePackage(String cmdline) {
        int colon = cmdline.indexOf(':');
        return colon < 0 ? cmdline : cmdline.substring(0, colon);
    }

    private static String maskList(int mask) {
        StringBuilder out = new StringBuilder();
        int start = -1;
        for (int cpu = 0; cpu <= 8; cpu++) {
            boolean set = cpu < 8 && (mask & (1 << cpu)) != 0;
            if (set && start < 0) start = cpu;
            if (!set && start >= 0) {
                if (out.length() > 0) out.append(',');
                int end = cpu - 1;
                out.append(start);
                if (end != start) out.append('-').append(end);
                start = -1;
            }
        }
        return out.toString();
    }

    static final class Rule {
        String appMain = "";
        String appRender = "";
        String appOther = "";
        String mainThread = "";
        String unityMain = "";
        String heavyThread = "";
        String heavyCores = "";
        String other = "";
        final Map<String, List<String>> comm = new LinkedHashMap<>();

        boolean hasAppRule() {
            return !appMain.isEmpty() || !appRender.isEmpty() || !appOther.isEmpty();
        }
    }

    private record Expected(int mask, String role) {}

    public static final class Result {
        public final int checked;
        public final int matched;
        public final int mismatched;
        public final String status;
        public final List<String> examples;

        Result(int checked, int matched, int mismatched,
               String status, List<String> examples) {
            this.checked = checked;
            this.matched = matched;
            this.mismatched = mismatched;
            this.status = status;
            this.examples = examples;
        }

        public String oneLine() {
            if (mismatched > 0) return "绑核 ✗ " + status;
            if (checked > 0) return "绑核 ✓ " + matched + "/" + checked;
            return "绑核 · " + status;
        }

        public String details() {
            StringBuilder out = new StringBuilder(status);
            for (String example : examples) out.append("\n• ").append(example);
            return out.toString();
        }
    }
}
