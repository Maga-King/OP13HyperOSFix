package local.mio.op13hyperosfix;

import android.content.ContentResolver;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Zero-poll userspace edge adapter for HyperOS official callbacks.
 *
 * ProcessManager supplies the resumed and multi-window application.  The
 * official MiuiFreeForm callback supplies a change edge; only on that edge do
 * we ask the official manager for its complete visible freeform list.  Power
 * mode and battery state arrive through framework callbacks.  There is no
 * foreground/activity polling; a 1 s temperature sampler exists only while a
 * configured bypass application is visible, externally powered and above the
 * configured minimum battery level.
 */
public final class SceneSchedulerBridge {
    private static final String PROCESS_SERVICE = "ProcessManager";
    private static final String PROCESS_DESCRIPTOR = "miui.IProcessManager";
    private static final String FG_DESCRIPTOR = "miui.process.IForegroundInfoListener";
    private static final String WINDOW_DESCRIPTOR = "miui.process.IForegroundWindowListener";
    private static final int PM_REGISTER_FG = 10;
    private static final int PM_UNREGISTER_FG = 11;
    private static final int PM_GET_FG = 12;
    private static final int PM_REGISTER_WINDOW = 19;
    private static final int PM_UNREGISTER_WINDOW = 20;

    private static final String ATM_SERVICE = "activity_task";
    private static final String ATM_DESCRIPTOR = "android.app.IActivityTaskManager";
    private static final int ATM_GET_FREEFORM_SERVICE = 128;
    private static final String FREEFORM_DESCRIPTOR = "miui.app.IMiuiFreeFormManager";
    private static final String FREEFORM_CALLBACK_DESCRIPTOR = "miui.app.IFreeformCallback";
    private static final int FREEFORM_REGISTER = 8;
    private static final int FREEFORM_UNREGISTER = 9;

    private static final String ROOT = "/data/adb/op13_hyperos_fix/sched";
    private static final String GAMES_CONFIG = ROOT + "/games.conf";
    private static final String NORMAL_CONFIG = ROOT + "/normal.conf";
    private static final String GAME_FAS_CONFIG = ROOT + "/game_fas.conf";
    private static final String BYPASS_CONFIG = ROOT + "/bypass.conf";
    private static final String SCHEDULER_CONFIG = ROOT + "/scheduler.conf";
    private static final String VISIBLE_STATE = ROOT + "/visible_apps.state";
    private static final String FPS_STATE = ROOT + "/fps_override.uid";
    private static final String LOG_FILE = ROOT + "/events.log";
    private static final String FAS_STATE = ROOT + "/fas.state";
    private static final String BYPASS_STATE = ROOT + "/bypass_active.state";
    private static final String BYPASS_NODE =
            "/sys/class/oplus_chg/battery/mmi_charging_enable";
    private static final String BATTERY_TEMP = "/sys/class/power_supply/battery/temp";
    private static final long BYPASS_SAMPLE_MS = 1000L;
    private static final Uri GAMEBOOSTER_PROVIDER =
            Uri.parse("content://com.miui.securitycenter.remoteprovider");
    private static final String GAMEBOOSTER_CURRENT_KEY = "key_currentbooster_pkg_uid";
    private static final long LOG_MAX_BYTES = 1024L * 1024L;
    private static final int LOG_MAX_LINES = 1000;
    private static final String FPS_HELPER =
            "/data/adb/op13_hyperos_fix/sched/sf_fps_override";
    private static final String TOAST_DEX =
            "/data/adb/op13_hyperos_fix/sched/classes.dex";
    private static final String PARAM_ROOT = "/sys/module/op13_scene_sched/parameters/";
    private static final String POLICY_CONTROL = "/proc/op13_scene_sched";
    private static final String THREAD_CONTROL = "/proc/op13_scene_threads";
    private static final String SCENE_THREADS_CONFIG = ROOT + "/thread.json";
    private static final String GPU_ROOT = "/sys/class/kgsl/kgsl-3d0/";
    private static final String WALT0 =
            "/sys/devices/system/cpu/cpufreq/policy0/walt/";
    private static final String WALT6 =
            "/sys/devices/system/cpu/cpufreq/policy6/walt/";
    private static final String TARGET_LOADS0 =
            "/sys/devices/system/cpu/cpu0/cpufreq/walt/target_loads";
    private static final String TARGET_LOADS6 =
            "/sys/devices/system/cpu/cpu6/cpufreq/walt/target_loads";
    private static final String CPUSET_ROOT = "/dev/cpuset/";
    private static final String CORE0 = "/sys/devices/system/cpu/cpu0/core_ctl/";
    private static final String CORE6 = "/sys/devices/system/cpu/cpu6/core_ctl/";
    private static final long CONFIG_REPAIR_MS = 10_000L;
    private static final long FAS_ACTIVE_SAMPLE_MS = 250L;
    private static final long FAS_STABLE_SAMPLE_MS = 500L;
    private static final long FAS_IDLE_SAMPLE_MS = 1000L;
    private static final String[] FAS_FPS_NODES = {
            "/sys/class/drm/card0/card0-sde-crtc-0/measured_fps",
            "/sys/class/drm/card0/device/drm/card0/card0-sde-crtc-0/measured_fps",
            "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/"
                    + "card0-sde-crtc-0/measured_fps"
    };
    private static final int[] FAS_FREQ0 = {
            384000, 556800, 748800, 960000, 1152000, 1363200, 1555200,
            1785600, 1996800, 2227200, 2400000, 2745600, 2918400,
            3072000, 3321600, 3532800
    };
    private static final int[] FAS_FREQ6 = {
            1017600, 1209600, 1401600, 1689600, 1958400, 2246400,
            2438400, 2649600, 2841600, 3072000, 3283200, 3513600,
            3801600, 4089600, 4204800, 4320000
    };
    private static final int[] FAS_MAX0 = { 7, 8, 10, 10 };
    private static final int[] FAS_MAX6 = { 6, 7, 9, 10 };
    private static final int[] FAS_FLOOR0 = { 5, 6, 7, 8 };
    private static final int[] FAS_FLOOR6 = { 4, 5, 6, 7 };
    private static final int MAX_SCENE_PROCESSES = 4;
    /* task_rename notifications arrive in short startup bursts.  One
       trailing audit closes a lost-uevent race without introducing an idle
       scanner: every edge moves the same callback, and no callback remains
       scheduled once the burst has gone quiet. */
    private static final long SCENE_THREAD_AUDIT_DELAY_MS = 750L;

    private static final int POWERSAVE = 0;
    private static final int BALANCE = 1;
    private static final int PERFORMANCE = 2;
    private static final int FAST = 3;

    private static final long[] GPU_MIN = {
            160000000L, 160000000L, 342000000L, 443000000L
    };
    private static final long[] GPU_MAX = {
            525000000L, 832000000L, 1050000000L, 1100000000L
    };

    private final IBinder processManager;
    private final IBinder freeformManager;
    private final HandlerThread observerThread;
    private final Handler handler;
    private final ForegroundListener foregroundListener = new ForegroundListener();
    private final WindowListener windowListener = new WindowListener();
    private final FreeformListener freeformListener = new FreeformListener();
    private final FileObserver settingsObserver;
    private final FileObserver configObserver;
    private final FileObserver packageObserver;
    private final ThermalObserver thermalObserver = new ThermalObserver();
    private final SceneThreadObserver sceneThreadObserver = new SceneThreadObserver();
    private final BatteryPowerObserver batteryPowerObserver = new BatteryPowerObserver();
    private final ChargerPowerObserver usbPowerObserver = new ChargerPowerObserver("usb");
    private final ChargerPowerObserver wirelessPowerObserver =
            new ChargerPowerObserver("wireless");
    private Context systemContext;
    private ContentObserver powerObserver;
    private BroadcastReceiver screenReceiver;
    private Process bypassToastProcess;
    private boolean precisePowerObserver;
    private ForegroundState foreground;
    private ForegroundState focusedWindow;
    private String lastDecision = "";
    private String pendingReason = "event";
    private int thermalState;
    private int effectiveMode = BALANCE;
    private int effectiveProfile;
    private int dailyMode = BALANCE;
    private boolean extremeActive;
    private boolean performanceActive;
    private boolean gameBoosterActive;
    private boolean sidebarPerformanceActive;
    private String boostedPackage = "";
    private int lastSaverSetting = -1;
    private int lastExtremeSetting = -1;
    private int lastPerformanceSetting = -1;
    private int lastGameBoosterSetting = -1;
    private int lastSidebarPerformanceSetting = -1;
    private String lastBoostedPackage = "";
    private String sidebarProbeGame = "";
    private String sidebarProbeSignature = "";
    private int activeGameUid = -1;
    private int fpsOverrideUid = -1;
    private final long[] gpuMin = GPU_MIN.clone();
    private final long[] gpuMax = GPU_MAX.clone();
    private long gpuExtremeMax = 342000000L;
    private final Map<String, ApplicationInfo> appInfoCache = new HashMap<>();
    private Set<String> installedPackages = new LinkedHashSet<>();
    private Set<String> currentVisiblePackages = new LinkedHashSet<>();
    private Set<String> bypassPackages = new LinkedHashSet<>();
    private final Map<String, SceneThreadRule> sceneThreadRules = new HashMap<>();
    private final Map<Integer, ActiveSceneProcess> activeSceneProcesses = new HashMap<>();
    private final Set<Long> managedThreadKeys = new LinkedHashSet<>();
    private String lastSceneThreadSignature = "";
    private boolean threadPlacementEnabled = true;
    private final Map<String, String> auxiliaryOriginal = new HashMap<>();
    private final Map<String, String> auxiliaryApplied = new HashMap<>();
    private String lastAuxiliaryKey = "";
    private boolean configLockEnabled = true;
    private boolean fasEnabled = true;
    private boolean fasSessionAllowed;
    private File fasFpsNode;
    private String fasPackage = "";
    private int fasMode = -1;
    private int fasIndex0 = -1;
    private int fasIndex6 = -1;
    private int fasApplied0 = -1;
    private int fasApplied6 = -1;
    private int fasWarmupSamples;
    private int fasStableSamples;
    private int fasDropSamples;
    private int fasIdleSamples;
    private int fasPromotionSamples;
    private int fasTuneTurn;
    private double fasMaximumObserved;
    private double fasLearnedTarget;
    private double fasLastFps;
    private double fasAverageFps;
    private double fasFpsVariance;
    private long fasNextSampleMs = FAS_STABLE_SAMPLE_MS;
    private long fasSamples;
    private boolean fasKernelAvailable = true;
    private boolean bypassEnabled = true;
    private int bypassEnterTempDecic = 450;
    private int bypassHysteresisDecic = 50;
    private int bypassMinBattery = 20;
    private int batteryLevel = -1;
    private boolean usbOnline;
    private boolean wirelessOnline;
    private boolean chargerPresent;
    private boolean screenOn = true;
    private boolean bypassTargetVisible;
    private String bypassTargetPackage = "";
    private boolean bypassOwned;
    private int lastBypassTempDecic = -1;
    private int lastBypassNode = -1;
    private boolean lowBatteryNodeVerified;
    private static volatile boolean loggingEnabled;
    private static boolean atomicPolicyAvailable = true;
    private static int logLineCount = -1;

    private static final class ForegroundState {
        String mainPackage = "";
        int mainUid = -1;
        int mainPid = -1;
        String multiPackage = "";
        int multiUid = -1;

        static ForegroundState readDirect(Parcel parcel) {
            ForegroundState state = new ForegroundState();
            state.mainPackage = clean(parcel.readString());
            state.mainUid = parcel.readInt();
            state.mainPid = parcel.readInt();
            parcel.readInt();                 // main display
            parcel.readString();              // last package
            parcel.readInt();                 // last uid
            parcel.readInt();                 // last pid
            parcel.readInt();                 // last display
            state.multiPackage = clean(parcel.readString());
            state.multiUid = parcel.readInt();
            parcel.readInt();                 // flags
            return state;
        }

        static ForegroundState readTyped(Parcel parcel) {
            return parcel.readInt() == 0 ? null : readDirect(parcel);
        }
    }

    private static final class Candidate {
        final String pkg;
        final String role;
        final int rolePriority;
        final int mode;
        final int uid;

        Candidate(String pkg, String role, int rolePriority, int mode, int uid) {
            this.pkg = pkg;
            this.role = role;
            this.rolePriority = rolePriority;
            this.mode = mode;
            this.uid = uid;
        }
    }

    private static final class SceneThreadRule {
        String appMain = "";
        String appRender = "";
        String appOther = "";
        String mainThread = "";
        String unityMain = "";
        String heavyThread = "";
        String heavyCores = "";
        String other = "";
        final Map<String, List<String>> comm = new HashMap<>();
        final Set<String> rr = new LinkedHashSet<>();
        final Set<String> ni = new LinkedHashSet<>();

        boolean hasAppRule() {
            return !appMain.isEmpty() || !appRender.isEmpty() || !appOther.isEmpty();
        }
    }

    private static final class ThreadPlacement {
        final int mask;
        final String role;

        ThreadPlacement(int mask, String role) {
            this.mask = mask;
            this.role = role;
        }
    }

    private static final class ActiveSceneProcess {
        final String pkg;
        final int tgid;
        final boolean primary;
        final boolean game;
        final SceneThreadRule rule;

        ActiveSceneProcess(String pkg, int tgid, boolean primary,
                           boolean game, SceneThreadRule rule) {
            this.pkg = pkg;
            this.tgid = tgid;
            this.primary = primary;
            this.game = game;
            this.rule = rule;
        }
    }

    private abstract class StateListener extends Binder implements IInterface {
        StateListener(String descriptor) {
            attachInterface(this, descriptor);
        }

        @Override public IBinder asBinder() { return this; }
    }

    private final class ForegroundListener extends StateListener {
        ForegroundListener() { super(FG_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(FG_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(FG_DESCRIPTOR);
                ForegroundState value = ForegroundState.readTyped(data);
                if (value != null) {
                    foreground = value;
                    queueRefresh("foreground");
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private final class WindowListener extends StateListener {
        WindowListener() { super(WINDOW_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(WINDOW_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(WINDOW_DESCRIPTOR);
                ForegroundState value = ForegroundState.readTyped(data);
                if (value != null) {
                    focusedWindow = value;
                    queueRefresh("window");
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private final class FreeformListener extends StateListener {
        FreeformListener() { super(FREEFORM_CALLBACK_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(FREEFORM_CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(FREEFORM_CALLBACK_DESCRIPTOR);
                // The edge is sufficient.  Query the official complete list,
                // avoiding a private Parcelable dependency in this tiny DEX.
                queueRefresh("freeform");
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private final class BatteryPowerObserver extends UEventObserver {
        @Override public void onUEvent(UEvent event) {
            final String capacity = event.get("POWER_SUPPLY_CAPACITY");
            if (capacity == null) return;
            handler.post(() -> {
                int level = parseInt(capacity, -1);
                if (level < 0 || level > 100 || level == batteryLevel) return;
                batteryLevel = level;
                lowBatteryNodeVerified = false;
                updateBypassTargetFromCurrentState();
                reconcileBypass("battery");
            });
        }
    }

    private final class ChargerPowerObserver extends UEventObserver {
        private final String supplyName;

        ChargerPowerObserver(String supplyName) { this.supplyName = supplyName; }

        @Override public void onUEvent(UEvent event) {
            final String onlineValue = event.get("POWER_SUPPLY_ONLINE");
            if (onlineValue == null) return;
            handler.post(() -> {
                boolean online = parseInt(onlineValue, 0) != 0;
                if ("usb".equals(supplyName)) {
                    if (usbOnline == online) return;
                    usbOnline = online;
                } else {
                    if (wirelessOnline == online) return;
                    wirelessOnline = online;
                }
                boolean present = usbOnline || wirelessOnline;
                if (present == chargerPresent) return;
                chargerPresent = present;
                lowBatteryNodeVerified = false;
                reconcileBypass("charger");
            });
        }
    }

    private final class ThermalObserver extends UEventObserver {
        @Override public void onUEvent(UEvent event) {
            String value = event.get("FPS_CAP");
            thermalState = Math.max(0, Math.min(3,
                    parseInt(event.get("THERMAL_STATE"), thermalState)));
            effectiveMode = clampMode(parseInt(event.get("EFFECTIVE_MODE"), effectiveMode));
            effectiveProfile = Math.max(0, Math.min(1,
                    parseInt(event.get("EFFECTIVE_PROFILE"), effectiveProfile)));
            extremeActive = "1".equals(event.get("EXTREME_STATE"));
            handler.post(() -> {
                try {
                    applyGpuAndFps(effectiveMode);
                    applySceneAuxiliary(effectiveMode, effectiveProfile);
                    logInfo("内核策略 → " + modeName(effectiveMode) + "模式"
                            + " | 配置族：" + (effectiveProfile == 1 ? "游戏" : "日常")
                            + " | 温控等级：" + thermalState
                            + " | 帧率上限：" + (value == null ? "0" : value));
                } catch (Exception error) {
                    logError("GPU/FPS 应用失败", error);
                }
            });
        }
    }

    private final class SceneThreadObserver extends UEventObserver {
        @Override public void onUEvent(UEvent event) {
            final int tgid = parseInt(event.get("TGID"), -1);
            final int tid = parseInt(event.get("TID"), -1);
            final long startBoottime = parseLong(event.get("START_BOOTTIME"), 0L);
            final String comm = event.get("COMM");
            if (tgid <= 0 || tid <= 0 || comm == null) return;
            handler.post(() -> {
                applySceneThreadEvent(tgid, tid, startBoottime, comm);
                queueSceneThreadAudit();
            });
        }
    }

    private SceneSchedulerBridge(IBinder pm, IBinder fm) {
        this.processManager = pm;
        this.freeformManager = fm;
        this.observerThread = new HandlerThread("MambaSchedEvents");
        this.observerThread.start();
        this.handler = new Handler(observerThread.getLooper());
        this.settingsObserver = new FileObserver("/data/system/users/0",
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
            @Override public void onEvent(int event, String path) {
                if (path != null && (path.contains("settings_system")
                        || path.contains("settings_global")
                        || path.contains("settings_secure"))) {
                    queuePowerRefresh();
                }
            }
        };
        this.configObserver = new FileObserver(ROOT,
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
            @Override public void onEvent(int event, String path) {
                if (path == null) return;
                if (path.equals("scheduler.conf")) {
                    handler.post(() -> {
                        loadSchedulerConfig(true);
                        lastDecision = "";
                        refresh("config");
                        /* The KO commits the policy synchronously, while its
                           uevent is delivered asynchronously.  Read back once
                           here so a config save cannot leave GPU one mode
                           behind until the uevent reaches userspace. */
                        readKernelState();
                        try {
                            applyGpuAndFps(effectiveMode);
                            applySceneAuxiliary(effectiveMode, effectiveProfile);
                        }
                        catch (Exception error) {
                            logError("配置应用失败", error);
                        }
                    });
                } else if (path.equals("bypass.conf")) {
                    handler.post(() -> {
                        loadBypassConfig();
                        updateBypassTargetFromCurrentState();
                        reconcileBypass("config");
                    });
                } else if (path.equals("games.conf") || path.equals("normal.conf")
                        || path.equals("game_fas.conf")) {
                    queueRefresh("config");
                } else if (path.equals("thread.json")) {
                    handler.post(() -> {
                        loadSceneThreadRules();
                        lastSceneThreadSignature = "";
                        queueRefresh("thread-config");
                    });
                }
            }
        };
        this.packageObserver = new FileObserver("/data/system",
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
            @Override public void onEvent(int event, String path) {
                if ("packages.list".equals(path)) {
                    handler.removeCallbacks(packageRefreshRunnable);
                    handler.postDelayed(packageRefreshRunnable, 300);
                }
            }
        };
        loadSchedulerConfig(true);
        loadBypassConfig();
        loadSceneThreadRules();
        bypassOwned = new File(BYPASS_STATE).isFile();
        fpsOverrideUid = (int) readLong(FPS_STATE, -1);
    }

    private static String clean(String value) {
        if (value == null || value.indexOf('|') >= 0 || value.indexOf(':') >= 0) return "";
        return value.trim();
    }

    private static String readSmallText(File file, int limit) throws Exception {
        byte[] data = new byte[Math.max(1, limit)];
        int total = 0;
        try (FileInputStream stream = new FileInputStream(file)) {
            while (total < data.length) {
                int read = stream.read(data, total, data.length - total);
                if (read <= 0) break;
                total += read;
            }
        }
        return new String(data, 0, total, StandardCharsets.UTF_8);
    }

    private static void addJsonStrings(JSONArray array, Set<String> target) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) target.add(value);
        }
    }

    private void loadSceneThreadRules() {
        Map<String, SceneThreadRule> parsed = new HashMap<>();
        File file = new File(SCENE_THREADS_CONFIG);
        if (!file.isFile()) {
            sceneThreadRules.clear();
            logInfo("Scene 线程规则文件不存在，保留通用渲染线程策略");
            return;
        }
        try {
            JSONArray entries = new JSONArray(readSmallText(file, 256 * 1024));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                SceneThreadRule rule = new SceneThreadRule();
                JSONObject app = entry.optJSONObject("app_cpuset");
                if (app != null) {
                    rule.appMain = app.optString("main", "").trim();
                    rule.appRender = app.optString("render", "").trim();
                    rule.appOther = app.optString("other", "").trim();
                }
                JSONObject cpuset = entry.optJSONObject("cpuset");
                if (cpuset != null) {
                    rule.mainThread = cpuset.optString("main_thread", "").trim();
                    rule.unityMain = cpuset.optString("unity_main", "").trim();
                    rule.heavyThread = cpuset.optString("heavy_thread", "").trim();
                    rule.heavyCores = cpuset.optString("heavy_cores", "").trim();
                    rule.other = cpuset.optString("other", "").trim();
                    addJsonStrings(cpuset.optJSONArray("rr"), rule.rr);
                    addJsonStrings(cpuset.optJSONArray("ni"), rule.ni);
                    JSONObject comm = cpuset.optJSONObject("comm");
                    if (comm != null) {
                        Iterator<String> keys = comm.keys();
                        while (keys.hasNext()) {
                            String cpus = keys.next();
                            JSONArray patterns = comm.optJSONArray(cpus);
                            if (patterns == null) continue;
                            List<String> values = new ArrayList<>();
                            for (int j = 0; j < patterns.length(); j++) {
                                String pattern = patterns.optString(j, "").trim();
                                if (!pattern.isEmpty()) values.add(pattern);
                            }
                            if (!values.isEmpty()) rule.comm.put(cpus, values);
                        }
                    }
                }
                JSONArray packages = entry.optJSONArray("packages");
                if (packages == null) continue;
                for (int j = 0; j < packages.length(); j++) {
                    String pkg = packages.optString(j, "").trim();
                    if (!pkg.isEmpty()) parsed.put(pkg, rule);
                }
            }
            sceneThreadRules.clear();
            sceneThreadRules.putAll(parsed);
            logInfo("已载入 Scene 线程规则：" + parsed.size() + " 个包名");
        } catch (Exception error) {
            logError("解析 Scene 线程规则失败，保留上一次有效规则", error);
        }
    }

    private static boolean matchesPrefixList(String comm, Iterable<String> patterns) {
        if (comm == null || patterns == null) return false;
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && comm.startsWith(pattern)) return true;
        }
        return false;
    }

    private static boolean matchesSeparatedPrefixes(String comm, String patterns) {
        if (comm == null || patterns == null || patterns.isEmpty()) return false;
        String[] values = patterns.split(";");
        for (String value : values) {
            value = value.trim();
            if (!value.isEmpty() && comm.startsWith(value)) return true;
        }
        return false;
    }

    private static int cpuListMask(String cpus) {
        if (cpus == null || cpus.trim().isEmpty()) return 0;
        int mask = 0;
        try {
            for (String part : cpus.trim().split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int dash = part.indexOf('-');
                int first = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
                int last = Integer.parseInt(dash < 0 ? part : part.substring(dash + 1));
                if (first < 0 || last < first || last > 7) return 0;
                for (int cpu = first; cpu <= last; cpu++) mask |= 1 << cpu;
            }
        } catch (NumberFormatException error) {
            return 0;
        }
        return mask & 0xff;
    }

    private static int companionSafeMask(int mask) {
        if (mask == 0x80) return 0x40;          // CPU7 -> CPU6
        if (mask == 0xc0) return 0x7e;          // 6-7 -> 1-6
        if (mask == 0xfe) return 0x7e;          // 1-7 -> 1-6
        if (mask == 0xff) return 0x7f;          // 0-7 -> 0-6
        return mask;
    }

    private static boolean genericPrimaryThread(String comm) {
        return comm.startsWith("UnityMain")
                || comm.startsWith("UEGameThread")
                || comm.startsWith("GameThread")
                || comm.startsWith("CrRendererMain");
    }

    private static boolean genericRenderThread(String comm) {
        return comm.startsWith("RenderThread")
                || comm.startsWith("UnityGfx")
                || comm.startsWith("UnityMultiRende")
                || comm.startsWith("RHIThread")
                || comm.startsWith("1.ui")
                || comm.startsWith("1.raster")
                || comm.startsWith("2.ui")
                || comm.startsWith("rtr.raster")
                || comm.startsWith("Compositor");
    }

    private static int countLogLines(File file) {
        int count = 0;
        if (!file.isFile()) return count;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            while (reader.readLine() != null && count < LOG_MAX_LINES) count++;
        } catch (Exception ignored) {
        }
        return count;
    }

    private static synchronized void logLine(String text) {
        /* LOGGING=off is a true fast path: no stat, no line counting, no
           timestamp allocation, no file open and no stdout/stderr write. */
        if (!loggingEnabled) return;
        File file = new File(LOG_FILE);
        if (logLineCount < 0) logLineCount = countLogLines(file);
        boolean rotate = file.length() >= LOG_MAX_BYTES || logLineCount >= LOG_MAX_LINES;
        if (rotate) {
            try (FileOutputStream ignored = new FileOutputStream(file, false)) {
                logLineCount = 0;
            } catch (Exception ignored) {
            }
        }
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date());
        try (FileWriter writer = new FileWriter(file, true)) {
            if (rotate) {
                writer.write(timestamp + " 日志达到 1 MiB/1000 行上限，已自动清空\n");
                logLineCount++;
            }
            writer.write(timestamp + " " + text + "\n");
            logLineCount++;
        } catch (Exception ignored) {
        }
    }

    private static void logInfo(String text) {
        logLine(text);
    }

    private static void logError(String text, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause)
            cause = cause.getCause();
        logLine("错误：" + text + " | " + cause);
    }

    private static String modeName(int mode) {
        switch (clampMode(mode)) {
            case POWERSAVE: return "省电";
            case BALANCE: return "均衡";
            case PERFORMANCE: return "性能";
            case FAST: return "极速";
            default: return "未知";
        }
    }

    private static String reasonName(String reason) {
        switch (reason) {
            case "foreground": return "主前台变化";
            case "window": return "焦点窗口变化";
            case "freeform": return "小窗变化";
            case "setting": return "系统电源模式变化";
            case "gamebooster": return "游戏侧边栏变化";
            case "gamebar-recheck": return "游戏进入前台后侧边栏复核";
            case "config": return "应用策略配置变化";
            case "package": return "应用安装/更新";
            case "initial": return "开机初始化";
            default: return reason;
        }
    }

    private static String roleName(String role) {
        switch (role) {
            case "main": return "主应用";
            case "multi": return "多前台";
            case "focus": return "焦点窗口";
            case "freeform": return "小窗";
            case "sidebar": return "游戏侧边栏";
            default: return "日常";
        }
    }

    private static IBinder getService(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method method = sm.getDeclaredMethod("getService", String.class);
        method.setAccessible(true);
        return (IBinder) method.invoke(null, name);
    }

    private static IBinder getFreeformService() throws Exception {
        IBinder atm = getService(ATM_SERVICE);
        if (atm == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ATM_DESCRIPTOR);
            if (!atm.transact(ATM_GET_FREEFORM_SERVICE, data, reply, 0)) return null;
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void writeText(String path, String value) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(path)) {
            stream.write(value.getBytes(StandardCharsets.US_ASCII));
            stream.flush();
        }
    }

    private static void writeUtf8Text(String path, String value) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(path)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }
    }

    /**
     * Scene and some vendor tuners deliberately leave KGSL controls at 0444.
     * Do not chmod on the normal path: retry exactly once only after a denied
     * write, and only for the two devfreq controls owned by this bridge.  Once
     * repaired, later policy changes are ordinary event-driven writes with no
     * permission checks or polling.
     */
    private static void writeGpuText(String path, String value) throws Exception {
        try {
            writeText(path, value);
            return;
        } catch (Exception denied) {
            if (!path.equals(GPU_ROOT + "devfreq/min_freq")
                    && !path.equals(GPU_ROOT + "devfreq/max_freq"))
                throw denied;
            File node = new File(path);
            if (!node.setWritable(true, true)) throw denied;
        }
        writeText(path, value);
    }

    /** Commit one complete userspace vote so the kernel never observes a
     *  request paired with stale profile/saver fields. */
    private static void writePolicy(int request, boolean gameProfile,
                                    int saver, int extreme, boolean active,
                                    String owner)
            throws Exception {
        String safeOwner = owner == null ? "" : owner.trim()
                .replace(' ', '_').replace('\t', '_')
                .replace('\r', '_').replace('\n', '_');
        if (safeOwner.length() > 180) safeOwner = safeOwner.substring(0, 180);
        if (safeOwner.isEmpty()) safeOwner = "daily";
        String atomic = "request=" + request
                + " profile=" + (gameProfile ? 1 : 0)
                + " saver=" + (saver > 0 ? 1 : 0)
                + " extreme=" + (extreme > 0 ? 1 : 0)
                + " active=" + (active ? 1 : 0)
                + " owner=" + safeOwner;
        Exception atomicFailure = null;
        if (atomicPolicyAvailable) {
            try {
                writeText(POLICY_CONTROL, atomic);
                return;
            } catch (Exception atomicError) {
                atomicPolicyAvailable = false;
                atomicFailure = atomicError;
            }
        }
        {
            /* Compatibility fallback for a restrictive procfs label or an
               older KO.  Apply a temporary saver cap first, so sequential
               parameters can only cause a short safe downshift. */
            writeText(PARAM_ROOT + "saver_state", "1");
            try {
                writeText(PARAM_ROOT + "requested_profile",
                        gameProfile ? "1" : "0");
            } catch (Exception ignored) {
            }
            writeText(PARAM_ROOT + "requested_mode", Integer.toString(request));
            writeText(PARAM_ROOT + "extreme_state", extreme > 0 ? "1" : "0");
            try {
                writeText(PARAM_ROOT + "screen_active", active ? "1" : "0");
            } catch (Exception ignored) {
            }
            writeText(PARAM_ROOT + "owner", safeOwner);
            writeText(PARAM_ROOT + "saver_state", saver > 0 ? "1" : "0");
            if (atomicFailure != null)
                logError("原子策略接口不可写，已使用安全降级路径", atomicFailure);
        }
    }

    private static long readLong(String path, long fallback) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(reader.readLine().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String readTextValue(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void writeAuxiliaryValue(String path, String value) {
        File node = new File(path);
        if (!node.isFile()) return;
        String current = readTextValue(path);
        if (!auxiliaryOriginal.containsKey(path))
            auxiliaryOriginal.put(path, current);
        if (value.equals(current)) {
            auxiliaryApplied.put(path, value);
            return;
        }
        try {
            writeText(path, value);
            String readback = readTextValue(path);
            if (!value.equals(readback))
                throw new IllegalStateException("readback=" + readback);
            auxiliaryApplied.put(path, value);
        } catch (Exception error) {
            logError("Scene WALT 辅助节点写入失败：" + path, error);
        }
    }

    private void restoreAuxiliaryValue(String path) {
        String original = auxiliaryOriginal.remove(path);
        String applied = auxiliaryApplied.remove(path);
        if (original == null || applied == null
                || !applied.equals(readTextValue(path))) return;
        try { writeText(path, original); }
        catch (Exception error) {
            logError("Scene 辅助节点恢复失败：" + path, error);
        }
    }

    private void restoreCoreControl() {
        String[] nodes = {
                CORE0 + "enable", CORE0 + "max_cpus", CORE0 + "min_cpus",
                CORE6 + "enable", CORE6 + "max_cpus", CORE6 + "min_cpus"
        };
        for (String node : nodes) restoreAuxiliaryValue(node);
    }

    private static final class FasSample {
        final double fps;
        final int frames;

        FasSample(double fps, int frames) {
            this.fps = fps;
            this.frames = frames;
        }
    }

    private File resolveFasFpsNode() {
        if (fasFpsNode != null && fasFpsNode.isFile()) return fasFpsNode;
        for (String path : FAS_FPS_NODES) {
            File candidate = new File(path);
            if (candidate.isFile() && candidate.canRead()) {
                fasFpsNode = candidate;
                return candidate;
            }
        }
        return null;
    }

    private static double parseFasNumber(String value, String key, double fallback) {
        int start = value.indexOf(key);
        if (start < 0) return fallback;
        start += key.length();
        while (start < value.length() && value.charAt(start) == ' ') start++;
        int end = start;
        while (end < value.length()) {
            char current = value.charAt(end);
            if ((current >= '0' && current <= '9') || current == '.' || current == '-') {
                end++;
            } else break;
        }
        if (end <= start) return fallback;
        try { return Double.parseDouble(value.substring(start, end)); }
        catch (Exception ignored) { return fallback; }
    }

    private FasSample readFasSample() {
        File node = resolveFasFpsNode();
        if (node == null) return null;
        String value = readTextValue(node.getAbsolutePath());
        double fps = parseFasNumber(value, "fps:", -1);
        int frames = (int) parseFasNumber(value, "frame_count:", -1);
        if (!Double.isFinite(fps) || fps < 0 || fps > 300 || frames < 0) return null;
        return new FasSample(fps, frames);
    }

    private static double fasTargetFor(double fps) {
        if (fps >= 152) return 165;
        if (fps >= 132) return 144;
        if (fps >= 104) return 120;
        if (fps >= 74) return 90;
        if (fps >= 51) return 60;
        if (fps >= 37) return 45;
        return 30;
    }

    private double temperatureAdjustedFasTarget() {
        double target = fasLearnedTarget;
        if (thermalState >= 2) return Math.min(60, target);
        if (thermalState == 1) {
            if (target >= 144) return 120;
            if (target >= 120) return 90;
        }
        return target;
    }

    private void writeFasState(String reason) {
        try {
            writeUtf8Text(FAS_STATE,
                    "enabled=" + (fasSessionAllowed ? 1 : 0) + "\n"
                    + "default_enabled=" + (fasEnabled ? 1 : 0) + "\n"
                    + "active=" + (fasPackage.isEmpty() ? 0 : 1) + "\n"
                    + "supported=" + (fasKernelAvailable && resolveFasFpsNode() != null ? 1 : 0) + "\n"
                    + "package=" + fasPackage + "\n"
                    + "mode=" + fasMode + "\n"
                    + "fps=" + String.format(Locale.ROOT, "%.1f", fasLastFps) + "\n"
                    + "average_fps=" + String.format(Locale.ROOT, "%.1f", fasAverageFps) + "\n"
                    + "jitter=" + String.format(Locale.ROOT, "%.1f",
                            Math.sqrt(Math.max(0, fasFpsVariance))) + "\n"
                    + "target=" + String.format(Locale.ROOT, "%.0f", fasLearnedTarget) + "\n"
                    + "effective_target=" + String.format(Locale.ROOT, "%.0f",
                            temperatureAdjustedFasTarget()) + "\n"
                    + "sample_ms=" + fasNextSampleMs + "\n"
                    + "cpu0_max=" + (fasIndex0 >= 0 ? FAS_FREQ0[fasIndex0] : 0) + "\n"
                    + "cpu6_max=" + (fasIndex6 >= 0 ? FAS_FREQ6[fasIndex6] : 0) + "\n"
                    + "samples=" + fasSamples + "\n"
                    + "reason=" + reason + "\n");
        } catch (Exception ignored) { }
    }

    private boolean applyFasVote(String reason) {
        if (!fasKernelAvailable || fasIndex0 < 0 || fasIndex6 < 0) return false;
        int cpu0 = FAS_FREQ0[fasIndex0];
        int cpu6 = FAS_FREQ6[fasIndex6];
        if (cpu0 == fasApplied0 && cpu6 == fasApplied6) return true;
        try {
            writeText(POLICY_CONTROL, "fas active=1 cpu0=" + cpu0 + " cpu6=" + cpu6);
            fasApplied0 = cpu0;
            fasApplied6 = cpu6;
            writeFasState(reason);
            logInfo("FAS " + fasPackage + " → CPU0 " + cpu0
                    + " / CPU6 " + cpu6 + " | " + reason);
            return true;
        } catch (Exception error) {
            fasKernelAvailable = false;
            handler.removeCallbacks(fasSampleRunnable);
            writeFasState("内核接口不可用");
            logError("FAS 内核上限投票失败", error);
            return false;
        }
    }

    private boolean lowerFasCap() {
        int floor0 = FAS_FLOOR0[fasMode];
        int floor6 = FAS_FLOOR6[fasMode];
        boolean prefer0 = (fasTuneTurn++ & 1) == 0;
        if (prefer0 && fasIndex0 > floor0) fasIndex0--;
        else if (fasIndex6 > floor6) fasIndex6--;
        else if (fasIndex0 > floor0) fasIndex0--;
        else return false;
        return true;
    }

    private boolean raiseFasCap(boolean severe) {
        int maximum0 = FAS_MAX0[fasMode];
        int maximum6 = FAS_MAX6[fasMode];
        if (severe) {
            boolean changed = fasIndex0 != maximum0 || fasIndex6 != maximum6;
            fasIndex0 = maximum0;
            fasIndex6 = maximum6;
            return changed;
        }
        if (fasIndex6 < maximum6) fasIndex6++;
        else if (fasIndex0 < maximum0) fasIndex0++;
        else return false;
        return true;
    }

    private final Runnable fasSampleRunnable = new Runnable() {
        @Override public void run() {
            if (!fasSessionAllowed || !screenOn || fasPackage.isEmpty()
                    || !fasKernelAvailable) return;
            FasSample sample = readFasSample();
            if (sample == null) {
                fasNextSampleMs = FAS_IDLE_SAMPLE_MS;
                handler.postDelayed(this, fasNextSampleMs);
                return;
            }
            fasSamples++;
            fasLastFps = sample.fps;
            if (fasAverageFps <= 0) {
                fasAverageFps = sample.fps;
                fasFpsVariance = 0;
            } else {
                double delta = sample.fps - fasAverageFps;
                fasAverageFps += delta * 0.25;
                fasFpsVariance = fasFpsVariance * 0.80 + delta * delta * 0.20;
            }
            boolean contentActive = sample.frames > 3 && sample.fps >= 8;
            if (contentActive) {
                fasMaximumObserved = Math.max(fasMaximumObserved, sample.fps);
            }

            if (fasLearnedTarget <= 0) {
                if (contentActive) fasWarmupSamples++;
                if (fasWarmupSamples >= 6) {
                    fasLearnedTarget = fasTargetFor(Math.max(10, fasMaximumObserved));
                    writeFasState("目标帧率已学习");
                }
                fasNextSampleMs = contentActive ? FAS_ACTIVE_SAMPLE_MS : FAS_IDLE_SAMPLE_MS;
                handler.postDelayed(this, fasNextSampleMs);
                return;
            }

            double promoted = fasTargetFor(Math.max(sample.fps, fasAverageFps));
            if (contentActive && promoted > fasLearnedTarget
                    && fasAverageFps > fasLearnedTarget * 1.08) {
                if (++fasPromotionSamples >= 4) {
                    fasPromotionSamples = 0;
                    fasLearnedTarget = promoted;
                    fasStableSamples = 0;
                    fasDropSamples = 0;
                    if (raiseFasCap(true)) applyFasVote("持续高帧目标提升");
                    else writeFasState("持续高帧目标提升");
                }
            } else {
                fasPromotionSamples = 0;
            }

            double target = temperatureAdjustedFasTarget();
            double jitter = Math.sqrt(Math.max(0, fasFpsVariance));
            double dropTolerance = Math.max(target * 0.045, jitter * 1.35);
            boolean severeDrop = contentActive
                    && sample.fps < target - Math.max(target * 0.18, dropTolerance * 2.0);

            if (!contentActive) {
                fasIdleSamples++;
                fasStableSamples = 0;
                fasDropSamples = 0;
                if (fasIdleSamples >= 6) {
                    fasIdleSamples = 0;
                    if (lowerFasCap()) applyFasVote("静止画面降档");
                }
                fasNextSampleMs = FAS_IDLE_SAMPLE_MS;
            } else {
                fasIdleSamples = 0;
                if (severeDrop) {
                    fasStableSamples = 0;
                    fasDropSamples = 0;
                    if (raiseFasCap(true)) applyFasVote("明显掉帧快速恢复");
                    fasNextSampleMs = FAS_ACTIVE_SAMPLE_MS;
                } else if (fasAverageFps < target - dropTolerance) {
                    fasStableSamples = 0;
                    if (++fasDropSamples >= 3) {
                        fasDropSamples = 0;
                        if (raiseFasCap(false)) applyFasVote("帧方差余量不足升档");
                    }
                    fasNextSampleMs = FAS_ACTIVE_SAMPLE_MS;
                } else if (fasAverageFps >= target - Math.max(target * 0.02, jitter * 0.55)
                        && jitter <= target * 0.06) {
                    fasDropSamples = 0;
                    if (++fasStableSamples >= 12) {
                        fasStableSamples = 0;
                        if (lowerFasCap()) applyFasVote("低方差稳定帧率降档");
                    }
                    fasNextSampleMs = FAS_STABLE_SAMPLE_MS;
                } else {
                    fasStableSamples = 0;
                    fasDropSamples = 0;
                    fasNextSampleMs = jitter > target * 0.08
                            ? FAS_ACTIVE_SAMPLE_MS : FAS_STABLE_SAMPLE_MS;
                }
            }
            handler.postDelayed(this, fasNextSampleMs);
        }
    };

    private void updateFasSession(String packageName, int mode, boolean allowed) {
        if (!allowed || !screenOn || packageName == null || packageName.isEmpty()) {
            stopFasSession(!allowed ? "应用配置关闭" : !screenOn ? "屏幕关闭" : "退出游戏");
            return;
        }
        mode = clampMode(mode);
        if (fasSessionAllowed && packageName.equals(fasPackage) && mode == fasMode) return;
        stopFasSession("切换游戏");
        fasSessionAllowed = true;
        if (resolveFasFpsNode() == null) {
            writeFasState("measured_fps 不可用");
            return;
        }
        fasPackage = packageName;
        fasMode = mode;
        fasIndex0 = FAS_MAX0[mode];
        fasIndex6 = FAS_MAX6[mode];
        fasApplied0 = -1;
        fasApplied6 = -1;
        fasWarmupSamples = 0;
        fasStableSamples = 0;
        fasDropSamples = 0;
        fasIdleSamples = 0;
        fasPromotionSamples = 0;
        fasTuneTurn = 0;
        fasMaximumObserved = 0;
        fasLearnedTarget = 0;
        fasLastFps = 0;
        fasAverageFps = 0;
        fasFpsVariance = 0;
        fasNextSampleMs = FAS_ACTIVE_SAMPLE_MS;
        fasSamples = 0;
        if (applyFasVote("进入游戏")) {
            handler.postDelayed(fasSampleRunnable, fasNextSampleMs);
        }
    }

    private void stopFasSession(String reason) {
        handler.removeCallbacks(fasSampleRunnable);
        boolean wasActive = !fasPackage.isEmpty() || fasApplied0 >= 0 || fasApplied6 >= 0;
        if (wasActive && fasKernelAvailable) {
            try { writeText(POLICY_CONTROL, "fas active=0 cpu0=0 cpu6=0"); }
            catch (Exception error) { logError("清除 FAS 上限投票失败", error); }
        }
        fasPackage = "";
        fasSessionAllowed = false;
        fasMode = -1;
        fasIndex0 = -1;
        fasIndex6 = -1;
        fasApplied0 = -1;
        fasApplied6 = -1;
        fasLearnedTarget = 0;
        fasLastFps = 0;
        fasAverageFps = 0;
        fasFpsVariance = 0;
        fasNextSampleMs = FAS_STABLE_SAMPLE_MS;
        fasSamples = 0;
        if (wasActive || !new File(FAS_STATE).isFile()) writeFasState(reason);
    }

    private void applySceneAuxiliary(int mode, int profile) {
        mode = clampMode(mode);
        boolean game = screenOn && profile == 1;
        String key = mode + "|" + (game ? 1 : 0) + "|" + (screenOn ? 1 : 0);
        if (key.equals(lastAuxiliaryKey)) return;

        final String[] appTarget0 = {
                "82 1152000:85 1363200:90",
                "80 1152000:85 1555200:90",
                "80 1555200:85 2227200:95",
                "78 1996800:83 2400000:87 2745600:95"
        };
        final String[] appTarget6 = {
                "82 1209600:85 1689600:90",
                "80 1689600:85 1958400:90",
                "80 1958400:85 2841600:90 3283200:95",
                "78 2246400:83 2841600:87 3513600:95"
        };
        final String[] gameTarget0 = {
                "82 1152000:87 1363200:90",
                "80 1152000:83 1555200:88",
                "80 1555200:85 1996800:90",
                "80 1689600:85 2400000:95"
        };
        final String[] gameTarget6 = {
                "82 1401600:87 1958400:95",
                "80 1401600:83 1958400:90 2438400:95",
                "80 2438400:85 2649600:95",
                "80 2246400:85 2841600:95"
        };
        final String[] inactiveTarget0 = {
                "85 1152000:90", "85 1152000:90",
                "83 1555200:90", "80 1996800:87 2400000:95"
        };
        final String[] inactiveTarget6 = {
                "85 1209600:90 1689600:95",
                "85 1209600:90 1689600:95",
                "83 1958400:87 2438400:93",
                "80 2246400:87 2841600:95"
        };

        int hispeed0;
        int hispeed6;
        if (!screenOn) {
            int[] inactive0 = { 0, 0, 960000, 1152000 };
            int[] inactive6 = { 0, 0, 1017600, 1017600 };
            hispeed0 = inactive0[mode];
            hispeed6 = inactive6[mode];
        } else if (game) {
            int[] game0 = { 1152000, 1152000, 1152000, 1152000 };
            int[] game6 = { 0, 1017600, 1017600, 1401600 };
            hispeed0 = game0[mode];
            hispeed6 = game6[mode];
        } else {
            int[] app0 = { 748800, 748800, 1152000, 1152000 };
            int[] app6 = { 0, 0, 0, 1017600 };
            hispeed0 = app0[mode];
            hispeed6 = app6[mode];
        }
        writeAuxiliaryValue(WALT0 + "down_rate_limit_us", "1000");
        writeAuxiliaryValue(WALT0 + "up_rate_limit_us", game ? "2000" : "1000");
        writeAuxiliaryValue(WALT6 + "down_rate_limit_us", "1000");
        writeAuxiliaryValue(WALT6 + "up_rate_limit_us", game ? "2000" : "1000");
        writeAuxiliaryValue(WALT0 + "hispeed_freq", Integer.toString(hispeed0));
        writeAuxiliaryValue(WALT6 + "hispeed_freq", Integer.toString(hispeed6));
        writeAuxiliaryValue(TARGET_LOADS0, screenOn
                ? (game ? gameTarget0[mode] : appTarget0[mode])
                : inactiveTarget0[mode]);
        writeAuxiliaryValue(TARGET_LOADS6, screenOn
                ? (game ? gameTarget6[mode] : appTarget6[mode])
                : inactiveTarget6[mode]);
        writeAuxiliaryValue("/proc/sys/walt/sched_boost", "0");
        writeAuxiliaryValue("/proc/sys/walt/input_boost/sched_boost_on_input", "0");

        if (game) {
            writeAuxiliaryValue(CPUSET_ROOT + "background/cpus", "0-1");
            writeAuxiliaryValue(CPUSET_ROOT + "system-background/cpus", "0-3");
            writeAuxiliaryValue(CPUSET_ROOT + "foreground/cpus", "0-4");
            writeAuxiliaryValue(CPUSET_ROOT + "top-app/cpus", "0-7");
            writeAuxiliaryValue(CORE0 + "enable", "1");
            writeAuxiliaryValue(CORE0 + "max_cpus", "6");
            writeAuxiliaryValue(CORE0 + "min_cpus", "6");
            writeAuxiliaryValue(CORE0 + "enable", "0");
            writeAuxiliaryValue(CORE6 + "enable", "1");
            writeAuxiliaryValue(CORE6 + "max_cpus", "2");
            writeAuxiliaryValue(CORE6 + "min_cpus", "2");
            writeAuxiliaryValue(CORE6 + "enable", "0");
        } else {
            restoreCoreControl();
            writeAuxiliaryValue(CPUSET_ROOT + "background/cpus", "0-3");
            writeAuxiliaryValue(CPUSET_ROOT + "system-background/cpus", "0-5");
            writeAuxiliaryValue(CPUSET_ROOT + "foreground/cpus", "0-7");
            writeAuxiliaryValue(CPUSET_ROOT + "top-app/cpus", "0-7");
            if (screenOn && (mode == BALANCE || mode == PERFORMANCE)) {
                writeAuxiliaryValue(CORE0 + "enable", "1");
                writeAuxiliaryValue(CORE0 + "max_cpus", "6");
                writeAuxiliaryValue(CORE0 + "min_cpus", "6");
                writeAuxiliaryValue(CORE0 + "enable", "0");
                writeAuxiliaryValue(CORE6 + "enable", "1");
                writeAuxiliaryValue(CORE6 + "max_cpus", "2");
                writeAuxiliaryValue(CORE6 + "min_cpus", "2");
                writeAuxiliaryValue(CORE6 + "enable", "0");
            } else if (!screenOn && mode <= BALANCE) {
                writeAuxiliaryValue(CORE6 + "enable", "1");
                writeAuxiliaryValue(CORE6 + "min_cpus", "0");
                writeAuxiliaryValue(CORE6 + "max_cpus", "2");
            }
        }

        boolean limiterOff = game && mode >= PERFORMANCE;
        writeAuxiliaryValue("/proc/game_opt/disable_cpufreq_limit",
                limiterOff ? "1" : "0");
        writeAuxiliaryValue("/sys/module/perfmgr/parameters/perfmgr_enable", "0");
        if (limiterOff) {
            writeAuxiliaryValue("/sys/module/migt/parameters/glk_disable", "1");
            writeAuxiliaryValue("/sys/module/migt/parameters/glk_freq_limit_walt", "0");
        } else {
            restoreAuxiliaryValue("/sys/module/migt/parameters/glk_disable");
            restoreAuxiliaryValue("/sys/module/migt/parameters/glk_freq_limit_walt");
        }
        writeAuxiliaryValue(GPU_ROOT + "devfreq/mod_percent", game ? "100" : "105");
        lastAuxiliaryKey = key;
        scheduleConfigRepair();
    }

    private final Runnable configRepairRunnable = new Runnable() {
        @Override public void run() {
            if (!configLockEnabled || !screenOn) return;
            for (Map.Entry<String, String> entry
                    : new HashMap<>(auxiliaryApplied).entrySet()) {
                if (entry.getValue().equals(readTextValue(entry.getKey()))) continue;
                try {
                    writeText(entry.getKey(), entry.getValue());
                } catch (Exception error) {
                    logError("Scene 配置锁修复失败：" + entry.getKey(), error);
                }
            }
            handler.postDelayed(this, CONFIG_REPAIR_MS);
        }
    };

    private void scheduleConfigRepair() {
        handler.removeCallbacks(configRepairRunnable);
        if (configLockEnabled && screenOn)
            handler.postDelayed(configRepairRunnable, CONFIG_REPAIR_MS);
    }

    private void restoreSceneAuxiliary() {
        for (Map.Entry<String, String> entry : auxiliaryOriginal.entrySet()) {
            String path = entry.getKey();
            String applied = auxiliaryApplied.get(path);
            /* Do not overwrite a newer external owner.  Restore only a node
               that still contains the value last committed by this bridge. */
            if (applied == null || !applied.equals(readTextValue(path))) continue;
            try { writeText(path, entry.getValue()); }
            catch (Exception ignored) { }
        }
        auxiliaryOriginal.clear();
        auxiliaryApplied.clear();
        lastAuxiliaryKey = "";
        handler.removeCallbacks(configRepairRunnable);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clampMode(int mode) {
        return Math.max(POWERSAVE, Math.min(FAST, mode));
    }

    private Map<String, Integer> readModes(String path) {
        Map<String, Integer> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String pkg = clean(line.substring(0, split));
                int mode = parseMode(line.substring(split + 1));
                /* Keep missing packages in the user's file, but do not make
                   them active.  Map.put deliberately makes the last valid
                   definition of a duplicate package win. */
                if (!pkg.isEmpty() && mode >= 0 && applicationInfo(pkg) != null)
                    result.put(pkg, mode);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, Boolean> readFasPolicies(String path) {
        Map<String, Boolean> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String pkg = clean(line.substring(0, split));
                String value = line.substring(split + 1).trim().toLowerCase(Locale.ROOT);
                if (pkg.isEmpty() || applicationInfo(pkg) == null) continue;
                if ("on".equals(value) || "1".equals(value)
                        || "true".equals(value)) result.put(pkg, true);
                else if ("off".equals(value) || "0".equals(value)
                        || "false".equals(value)) result.put(pkg, false);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static int parseMode(String value) {
        switch (value.trim().toLowerCase()) {
            case "powersave": case "0": return POWERSAVE;
            case "balance": case "1": return BALANCE;
            case "performance": case "2": return PERFORMANCE;
            case "fast": case "3": return FAST;
            default: return -1;
        }
    }

    private void loadSchedulerConfig(boolean applyDaily) {
        boolean nextLoggingEnabled = false;
        boolean nextThreadPlacementEnabled = true;
        boolean nextConfigLockEnabled = true;
        boolean nextFasEnabled = true;
        try (BufferedReader reader = new BufferedReader(new FileReader(SCHEDULER_CONFIG))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("DAILY_MODE=")) {
                    int mode = parseMode(line.substring(11).replace("\"", "")
                            .replace("'", ""));
                    if (mode >= 0) dailyMode = mode;
                    if (applyDaily && mode >= 0)
                        writeText(PARAM_ROOT + "daily_mode", Integer.toString(mode));
                } else if (line.startsWith("LOGGING=")) {
                    String value = line.substring(8).replace("\"", "")
                            .replace("'", "").trim().toLowerCase(Locale.ROOT);
                    nextLoggingEnabled = "on".equals(value) || "1".equals(value)
                            || "true".equals(value) || "yes".equals(value);
                } else if (line.startsWith("THREAD_PLACEMENT=")) {
                    nextThreadPlacementEnabled = parseEnabled(
                            line.substring(17), nextThreadPlacementEnabled);
                } else if (line.startsWith("CONFIG_LOCK=")) {
                    nextConfigLockEnabled = parseEnabled(
                            line.substring(12), nextConfigLockEnabled);
                } else if (line.startsWith("FAS=")) {
                    nextFasEnabled = parseEnabled(line.substring(4), nextFasEnabled);
                } else if (line.startsWith("GPU_MIN="))
                    parseLongArray(line.substring(8), gpuMin);
                else if (line.startsWith("GPU_MAX="))
                    parseLongArray(line.substring(8), gpuMax);
                else if (line.startsWith("GPU_EXTREME_MAX="))
                    gpuExtremeMax = parseLong(line.substring(16), gpuExtremeMax);
            }
        } catch (Exception ignored) {
        }
        loggingEnabled = nextLoggingEnabled;
        threadPlacementEnabled = nextThreadPlacementEnabled;
        configLockEnabled = nextConfigLockEnabled;
        fasEnabled = nextFasEnabled;
        scheduleConfigRepair();
        if (!loggingEnabled) logLineCount = -1;
    }

    private static boolean parseEnabled(String value, boolean fallback) {
        if (value == null) return fallback;
        switch (value.replace("\"", "").replace("'", "")
                .trim().toLowerCase(Locale.ROOT)) {
            case "on": case "1": case "true": case "yes": return true;
            case "off": case "0": case "false": case "no": return false;
            default: return fallback;
        }
    }

    private static int parseCelsiusDecic(String value, int fallback) {
        try {
            double celsius = Double.parseDouble(value.replace("\"", "")
                    .replace("'", "").trim());
            if (Double.isNaN(celsius) || Double.isInfinite(celsius)) return fallback;
            return (int) Math.round(celsius * 10.0d);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void loadBypassConfig() {
        boolean enabled = true;
        int enterTemp = 450;
        int hysteresis = 50;
        int minimumBattery = 20;
        Set<String> packages = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(BYPASS_CONFIG))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\ufeff", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int comment = line.indexOf('#');
                if (comment >= 0) line = line.substring(0, comment).trim();
                if (line.isEmpty()) continue;
                int split = line.indexOf('=');
                if (split > 0) {
                    String key = line.substring(0, split).trim().toUpperCase(Locale.ROOT);
                    String value = line.substring(split + 1).trim();
                    if ("ENABLE".equals(key)) enabled = parseEnabled(value, enabled);
                    else if ("ENTER_TEMP".equals(key))
                        enterTemp = parseCelsiusDecic(value, enterTemp);
                    else if ("HYSTERESIS".equals(key))
                        hysteresis = parseCelsiusDecic(value, hysteresis);
                    else if ("MIN_BATTERY".equals(key))
                        minimumBattery = parseInt(value.replace("\"", "")
                                .replace("'", ""), minimumBattery);
                    continue;
                }
                /* A package preset may be written before the application is
                   installed.  Visibility can only contain installed packages,
                   so retaining it here costs no PackageManager query. */
                if (line.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
                    packages.add(line);
            }
        } catch (Exception ignored) {
        }
        bypassEnabled = enabled;
        bypassEnterTempDecic = Math.max(300, Math.min(550, enterTemp));
        bypassHysteresisDecic = Math.max(10, Math.min(150, hysteresis));
        bypassMinBattery = Math.max(5, Math.min(100, minimumBattery));
        bypassPackages = packages;
        lowBatteryNodeVerified = false;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.replace("\"", "").replace("'", "").trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private ApplicationInfo applicationInfo(String pkg) {
        if (appInfoCache.containsKey(pkg)) return appInfoCache.get(pkg);
        ApplicationInfo info = null;
        try {
            Class<?> globals = Class.forName("android.app.AppGlobals");
            Object pm = globals.getDeclaredMethod("getPackageManager").invoke(null);
            Method method = pm.getClass().getMethod("getApplicationInfo",
                    String.class, long.class, int.class);
            method.setAccessible(true);
            info = (ApplicationInfo) method.invoke(pm, pkg, 0L, 0);
        } catch (Exception error) {
            logError("查询包名失败：" + pkg, error);
        }
        appInfoCache.put(pkg, info);
        return info;
    }

    private static void parseLongArray(String value, long[] output) {
        value = value.replace("\"", "").replace("'", "").trim();
        String[] fields = value.split(",");
        if (fields.length != output.length) return;
        long[] parsed = new long[output.length];
        try {
            for (int i = 0; i < fields.length; i++)
                parsed[i] = Long.parseLong(fields[i].trim());
            System.arraycopy(parsed, 0, output, 0, output.length);
        } catch (NumberFormatException ignored) {
        }
    }

    private boolean platformSaysGame(String pkg) {
        if (pkg.isEmpty()) return false;
        ApplicationInfo info = applicationInfo(pkg);
        return info != null && info.category == ApplicationInfo.CATEGORY_GAME;
    }

    private int packageUid(String pkg) {
        ApplicationInfo info = applicationInfo(pkg);
        return info == null ? -1 : info.uid;
    }

    private int gameMode(String pkg, Map<String, Integer> games) {
        Integer value = games.get(pkg);
        return value == null ? -1 : value;
    }

    private static int configuredMode(String pkg, Map<String, Integer> modes) {
        Integer value = modes.get(pkg);
        return value == null ? -1 : value;
    }

    private boolean setBypassOwned(boolean owned) {
        if (owned) {
            try {
                writeText(BYPASS_STATE, "1\n");
                bypassOwned = true;
                return true;
            } catch (Exception error) {
                bypassOwned = false;
                logError("记录旁路供电状态失败，拒绝进入旁路", error);
                return false;
            }
        } else {
            bypassOwned = false;
            new File(BYPASS_STATE).delete();
            return true;
        }
    }

    private int readBypassNode() {
        int value = (int) readLong(BYPASS_NODE, -1);
        lastBypassNode = value;
        return value;
    }

    private boolean writeBypassNode(int value) {
        try {
            writeText(BYPASS_NODE, Integer.toString(value));
            int confirmed = readBypassNode();
            if (confirmed != value) {
                logInfo("旁路供电节点写入后校验失败：请求=" + value + "，回读=" + confirmed);
                return false;
            }
            return true;
        } catch (Exception error) {
            logError("旁路供电节点写入失败：" + value, error);
            return false;
        }
    }

    private void showBypassToast(String text) {
        handler.post(() -> {
            try {
                if (bypassToastProcess != null) bypassToastProcess.destroy();
                new ProcessBuilder("/system/bin/pkill", "-f",
                        "app_process /system/bin HyperSchedToast")
                        .redirectErrorStream(true).start().waitFor();
                String encoded = Base64.encodeToString(
                        text.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
                String command = "exec /system/bin/env CLASSPATH=" + TOAST_DEX
                        + " /system/bin/app_process /system/bin HyperSchedToast " + encoded;
                bypassToastProcess = new ProcessBuilder(
                        "/system/bin/su", "2000", "-c", command)
                        .redirectErrorStream(true).start();
            } catch (Exception error) {
                logError("旁路供电 Toast 显示失败", error);
            }
        });
    }

    private void restoreCharging(String reason, boolean alwaysCheck) {
        /* Never take ownership of a charging switch changed by another tool.
           A forced check may verify the node, but only our persistent marker
           authorizes a write back to 1. */
        if (!bypassOwned) {
            if (alwaysCheck) readBypassNode();
            return;
        }
        int node = readBypassNode();
        if (node == 0) {
            if (!writeBypassNode(1)) return;
            logInfo("旁路供电已退出，恢复正常充电 | 原因：" + reason);
            showBypassToast("已关闭旁路供电，恢复正常充电\n原因：" + reason);
        } else if (node < 0) {
            /* Keep the ownership marker.  service.sh will retry restoration
               before restarting the adapter instead of silently forgetting. */
            return;
        }
        setBypassOwned(false);
    }

    private void enterBypass(String reason) {
        /* Persist ownership before touching the charging switch.  A crash in
           the following microseconds is then recoverable by service.sh. */
        if (!bypassOwned && !setBypassOwned(true)) return;
        int node = readBypassNode();
        if (node != 0 && !writeBypassNode(0)) {
            restoreCharging("进入旁路失败", true);
            return;
        }
        logInfo(bypassTargetPackage + " → 进入旁路供电 | 电量=" + batteryLevel
                + "% | 电池温度=" + String.format(Locale.ROOT, "%.1f",
                lastBypassTempDecic / 10.0f) + "°C | 原因：" + reason);
        showBypassToast("已开启旁路供电\n" + bypassTargetPackage + "\n电池温度："
                + String.format(Locale.ROOT, "%.1f", lastBypassTempDecic / 10.0f) + "°C");
    }

    private boolean bypassMonitorEligible() {
        return bypassEnabled && bypassTargetVisible && chargerPresent && screenOn
                && batteryLevel >= bypassMinBattery;
    }

    private void updateBypassTargetFromCurrentState() {
        String target = "";
        /* Battery qualification is deliberately the outermost gate.  Below
           MIN_BATTERY there is no package-list walk and no temperature work. */
        if (bypassEnabled && batteryLevel >= bypassMinBattery) {
            for (String pkg : bypassPackages) {
                if (currentVisiblePackages.contains(pkg)) {
                    target = pkg;
                    break;
                }
            }
        }
        bypassTargetPackage = target;
        bypassTargetVisible = !target.isEmpty();
    }

    private void reconcileBypass(String reason) {
        handler.removeCallbacks(bypassSampleRunnable);
        if (batteryLevel < bypassMinBattery) {
            /* Fail open for charging: one node read on a battery edge, no
               temperature read, package scan, timer or repeated work. */
            if (!lowBatteryNodeVerified || bypassOwned) {
                restoreCharging("电量低于 " + bypassMinBattery + "%", true);
                lowBatteryNodeVerified = !bypassOwned && lastBypassNode == 1;
            }
            return;
        }
        lowBatteryNodeVerified = false;
        if (!bypassEnabled) {
            restoreCharging("配置已关闭", false);
            return;
        }
        if (!chargerPresent) {
            restoreCharging("充电器已断开", false);
            return;
        }
        if (!screenOn) {
            restoreCharging("屏幕已关闭", false);
            return;
        }
        if (!bypassTargetVisible) {
            restoreCharging("名单应用已退出可见态", false);
            return;
        }
        handler.post(bypassSampleRunnable);
    }

    private final Runnable bypassSampleRunnable = new Runnable() {
        @Override public void run() {
            if (!bypassMonitorEligible()) return;
            int temp = (int) readLong(BATTERY_TEMP, -1);
            lastBypassTempDecic = temp;
            if (temp < 0) {
                restoreCharging("电池温度不可读", false);
            } else if (bypassOwned) {
                if (temp <= bypassEnterTempDecic - bypassHysteresisDecic)
                    restoreCharging("温度已下降 "
                            + String.format(Locale.ROOT, "%.1f",
                            bypassHysteresisDecic / 10.0f) + "°C", true);
                else if (readBypassNode() != 0)
                    enterBypass("节点被系统恢复，重新校准");
            } else if (temp >= bypassEnterTempDecic) {
                enterBypass("达到温度阈值");
            }
            if (bypassMonitorEligible())
                handler.postDelayed(this, BYPASS_SAMPLE_MS);
        }
    };

    private List<String> freeformPackages() {
        List<String> packages = new ArrayList<>();
        try {
            Class<?> manager = Class.forName("miui.app.MiuiFreeFormManager");
            Method method = manager.getMethod("getAllFreeFormStackInfosOnDisplay", int.class);
            Object value = method.invoke(null, 0);
            if (!(value instanceof List<?>)) return packages;
            for (Object stack : (List<?>) value) {
                if (stack == null) continue;
                Field packageField = stack.getClass().getField("packageName");
                Field hiddenField = stack.getClass().getField("hadHideStackFormFullScreen");
                String pkg = clean((String) packageField.get(stack));
                boolean hidden = hiddenField.getBoolean(stack);
                if (!hidden && !pkg.isEmpty() && !packages.contains(pkg)) packages.add(pkg);
            }
        } catch (Exception error) {
            logError("查询官方小窗列表失败", error);
        }
        return packages;
    }

    private static String processCmdline(int pid) {
        try {
            String value = readSmallText(new File("/proc/" + pid + "/cmdline"), 256);
            int nul = value.indexOf('\0');
            return (nul < 0 ? value : value.substring(0, nul)).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<Integer> processIdsForPackage(String pkg, int hintedPid) {
        Set<Integer> exact = new LinkedHashSet<>();
        Set<Integer> children = new LinkedHashSet<>();
        if (hintedPid > 0) {
            String cmdline = processCmdline(hintedPid);
            if (pkg.equals(cmdline)) exact.add(hintedPid);
        }
        File[] processes = new File("/proc").listFiles();
        if (processes != null) {
            for (File process : processes) {
                String name = process.getName();
                if (name.isEmpty()) continue;
                int pid;
                try { pid = Integer.parseInt(name); }
                catch (NumberFormatException ignored) { continue; }
                String cmdline = processCmdline(pid);
                if (pkg.equals(cmdline)) exact.add(pid);
                else if (cmdline.startsWith(pkg + ":")) children.add(pid);
            }
        }
        /* Scene's game path targets the package's main process.  A child
           process is only a fallback for unusual split-process games. */
        if (exact.isEmpty()) exact.addAll(children);
        return new ArrayList<>(exact);
    }

    private static String readThreadComm(int tgid, int tid) {
        try {
            return readSmallText(new File("/proc/" + tgid + "/task/" + tid + "/comm"),
                    128).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private ThreadPlacement placementFor(ActiveSceneProcess process,
                                         int tid, String comm) {
        SceneThreadRule rule = process.rule;
        String cpus = "";
        String role = "other";

        if (!process.game && rule != null && rule.hasAppRule()) {
            if (tid == process.tgid) {
                cpus = rule.appMain;
                role = "app-main";
            } else if (genericRenderThread(comm)) {
                cpus = rule.appRender;
                role = "app-render";
            } else {
                cpus = rule.appOther;
                role = "app-other";
            }
        } else if (rule != null && !rule.hasAppRule()) {
            if (tid == process.tgid && !rule.mainThread.isEmpty()) {
                cpus = rule.mainThread;
                role = "main";
            } else if (!rule.unityMain.isEmpty() && comm.startsWith("UnityMain")) {
                cpus = rule.unityMain;
                role = "unity-main";
            } else {
                for (Map.Entry<String, List<String>> entry : rule.comm.entrySet()) {
                    if (matchesPrefixList(comm, entry.getValue())) {
                        cpus = entry.getKey();
                        role = "rule-comm";
                        break;
                    }
                }
                if (cpus.isEmpty() && !rule.heavyCores.isEmpty()
                        && matchesSeparatedPrefixes(comm, rule.heavyThread)) {
                    cpus = rule.heavyCores;
                    role = "rule-heavy";
                }
                if (cpus.isEmpty()) {
                    cpus = rule.other;
                    role = "rule-other";
                }
            }
        }

        /* Scene 9's generic RenderBooster covers games absent from
           threads.json.  These names were recovered from getRenderThread(). */
        if (cpus.isEmpty() && process.game) {
            if (tid == process.tgid || genericPrimaryThread(comm)) {
                cpus = "6-7";
                role = tid == process.tgid ? "generic-main" : "generic-primary";
            } else if (genericRenderThread(comm)) {
                cpus = "6";
                role = "generic-render";
            }
        }

        int mask = cpuListMask(cpus);
        if (!process.primary) mask = companionSafeMask(mask);
        return new ThreadPlacement(mask, role);
    }

    private void submitThreadPlacement(ActiveSceneProcess process,
                                       int tid, long startBoottime,
                                       String comm, boolean clearIfUnmatched) {
        ThreadPlacement placement = placementFor(process, tid, comm);
        long key = ((long) process.tgid << 32) | (tid & 0xffffffffL);
        if (placement.mask == 0) {
            if (!clearIfUnmatched || !managedThreadKeys.remove(key)) return;
            try {
                writeText(THREAD_CONTROL,
                        "clear tgid=" + process.tgid
                        + " tid=" + tid
                        + " start=" + startBoottime);
            } catch (Exception error) {
                managedThreadKeys.add(key);
                logError("撤销过期线程核心分配失败：" + process.pkg + "/" + tid
                        + "/" + comm, error);
            }
            return;
        }
        try {
            writeText(THREAD_CONTROL,
                    "apply tgid=" + process.tgid
                    + " tid=" + tid
                    + " start=" + startBoottime
                    + " mask=" + Integer.toHexString(placement.mask)
                    + " role=" + placement.role);
            managedThreadKeys.add(key);
        } catch (Exception error) {
            logError("提交线程核心分配失败：" + process.pkg + "/" + tid
                    + "/" + comm, error);
        }
    }

    private void applySceneThreadEvent(int tgid, int tid,
                                       long startBoottime, String comm) {
        ActiveSceneProcess process = activeSceneProcesses.get(tgid);
        if (process == null) return;
        submitThreadPlacement(process, tid, startBoottime, comm.trim(), true);
    }

    private void scanSceneProcess(ActiveSceneProcess process,
                                  boolean unmanagedOnly) {
        File[] tasks = new File("/proc/" + process.tgid + "/task").listFiles();
        if (tasks == null) return;
        for (File task : tasks) {
            int tid;
            try { tid = Integer.parseInt(task.getName()); }
            catch (NumberFormatException ignored) { continue; }
            long key = ((long) process.tgid << 32) | (tid & 0xffffffffL);
            if (unmanagedOnly && managedThreadKeys.contains(key)) continue;
            String comm = readThreadComm(process.tgid, tid);
            if (!comm.isEmpty())
                submitThreadPlacement(process, tid, 0L, comm, false);
        }
    }

    private void queueSceneThreadAudit() {
        handler.removeCallbacks(sceneThreadAuditRunnable);
        if (!activeSceneProcesses.isEmpty())
            handler.postDelayed(sceneThreadAuditRunnable,
                    SCENE_THREAD_AUDIT_DELAY_MS);
    }

    private final Runnable sceneThreadAuditRunnable = new Runnable() {
        @Override public void run() {
            for (ActiveSceneProcess process
                    : new ArrayList<>(activeSceneProcesses.values()))
                scanSceneProcess(process, true);
        }
    };

    private void reconcileSceneThreads(Map<String, Candidate> visibleGames,
                                       Map<String, Candidate> visibleApps,
                                       Candidate gameOwner,
                                       Candidate normalOwner,
                                       ForegroundState state) {
        List<ActiveSceneProcess> next = new ArrayList<>();
        if (threadPlacementEnabled && new File(THREAD_CONTROL).isFile()) {
            Map<String, Candidate> selected = visibleGames.isEmpty()
                    ? visibleApps : visibleGames;
            boolean selectedGames = !visibleGames.isEmpty();
            Candidate selectedOwner = selectedGames ? gameOwner : normalOwner;
            if (selectedOwner == null) {
                for (Candidate candidate : selected.values())
                    selectedOwner = better(selectedOwner, candidate);
            }
            List<String> selectedPackages = new ArrayList<>(selected.keySet());
            Collections.sort(selectedPackages);
            if (selectedOwner != null && selectedPackages.remove(selectedOwner.pkg))
                selectedPackages.add(0, selectedOwner.pkg);
            for (String pkg : selectedPackages) {
                boolean primary = selectedOwner != null && pkg.equals(selectedOwner.pkg);
                int hintedPid = pkg.equals(state.mainPackage) ? state.mainPid : -1;
                List<Integer> pids = new ArrayList<>();
                for (ActiveSceneProcess active : activeSceneProcesses.values()) {
                    if (pkg.equals(active.pkg)
                            && new File("/proc/" + active.tgid + "/task").isDirectory()
                            && pkg.equals(processCmdline(active.tgid)))
                        pids.add(active.tgid);
                }
                if (pids.isEmpty()) pids = processIdsForPackage(pkg, hintedPid);
                for (int pid : pids) {
                    next.add(new ActiveSceneProcess(pkg, pid, primary, selectedGames,
                            sceneThreadRules.get(pkg)));
                    if (next.size() >= MAX_SCENE_PROCESSES) break;
                }
                if (next.size() >= MAX_SCENE_PROCESSES) break;
            }
        }
        /* Put the frequency-policy owner first so a two-game layout always
           assigns the primary CPU7-side policy deterministically. */
        next.sort((left, right) -> {
            if (left.primary != right.primary) return left.primary ? -1 : 1;
            int pkg = left.pkg.compareTo(right.pkg);
            return pkg != 0 ? pkg : Integer.compare(left.tgid, right.tgid);
        });
        StringBuilder signature = new StringBuilder();
        for (ActiveSceneProcess process : next) {
            if (signature.length() > 0) signature.append(',');
            signature.append(process.pkg).append(':').append(process.tgid)
                    .append(':').append(process.primary ? '1' : '0')
                    .append(':').append(process.game ? 'g' : 'a');
        }
        String value = signature.toString();
        if (value.equals(lastSceneThreadSignature)) return;

        Map<Integer, ActiveSceneProcess> replacement = new HashMap<>();
        StringBuilder command = new StringBuilder("replace count=").append(next.size());
        for (ActiveSceneProcess process : next) {
            replacement.put(process.tgid, process);
            command.append(' ').append(process.tgid).append(':')
                    .append(process.primary ? '0' : '1');
        }
        try {
            /* A topology, owner or ruleset change is a new atomic placement
               transaction.  Release the previous set first so rules that no
               longer match cannot leave a stale affinity record behind. */
            if (!next.isEmpty()
                    && (!activeSceneProcesses.isEmpty() || !managedThreadKeys.isEmpty()))
                writeText(THREAD_CONTROL, "replace count=0");
            if (new File(THREAD_CONTROL).isFile()) writeText(THREAD_CONTROL, command.toString());
            managedThreadKeys.clear();
            activeSceneProcesses.clear();
            activeSceneProcesses.putAll(replacement);
            lastSceneThreadSignature = value;
            for (ActiveSceneProcess process : next)
                scanSceneProcess(process, false);
            queueSceneThreadAudit();
            logInfo(next.isEmpty()
                    ? "Scene 线程放置已退出并解除用户亲和性限制"
                    : "Scene 线程放置已更新：" + value);
        } catch (Exception error) {
            /* Fail closed.  A preceding replace count=0 may already have
               released the kernel records; never leave Java believing the
               old transaction is still active, otherwise an identical next
               foreground edge would be incorrectly short-circuited. */
            managedThreadKeys.clear();
            activeSceneProcesses.clear();
            lastSceneThreadSignature = "";
            try {
                if (new File(THREAD_CONTROL).isFile())
                    writeText(THREAD_CONTROL, "replace count=0");
            } catch (Exception ignored) { }
            logError("切换 Scene 线程放置事务失败", error);
        }
    }

    private static Candidate better(Candidate current, Candidate next) {
        if (next == null) return current;
        if (current == null || next.mode > current.mode) return next;
        if (next.mode == current.mode && next.rolePriority > current.rolePriority) return next;
        if (next.mode == current.mode && next.rolePriority == current.rolePriority
                && next.pkg.compareTo(current.pkg) < 0) return next;
        return current;
    }

    /* A single freeform transition produces several official Binder edges.
       Coalesce that burst for 80 ms so an intermediate empty-window parcel
       cannot momentarily drop the game vote.  This is a one-shot event timer,
       not sampling or polling. */
    private synchronized void queueRefresh(String reason) {
        pendingReason = reason;
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 80);
    }

    /* SQLite commits can emit several inotify edges for one setting change. */
    private void queuePowerRefresh() {
        handler.removeCallbacks(powerRefreshRunnable);
        handler.postDelayed(powerRefreshRunnable, 180);
    }

    private final Runnable powerRefreshRunnable = new Runnable() {
        @Override public void run() { updatePowerState("setting"); }
    };

    /* One delayed edge catches Game Turbo committing its session settings
       just after the foreground Binder callback.  It runs once per selected
       game activation and is cancelled when no configured game is visible;
       it is not a periodic sampler. */
    private final Runnable sidebarRecheckRunnable = new Runnable() {
        @Override public void run() {
            if (!sidebarProbeGame.isEmpty()) refresh("gamebar-recheck");
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            String reason;
            synchronized (SceneSchedulerBridge.this) {
                reason = pendingReason;
            }
            refresh(reason);
        }
    };

    private synchronized void refresh(String reason) {
        ForegroundState state = foreground;
        if (state == null) return;
        Map<String, Integer> games = readModes(GAMES_CONFIG);
        Map<String, Integer> normals = readModes(NORMAL_CONFIG);
        Map<String, Boolean> gameFasPolicies = readFasPolicies(GAME_FAS_CONFIG);
        Set<String> seen = new LinkedHashSet<>();
        Set<String> visibleGames = new LinkedHashSet<>();
        Map<String, Candidate> visibleGameCandidates = new HashMap<>();
        Map<String, Candidate> visibleAppThreadCandidates = new HashMap<>();
        Candidate gameOwner = null;
        Candidate normalOwner = null;

        String[] basePackages = { state.mainPackage, state.multiPackage,
                focusedWindow == null ? "" : focusedWindow.mainPackage };
        int[] baseUids = { state.mainUid, state.multiUid,
                focusedWindow == null ? -1 : focusedWindow.mainUid };
        String[] roles = { "main", "multi", "focus" };
        int[] priorities = { 3, 2, 1 };
        for (int i = 0; i < basePackages.length; i++) {
            String pkg = basePackages[i];
            if (pkg.isEmpty() || !seen.add(pkg)) continue;
            int mode = gameMode(pkg, games);
            if (mode >= 0) {
                visibleGames.add(pkg);
                Candidate candidate = new Candidate(pkg, roles[i], priorities[i],
                        mode, baseUids[i]);
                visibleGameCandidates.put(pkg,
                        better(visibleGameCandidates.get(pkg), candidate));
                gameOwner = better(gameOwner, candidate);
            } else {
                mode = configuredMode(pkg, normals);
                if (mode >= 0) normalOwner = better(normalOwner,
                        new Candidate(pkg, roles[i], priorities[i], mode, baseUids[i]));
                SceneThreadRule appRule = sceneThreadRules.get(pkg);
                if (appRule != null && appRule.hasAppRule()) {
                    Candidate candidate = new Candidate(pkg, roles[i], priorities[i],
                            mode >= 0 ? mode : dailyMode, baseUids[i]);
                    visibleAppThreadCandidates.put(pkg,
                            better(visibleAppThreadCandidates.get(pkg), candidate));
                }
            }
        }
        for (String pkg : freeformPackages()) {
            if (!seen.add(pkg)) continue;
            int mode = gameMode(pkg, games);
            if (mode >= 0) {
                visibleGames.add(pkg);
                Candidate candidate = new Candidate(pkg, "freeform", 1,
                        mode, packageUid(pkg));
                visibleGameCandidates.put(pkg,
                        better(visibleGameCandidates.get(pkg), candidate));
                gameOwner = better(gameOwner, candidate);
            } else {
                mode = configuredMode(pkg, normals);
                if (mode >= 0) normalOwner = better(normalOwner,
                        new Candidate(pkg, "freeform", 1, mode, packageUid(pkg)));
                SceneThreadRule appRule = sceneThreadRules.get(pkg);
                if (appRule != null && appRule.hasAppRule()) {
                    Candidate candidate = new Candidate(pkg, "freeform", 1,
                            mode >= 0 ? mode : dailyMode, packageUid(pkg));
                    visibleAppThreadCandidates.put(pkg,
                            better(visibleAppThreadCandidates.get(pkg), candidate));
                }
            }
        }

        currentVisiblePackages = new LinkedHashSet<>(seen);
        updateBypassTargetFromCurrentState();
        reconcileBypass("visibility");

        /* Only a visible games.conf candidate is allowed to probe Game Turbo.
           normal.conf is an ordinary-app policy table and never touches game
           sidebar state.  A setting event already refreshed these fields, so
           do not duplicate the provider call on that same edge. */
        if (gameOwner != null) {
            String visibleGameSignature = String.join(",", visibleGames);
            boolean newGameActivation = !visibleGameSignature.equals(
                    sidebarProbeSignature);
            sidebarProbeSignature = visibleGameSignature;
            sidebarProbeGame = gameOwner.pkg;
            if (!"setting".equals(reason)) refreshSidebarForConfiguredGame();
            if (newGameActivation) {
                handler.removeCallbacks(sidebarRecheckRunnable);
                handler.postDelayed(sidebarRecheckRunnable, 500);
            }
        } else {
            sidebarProbeGame = "";
            sidebarProbeSignature = "";
            handler.removeCallbacks(sidebarRecheckRunnable);
            clearSidebarState();
        }

        /* Xiaomi's Game Turbo performance switch is a lower-bound vote, not
           a cap.  Only honor it while gb_boosting is active and the official
           current-booster package is a configured, visible game. */
        if (gameBoosterActive && sidebarPerformanceActive
                && !boostedPackage.isEmpty() && seen.contains(boostedPackage)
                && games.containsKey(boostedPackage)
                && games.get(boostedPackage) < PERFORMANCE) {
            gameOwner = better(gameOwner, new Candidate(boostedPackage, "sidebar", 4,
                    PERFORMANCE, packageUid(boostedPackage)));
        }

        /* A visible game always wins over a normal.conf application.  Within
           each class the existing mode/role/stable-name ordering applies. */
        Candidate owner = gameOwner != null ? gameOwner : normalOwner;
        boolean ownerIsGame = gameOwner != null;
        int request = owner == null ? -1 : owner.mode;
        String ownerText = owner == null ? "daily:" + state.mainPackage
                : (ownerIsGame ? "game:" : "normal:")
                + owner.pkg + "@" + owner.role;
        /* Priority: saver/thermal kernel caps > game > system performance
           floor > normal.conf/daily.  A game therefore keeps its configured
           mode; the system switch only lifts ordinary applications. */
        int baseMode = owner == null ? dailyMode : owner.mode;
        if (!ownerIsGame && performanceActive && baseMode < PERFORMANCE) {
            request = PERFORMANCE;
            ownerText = owner == null ? "system:performance"
                    : ownerText + "+system-performance";
        }
        activeGameUid = ownerIsGame ? owner.uid : -1;
        boolean ownerFasEnabled = ownerIsGame
                && gameFasPolicies.getOrDefault(owner.pkg, fasEnabled);
        updateFasSession(ownerIsGame ? owner.pkg : "",
                ownerIsGame ? owner.mode : dailyMode, ownerFasEnabled);
        reconcileSceneThreads(screenOn ? visibleGameCandidates : new HashMap<>(),
                screenOn ? visibleAppThreadCandidates : new HashMap<>(),
                screenOn ? gameOwner : null, screenOn ? normalOwner : null, state);
        try {
            writeText(VISIBLE_STATE,
                    "main=" + state.mainPackage + "\n"
                    + "multi=" + state.multiPackage + "\n"
                    + "focus=" + (focusedWindow == null ? "" : focusedWindow.mainPackage) + "\n"
                    + "visible=" + String.join(",", seen) + "\n"
                    + "game_owner=" + ownerText + "\n"
                    + "policy_owner=" + ownerText + "\n"
                    + "requested_profile=" + (ownerIsGame ? "game" : "app") + "\n"
                    + "system_performance=" + (performanceActive ? 1 : 0) + "\n"
                    + "game_booster=" + (gameBoosterActive ? 1 : 0) + "\n"
                    + "game_sidebar_performance="
                    + (sidebarPerformanceActive ? 1 : 0) + "\n"
                    + "booster_pkg=" + boostedPackage + "\n"
                    + "sidebar_probe_game=" + sidebarProbeGame + "\n"
                    + "bypass_enabled=" + (bypassEnabled ? 1 : 0) + "\n"
                    + "bypass_target=" + bypassTargetPackage + "\n"
                    + "bypass_battery=" + batteryLevel + "\n"
                    + "bypass_charger=" + (chargerPresent ? 1 : 0) + "\n"
                    + "bypass_screen_on=" + (screenOn ? 1 : 0) + "\n"
                    + "screen_active=" + (screenOn ? 1 : 0) + "\n"
                    + "bypass_temp_decic=" + lastBypassTempDecic + "\n"
                    + "bypass_node=" + lastBypassNode + "\n"
                    + "bypass_active=" + (bypassOwned ? 1 : 0) + "\n"
                    + "requested_mode=" + request + "\n");
        } catch (Exception error) {
            logError("写入可见应用状态失败", error);
        }
        String decision = request + "|" + (ownerIsGame ? 1 : 0) + "|"
                + ownerText + "|" + thermalState + "|"
                + (lastSaverSetting > 0 ? 1 : 0) + "|"
                + (lastExtremeSetting > 0 ? 1 : 0) + "|"
                + (screenOn ? 1 : 0);
        if (decision.equals(lastDecision)) return;
        try {
            writePolicy(request, ownerIsGame,
                    lastSaverSetting, lastExtremeSetting, screenOn, ownerText);
            applySceneAuxiliary(request < 0 ? dailyMode : request,
                    ownerIsGame ? 1 : 0);
            lastDecision = decision;
            String packageName = owner == null ? state.mainPackage : owner.pkg;
            String role = owner == null ? (performanceActive ? "系统性能" : "日常")
                    : roleName(owner.role);
            int requestedDisplayMode = request < 0 ? dailyMode : request;
            logInfo(packageName + "（" + role + "）→ 请求"
                    + modeName(requestedDisplayMode) + "模式"
                    + " | 触发：" + reasonName(reason)
                    + " | 系统性能：" + (performanceActive ? "开" : "关")
                    + " | 温控等级：" + thermalState
                    + " | 可见：" + String.join(",", seen));
        } catch (Exception error) {
            logError("应用调度策略失败", error);
        }
    }

    private void applyGpuAndFps(int mode) throws Exception {
        // The live Scene profile only owns mod_percent. GPU min/max and FPS
        // remain under the platform/game, display and thermal controllers.
    }

    private static boolean setFrameRateOverride(int uid, int fps) {
        try {
            Process process = new ProcessBuilder(FPS_HELPER,
                    Integer.toString(uid), Integer.toString(fps))
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception error) {
            logError("帧率覆盖失败", error);
            return false;
        }
    }

    private void applyFrameRateCap(int fps) {
        int desiredUid = fps > 0 ? activeGameUid : -1;
        if (fpsOverrideUid >= 0 && fpsOverrideUid != desiredUid) {
            if (setFrameRateOverride(fpsOverrideUid, 0)) {
                fpsOverrideUid = -1;
                new File(FPS_STATE).delete();
            }
        }
        if (desiredUid >= 0 && fpsOverrideUid != desiredUid
                && setFrameRateOverride(desiredUid, fps)) {
            fpsOverrideUid = desiredUid;
            try { writeText(FPS_STATE, Integer.toString(desiredUid)); }
            catch (Exception error) {
                logError("记录帧率覆盖状态失败", error);
            }
        }
    }

    private void updatePowerState(String reason) {
        try {
            int saver = Math.max(readSetting("global", "low_power"),
                    readSetting("system", "POWER_SAVE_MODE_OPEN"));
            int extreme = readSetting("system", "power_supersave_mode_open");
            int performance = readSetting("system", "POWER_PERFORMANCE_MODE_OPEN");
            boolean sidebarEligible = !sidebarProbeGame.isEmpty();
            int gameBooster = sidebarEligible
                    ? readSetting("secure", "gb_boosting") : 0;
            int sidebarPerformance = sidebarEligible
                    ? readSetting("system", "game_toolbox_wild_mode") : 0;
            String currentBoostedPackage = gameBooster != 0 && sidebarPerformance != 0
                    ? readCurrentBoosterPackage() : "";
            saver = saver != 0 ? 1 : 0;
            extreme = extreme != 0 ? 1 : 0;
            performance = performance != 0 ? 1 : 0;
            gameBooster = gameBooster != 0 ? 1 : 0;
            sidebarPerformance = sidebarPerformance != 0 ? 1 : 0;
            boolean changed = saver != lastSaverSetting
                    || extreme != lastExtremeSetting
                    || performance != lastPerformanceSetting
                    || gameBooster != lastGameBoosterSetting
                    || sidebarPerformance != lastSidebarPerformanceSetting
                    || !currentBoostedPackage.equals(lastBoostedPackage);
            if (!changed && !"initial".equals(reason)) return;
            lastSaverSetting = saver;
            lastExtremeSetting = extreme;
            lastPerformanceSetting = performance;
            lastGameBoosterSetting = gameBooster;
            lastSidebarPerformanceSetting = sidebarPerformance;
            lastBoostedPackage = currentBoostedPackage;
            performanceActive = performance != 0;
            gameBoosterActive = gameBooster != 0;
            sidebarPerformanceActive = sidebarPerformance != 0;
            boostedPackage = currentBoostedPackage;
            lastDecision = "";
            refresh(reason);
        } catch (Exception error) {
            logError("电源模式读取失败", error);
        }
    }

    private void refreshSidebarForConfiguredGame() {
        int gameBooster = readSetting("secure", "gb_boosting") != 0 ? 1 : 0;
        int sidebarPerformance = readSetting(
                "system", "game_toolbox_wild_mode") != 0 ? 1 : 0;
        String currentBoostedPackage = gameBooster != 0 && sidebarPerformance != 0
                ? readCurrentBoosterPackage() : "";
        lastGameBoosterSetting = gameBooster;
        lastSidebarPerformanceSetting = sidebarPerformance;
        lastBoostedPackage = currentBoostedPackage;
        gameBoosterActive = gameBooster != 0;
        sidebarPerformanceActive = sidebarPerformance != 0;
        boostedPackage = currentBoostedPackage;
    }

    private void clearSidebarState() {
        lastGameBoosterSetting = 0;
        lastSidebarPerformanceSetting = 0;
        lastBoostedPackage = "";
        gameBoosterActive = false;
        sidebarPerformanceActive = false;
        boostedPackage = "";
    }

    /* Prefer the exact Settings provider once a system context is available.
       The command fallback is only for ports that reject provider access and
       still runs solely on an event edge; it never polls. */
    private int readSetting(String namespace, String key) {
        if (systemContext != null) {
            try {
                ContentResolver resolver = systemContext.getContentResolver();
                if ("global".equals(namespace))
                    return Settings.Global.getInt(resolver, key, 0);
                if ("secure".equals(namespace))
                    return Settings.Secure.getInt(resolver, key, 0);
                return Settings.System.getInt(resolver, key, 0);
            } catch (Exception ignored) {
            }
        }
        try {
            Process process = new ProcessBuilder("/system/bin/settings", "get",
                    namespace, key).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String value = reader.readLine();
                process.waitFor();
                return value == null ? 0 : Integer.parseInt(value.trim());
            }
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String readCurrentBoosterPackage() {
        try {
            /* /system/bin/content creates a registered command application,
               unlike this deliberately tiny root app_process.  Arguments are
               constants, so no package text ever enters a shell command. */
            Process process = new ProcessBuilder(
                    "/system/bin/content", "call",
                    "--uri", GAMEBOOSTER_PROVIDER.toString(),
                    "--method", "callPreference", "--arg", "GET",
                    "--extra", "type:i:2",
                    "--extra", "key:s:" + GAMEBOOSTER_CURRENT_KEY,
                    "--extra", "default:s:")
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line);
            }
            process.waitFor();
            String marker = GAMEBOOSTER_CURRENT_KEY + "=";
            int begin = output.indexOf(marker);
            if (begin < 0) return "";
            begin += marker.length();
            int end = output.indexOf("}]", begin);
            String raw = output.substring(begin, end < 0 ? output.length() : end);
            int split = raw.indexOf(',');
            return clean(split < 0 ? raw : raw.substring(0, split));
        } catch (Exception error) {
            logError("读取游戏加速当前包失败", error);
            return "";
        }
    }

    private Context createSystemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private boolean registerPrecisePowerObserver() {
        final Throwable[] failure = new Throwable[1];
        CountDownLatch ready = new CountDownLatch(1);
        handler.post(() -> {
            try {
                /* ActivityThread.systemMain() requires a prepared Looper.
                   Construct it on our already-running event HandlerThread,
                   never on the blocking app_process entry thread. */
                systemContext = createSystemContext();
                ContentResolver resolver = systemContext.getContentResolver();
                powerObserver = new ContentObserver(handler) {
                    @Override public void onChange(boolean selfChange, Uri uri) {
                        if (uri == null) return;
                        String key = uri.getLastPathSegment();
                        if ("low_power".equals(key)
                                || "POWER_SAVE_MODE_OPEN".equals(key)
                                || "power_supersave_mode_open".equals(key)
                                || "POWER_PERFORMANCE_MODE_OPEN".equals(key)
                                || "gb_boosting".equals(key)
                                || "game_toolbox_wild_mode".equals(key)
                                || GAMEBOOSTER_CURRENT_KEY.equals(key))
                            queuePowerRefresh();
                    }
                };
                resolver.registerContentObserver(
                        Settings.Global.CONTENT_URI, true, powerObserver);
                resolver.registerContentObserver(
                        Settings.System.CONTENT_URI, true, powerObserver);
                resolver.registerContentObserver(
                        Settings.Secure.CONTENT_URI, true, powerObserver);
                try {
                    PowerManager powerManager = (PowerManager)
                            systemContext.getSystemService(Context.POWER_SERVICE);
                    if (powerManager != null) screenOn = powerManager.isInteractive();
                    screenReceiver = new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            String action = intent == null ? "" : intent.getAction();
                            if (!Intent.ACTION_SCREEN_ON.equals(action)
                                    && !Intent.ACTION_SCREEN_OFF.equals(action)) return;
                            screenOn = Intent.ACTION_SCREEN_ON.equals(action);
                            updateBypassTargetFromCurrentState();
                            reconcileBypass(screenOn ? "screen-on" : "screen-off");
                            queueRefresh(screenOn ? "screen-on" : "screen-off");
                        }
                    };
                    IntentFilter screenFilter = new IntentFilter();
                    screenFilter.addAction(Intent.ACTION_SCREEN_ON);
                    screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
                    systemContext.registerReceiver(
                            screenReceiver, screenFilter, null, handler);
                } catch (Throwable screenError) {
                    screenReceiver = null;
                    logError("亮灭屏回调不可用，保留前台事件门控", screenError);
                }
                precisePowerObserver = true;
            } catch (Throwable error) {
                failure[0] = error;
            } finally {
                ready.countDown();
            }
        });
        try {
            ready.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure[0] = error;
        }
        if (failure[0] == null && precisePowerObserver) {
            logInfo("已注册电源模式 Provider 回调；XML 提交事件仅作移植兼容兜底");
            return true;
        }
        {
            precisePowerObserver = false;
            systemContext = null;
            powerObserver = null;
            logError("精确电源模式回调不可用，启用文件事件兼容回退",
                    failure[0] == null ? new IllegalStateException("unknown") : failure[0]);
            return false;
        }
    }

    private void unregisterPowerObserver() {
        if (systemContext != null && screenReceiver != null) {
            try { systemContext.unregisterReceiver(screenReceiver); }
            catch (Exception ignored) { }
            screenReceiver = null;
        }
        if (!precisePowerObserver || systemContext == null || powerObserver == null) return;
        try {
            systemContext.getContentResolver().unregisterContentObserver(powerObserver);
        } catch (Exception ignored) {
        }
        precisePowerObserver = false;
    }

    private void registerPowerSupplyObservers() {
        batteryLevel = (int) readLong("/sys/class/power_supply/battery/capacity", -1);
        usbOnline = readLong("/sys/class/power_supply/usb/online", 0) != 0;
        wirelessOnline = readLong("/sys/class/power_supply/wireless/online", 0) != 0;
        chargerPresent = usbOnline || wirelessOnline;
        batteryPowerObserver.startObserving("POWER_SUPPLY_NAME=battery");
        usbPowerObserver.startObserving("POWER_SUPPLY_NAME=usb");
        wirelessPowerObserver.startObserving("POWER_SUPPLY_NAME=wireless");
        logInfo("已注册内核 battery/usb/wireless 电源事件；旁路供电待机无轮询");
    }

    private void unregisterPowerSupplyObservers() {
        batteryPowerObserver.stopObserving();
        usbPowerObserver.stopObserving();
        wirelessPowerObserver.stopObserving();
    }

    private void handlePackageChanged(String pkg, boolean removed) {
        if (pkg == null || pkg.isEmpty()) return;
        appInfoCache.remove(pkg);
        if (!removed) {
            Map<String, Integer> games = readModes(GAMES_CONFIG);
            boolean existed = games.containsKey(pkg);
            int mode = gameMode(pkg, games);
            if (!existed && mode >= 0)
                logInfo(pkg + "（新安装游戏）→ 已自动加入游戏表，默认性能模式");
        }
        queueRefresh("package");
    }

    private Set<String> readInstalledPackages() {
        Set<String> packages = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/data/system/packages.list"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf(' ');
                String pkg = clean(split < 0 ? line : line.substring(0, split));
                if (!pkg.isEmpty()) packages.add(pkg);
            }
        } catch (Exception error) {
            logError("读取 PackageManager 包名快照失败", error);
        }
        return packages;
    }

    private void reconcilePackages() {
        Set<String> current = readInstalledPackages();
        if (current.isEmpty()) return;
        Set<String> previous = installedPackages;
        installedPackages = current;
        if (previous.isEmpty()) return;
        for (String pkg : current) {
            if (!previous.contains(pkg)) handlePackageChanged(pkg, false);
        }
        for (String pkg : previous) {
            if (!current.contains(pkg)) handlePackageChanged(pkg, true);
        }
    }

    private final Runnable packageRefreshRunnable = new Runnable() {
        @Override public void run() { reconcilePackages(); }
    };

    private void registerPackageObserver() {
        installedPackages = readInstalledPackages();
        packageObserver.startWatching();
        logInfo("已监听 PackageManager 安装事件；新游戏安装后实时去重加入游戏表");
    }

    private void unregisterPackageObserver() {
        packageObserver.stopWatching();
    }

    private ForegroundState queryForeground() throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROCESS_DESCRIPTOR);
            if (!processManager.transact(PM_GET_FG, data, reply, 0)) return null;
            reply.readException();
            return ForegroundState.readDirect(reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void transactListener(IBinder service, String descriptor,
                                         int transaction, IBinder callback)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeStrongBinder(callback);
            if (!service.transact(transaction, data, reply, 0))
                throw new RemoteException("transaction rejected: " + transaction);
            if (reply.dataAvail() > 0) reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void register() throws Exception {
        transactListener(processManager, PROCESS_DESCRIPTOR, PM_REGISTER_FG, foregroundListener);
        transactListener(processManager, PROCESS_DESCRIPTOR, PM_REGISTER_WINDOW, windowListener);
        if (freeformManager != null)
            transactListener(freeformManager, FREEFORM_DESCRIPTOR, FREEFORM_REGISTER, freeformListener);

        registerPrecisePowerObserver();
        registerPowerSupplyObservers();
        /* HyperOS SettingsProvider does not consistently emit key-level
           observer callbacks for vendor uppercase keys.  The atomic XML edge
           is a compatibility wakeup only; direct provider reads and the
           unchanged-state guard make it cheap and still fully event-driven. */
        settingsObserver.startWatching();
        registerPackageObserver();
        configObserver.startWatching();
        thermalObserver.startObserving("HYPER_SCHED=1");
        sceneThreadObserver.startObserving("HYPER_SCHED_THREAD=1");
    }

    private void unregister() {
        handler.removeCallbacks(bypassSampleRunnable);
        handler.removeCallbacks(sceneThreadAuditRunnable);
        stopFasSession("调度桥退出");
        sceneThreadObserver.stopObserving();
        restoreSceneAuxiliary();
        try {
            if (new File(THREAD_CONTROL).isFile())
                writeText(THREAD_CONTROL, "replace count=0");
        } catch (Exception ignored) { }
        activeSceneProcesses.clear();
        lastSceneThreadSignature = "";
        restoreCharging("适配器退出", false);
        try { transactListener(processManager, PROCESS_DESCRIPTOR,
                PM_UNREGISTER_WINDOW, windowListener); } catch (Exception ignored) { }
        try { transactListener(processManager, PROCESS_DESCRIPTOR,
                PM_UNREGISTER_FG, foregroundListener); } catch (Exception ignored) { }
        if (freeformManager != null) {
            try { transactListener(freeformManager, FREEFORM_DESCRIPTOR,
                    FREEFORM_UNREGISTER, freeformListener); } catch (Exception ignored) { }
        }
        unregisterPackageObserver();
        unregisterPowerSupplyObservers();
        unregisterPowerObserver();
        settingsObserver.stopWatching();
        configObserver.stopWatching();
        thermalObserver.stopObserving();
        observerThread.quitSafely();
    }

    private void run() throws Exception {
        foreground = queryForeground();
        if (foreground == null) throw new IllegalStateException("no foreground state");
        register();
        readKernelState();
        applyGpuAndFps(effectiveMode);
        applySceneAuxiliary(effectiveMode, effectiveProfile);
        updatePowerState("initial");
        Runtime.getRuntime().addShutdownHook(new Thread(this::unregister));
        logInfo("官方主前台/焦点窗口/小窗/电源模式回调已注册，无轮询");
        new CountDownLatch(1).await();
    }

    private void readKernelState() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/op13_scene_sched"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int marker = line.indexOf("effective=");
                if (marker >= 0) {
                    int start = marker + "effective=".length();
                    int end = line.indexOf('(', start);
                    if (end < 0) end = line.length();
                    effectiveMode = clampMode(
                            Integer.parseInt(line.substring(start, end).trim()));
                }
                marker = line.indexOf("effective_profile=");
                if (marker >= 0)
                    effectiveProfile = Math.max(0, Math.min(1,
                            parseField(line, marker + "effective_profile=".length())));
                marker = line.indexOf("extreme=");
                if (marker >= 0)
                    extremeActive = parseField(line, marker + "extreme=".length()) != 0;
                marker = line.indexOf("fps_cap=");
                if (marker >= 0 && parseField(line,
                        marker + "fps_cap=".length()) == 60)
                    thermalState = 3;
                marker = line.indexOf("thermal=");
                if (marker >= 0)
                    thermalState = Math.max(0, Math.min(3,
                            parseField(line, marker + "thermal=".length())));
            }
        } catch (Exception ignored) {
        }
    }

    private static int parseField(String line, int start) {
        int end = start;
        while (end < line.length() && (line.charAt(end) == '-'
                || Character.isDigit(line.charAt(end)))) end++;
        return parseInt(line.substring(start, end), 0);
    }

    public static void main(String[] args) throws Exception {
        IBinder process = getService(PROCESS_SERVICE);
        if (process == null) throw new IllegalStateException("ProcessManager unavailable");
        new SceneSchedulerBridge(process, getFreeformService()).run();
    }
}
