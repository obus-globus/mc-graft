package mcgraft.rt;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection shim for a mixin-added field that was relocated to a per-instance {@code $State} because its target was
 *  already loaded at attach (so the field could not be added to the class). A mod that reads its OWN added field by
 *  reflection — {@code Target.class.getDeclaredField("f")} then {@code field.set/get(target, ...)} — would otherwise
 *  hit NoSuchFieldException / IllegalArgumentException. The payload's {@code Class.getDeclaredField} and
 *  {@code Field.get/set} calls are rewritten to these methods, which resolve the field on the {@code $State} class and
 *  redirect the instance to {@code sidecar.getState(target)}. Non-relocated fields pass through unchanged. */
public final class StateReflect {
    private static final ClassLoader SYS = Platform.LOADER;
    private static final Map<String,String> FIELD_STATE = new ConcurrentHashMap<>();   // "target|name" -> $State internal name
    private static final Map<String,String> STATE_SIDECAR = new ConcurrentHashMap<>(); // $State internal name -> sidecar internal name
    private static final Map<Class<?>,Method> GETSTATE = new ConcurrentHashMap<>();     // $State class -> sidecar.getState(target)

    private StateReflect() {}

    public static void register(String targetInternal, String fieldName, String sidecarInternal, String stateInternal) {
        FIELD_STATE.put(targetInternal + "|" + fieldName, stateInternal);
        STATE_SIDECAR.put(stateInternal, sidecarInternal);
    }

    /** Payload {@code someClass.getDeclaredField(name)} — return the $State field for a relocated added field. */
    public static Field declaredField(Class<?> target, String name) throws NoSuchFieldException {
        String stateInternal = FIELD_STATE.get(target.getName().replace('.','/') + "|" + name);
        if (stateInternal != null) try {
            Field f = Class.forName(stateInternal.replace('/','.'), false, SYS).getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) { /* fall through to the real class */ }
        return target.getDeclaredField(name);
    }

    // Payload Field.get/set (and primitive variants) — redirect a relocated field's instance to its $State.
    public static Object get(Field f, Object o) throws IllegalAccessException { return f.get(actual(f,o)); }
    public static void set(Field f, Object o, Object v) throws IllegalAccessException { f.set(actual(f,o), v); }
    public static boolean getBoolean(Field f, Object o) throws IllegalAccessException { return f.getBoolean(actual(f,o)); }
    public static void setBoolean(Field f, Object o, boolean v) throws IllegalAccessException { f.setBoolean(actual(f,o), v); }
    public static int getInt(Field f, Object o) throws IllegalAccessException { return f.getInt(actual(f,o)); }
    public static void setInt(Field f, Object o, int v) throws IllegalAccessException { f.setInt(actual(f,o), v); }
    public static long getLong(Field f, Object o) throws IllegalAccessException { return f.getLong(actual(f,o)); }
    public static void setLong(Field f, Object o, long v) throws IllegalAccessException { f.setLong(actual(f,o), v); }
    public static float getFloat(Field f, Object o) throws IllegalAccessException { return f.getFloat(actual(f,o)); }
    public static void setFloat(Field f, Object o, float v) throws IllegalAccessException { f.setFloat(actual(f,o), v); }
    public static double getDouble(Field f, Object o) throws IllegalAccessException { return f.getDouble(actual(f,o)); }
    public static void setDouble(Field f, Object o, double v) throws IllegalAccessException { f.setDouble(actual(f,o), v); }
    public static byte getByte(Field f, Object o) throws IllegalAccessException { return f.getByte(actual(f,o)); }
    public static void setByte(Field f, Object o, byte v) throws IllegalAccessException { f.setByte(actual(f,o), v); }
    public static short getShort(Field f, Object o) throws IllegalAccessException { return f.getShort(actual(f,o)); }
    public static void setShort(Field f, Object o, short v) throws IllegalAccessException { f.setShort(actual(f,o), v); }
    public static char getChar(Field f, Object o) throws IllegalAccessException { return f.getChar(actual(f,o)); }
    public static void setChar(Field f, Object o, char v) throws IllegalAccessException { f.setChar(actual(f,o), v); }

    /** If f is a relocated ($State) field, map the target instance to its per-instance state; else the object unchanged. */
    private static Object actual(Field f, Object obj) {
        Class<?> dc = f.getDeclaringClass();
        if (obj == null || !STATE_SIDECAR.containsKey(dc.getName().replace('.','/'))) return obj;
        try {
            Method gs = GETSTATE.computeIfAbsent(dc, StateReflect::resolveGetState);
            return gs == null ? obj : gs.invoke(null, obj);
        } catch (Throwable t) { return obj; }
    }

    private static Method resolveGetState(Class<?> stateClass) {
        try {
            String sidecar = STATE_SIDECAR.get(stateClass.getName().replace('.','/'));
            Class<?> sc = Class.forName(sidecar.replace('/','.'), false, SYS);
            for (Method m : sc.getDeclaredMethods())
                if (m.getName().equals("getState") && m.getReturnType() == stateClass) { m.setAccessible(true); return m; }
        } catch (Throwable ignore) {}
        return null;
    }
}
