package local.mio.op13hyperosfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class RootInstaller {
    private static final String BASE = "/data/adb/op13_hyperos_fix";
    private static final String BIN = BASE + "/bin";
    private static final String LOG = BASE + "/log";
    private static final String LTPO_MODULE = "op13_ltpo_restore";
    private static final String FOD_MODULE = "op13_fod_bridge";
    private static final String SCENE_MODULE = "op13_scene_sched";
    private static final String PREFS = "root_bootstrap";
    private static final String DEPLOYED_VERSION = "deployed_version";
    private static final long SU_TIMEOUT_SECONDS = 8L;
    private static final String TOUCH_TOOL = "/odm/bin/touchHidlTest";
    private static final String BYPASS_NODE =
            "/sys/class/oplus_chg/battery/mmi_charging_enable";
    private static final String BYPASS_NODE_FALLBACK =
            "/sys/class/power_supply/battery/mmi_charging_enable";
    private static final String BYPASS_OWNER = BASE + "/game_bypass_owned";

    private RootInstaller() {
    }

    static StartResult ensureStarted(Context context, boolean forceDeploy) {
        Context storage = context.createDeviceProtectedStorageContext();
        try {
            File stage = new File(storage.getFilesDir(), "root_stage");
            if (!stage.isDirectory() && !stage.mkdirs()) {
                return StartResult.error("无法创建 ROOT 组件暂存目录");
            }
            File f4 = extract(storage, stage, "f4_wake_bin");
            File fp = extract(storage, stage, "SysUIFPfix");
            File fod = extract(storage, stage, "op13_fod_bridge.ko");
            File ltpo = extract(storage, stage, "op13_ltpo_restore.ko");
            File ltpoLoader = extract(storage, stage, "op13_ltpo_loader.sh");
            File sceneModule = extract(storage, stage, "op13_scene_sched.ko");
            File sceneThreads = extract(storage, stage, "thread.json");

            long version = versionCode(context);
            SharedPreferences preferences = storage.getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE);
            boolean redeploy = forceDeploy
                    || preferences.getLong(DEPLOYED_VERSION, -1L) != version;
            boolean doubleTapEnabled = ModuleConfig.isEnabled(storage,
                    ModuleConfig.DOUBLE_TAP_WAKE, true);
            int touchRate = sanitizeTouchSamplingRate(ModuleConfig.getInt(
                    storage, ModuleConfig.TOUCH_SAMPLING_RATE, 120));
            boolean gameBypassEnabled = ModuleConfig.isEnabled(storage,
                    ModuleConfig.GAME_BYPASS_ENABLED, false);
            boolean sceneSchedulerEnabled = ModuleConfig.isEnabled(storage,
                    ModuleConfig.SCENE_SCHEDULER_ENABLED, false);
            int sceneDailyMode = clampSceneMode(ModuleConfig.getInt(storage,
                    ModuleConfig.SCENE_DAILY_MODE, 0));
            int sceneGameMode = clampSceneMode(ModuleConfig.getInt(storage,
                    ModuleConfig.SCENE_GAME_MODE, 2));
            boolean sceneThreadPlacement = ModuleConfig.isEnabled(storage,
                    ModuleConfig.SCENE_THREAD_PLACEMENT, true);
            boolean sceneConfigLock = ModuleConfig.isEnabled(storage,
                    ModuleConfig.SCENE_CONFIG_LOCK, true);
            boolean sceneFasEnabled = ModuleConfig.isEnabled(storage,
                    ModuleConfig.SCENE_FAS_ENABLED, true);
            boolean gameOptBlockEnabled = ModuleConfig.isEnabled(storage,
                    ModuleConfig.GAMEOPT_BLOCK_ENABLED, true);
            boolean schedulerLogging = ModuleConfig.isEnabled(storage,
                    ModuleConfig.SCHEDULER_LOGGING, false);
            boolean dailyPowerMonitor = ModuleConfig.isEnabled(storage,
                    ModuleConfig.DAILY_POWER_MONITOR, false);
            String sceneGames = ModuleConfig.getString(storage,
                    ModuleConfig.GAME_PACKAGES, "");
            String sceneAppPolicies = ModuleConfig.getString(storage,
                    ModuleConfig.GAME_APP_POLICIES, "");
            String apkPath = context.getApplicationInfo().sourceDir;
            PowerManager powerManager = storage.getSystemService(PowerManager.class);
            boolean interactive = powerManager == null || powerManager.isInteractive();
            int appliedTouchRate = touchRateForState(touchRate, interactive);

            String script = "uid=$(id -u 2>/dev/null)\n"
                    + "echo ROOT_UID=$uid\n"
                    + "[ \"$uid\" = 0 ] || exit 90\n"
                    + ifaaPolicyScript()
                    + touchSamplingScript(appliedTouchRate)
                    + "mkdir -p '" + BIN + "' '" + LOG + "'\n"
                    + staleBypassRecoveryScript(gameBypassEnabled)
                    + "touch '" + BASE + "/ltpo_loader.lock'\n"
                    + "chown 0:0 '" + BASE + "/ltpo_loader.lock'\n"
                    + "chmod 0600 '" + BASE + "/ltpo_loader.lock'\n"
                    + "f4_enabled=" + (doubleTapEnabled ? "1" : "0") + "\n"
                    + "redeploy=" + (redeploy ? "1" : "0") + "\n"
                    + "[ -x '" + BIN + "/f4_wake_bin' ] || redeploy=1\n"
                    + "[ -x '" + BIN + "/SysUIFPfix' ] || redeploy=1\n"
                    + "[ -r '" + BIN + "/op13_fod_bridge.ko' ] || redeploy=1\n"
                    + "[ -r '" + BIN + "/op13_ltpo_restore.ko' ] || redeploy=1\n"
                    + "[ -x '" + BIN + "/op13_ltpo_loader.sh' ] || redeploy=1\n"
                    + "[ -r '" + BIN + "/op13_scene_sched.ko' ] || redeploy=1\n"
                    + "[ -r '" + BASE + "/sched/thread.json' ] || redeploy=1\n"
                    + "if [ \"$redeploy\" = 1 ]; then\n"
                    + "  pkill -x f4_wake_bin 2>/dev/null || true\n"
                    + "  pkill -x SysUIFPfix 2>/dev/null || true\n"
                    + "  sleep 1\n"
                    + "  cp -f " + quote(f4.getAbsolutePath()) + " '" + BIN
                    + "/f4_wake_bin'\n"
                    + "  cp -f " + quote(fp.getAbsolutePath()) + " '" + BIN
                    + "/SysUIFPfix'\n"
                    + "  cp -f " + quote(fod.getAbsolutePath()) + " '" + BIN
                    + "/op13_fod_bridge.ko'\n"
                    + "  cp -f " + quote(ltpo.getAbsolutePath()) + " '" + BIN
                    + "/op13_ltpo_restore.ko'\n"
                    + "  cp -f " + quote(ltpoLoader.getAbsolutePath()) + " '" + BIN
                    + "/op13_ltpo_loader.sh'\n"
                    + "  mkdir -p '" + BASE + "/sched'\n"
                    + "  cp -f " + quote(sceneModule.getAbsolutePath()) + " '" + BIN
                    + "/op13_scene_sched.ko'\n"
                    + "  cp -f " + quote(sceneThreads.getAbsolutePath()) + " '" + BASE
                    + "/sched/thread.json'\n"
                    + "  chown 0:0 '" + BIN + "/f4_wake_bin' '" + BIN
                    + "/SysUIFPfix' '" + BIN + "/op13_fod_bridge.ko' '" + BIN
                    + "/op13_ltpo_restore.ko' '" + BIN
                    + "/op13_ltpo_loader.sh'\n"
                    + "  chown 0:0 '" + BIN + "/op13_scene_sched.ko' '" + BASE
                    + "/sched/thread.json'\n"
                    + "  chmod 0700 '" + BIN + "/f4_wake_bin' '" + BIN
                    + "/SysUIFPfix' '" + BIN + "/op13_ltpo_loader.sh'\n"
                    + "  chmod 0644 '" + BIN + "/op13_fod_bridge.ko' '" + BIN
                    + "/op13_ltpo_restore.ko'\n"
                    + "  chmod 0644 '" + BIN + "/op13_scene_sched.ko' '" + BASE
                    + "/sched/thread.json'\n"
                    + "  restorecon -RF '" + BASE + "' 2>/dev/null || true\n"
                    + "fi\n"
                    + sceneSchedulerScript(sceneSchedulerEnabled, sceneDailyMode,
                    sceneGameMode, sceneThreadPlacement, sceneConfigLock, sceneFasEnabled,
                    gameOptBlockEnabled, schedulerLogging, sceneGames,
                    sceneAppPolicies, apkPath)
                    + "if [ \"$f4_enabled\" = 1 ]; then\n"
                    + "  if ! pidof f4_wake_bin >/dev/null 2>&1; then\n"
                    + "    /system/bin/setsid '" + BIN + "/f4_wake_bin' >>'" + LOG
                    + "/f4_wake.log' 2>&1 </dev/null &\n"
                    + "  fi\n"
                    + "else\n"
                    + "  pkill -x f4_wake_bin 2>/dev/null || true\n"
                    + "fi\n"
                    + xiaomiSerialSyncScript()
                    + "need_kernel_modules=0\n"
                    + "case \"$(uname -r)\" in\n"
                    + "  6.6.*)\n"
                    + "    if ! grep -q '^" + LTPO_MODULE + " ' /proc/modules 2>/dev/null"
                    + " || ! grep -q '^" + FOD_MODULE + " ' /proc/modules 2>/dev/null; then\n"
                    + "      need_kernel_modules=1\n"
                    + "    fi\n"
                    + "    ;;\n"
                    + "esac\n"
                    + "need_fp_fallback=0\n"
                    + "if ! grep -q '^" + FOD_MODULE
                    + " ' /proc/modules 2>/dev/null"
                    + " && ! pidof SysUIFPfix >/dev/null 2>&1; then\n"
                    + "  need_fp_fallback=1\n"
                    + "fi\n"
                    + "if [ \"$need_kernel_modules\" = 1 ]"
                    + " || [ \"$need_fp_fallback\" = 1 ]; then\n"
                    + "  if [ \"$(getprop sys.boot_completed)\" = 1 ]; then\n"
                    + "    /system/bin/sh '" + BIN + "/op13_ltpo_loader.sh' <'" + BASE
                    + "/ltpo_loader.lock' >>'" + LOG
                    + "/op13_ltpo_loader.log' 2>&1 || true\n"
                    + "  else\n"
                    + "    /system/bin/setsid /system/bin/sh '" + BIN
                    + "/op13_ltpo_loader.sh' >>'" + LOG
                    + "/op13_ltpo_loader.log' 2>&1 <'" + BASE
                    + "/ltpo_loader.lock' &\n"
                    + "  fi\n"
                    + "fi\n"
                    + "if [ " + (dailyPowerMonitor ? "1" : "0") + " = 1 ]; then\n"
                    + "  am start-foreground-service -n local.mio.op13hyperosfix/"
                    + ".monitor.DailyPowerMonitorService"
                    + " -a local.mio.op13hyperosfix.monitor.DAILY_START"
                    + " >/dev/null 2>&1 || true\n"
                    + "fi\n"
                    + "sleep 1\n"
                    + "echo F4=$(pidof f4_wake_bin 2>/dev/null)\n"
                    + "echo F4_ENABLED=$f4_enabled\n"
                    + "echo FP=$(pidof SysUIFPfix 2>/dev/null)\n"
                    + "grep -q '^" + FOD_MODULE
                    + " ' /proc/modules 2>/dev/null && echo FOD_KO=1 || echo FOD_KO=0\n"
                    + "grep -q '^" + LTPO_MODULE
                    + " ' /proc/modules 2>/dev/null && echo KO=1 || echo KO=0\n"
                    + "echo BOOT=$(getprop sys.boot_completed)\n"
                    + "exit 0\n";

            StartResult result = parse(runSuShell(script));
            if (result.rootGranted && result.allRunning()) {
                preferences.edit().putLong(DEPLOYED_VERSION, version).apply();
            }
            return result;
        } catch (Throwable error) {
            return StartResult.error("ROOT 组件启动失败：" + error.getMessage());
        }
    }

    static StartResult status(Context context) {
        try {
            boolean doubleTapEnabled = ModuleConfig.isEnabled(context,
                    ModuleConfig.DOUBLE_TAP_WAKE, true);
            String script = "uid=$(id -u 2>/dev/null)\n"
                    + "echo ROOT_UID=$uid\n"
                    + "[ \"$uid\" = 0 ] || exit 90\n"
                    + "echo F4=$(pidof f4_wake_bin 2>/dev/null)\n"
                    + "echo F4_ENABLED=" + (doubleTapEnabled ? "1" : "0") + "\n"
                    + xiaomiSerialStatusScript()
                    + "echo FP=$(pidof SysUIFPfix 2>/dev/null)\n"
                    + "grep -q '^" + FOD_MODULE
                    + " ' /proc/modules 2>/dev/null && echo FOD_KO=1 || echo FOD_KO=0\n"
                    + "grep -q '^" + LTPO_MODULE
                    + " ' /proc/modules 2>/dev/null && echo KO=1 || echo KO=0\n"
                    + "echo BOOT=$(getprop sys.boot_completed)\n"
                    + "exit 0\n";
            return parse(runSuShell(script));
        } catch (Throwable error) {
            return StartResult.error("ROOT 状态检测失败：" + error.getMessage());
        }
    }

    static ConfigWriter.Result applyTouchSamplingRate(
            Context context, int requestedRate, boolean interactive) {
        int selected = sanitizeTouchSamplingRate(requestedRate);
        int applied = touchRateForState(selected, interactive);
        try {
            CommandResult result = runSuShell(
                    "uid=$(id -u 2>/dev/null)\n"
                            + "[ \"$uid\" = 0 ] || exit 90\n"
                            + "[ -x '" + TOUCH_TOOL + "' ] || exit 92\n"
                            + "'" + TOUCH_TOOL + "' -c wo 0 182 " + applied
                            + " >/dev/null 2>&1 || exit 93\n"
                            + "exit 0\n");
            if (result.exitCode == 0 && !result.timedOut) {
                return new ConfigWriter.Result(true, "");
            }
            String message;
            if (result.timedOut) {
                message = "触控采样率设置超时";
            } else if (result.exitCode == 90) {
                message = "未获得 ROOT 授权";
            } else if (result.exitCode == 92) {
                message = "未找到 touchHidlTest";
            } else {
                message = "触控采样率设置失败，退出码 " + result.exitCode;
            }
            return new ConfigWriter.Result(false, message);
        } catch (Throwable error) {
            return new ConfigWriter.Result(false,
                    "触控采样率设置失败：" + error.getMessage());
        }
    }

    static ConfigWriter.Result applyGameOptimizationState(
            int requestedRate, boolean requestedBypass) {
        int rate = sanitizeTouchSamplingRate(requestedRate);
        String script = "uid=$(id -u 2>/dev/null)\n"
                + "[ \"$uid\" = 0 ] || exit 90\n"
                + "mkdir -p '" + BASE + "'\n"
                + "touch_applied=0\n"
                + "if [ -x '" + TOUCH_TOOL + "' ]; then\n"
                + "  '" + TOUCH_TOOL + "' -c wo 0 182 " + rate
                + " >/dev/null 2>&1 && touch_applied=1\n"
                + "fi\n"
                + "node=''\n"
                + "[ -e '" + BYPASS_NODE + "' ] && node='" + BYPASS_NODE + "'\n"
                + "[ -z \"$node\" ] && [ -e '" + BYPASS_NODE_FALLBACK
                + "' ] && node='" + BYPASS_NODE_FALLBACK + "'\n"
                + "want=" + (requestedBypass ? "1" : "0") + "\n"
                + "if [ \"$want\" = 1 ]; then\n"
                + "  wired=$(cat /sys/class/power_supply/usb/online 2>/dev/null)\n"
                + "  [ \"$wired\" = 1 ]"
                + " || wired=$(cat /sys/class/oplus_chg/battery/wired_online 2>/dev/null)\n"
                + "  [ \"$wired\" = 1 ]"
                + " || wired=$(cat /sys/class/power_supply/ac/online 2>/dev/null)\n"
                + "  level=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)\n"
                + "  case \"$level\" in ''|*[!0-9]*) level=0;; esac\n"
                + "  if [ \"$wired\" = 1 ] && [ \"$level\" -ge 15 ]"
                + " && [ -n \"$node\" ]; then\n"
                + "    current=$(cat \"$node\" 2>/dev/null)\n"
                + "    if [ \"$current\" = 1 ]; then\n"
                + "      echo 0 > \"$node\" && [ \"$(cat \"$node\")\" = 0 ]"
                + " && touch '" + BYPASS_OWNER + "'\n"
                + "    fi\n"
                + "  fi\n"
                + "else\n"
                + "  if [ -e '" + BYPASS_OWNER + "' ]; then\n"
                + "    [ -z \"$node\" ] || echo 1 > \"$node\"\n"
                + "    rm -f '" + BYPASS_OWNER + "'\n"
                + "  fi\n"
                + "fi\n"
                + "echo TOUCH_APPLIED=$touch_applied\n"
                + "echo TOUCH_RATE=" + rate + "\n"
                + "echo WIRED=${wired:-0}\n"
                + "echo BATTERY_LEVEL=${level:-0}\n"
                + "if [ -n \"$node\" ]; then\n"
                + "  echo BYPASS_VALUE=$(cat \"$node\" 2>/dev/null)\n"
                + "else\n"
                + "  echo BYPASS_VALUE=UNAVAILABLE\n"
                + "fi\n"
                + "[ -e '" + BYPASS_OWNER + "' ]"
                + " && echo BYPASS_OWNED=1 || echo BYPASS_OWNED=0\n"
                + "exit 0\n";
        try {
            CommandResult result = runSuShell(script);
            if (result.exitCode == 0 && !result.timedOut) {
                return new ConfigWriter.Result(true, result.output.trim());
            }
            String message = result.timedOut
                    ? "游戏优化状态设置超时"
                    : result.exitCode == 90
                    ? "未获得 ROOT 授权"
                    : "游戏优化状态设置失败，退出码 " + result.exitCode;
            return new ConfigWriter.Result(false, message);
        } catch (Throwable error) {
            return new ConfigWriter.Result(false,
                    "游戏优化状态设置失败：" + error.getMessage());
        }
    }

    static int sanitizeTouchSamplingRate(int rate) {
        int[] supported = {70, 120, 180, 240, 360};
        int closest = 120;
        int distance = Integer.MAX_VALUE;
        for (int candidate : supported) {
            int currentDistance = Math.abs(candidate - rate);
            if (currentDistance < distance) {
                closest = candidate;
                distance = currentDistance;
            }
        }
        return closest;
    }

    private static int clampSceneMode(int mode) {
        return Math.max(0, Math.min(3, mode));
    }

    private static String sceneModeName(int mode) {
        return switch (clampSceneMode(mode)) {
            case 0 -> "powersave";
            case 1 -> "balance";
            case 2 -> "performance";
            default -> "fast";
        };
    }

    private static String sceneSchedulerScript(boolean enabled, int dailyMode,
            int gameMode, boolean threadPlacement, boolean configLock,
            boolean fasEnabled,
            boolean gameOptBlock, boolean schedulerLogging, String packages,
            String appPolicies, String apkPath) {
        StringBuilder games = new StringBuilder();
        StringBuilder normals = new StringBuilder();
        StringBuilder gameFas = new StringBuilder();
        Set<String> gamePackages = new LinkedHashSet<>();
        Map<String, GameAppPolicy> policies = GameAppPolicy.parse(appPolicies);
        if (packages != null) {
            for (String item : packages.split(",")) {
                String pkg = item.trim();
                if (GameAppPolicy.isPackageName(pkg) && gamePackages.add(pkg)) {
                    GameAppPolicy policy = policies.get(pkg);
                    int mode = policy != null && policy.getMode() >= 0
                            ? policy.getMode() : gameMode;
                    boolean appFas = policy != null && policy.getFas() >= 0
                            ? policy.getFas() == 1 : fasEnabled;
                    games.append(pkg).append('=')
                            .append(sceneModeName(mode)).append('\n');
                    gameFas.append(pkg).append('=')
                            .append(appFas ? "on" : "off").append('\n');
                }
            }
        }
        for (Map.Entry<String, GameAppPolicy> entry : policies.entrySet()) {
            if (!gamePackages.contains(entry.getKey()) && entry.getValue().getMode() >= 0) {
                normals.append(entry.getKey()).append('=')
                        .append(sceneModeName(entry.getValue().getMode())).append('\n');
            }
        }
        String scheduler = "LOGGING=" + (schedulerLogging ? "on" : "off") + "\n"
                + "THREAD_PLACEMENT=" + (threadPlacement ? "on" : "off") + "\n"
                + "CONFIG_LOCK=" + (configLock ? "on" : "off") + "\n"
                + "FAS=" + (fasEnabled ? "on" : "off") + "\n"
                + "DAILY_MODE=" + sceneModeName(dailyMode) + "\n";
        String scene = BASE + "/sched";
        return "old_sched='" + BASE + "/scene'\n"
                + "old_pid=$(cat \"$old_sched/bridge.pid\" 2>/dev/null)\n"
                + "case \"$old_pid\" in *[!0-9]*|'') ;; *) kill \"$old_pid\""
                + " 2>/dev/null || true ;; esac\n"
                + "rm -f \"$old_sched/bridge.pid\" \"$old_sched/scheduler.conf\""
                + " \"$old_sched/games.conf\" \"$old_sched/normal.conf\""
                + " \"$old_sched/game_fas.conf\""
                + " \"$old_sched/bypass.conf\" \"$old_sched/visible_apps.state\""
                + " \"$old_sched/status.state\" \"$old_sched/scene_threads.json\""
                + " \"$old_sched/events.log\" \"$old_sched/gameopt_mounts.state\"\n"
                + "rmdir \"$old_sched\" 2>/dev/null || true\n"
                + "mkdir -p '" + scene + "'\n"
                + "printf %s " + quote(scheduler) + " >'" + scene
                + "/scheduler.conf'\n"
                + "printf %s " + quote(games.toString()) + " >'" + scene
                + "/games.conf'\n"
                + "printf %s " + quote(normals.toString()) + " >'" + scene
                + "/normal.conf'\n"
                + "printf %s " + quote(gameFas.toString()) + " >'" + scene
                + "/game_fas.conf'\n"
                + "printf %s 'ENABLE=off\\n' >'" + scene + "/bypass.conf'\n"
                + "chown -R 0:0 '" + scene + "'\n"
                + "chmod 0700 '" + scene + "'\n"
                + "chmod 0600 '" + scene + "'/*.conf '" + scene
                + "/visible_apps.state' 2>/dev/null || true\n"
                + "chmod 0644 '" + scene + "/thread.json' 2>/dev/null || true\n"
                + "stop_scene_bridge() {\n"
                + "  p=$(cat '" + scene + "/bridge.pid' 2>/dev/null)\n"
                + "  case \"$p\" in *[!0-9]*|'') ;; *) kill \"$p\" 2>/dev/null || true ;; esac\n"
                + "  rm -f '" + scene + "/bridge.pid'\n"
                + "}\n"
                + "gameopt_shadow=/dev/op13_sched_gameopt\n"
                + "gameopt_owner='" + scene + "/gameopt_mounts.state'\n"
                + "gameopt_is_mounted() {\n"
                + "  awk -v target=\"$1\" '$5 == target { found=1 } END { exit !found }'"
                + " /proc/1/mountinfo 2>/dev/null\n"
                + "}\n"
                + "gameopt_is_ours() {\n"
                + "  awk -v target=\"$1\" '$5 == target && $4 ~"
                + " /^\\/op13_sched_gameopt\\// { found=1 } END { exit !found }'"
                + " /proc/1/mountinfo 2>/dev/null\n"
                + "}\n"
                + "hide_gameopt_node() {\n"
                + "  target=\"$1\"\n"
                + "  name=\"${target##*/}\"\n"
                + "  [ -e \"$target\" ] || return 0\n"
                + "  gameopt_is_ours \"$target\" && return 0\n"
                + "  mkdir -p \"$gameopt_shadow\"\n"
                + "  cat \"$target\" >\"$gameopt_shadow/$name\" 2>/dev/null || :"
                + " >\"$gameopt_shadow/$name\"\n"
                + "  chmod 0600 \"$gameopt_shadow/$name\"\n"
                + "  if nsenter -t 1 -m -- mount -o bind"
                + " \"$gameopt_shadow/$name\" \"$target\"; then\n"
                + "    printf '%s\\n' \"$target\" >>\"$gameopt_owner\"\n"
                + "  fi\n"
                + "}\n"
                + "hide_gameopt() {\n"
                + "  : >\"$gameopt_owner.new\"\n"
                + "  if [ -f \"$gameopt_owner\" ]; then\n"
                + "    while IFS= read -r target; do\n"
                + "      gameopt_is_ours \"$target\" && printf '%s\\n' \"$target\""
                + " >>\"$gameopt_owner.new\"\n"
                + "    done <\"$gameopt_owner\"\n"
                + "  fi\n"
                + "  mv -f \"$gameopt_owner.new\" \"$gameopt_owner\"\n"
                + "  hide_gameopt_node /proc/game_opt/cpu_max_freq\n"
                + "  hide_gameopt_node /proc/game_opt/cpu_min_freq\n"
                + "}\n"
                + "unhide_gameopt() {\n"
                + "  for target in /proc/game_opt/cpu_max_freq"
                + " /proc/game_opt/cpu_min_freq; do\n"
                + "    while gameopt_is_ours \"$target\"; do\n"
                + "      nsenter -t 1 -m -- umount \"$target\" 2>/dev/null || break\n"
                + "    done\n"
                + "  done\n"
                + "  rm -f \"$gameopt_owner\"\n"
                + "  rm -f \"$gameopt_shadow/cpu_max_freq\""
                + " \"$gameopt_shadow/cpu_min_freq\"\n"
                + "  rmdir \"$gameopt_shadow\" 2>/dev/null || true\n"
                + "}\n"
                + "gameopt_block=" + (gameOptBlock ? "1" : "0") + "\n"
                + "if [ \"$gameopt_block\" = 1 ]; then\n"
                + "  hide_gameopt\n"
                + "else\n"
                + "  unhide_gameopt\n"
                + "fi\n"
                + "scene_enabled=" + (enabled ? "1" : "0") + "\n"
                + "if [ \"$redeploy\" = 1 ]"
                + " && grep -q '^" + SCENE_MODULE + " ' /proc/modules 2>/dev/null; then\n"
                + "  old_bridge=$(cat '" + scene + "/bridge.pid' 2>/dev/null)\n"
                + "  stop_scene_bridge\n"
                + "  wait_count=0\n"
                + "  while [ -n \"$old_bridge\" ] && [ -d /proc/\"$old_bridge\" ]"
                + " && [ \"$wait_count\" -lt 20 ]; do\n"
                + "    sleep 0.1\n"
                + "    wait_count=$((wait_count + 1))\n"
                + "  done\n"
                + "  rmmod '" + SCENE_MODULE + "' 2>/dev/null || true\n"
                + "fi\n"
                + "if [ \"$scene_enabled\" != 1 ]; then\n"
                + "  stop_scene_bridge\n"
                + "  sleep 0.2\n"
                + "  grep -q '^" + SCENE_MODULE
                + " ' /proc/modules 2>/dev/null && rmmod '" + SCENE_MODULE
                + "' 2>/dev/null || true\n"
                + "  echo disabled >'" + scene + "/status.state'\n"
                + "  rm -f '" + scene + "/fas.state'\n"
                + "else\n"
                + "  case \"$(uname -r)\" in\n"
                + "    6.6.*)\n"
                + "      grep -q '^" + SCENE_MODULE
                + " ' /proc/modules 2>/dev/null || insmod '" + BIN
                + "/op13_scene_sched.ko'\n"
                + "      if [ -e /proc/op13_scene_sched ]; then\n"
                + "        p=$(cat '" + scene + "/bridge.pid' 2>/dev/null)\n"
                + "        if [ -z \"$p\" ] || [ ! -d /proc/\"$p\" ]; then\n"
                + "          CLASSPATH=" + quote(apkPath)
                + " /system/bin/setsid /system/bin/app_process /system/bin"
                + " --nice-name=op13_scene_sched local.mio.op13hyperosfix.SceneSchedulerBridge"
                + " >>'" + LOG + "/scene_scheduler.log' 2>&1 </dev/null &\n"
                + "          echo $! >'" + scene + "/bridge.pid'\n"
                + "        fi\n"
                + "        echo running >'" + scene + "/status.state'\n"
                + "      else\n"
                + "        echo ko_failed >'" + scene + "/status.state'\n"
                + "      fi\n"
                + "      ;;\n"
                + "    *) echo unsupported_kernel >'" + scene + "/status.state' ;;\n"
                + "  esac\n"
                + "fi\n";
    }

    private static int touchRateForState(int selectedRate, boolean interactive) {
        return selectedRate > 120 && !interactive ? 70 : selectedRate;
    }

    private static String touchSamplingScript(int rate) {
        return "if [ -x '" + TOUCH_TOOL + "' ]; then\n"
                + "  if '" + TOUCH_TOOL + "' -c wo 0 182 " + rate
                + " >/dev/null 2>&1; then\n"
                + "    echo TOUCH_RATE=" + rate + "\n"
                + "  else\n"
                + "    echo TOUCH_RATE=FAILED\n"
                + "  fi\n"
                + "else\n"
                + "  echo TOUCH_RATE=UNAVAILABLE\n"
                + "fi\n";
    }

    private static String staleBypassRecoveryScript(boolean bypassEnabled) {
        if (bypassEnabled) return "";
        return "if [ -e '" + BYPASS_OWNER + "' ]; then\n"
                + "  bypass_node=''\n"
                + "  [ -e '" + BYPASS_NODE + "' ] && bypass_node='"
                + BYPASS_NODE + "'\n"
                + "  [ -z \"$bypass_node\" ] && [ -e '"
                + BYPASS_NODE_FALLBACK + "' ] && bypass_node='"
                + BYPASS_NODE_FALLBACK + "'\n"
                + "  [ -z \"$bypass_node\" ] || echo 1 > \"$bypass_node\"\n"
                + "  rm -f '" + BYPASS_OWNER + "'\n"
                + "fi\n";
    }

    private static StartResult parse(CommandResult command) {
        String rootUid = value(command.output, "ROOT_UID=");
        boolean rootGranted = "0".equals(rootUid);
        String f4Pid = value(command.output, "F4=");
        String fpPid = value(command.output, "FP=");
        boolean fodBridgeLoaded = "1".equals(value(command.output, "FOD_KO="));
        boolean ltpoLoaded = "1".equals(value(command.output, "KO="));
        boolean bootCompleted = "1".equals(value(command.output, "BOOT="));
        String doubleTapValue = value(command.output, "F4_ENABLED=");
        boolean doubleTapEnabled = !"0".equals(doubleTapValue);
        String serialStatus = value(command.output, "XIAOMI_SN=");
        boolean serialAvailable = rootGranted && !serialStatus.isEmpty()
                && !"NO_SOURCE".equals(serialStatus);
        boolean serialSynchronized = "OK".equals(serialStatus);
        return new StartResult(rootGranted, doubleTapEnabled, !f4Pid.isEmpty(),
                !fpPid.isEmpty(), fodBridgeLoaded, ltpoLoaded, serialAvailable,
                serialSynchronized, bootCompleted, command.timedOut,
                command.exitCode, command.output.trim(), null);
    }

    private static String xiaomiSerialSyncScript() {
        return "serialno=$(getprop ro.serialno)\n"
                + "[ -n \"$serialno\" ] || serialno=$(getprop ro.boot.serialno)\n"
                + "if [ -z \"$serialno\" ]; then\n"
                + "  echo XIAOMI_SN=NO_SOURCE\n"
                + "else\n"
                + "  current=$(getprop ro.ril.oem.psno)\n"
                + "  if [ \"$current\" != \"$serialno\" ]; then\n"
                + "    if [ -x /data/adb/ksu/bin/resetprop ]; then\n"
                + "      /data/adb/ksu/bin/resetprop -n ro.ril.oem.psno \"$serialno\"\n"
                + "    elif command -v resetprop >/dev/null 2>&1; then\n"
                + "      resetprop -n ro.ril.oem.psno \"$serialno\"\n"
                + "    fi\n"
                + "  fi\n"
                + "  if [ \"$(getprop persist.custom.serialno)\" != \"$serialno\" ]; then\n"
                + "    setprop persist.custom.serialno \"$serialno\"\n"
                + "  fi\n"
                + "  if [ \"$(getprop ro.ril.oem.psno)\" = \"$serialno\" ]"
                + " && [ \"$(getprop persist.custom.serialno)\" = \"$serialno\" ]; then\n"
                + "    echo XIAOMI_SN=OK\n"
                + "  else\n"
                + "    echo XIAOMI_SN=FAILED\n"
                + "  fi\n"
                + "fi\n";
    }

    private static String ifaaPolicyScript() {
        return "ifaa_domain=$(ps -AZ 2>/dev/null | awk '$NF == \"org.ifaa.aidl.manager\""
                + " { split($1, a, \":\"); print a[3]; exit }')\n"
                + "if [ -z \"$ifaa_domain\" ]; then\n"
                + "  ifaa_target=$(dumpsys package org.ifaa.aidl.manager 2>/dev/null"
                + " | sed -n 's/.*targetSdk=\\([0-9][0-9]*\\).*/\\1/p'"
                + " | head -n 1)\n"
                + "  [ -n \"$ifaa_target\" ]"
                + " && ifaa_domain=\"platform_app_$ifaa_target\"\n"
                + "fi\n"
                + "IFAA_POLICY=0\n"
                + "case \"$ifaa_domain\" in\n"
                + "  platform_app|platform_app_[0-9]*|system_app|system_app_[0-9]*)\n"
                + "    ifaa_rule=\"typeattribute $ifaa_domain"
                + " hal_fingerprintpay_client\"\n"
                + "    if [ -x /data/adb/ksu/bin/ksud ]; then\n"
                + "      if /data/adb/ksu/bin/ksud sepolicy check \"$ifaa_rule\""
                + " >/dev/null 2>&1"
                + " && /data/adb/ksu/bin/ksud sepolicy patch \"$ifaa_rule\""
                + " >/dev/null 2>&1; then\n"
                + "        IFAA_POLICY=1\n"
                + "      fi\n"
                + "    elif [ -x /data/adb/magisk/magiskpolicy ]; then\n"
                + "      /data/adb/magisk/magiskpolicy --live \"$ifaa_rule\""
                + " >/dev/null 2>&1 && IFAA_POLICY=1\n"
                + "    elif command -v magiskpolicy >/dev/null 2>&1; then\n"
                + "      magiskpolicy --live \"$ifaa_rule\""
                + " >/dev/null 2>&1 && IFAA_POLICY=1\n"
                + "    fi\n"
                + "    ;;\n"
                + "esac\n"
                + "echo IFAA_POLICY=$IFAA_POLICY\n";
    }

    private static String xiaomiSerialStatusScript() {
        return "serialno=$(getprop ro.serialno)\n"
                + "[ -n \"$serialno\" ] || serialno=$(getprop ro.boot.serialno)\n"
                + "if [ -z \"$serialno\" ]; then\n"
                + "  echo XIAOMI_SN=NO_SOURCE\n"
                + "elif [ \"$(getprop ro.ril.oem.psno)\" = \"$serialno\" ]"
                + " && [ \"$(getprop persist.custom.serialno)\" = \"$serialno\" ]; then\n"
                + "  echo XIAOMI_SN=OK\n"
                + "else\n"
                + "  echo XIAOMI_SN=FAILED\n"
                + "fi\n";
    }

    private static String value(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static long versionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return info.getLongVersionCode();
    }

    private static File extract(Context context, File directory, String name) throws Exception {
        File output = new File(directory, name);
        try (InputStream input = context.getAssets().open(name);
             FileOutputStream stream = new FileOutputStream(output, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                stream.write(buffer, 0, read);
            }
            stream.flush();
        }
        return output;
    }

    private static CommandResult runSuShell(String script) throws Exception {
        Process process = new ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1L, TimeUnit.SECONDS);
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitCode = finished ? process.exitValue() : -1;
        return new CommandResult(exitCode, output.toString(), !finished);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static final class StartResult {
        final boolean rootGranted;
        final boolean doubleTapEnabled;
        final boolean f4Running;
        final boolean fpRunning;
        final boolean fodBridgeLoaded;
        final boolean ltpoLoaded;
        final boolean serialAvailable;
        final boolean serialSynchronized;
        final boolean bootCompleted;
        final boolean timedOut;
        final int exitCode;
        final String output;
        final String error;

        StartResult(boolean rootGranted, boolean doubleTapEnabled, boolean f4Running,
                    boolean fpRunning, boolean fodBridgeLoaded, boolean ltpoLoaded,
                    boolean serialAvailable, boolean serialSynchronized,
                    boolean bootCompleted,
                    boolean timedOut, int exitCode, String output, String error) {
            this.rootGranted = rootGranted;
            this.doubleTapEnabled = doubleTapEnabled;
            this.f4Running = f4Running;
            this.fpRunning = fpRunning;
            this.fodBridgeLoaded = fodBridgeLoaded;
            this.ltpoLoaded = ltpoLoaded;
            this.serialAvailable = serialAvailable;
            this.serialSynchronized = serialSynchronized;
            this.bootCompleted = bootCompleted;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        static StartResult error(String message) {
            return new StartResult(false, true, false, false, false, false,
                    false, false, false, false, -1, "", message);
        }

        boolean allRunning() {
            return (!doubleTapEnabled || f4Running) && (fodBridgeLoaded || fpRunning)
                    && (ltpoLoaded || !bootCompleted);
        }

        boolean shouldShowRootToast() {
            return !rootGranted;
        }

        String toDisplayText() {
            if (error != null) {
                return error;
            }
            if (!rootGranted) {
                return timedOut
                        ? "ROOT 授权检测超时，修复组件未启动。"
                        : "未检测到 ROOT 授权，修复组件未启动。";
            }
            StringBuilder text = new StringBuilder("ROOT：已授权\n")
                    .append("双击亮屏组件：")
                    .append(!doubleTapEnabled ? "已关闭" : (f4Running ? "运行中" : "未运行"))
                    .append('\n')
                    .append("指纹事件组件：")
                    .append(fodBridgeLoaded ? "内核桥接" : (fpRunning ? "日志回退" : "未运行"))
                    .append('\n')
                    .append("LTPO 内核模块：")
                    .append(ltpoLoaded ? "已加载"
                            : (bootCompleted ? "未加载" : "等待开机完成"))
                    .append('\n')
                    .append("小米 SN 兼容：")
                    .append(!serialAvailable ? "无可用序列号"
                            : (serialSynchronized ? "已同步" : "同步失败"));
            if (!allRunning()) {
                text.append("\n启动命令退出码：").append(exitCode);
                if (!output.isEmpty()) {
                    text.append("\n").append(output);
                }
            }
            return text.toString();
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        final boolean timedOut;

        CommandResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }
}
