package com.uxplima.discordsync.multiserver.utils;

import java.lang.reflect.Field;

public final class ReflectionUtils {
    private ReflectionUtils() {}

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = null;
        while (clazz != null && clazz != Object.class) {
            try {
                field = clazz.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException("Field '" + fieldName + "' not found on " + target.getClass());
        }
        field.setAccessible(true);
        field.set(target, value);
    }

    public static Object getField(Object target, String fieldName) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = null;
        while (clazz != null && clazz != Object.class) {
            try {
                field = clazz.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException("Field '" + fieldName + "' not found on " + target.getClass());
        }
        field.setAccessible(true);
        return field.get(target);
    }
}
