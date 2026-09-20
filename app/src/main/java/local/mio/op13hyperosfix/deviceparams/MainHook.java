package local.mio.op13hyperosfix.deviceparams;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "COSOS4Params: ";
    private static final String PARAMS_MANAGER =
            "com.android.settings.device.DeviceParamsManager";
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ConfigContract.SETTINGS_PACKAGE.equals(lpparam.packageName)) return;
        CompatResolver resolver = new CompatResolver(lpparam);
        try {
            hookParamsManager(resolver.exactClass(PARAMS_MANAGER));
            hookCacheReader(resolver.findCacheReadMethod("basic_info_key"), true);
            hookCacheReader(resolver.findCacheReadMethod("camera_info_key"), false);
            hookMarketName(resolver.findMarketNameMethod());
            AppearanceHooks.install(resolver.findMyDeviceClass(),
                    resolver.findBgDataManagerClass(), lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "package hook setup failed safely: " + t);
        } finally {
            resolver.close();
        }
    }

    private static void hookParamsManager(Class<?> target) {
        if (target == null) {
            XposedBridge.log(TAG + "DeviceParamsManager name changed; cache hooks used");
            return;
        }
        try {
            XposedBridge.hookAllMethods(target, "getBasicParams", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context context = RuntimeUtils.findContext(param.thisObject);
                        HookConfig snapshot = HookConfig.load(context);
                        if (!snapshot.paramsEnabled) return;
                        String ram = RamUtils.getBasicParamValue(context);
                        if (ram.length() == 0) return;
                        param.setResult(buildBasicJson(snapshot, ram));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "getBasicParams fail-open: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(target, "getCameraParams", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context context = RuntimeUtils.findContext(param.thisObject);
                        HookConfig snapshot = HookConfig.load(context);
                        if (!snapshot.paramsEnabled) return;
                        param.setResult(buildCameraJson(snapshot));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "getCameraParams fail-open: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "DeviceParamsManager hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "DeviceParamsManager hook install failed: " + t);
        }
    }

    private static void hookCacheReader(Method method, final boolean basic) {
        if (method == null) {
            XposedBridge.log(TAG + (basic ? "basic" : "camera")
                    + " cache reader unavailable on this build");
            return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context context = param.args != null && param.args.length > 0
                                && param.args[0] instanceof Context
                                ? (Context) param.args[0] : RuntimeUtils.currentApplication();
                        HookConfig snapshot = HookConfig.load(context);
                        if (!snapshot.paramsEnabled) return;
                        if (basic) {
                            String ram = RamUtils.getBasicParamValue(context);
                            if (ram.length() == 0) return;
                            param.setResult(buildBasicJson(snapshot, ram));
                        } else {
                            param.setResult(buildCameraJson(snapshot));
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "cache reader fail-open: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + (basic ? "basic" : "camera")
                    + " cache hook installed: " + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "cache hook install failed: " + t);
        }
    }

    private static void hookMarketName(Method method) {
        if (method == null) {
            XposedBridge.log(TAG + "market-name method unavailable on this build");
            return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        HookConfig snapshot = HookConfig.load(RuntimeUtils.currentApplication());
                        if (!snapshot.paramsEnabled) return;
                        param.setResult(snapshot.marketName);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "market-name fail-open: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "market-name hook installed: " + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "market-name hook install failed: " + t);
        }
    }

    static String buildBasicJson(HookConfig s, String ram) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject mishop = new JSONObject();
        mishop.put("RightValue", "1");
        mishop.put("ShowRedDot", "true");
        mishop.put("Url", "1");
        root.put("Mishop", mishop);
        root.put("BasicInfoToggle", "1");

        JSONArray items = new JSONArray();
        items.put(item("处理器", s.processor, "0"));
        items.put(item("电池容量", s.battery, "1"));
        items.put(item("后置摄像头", s.rearCamera, "2"));
        items.put(item("屏幕尺寸", s.screenSize, "3"));
        items.put(item("分辨率", s.resolution, "4"));
        items.put(item("运行内存", ram, "5"));
        root.put("BasicItems", items);
        return root.toString();
    }

    private static JSONObject item(String title, String summary, String index)
            throws Exception {
        JSONObject item = new JSONObject();
        item.put("Title", title);
        item.put("Summary", summary);
        item.put("Index", index);
        return item;
    }

    static String buildCameraJson(HookConfig s) throws Exception {
        JSONObject camera = new JSONObject();
        camera.put("front_camera", s.frontCamera);
        camera.put("rear_camera", s.rearCamera);
        JSONObject data = new JSONObject();
        data.put("BasicInfoToggle", "1");
        data.put("camera", camera);
        JSONObject root = new JSONObject();
        root.put("data", data);
        root.put("status", "true");
        return root.toString();
    }

}
