package local.mio.op13hyperosfix.monitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded store for opt-in power accounting. */
public final class DailyPowerStore {
    private static final String PREFS = "daily_power_v1";
    private static final String STATE = "state";
    private static final long MAX_DELTA_MS = 30_000L;
    private static final long POINT_PERIOD_MS = 60_000L;
    private static final int MAX_POINTS = 1440;
    private static final int MAX_APPS = 160;
    private static final long FLUSH_PERIOD_MS = 60_000L;
    private static State state;
    private static long lastFlushElapsed;

    private DailyPowerStore() {}

    public static synchronized Summary snapshot(Context context) {
        ensureLoaded(context);
        return state.summary();
    }

    public static synchronized void record(Context context, ProbeSnapshot sample) {
        ensureLoaded(context);
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        if (state.startedWall <= 0) state.startedWall = nowWall;
        if (state.startCapacity < 0 && sample.batteryCapacity >= 0)
            state.startCapacity = sample.batteryCapacity;
        state.capacity = sample.batteryCapacity;
        state.status = sample.batteryStatus;
        state.paused = sample.usbOnline
                || !"Discharging".equalsIgnoreCase(sample.batteryStatus);
        state.cellVoltageUv = sample.batteryVoltageUv;
        state.packVoltageUv = sample.batteryPackVoltageUv > 0
                ? sample.batteryPackVoltageUv : sample.batteryVoltageUv * 2;
        state.chargeCounterUah = sample.batteryChargeCounterUah;
        state.temperatureDecic = sample.tempDecic;
        state.currentPowerW = sample.batteryPowerW;

        if (state.paused) {
            state.lastElapsed = 0;
            state.lastPowerW = -1;
            if (nowElapsed - lastFlushElapsed >= FLUSH_PERIOD_MS) flush(context);
            return;
        }
        long delta = state.lastElapsed > 0 ? nowElapsed - state.lastElapsed : 0;
        if (delta > 0 && delta <= MAX_DELTA_MS && sample.batteryPowerW >= 0
                && sample.batteryPowerW < 250) {
            double previous = state.lastPowerW >= 0
                    ? state.lastPowerW : sample.batteryPowerW;
            double energy = (previous + sample.batteryPowerW) * 0.5
                    * delta / 3_600_000.0;
            state.energyWh += energy;
            state.durationMs += delta;
            state.powerDurationMs += delta;
            String pkg = validPackage(sample.mainPackage)
                    ? sample.mainPackage : "com.android.systemui";
            AppRecord app = state.apps.computeIfAbsent(pkg, AppRecord::new);
            app.durationMs += delta;
            app.energyWh += energy;
            app.samples++;
            app.temperatureSum += Math.max(0, sample.tempDecic) * (double) delta;
            app.temperatureDurationMs += delta;
            app.maximumTemperatureDecic = Math.max(
                    app.maximumTemperatureDecic, sample.tempDecic);
        }
        state.lastElapsed = nowElapsed;
        state.lastPowerW = sample.batteryPowerW;

        if (state.points.isEmpty()
                || nowWall - state.points.get(state.points.size() - 1).wall >= POINT_PERIOD_MS) {
            state.points.add(new Point(nowWall, sample.batteryCapacity,
                    sample.batteryPowerW, sample.tempDecic, sample.mainPackage));
            while (state.points.size() > MAX_POINTS) state.points.remove(0);
        }
        trimApps(state.apps);
        if (nowElapsed - lastFlushElapsed >= FLUSH_PERIOD_MS) flush(context);
    }

    public static synchronized void reset(Context context) {
        state = new State();
        lastFlushElapsed = 0;
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(STATE).apply();
    }

    public static synchronized void flush(Context context) {
        ensureLoaded(context);
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(STATE, state.toJson().toString()).apply();
        lastFlushElapsed = SystemClock.elapsedRealtime();
    }

    private static void ensureLoaded(Context context) {
        if (state != null) return;
        SharedPreferences prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        state = State.fromJson(prefs.getString(STATE, ""));
    }

    private static boolean validPackage(String value) {
        return value != null && value.matches("[A-Za-z0-9._]+")
                && value.indexOf('.') > 0;
    }

    private static void trimApps(Map<String, AppRecord> apps) {
        if (apps.size() <= MAX_APPS) return;
        AppRecord smallest = apps.values().stream()
                .min(Comparator.comparingLong(item -> item.durationMs)).orElse(null);
        if (smallest != null) apps.remove(smallest.packageName);
    }

    public static final class Summary {
        public long startedWall;
        public long durationMs;
        public double energyWh;
        public int startCapacity;
        public int capacity;
        public int temperatureDecic;
        public double currentPowerW;
        public double averagePowerW;
        public double remainingHours;
        public long cellVoltageUv;
        public String status;
        public boolean paused;
        public List<AppRecord> apps = List.of();
        public List<Point> points = List.of();
    }

    public static final class AppRecord {
        public final String packageName;
        public long durationMs;
        public double energyWh;
        public long samples;
        public double temperatureSum;
        public long temperatureDurationMs;
        public int maximumTemperatureDecic = -1;

        AppRecord(String packageName) { this.packageName = packageName; }

        public double averagePowerW() {
            return durationMs > 0 ? energyWh * 3_600_000.0 / durationMs : 0;
        }

        public double averageTemperatureC() {
            return temperatureDurationMs > 0
                    ? temperatureSum / temperatureDurationMs / 10.0 : 0;
        }
    }

    public static final class Point {
        public final long wall;
        public final int capacity;
        public final double powerW;
        public final int temperatureDecic;
        public final String packageName;

        Point(long wall, int capacity, double powerW, int temperatureDecic,
              String packageName) {
            this.wall = wall;
            this.capacity = capacity;
            this.powerW = powerW;
            this.temperatureDecic = temperatureDecic;
            this.packageName = packageName == null ? "" : packageName;
        }
    }

    private static final class State {
        long startedWall;
        long lastElapsed;
        long durationMs;
        long powerDurationMs;
        double energyWh;
        double lastPowerW = -1;
        int startCapacity = -1;
        int capacity = -1;
        int temperatureDecic = -1;
        double currentPowerW = -1;
        long cellVoltageUv;
        long packVoltageUv;
        long chargeCounterUah;
        String status = "Unknown";
        boolean paused;
        final LinkedHashMap<String, AppRecord> apps = new LinkedHashMap<>();
        final ArrayList<Point> points = new ArrayList<>();

        Summary summary() {
            Summary out = new Summary();
            out.startedWall = startedWall;
            out.durationMs = durationMs;
            out.energyWh = energyWh;
            out.startCapacity = startCapacity;
            out.capacity = capacity;
            out.temperatureDecic = temperatureDecic;
            out.currentPowerW = currentPowerW;
            out.averagePowerW = powerDurationMs > 0
                    ? energyWh * 3_600_000.0 / powerDurationMs : 0;
            double remainingWh = chargeCounterUah > 0 && packVoltageUv > 0
                    ? chargeCounterUah / 1_000_000.0 * packVoltageUv / 1_000_000.0
                    : 0;
            out.remainingHours = out.averagePowerW > 0.05
                    ? remainingWh / out.averagePowerW : 0;
            out.cellVoltageUv = cellVoltageUv;
            out.status = status;
            out.paused = paused;
            ArrayList<AppRecord> sorted = new ArrayList<>(apps.values());
            sorted.sort((a, b) -> Long.compare(b.durationMs, a.durationMs));
            out.apps = List.copyOf(sorted);
            out.points = List.copyOf(points);
            return out;
        }

        JSONObject toJson() {
            JSONObject root = new JSONObject();
            try {
                root.put("started", startedWall).put("duration", durationMs)
                        .put("power_duration", powerDurationMs).put("energy", energyWh)
                        .put("last_power", lastPowerW).put("start_capacity", startCapacity)
                        .put("capacity", capacity).put("temp", temperatureDecic)
                        .put("current_power", currentPowerW).put("cell_uv", cellVoltageUv)
                        .put("pack_uv", packVoltageUv).put("counter_uah", chargeCounterUah)
                        .put("status", status);
                JSONArray appArray = new JSONArray();
                for (AppRecord app : apps.values()) {
                    appArray.put(new JSONObject().put("pkg", app.packageName)
                            .put("duration", app.durationMs).put("energy", app.energyWh)
                            .put("samples", app.samples).put("temp_sum", app.temperatureSum)
                            .put("temp_duration", app.temperatureDurationMs)
                            .put("max_temp", app.maximumTemperatureDecic));
                }
                root.put("apps", appArray);
                JSONArray pointArray = new JSONArray();
                for (Point point : points) {
                    pointArray.put(new JSONObject().put("wall", point.wall)
                            .put("capacity", point.capacity).put("power", point.powerW)
                            .put("temp", point.temperatureDecic)
                            .put("pkg", point.packageName));
                }
                root.put("points", pointArray);
            } catch (Exception ignored) {}
            return root;
        }

        static State fromJson(String raw) {
            State out = new State();
            if (raw == null || raw.isBlank()) return out;
            try {
                JSONObject root = new JSONObject(raw);
                out.startedWall = root.optLong("started");
                out.durationMs = root.optLong("duration");
                out.powerDurationMs = root.optLong("power_duration");
                out.energyWh = root.optDouble("energy");
                out.lastPowerW = root.optDouble("last_power", -1);
                out.startCapacity = root.optInt("start_capacity", -1);
                out.capacity = root.optInt("capacity", -1);
                out.temperatureDecic = root.optInt("temp", -1);
                out.currentPowerW = root.optDouble("current_power", -1);
                out.cellVoltageUv = root.optLong("cell_uv");
                out.packVoltageUv = root.optLong("pack_uv");
                out.chargeCounterUah = root.optLong("counter_uah");
                out.status = root.optString("status", "Unknown");
                JSONArray apps = root.optJSONArray("apps");
                for (int i = 0; apps != null && i < apps.length(); i++) {
                    JSONObject value = apps.optJSONObject(i);
                    if (value == null) continue;
                    String pkg = value.optString("pkg");
                    if (!validPackage(pkg)) continue;
                    AppRecord app = new AppRecord(pkg);
                    app.durationMs = value.optLong("duration");
                    app.energyWh = value.optDouble("energy");
                    app.samples = value.optLong("samples");
                    app.temperatureSum = value.optDouble("temp_sum");
                    app.temperatureDurationMs = value.optLong("temp_duration");
                    app.maximumTemperatureDecic = value.optInt("max_temp", -1);
                    out.apps.put(pkg, app);
                }
                JSONArray points = root.optJSONArray("points");
                for (int i = 0; points != null && i < points.length(); i++) {
                    JSONObject value = points.optJSONObject(i);
                    if (value != null) out.points.add(new Point(
                            value.optLong("wall"), value.optInt("capacity", -1),
                            value.optDouble("power", -1), value.optInt("temp", -1),
                            value.optString("pkg", "")));
                }
            } catch (Exception ignored) {
                return new State();
            }
            return out;
        }
    }
}
