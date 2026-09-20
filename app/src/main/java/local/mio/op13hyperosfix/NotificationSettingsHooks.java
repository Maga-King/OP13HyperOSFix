package local.mio.op13hyperosfix;

import android.app.Notification;
import android.app.AndroidAppHelper;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;

final class NotificationSettingsHooks {
    private static final Set<String> VISIBLE_PREF_KEYS = Set.of(
            "importance", "badge", "setting_badge", "allow_keyguard");
    private static final String CHANNEL_SETTINGS =
            "com.android.settings.notification.ChannelNotificationSettings";
    private static final String[] CHANNEL_SETTINGS_CANDIDATES = {
            CHANNEL_SETTINGS,
            "com.android.settings.notification.app.ChannelNotificationSettings"
    };
    private static final String[] BASE_NOTIFICATION_SETTINGS_CANDIDATES = {
            "com.android.settings.notification.BaseNotificationSettings",
            "com.android.settings.notification.app.BaseNotificationSettings"
    };
    private static final Map<Object, WeakReference<Object>> IMPORTANCE_OWNERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final String STACK_COORDINATOR =
            "com.android.systemui.statusbar.notification.collection.coordinator.StackCoordinator";
    private static final String[] STACK_RENDER_LISTENER_CANDIDATES = {
            STACK_COORDINATOR + "$attach$1",
            STACK_COORDINATOR + "$attach$2",
            STACK_COORDINATOR + "$attach$3",
            STACK_COORDINATOR + "$$ExternalSyntheticLambda0",
            STACK_COORDINATOR + "$$ExternalSyntheticLambda1",
            STACK_COORDINATOR + "$$ExternalSyntheticLambda2"
    };

    private static volatile boolean systemUiImportanceFilterEnabled = true;
    private static volatile boolean systemUiConfigObserverRegistered;
    private static volatile Context systemUiContext;
    private static ContentObserver systemUiConfigObserver;

    private NotificationSettingsHooks() {
    }

    static void installSettings(ClassLoader loader) {
        hookNotificationIconCount(loader);
        hookVisibleChannelPreferences(loader);
        hookImportancePreference(loader);
        hookImportanceChangeDispatch(loader);
    }

    static void installSystemUi(ClassLoader loader) {
        ManageAllNotificationsHooks.installFramework(loader, "systemui");
        hookLowImportanceNotificationFilter(loader);

        Class<?> menuRow = Reflect.findClass(loader,
                "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow");
        if (menuRow == null) {
            HookLog.once("notification_menu_missing",
                    "MiuiNotificationMenuRow missing; channel shortcut skipped");
            return;
        }

        int oldHooks = Reflect.hookNamedMethods(menuRow, "onClickInfoItem",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = firstContext(param.args);
                        if (openChannelSettings(param.thisObject, context, loader)) {
                            param.setResult(null);
                        }
                    }
                });

        int newHooks = Reflect.hookNamedMethods(menuRow, "createMenuViews",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        replaceAndroid17InfoListener(param.thisObject, loader);
                    }
                });
        HookLog.info("more notification menu hooks old=" + oldHooks + ", new=" + newHooks);
    }

    private static void hookLowImportanceNotificationFilter(ClassLoader loader) {
        int hookCount = 0;
        String hookedClass = null;
        for (String className : STACK_RENDER_LISTENER_CANDIDATES) {
            Class<?> listener = Reflect.findClass(loader, className);
            if (listener == null || !isStackCoordinatorListener(listener)) {
                continue;
            }
            int count = Reflect.hookNamedMethods(listener, "onAfterRenderList",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            filterLowImportanceNotifications(param, 0);
                        }
                    });
            if (count > 0) {
                hookCount += count;
                hookedClass = className;
                break;
            }
        }
        if (hookCount == 0) {
            HookLog.once("notification_importance_filter_missing",
                    "StackCoordinator render listener missing; low importance filter skipped");
        } else {
            HookLog.info("notification importance filter hooks=" + hookCount
                    + ", class=" + hookedClass);
        }
    }

    private static boolean isStackCoordinatorListener(Class<?> listener) {
        Method method = Reflect.findMethod(listener, "onAfterRenderList", List.class);
        if (method == null) {
            return false;
        }
        for (Field field : listener.getDeclaredFields()) {
            if (STACK_COORDINATOR.equals(field.getType().getName())) {
                return true;
            }
        }
        return listener.getName().equals(STACK_RENDER_LISTENER_CANDIDATES[0]);
    }

    private static void filterLowImportanceNotifications(
            XC_MethodHook.MethodHookParam param, int listArgument) {
        if (param.args.length <= listArgument
                || !(param.args[listArgument] instanceof List)
                || !isSystemUiImportanceFilterEnabled()) {
            return;
        }
        List<?> source = (List<?>) param.args[listArgument];
        ArrayList<Object> filtered = null;
        for (int index = 0; index < source.size(); index++) {
            Object item = source.get(index);
            int importance = notificationImportance(item);
            boolean keep = importance == Integer.MIN_VALUE || importance > 1;
            if (!keep && filtered == null) {
                filtered = new ArrayList<>(source.size() - 1);
                for (int previous = 0; previous < index; previous++) {
                    filtered.add(source.get(previous));
                }
            } else if (keep && filtered != null) {
                filtered.add(item);
            }
        }
        if (filtered != null) {
            param.args[listArgument] = filtered;
        }
    }

    private static int notificationImportance(Object listEntry) {
        try {
            Object entry = representativeEntry(listEntry);
            if (entry == null) {
                return Integer.MIN_VALUE;
            }
            Object ranking = Reflect.get(entry, "mRanking", "ranking");
            if (ranking == null) {
                ranking = safeCall(entry, "getRanking");
            }
            Object importance = safeCall(ranking, "getImportance");
            return importance instanceof Number
                    ? ((Number) importance).intValue() : Integer.MIN_VALUE;
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static Object representativeEntry(Object listEntry) {
        return safeCall(listEntry, "getRepresentativeEntry");
    }

    private static boolean isSystemUiImportanceFilterEnabled() {
        ensureSystemUiConfigObserver();
        return systemUiImportanceFilterEnabled;
    }

    private static void ensureSystemUiConfigObserver() {
        if (systemUiConfigObserverRegistered) {
            return;
        }
        Context context;
        try {
            context = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
            return;
        }
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        context = application != null ? application : context;
        synchronized (NotificationSettingsHooks.class) {
            if (systemUiConfigObserverRegistered) {
                return;
            }
            systemUiContext = context;
            refreshSystemUiImportanceFilter();
            systemUiConfigObserver = new ContentObserver(
                    new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    refreshSystemUiImportanceFilter();
                }
            };
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(ModuleConfig.MORE_NOTIFICATION_SETTINGS),
                    false, systemUiConfigObserver);
            systemUiConfigObserverRegistered = true;
        }
    }

    private static void refreshSystemUiImportanceFilter() {
        Context context = systemUiContext;
        if (context != null) {
            systemUiImportanceFilterEnabled = ModuleConfig.isEnabled(context,
                    ModuleConfig.MORE_NOTIFICATION_SETTINGS, true);
        }
    }

    private static void hookNotificationIconCount(ClassLoader loader) {
        Class<?> target = Reflect.findClass(loader,
                "com.android.settings.IconDisplayCustomizationSettings");
        if (target == null) {
            HookLog.once("notification_count_class_missing",
                    "notification icon count settings missing");
            return;
        }
        int count = Reflect.hookNamedMethods(target, "setupShowNotificationIconCount",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        expandNotificationIconCount(param.thisObject);
                    }
                });
        HookLog.info("notification icon count hooks=" + count);
    }

    private static void expandNotificationIconCount(Object fragment) {
        try {
            Object preference = Reflect.get(fragment, "mShowNotificationIconCount");
            if (preference == null) {
                return;
            }
            Context context = contextFromFragment(fragment);
            String[] original = (String[]) Reflect.get(fragment, "mShowNotificationEntries");
            String[] entries = new String[7];
            String[] values = new String[7];
            for (int value = 0; value <= 6; value++) {
                values[value] = Integer.toString(value);
                entries[value] = notificationCountLabel(context, original, value);
            }
            Reflect.set(fragment, entries, "mShowNotificationEntries");
            Reflect.call(preference, "setEntries", (Object) entries);
            Reflect.call(preference, "setEntryValues", (Object) values);
            Reflect.call(fragment, "updateShowNotificationIconCount");
            HookLog.once("notification_count_expanded",
                    "notification icon count expanded to 0..6");
        } catch (Throwable error) {
            HookLog.error("notification icon count expansion failed", error);
        }
    }

    private static String notificationCountLabel(Context context, String[] original, int value) {
        if (value == 0 && original != null && original.length > 0) {
            return original[0];
        }
        if (context != null) {
            try {
                Resources resources = context.getResources();
                int id = resources.getIdentifier("display_notification_icon_3",
                        "string", "com.android.settings");
                if (id != 0) {
                    return resources.getString(id, value);
                }
            } catch (Throwable ignored) {
            }
        }
        if (original != null && original.length > 1) {
            String sample = original[1];
            int digit = sample.indexOf('1');
            if (digit >= 0) {
                return sample.substring(0, digit) + value + sample.substring(digit + 1);
            }
        }
        return String.format(Locale.getDefault(), "显示%d个", value);
    }

    private static void hookVisibleChannelPreferences(ClassLoader loader) {
        int count = 0;
        for (String className : BASE_NOTIFICATION_SETTINGS_CANDIDATES) {
            Class<?> target = Reflect.findClass(loader, className);
            if (target == null) {
                continue;
            }
            count += Reflect.hookNamedMethods(target, "setPrefVisible",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length < 2 || param.args[0] == null
                                    || !moreNotificationEnabled(param.thisObject)) {
                                return;
                            }
                            Object key = Reflect.call(param.args[0], "getKey");
                            if (key instanceof String && VISIBLE_PREF_KEYS.contains(key)) {
                                param.args[1] = true;
                            }
                        }
                    });
        }
        if (count == 0) {
            HookLog.once("base_notification_settings_missing",
                    "BaseNotificationSettings missing");
            return;
        }
        HookLog.info("visible notification preference hooks=" + count);
    }

    private static void hookImportancePreference(ClassLoader loader) {
        int count = 0;
        for (String className : CHANNEL_SETTINGS_CANDIDATES) {
            Class<?> target = Reflect.findClass(loader, className);
            if (target == null) {
                continue;
            }
            count += Reflect.hookNamedMethods(target, "setupChannelDefaultPrefs",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (moreNotificationEnabled(param.thisObject)) {
                                setupImportancePreference(param.thisObject);
                            }
                        }
                    });
        }
        if (count == 0) {
            HookLog.once("channel_notification_settings_missing",
                    "ChannelNotificationSettings missing");
            return;
        }
        HookLog.info("notification importance hooks=" + count);
    }

    private static void setupImportancePreference(Object settings) {
        try {
            Object preference = Reflect.call(settings, "findPreference", "importance");
            if (preference == null) {
                return;
            }
            Reflect.set(settings, preference, "mImportance");
            int importance = Reflect.getInt(settings, 0, "mBackupImportance");
            if (importance > 0) {
                Object index = Reflect.call(preference, "findSpinnerIndexOfValue",
                        Integer.toString(importance));
                if (index instanceof Number && ((Number) index).intValue() >= 0) {
                    Reflect.call(preference, "setValueIndex", ((Number) index).intValue());
                }
            }
            IMPORTANCE_OWNERS.put(preference, new WeakReference<>(settings));
            Object channel = Reflect.get(settings, "mChannel");
            HookLog.info("importance preference bound: fragment="
                    + settings.getClass().getName() + ", pkg="
                    + Reflect.get(settings, "mPkg") + ", channel="
                    + (channel instanceof NotificationChannel
                    ? ((NotificationChannel) channel).getId() : "null"));
        } catch (Throwable error) {
            HookLog.error("notification importance setup failed", error);
        }
    }

    private static void hookImportanceChangeDispatch(ClassLoader loader) {
        Class<?> preference = Reflect.findClass(loader, "androidx.preference.Preference");
        int count = Reflect.hookNamedMethods(preference, "callChangeListener",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        WeakReference<Object> ownerReference =
                                IMPORTANCE_OWNERS.get(param.thisObject);
                        Object settings = ownerReference == null ? null : ownerReference.get();
                        if (settings == null || param.args.length == 0
                                || !moreNotificationEnabled(settings)) {
                            return;
                        }
                        param.setResult(applyImportance(settings, param.args[0], loader));
                    }
                });
        HookLog.info("notification importance dispatch hooks=" + count);
    }

    private static boolean applyImportance(Object settings, Object value,
            ClassLoader loader) {
        try {
            int importance = Integer.parseInt(String.valueOf(value));
            Object channelObject = Reflect.get(settings, "mChannel");
            if (!(channelObject instanceof NotificationChannel)) {
                throw new IllegalStateException("mChannel is "
                        + Reflect.describe(channelObject));
            }
            NotificationChannel channel = (NotificationChannel) channelObject;
            String packageName = (String) Reflect.get(settings, "mPkg");
            int uid = Reflect.getInt(settings, -1, "mUid");
            if (packageName == null || uid < 0) {
                throw new IllegalStateException("invalid channel owner pkg="
                        + packageName + ", uid=" + uid);
            }

            channel.setImportance(importance);
            Reflect.call(channel, "lockFields", 4);

            Class<?> serviceCompat = Reflect.findClass(loader,
                    "com.android.server.notification.NotificationManagerServiceCompat");
            if (serviceCompat != null) {
                Reflect.callStatic(serviceCompat,
                        "updateNotificationChannelForPackage",
                        packageName, uid, channel);
            } else {
                Object backend = Reflect.get(settings, "mBackend");
                Reflect.call(backend, "updateChannel", packageName, uid, channel);
            }

            String conversationId = (String) Reflect.get(settings, "mConversationId");
            NotificationChannel persisted = readChannel(settings, serviceCompat,
                    packageName, uid, channel.getId(), conversationId);
            if (persisted == null) {
                throw new IllegalStateException("channel verification returned null");
            }
            int actualImportance = persisted.getImportance();
            Reflect.set(settings, persisted, "mChannel");
            Reflect.set(settings, actualImportance, "mBackupImportance");
            Reflect.call(settings, "updateDependents", actualImportance == 0);
            HookLog.info("notification importance persisted: " + packageName + "/"
                    + channel.getId() + " requested=" + importance
                    + ", actual=" + actualImportance
                    + ", locked=" + Reflect.call(persisted, "getUserLockedFields"));
            return actualImportance == importance;
        } catch (Throwable error) {
            HookLog.error("notification importance change failed", error);
            return false;
        }
    }

    private static NotificationChannel readChannel(Object settings, Class<?> serviceCompat,
            String packageName, int uid, String channelId, String conversationId) {
        Object result;
        if (serviceCompat != null) {
            result = Reflect.callStatic(serviceCompat,
                    "getNotificationChannelForPackage", packageName, uid,
                    channelId, conversationId, false);
        } else {
            Object backend = Reflect.get(settings, "mBackend");
            result = Reflect.call(backend, "getChannel", packageName, uid,
                    channelId, conversationId);
        }
        return result instanceof NotificationChannel ? (NotificationChannel) result : null;
    }

    private static void replaceAndroid17InfoListener(Object row, ClassLoader loader) {
        try {
            Context context = (Context) Reflect.get(row, "mContext");
            if (!ModuleConfig.isEnabled(context,
                    ModuleConfig.MORE_NOTIFICATION_SETTINGS, true)
                    || !canOpenChannelSettings(row, loader)) {
                return;
            }
            Object infoItem = Reflect.get(row, "mInfoItem");
            if (infoItem == null) {
                return;
            }
            View.OnClickListener listener = view -> openChannelSettings(row, context, loader);
            Reflect.call(infoItem, "setOnClickListener", listener);
        } catch (Throwable error) {
            HookLog.error("Android 17 notification menu listener failed", error);
        }
    }

    private static boolean openChannelSettings(Object row, Context suppliedContext,
            ClassLoader loader) {
        try {
            Context context = suppliedContext != null
                    ? suppliedContext : (Context) Reflect.get(row, "mContext");
            if (!ModuleConfig.isEnabled(context,
                    ModuleConfig.MORE_NOTIFICATION_SETTINGS, true)) {
                return false;
            }
            ChannelTarget target = resolveChannelTarget(row, loader);
            if (target == null) {
                return false;
            }

            Bundle arguments = new Bundle();
            arguments.putString("android.provider.extra.CHANNEL_ID", target.channelId);
            arguments.putString("android.provider.extra.APP_PACKAGE", target.packageName);
            arguments.putString("package", target.packageName);
            arguments.putInt("uid", target.uid);
            arguments.putString("miui.targetPkg", target.packageName);

            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.settings", "com.android.settings.SubSettings")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(":android:show_fragment", CHANNEL_SETTINGS)
                    .putExtra(":settings:show_fragment", CHANNEL_SETTINGS)
                    .putExtra(":android:show_fragment_args", arguments)
                    .putExtra(":settings:show_fragment_args", arguments);
            try {
                Reflect.call(context, "startActivityAsUser", intent, Process.myUserHandle());
            } catch (Throwable hiddenApiError) {
                context.startActivity(intent);
            }
            collapseNotificationPanel(row);
            return true;
        } catch (Throwable error) {
            HookLog.error("open channel notification settings failed", error);
            return false;
        }
    }

    private static boolean canOpenChannelSettings(Object row, ClassLoader loader) {
        return resolveChannelTarget(row, loader) != null;
    }

    private static ChannelTarget resolveChannelTarget(Object row, ClassLoader loader) {
        try {
            Object sbn = Reflect.get(row, "mSbn");
            if (sbn == null) {
                Object parent = Reflect.get(row, "mParent");
                Object entry = parent == null ? null : safeCall(parent, "getEntry");
                sbn = entry == null ? null : safeCall(entry, "getSbn");
                if (sbn == null && entry != null) {
                    sbn = Reflect.get(entry, "mSbn", "sbn");
                }
            }
            if (sbn == null || isHybridNotification(sbn, loader)) {
                return null;
            }

            String packageName = stringCall(sbn, "getTargetPackageName");
            if (packageName == null) {
                packageName = stringCall(sbn, "getPackageName");
            }
            int uid = intCall(sbn, "getAppUid", -1);
            if (uid < 0) {
                uid = intCall(sbn, "getUid", -1);
            }

            String channelId = null;
            Object parent = Reflect.get(row, "mParent");
            Object entry = parent == null ? null : safeCall(parent, "getEntry");
            Object channel = entry == null ? null : safeCall(entry, "getChannel");
            if (channel == null && entry != null) {
                Object ranking = Reflect.get(entry, "mRanking", "ranking");
                if (ranking == null) {
                    ranking = safeCall(entry, "getRanking");
                }
                channel = ranking == null ? null : safeCall(ranking, "getChannel");
            }
            if (channel instanceof NotificationChannel) {
                channelId = ((NotificationChannel) channel).getId();
            }

            Notification notification = null;
            Object notificationObject = Reflect.call(sbn, "getNotification");
            if (notificationObject instanceof Notification) {
                notification = (Notification) notificationObject;
                if (channelId == null) {
                    channelId = notification.getChannelId();
                }
            }
            String opPackage = stringCall(sbn, "getOpPkg");
            if (notification != null && "com.miui.systemAdSolution".equals(opPackage)) {
                channelId = notification.extras.getString("xmsf_msa_channel_id", channelId);
            }
            if (packageName == null || uid < 0 || channelId == null
                    || "miscellaneous".equals(channelId)) {
                return null;
            }
            return new ChannelTarget(packageName, uid, channelId);
        } catch (Throwable error) {
            HookLog.once("notification_channel_target_failed",
                    "cannot resolve notification channel: " + error);
            return null;
        }
    }

    private static boolean isHybridNotification(Object sbn, ClassLoader loader) {
        for (String className : new String[]{
                "com.miui.systemui.notification.MiuiBaseNotifUtil",
                "com.android.systemui.miui.statusbar.notification.NotificationUtil"}) {
            Class<?> utility = Reflect.findClass(loader, className);
            if (utility == null) {
                continue;
            }
            try {
                Object value = Reflect.callStatic(utility, "isHybrid", sbn);
                if (value instanceof Boolean) {
                    return (Boolean) value;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void collapseNotificationPanel(Object row) {
        Object controller = Reflect.get(row, "mModalController");
        if (controller == null) {
            return;
        }
        try {
            Reflect.call(controller, "animExitModal", "MORE");
        } catch (Throwable ignored) {
            try {
                Reflect.call(controller, "animExitModelCollapsePanels");
            } catch (Throwable ignoredAgain) {
            }
        }
        try {
            Object collapse = Reflect.get(controller, "collapseRunnable");
            if (collapse != null) {
                Reflect.call(collapse, "invoke");
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean moreNotificationEnabled(Object fragment) {
        return ModuleConfig.isEnabled(contextFromFragment(fragment),
                ModuleConfig.MORE_NOTIFICATION_SETTINGS, true);
    }

    private static Context contextFromFragment(Object fragment) {
        try {
            Object context = Reflect.call(fragment, "getContext");
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context firstContext(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Context) {
                return (Context) arg;
            }
        }
        return null;
    }

    private static String stringCall(Object target, String method) {
        try {
            Object value = Reflect.call(target, method);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int intCall(Object target, String method, int fallback) {
        try {
            Object value = Reflect.call(target, method);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object safeCall(Object target, String method, Object... args) {
        try {
            return target == null ? null : Reflect.call(target, method, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class ChannelTarget {
        final String packageName;
        final int uid;
        final String channelId;

        ChannelTarget(String packageName, int uid, String channelId) {
            this.packageName = packageName;
            this.uid = uid;
            this.channelId = channelId;
        }
    }
}
