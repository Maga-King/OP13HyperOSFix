package local.mio.op13hyperosfix;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class Reflect {
    private static final Set<String> HOOKED_MEMBERS = ConcurrentHashMap.newKeySet();

    private Reflect() {
    }

    static Class<?> findClass(ClassLoader loader, String... names) {
        for (String name : names) {
            Class<?> result = XposedHelpers.findClassIfExists(name, loader);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    static Object get(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Field field = findField(target.getClass(), name);
                if (field != null) {
                    return field.get(target);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static boolean getBoolean(Object target, boolean fallback, String... names) {
        Object value = get(target, names);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    static int getInt(Object target, int fallback, String... names) {
        Object value = get(target, names);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static Object[] findFieldValuesWithMethod(Object target, String methodName,
            Class<?>... parameters) {
        if (target == null) {
            return new Object[0];
        }
        List<Object> matches = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> current = target.getClass(); current != null;
                current = current.getSuperclass()) {
            String className = current.getName();
            if (className.startsWith("android.") || className.startsWith("java.")) {
                break;
            }
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null && seen.add(value)
                            && findMethod(value.getClass(), methodName, parameters) != null) {
                        matches.add(value);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return matches.toArray();
    }

    static boolean set(Object target, Object value, String... names) {
        if (target == null) {
            return false;
        }
        for (String name : names) {
            try {
                Field field = findField(target.getClass(), name);
                if (field != null) {
                    field.set(target, value);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static Object call(Object target, String method, Object... args) {
        return XposedHelpers.callMethod(target, method, args);
    }

    static Object callStatic(Class<?> target, String method, Object... args) {
        return XposedHelpers.callStaticMethod(target, method, args);
    }

    static int hookNamedMethods(Class<?> target, String name, XC_MethodHook callback) {
        if (target == null) {
            return 0;
        }
        int count = 0;
        for (Class<?> current = target; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.isBridge()) {
                    continue;
                }
                String key = method.toGenericString();
                if (!HOOKED_MEMBERS.add(key)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, callback);
                    count++;
                } catch (Throwable error) {
                    HOOKED_MEMBERS.remove(key);
                    HookLog.error("hook failed " + key, error);
                }
            }
        }
        return count;
    }

    static boolean hookMethodOnce(Member member, XC_MethodHook callback) {
        if (member == null) {
            return false;
        }
        String key = member.toString();
        if (!HOOKED_MEMBERS.add(key)) {
            return true;
        }
        try {
            XposedBridge.hookMethod(member, callback);
            return true;
        } catch (Throwable error) {
            HOOKED_MEMBERS.remove(key);
            HookLog.error("hook failed " + key, error);
            return false;
        }
    }

    static Method findMethod(Class<?> target, String name, Class<?>... parameters) {
        for (Class<?> current = target; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    static Method findCompatibleMethod(Class<?> target, String name, int parameterCount) {
        for (Class<?> current = target; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    static boolean isStatic(Method method) {
        return Modifier.isStatic(method.getModifiers());
    }

    private static Field findField(Class<?> target, String name) {
        for (Class<?> current = target; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    static String describe(Object target) {
        return target == null ? "null" : target.getClass().getName();
    }

    static String describeArgs(Object[] args) {
        return Arrays.toString(args);
    }
}
