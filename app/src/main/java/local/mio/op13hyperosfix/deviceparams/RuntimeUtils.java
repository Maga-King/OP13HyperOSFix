package local.mio.op13hyperosfix.deviceparams;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class RuntimeUtils {
    private RuntimeUtils() {
    }

    static Application currentApplication() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method method = thread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            return (Application) method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Context findContext(Object owner) {
        if (owner instanceof Context) return (Context) owner;
        if (owner != null) {
            Class<?> type = owner.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField("mContext");
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value instanceof Context) return (Context) value;
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                } catch (Throwable ignored) {
                    break;
                }
            }
            try {
                Method method = owner.getClass().getMethod("getActivity");
                Object value = method.invoke(owner);
                if (value instanceof Context) return (Context) value;
            } catch (Throwable ignored) {
            }
        }
        return currentApplication();
    }

    static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
