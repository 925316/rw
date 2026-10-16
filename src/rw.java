import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class rw {
    static PrintWriter L = null;

    static void log(String s) {
        System.out.println(s);
        if (L == null) openLog();
        if (L != null) {
            L.println(s);
            L.flush();
        }
    }

    static synchronized void openLog() {
        try {
            L = new PrintWriter(new FileWriter("rw.log", true), true);
        } catch (Throwable ignore) {
        }
    }

    static String buildStamp() {
        try (InputStream in = rw.class.getResourceAsStream("/build-stamp.txt")) {
            if (in == null) return "unknown";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String s = r.readLine();
                return s == null ? "unknown" : s.trim();
            }
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    static volatile boolean started = false;
    static volatile boolean infinite = true;
    static volatile boolean running = true;
    static volatile boolean engineLogged = false;
    static volatile boolean moneyLogged = false;
    static volatile Object localTeam = null;
    static volatile int moneyTraceBudget = 1;

    // Host program symbols. PKG is decoded at runtime so the source never spells it out.
    static final String PKG = decode("Y29tLmNvcnJvZGluZ2dhbWVzLnJ0cw==");
    static final String CL_ENGINE = PKG + ".gameFramework.l";
    static final String CL_PLAYER_TEAM = PKG + ".game.n";
    static final String FIELD_MONEY = "o"; // team credits
    static final String CL_UNIT_TYPE = PKG + ".game.units.custom.l";
    static final String CL_UNIT = PKG + ".game.units.am";
    static final String CL_UNIT_CUSTOM = PKG + ".game.units.custom.j";
    static final String CL_UNITS_D = PKG + ".game.units.d.";
    static final String CL_UNITS_E_B = PKG + ".game.units.e.b";
    static final String CL_UNITS_D_M = PKG + ".game.units.d.m";
    static final String CL_UNITS_D_A = PKG + ".game.units.d.a";

    static String decode(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
    static final String[] LOCAL_UNIT_TYPE_FIELDS = new String[] {"x", "z"};
    static final String UNIT_OWNER_FIELD = "bX";
    static final String UNIT_REGISTRY_FIELD = "bE";

    static Set<Object> patchedLiveUnits = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    static Set<Object> knownLiveUnits = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    static final Map<Object, Object> localTypeClones = new IdentityHashMap<Object, Object>();
    static Set<Object> buildWatchUnits = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    static volatile Object unsafe = null;

    public static void premain(String args, Instrumentation inst) { start(); }
    public static void agentmain(String args, Instrumentation inst) { start(); }

    static void start() {
        synchronized (rw.class) {
            if (started) {
                log("[rw] already loaded, ignoring duplicate attach");
                return;
            }
            started = true;
        }
        log("[rw] agent loaded: local-only patch");
        log("[rw] rev=facq4+cmscan+nodf (factory queue refresh, direct cm finish, no d.f upgrade)");
        log("[rw] build=" + buildStamp() + " java=" + System.getProperty("java.version"));
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignore) {
                }
                while (running) {
                    try {
                        tick();
                        Thread.sleep(500);
                    } catch (Throwable ex) {
                        log("[rw] tick err " + ex);
                    }
                }
            }
        }, "rw");
        t.setDaemon(true);
        t.start();
    }

    static void tick() {
        try {
            Object engine = getEngine();
            if (engine == null) {
                log("[tracer] engine not ready");
                return;
            }
            if (!engineLogged) {
                log("[tracer] engine.class=" + engine.getClass().getName());
                engineLogged = true;
            }
            if (engine == null || !infinite) return;
            try {
                Object team = findLocalTeam(engine);
                if (team == null) {
                    log("[tracer] local team not found");
                    return;
                }
                if (team != localTeam) {
                    localTeam = team;
                    log("[tracer] team.class=" + team.getClass().getName() + " fields=" + fieldSummary(team.getClass()));
                }
                Field money = team.getClass().getField(FIELD_MONEY);
                double cur = money.getDouble(team);
                if (cur < 500000.0) {
                    log("[tracer] money before top-up: " + FIELD_MONEY + "=" + cur);
                    money.setDouble(team, 500000.0);
                } else if (moneyTraceBudget > 0) {
                    moneyTraceBudget = 0;
                }
            } catch (Throwable ex) {
                log("[tracer] money err=" + ex);
            }
            traceUnitInstances();
        } catch (Throwable t) {
            log("[rw] tick err: " + t);
        }
    }

    static Object getEngine() {
        try {
            return Class.forName(CL_ENGINE).getMethod("B").invoke(null);
        } catch (Throwable ignore) {
        }
        try {
            return Class.forName(CL_ENGINE).getMethod("u").invoke(null);
        } catch (Throwable ignore) {
        }
        return null;
    }

    static Object findLocalTeam(Object engine) {
        if (engine == null) return null;
        try {
            Field f = engine.getClass().getField("bs");
            Object v = f.get(engine);
            if (v != null) return v;
        } catch (Throwable ignore) {
        }
        try {
            Field f = engine.getClass().getField("bb");
            Object v = f.get(engine);
            if (v != null) return v;
        } catch (Throwable ignore) {
        }
        try {
            Class<?> teamClass = Class.forName(CL_PLAYER_TEAM);
            for (Class<?> c = engine.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!teamClass.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(engine);
                    if (v != null) return v;
                }
            }
        } catch (Throwable t) {
            log("[tracer] team scan failed: " + t);
        }
        return null;
    }

    static String fieldSummary(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    sb.append(f.getName()).append('(').append(f.getType().getSimpleName()).append(") ");
                }
            }
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }

    static void traceUnitInstances() {
        try {
            Class<?> am = Class.forName(CL_UNIT);
            Field rf = am.getField(UNIT_REGISTRY_FIELD);
            Object units = rf.get(null);
            if (!(units instanceof Collection)) return;
            Collection<?> collection = (Collection<?>) units;

            if (knownLiveUnits.isEmpty() && !collection.isEmpty()) {
                Map<String, Integer> classCounts = new TreeMap<String, Integer>();
                for (Object o : collection) {
                    knownLiveUnits.add(o);
                    if (localTeam != null) maybePatchLocalUnit(o);
                    String cls = (o == null ? "null" : o.getClass().getName());
                    Integer prev = classCounts.get(cls);
                    if (prev == null)
                        classCounts.put(cls, 1);
                    else
                        classCounts.put(cls, prev.intValue() + 1);
                }
                log("[tracer] live-unit init total=" + collection.size() + " classes=" + classCounts);
                return;
            }

            if (localTeam != null) {
                for (Object o : collection) {
                    if (o == null || patchedLiveUnits.contains(o)) continue;
                    if (!isOwnedByLocal(o)) {
                        patchedLiveUnits.add(o);
                        continue;
                    }
                    if (maybePatchLocalUnit(o)) patchedLiveUnits.add(o);
                    watchBuildUnit(o);
                }
            }
            if (localTeam != null) sweepBuildWatch();
            for (Object o : collection) {
                if (o == null) continue;
                finishLocalConstruction(o);
            }

            List<Object> added = new ArrayList<Object>();
            for (Object o : collection) {
                if (!knownLiveUnits.contains(o)) {
                    knownLiveUnits.add(o);
                    added.add(o);
                }
            }
            if (!added.isEmpty()) {
                for (Object o : added) {
                    String unitText = "null";
                    if (o != null) {
                        try {
                            unitText = String.valueOf(o);
                        } catch (Throwable t) {
                            unitText = "toStringError=" + t;
                        }
                    }
                    log("[live-add] class=" + (o == null ? "null" : o.getClass().getName()) + " unit=" + unitText);
                    if (o != null && isOwnedByLocal(o)) maybePatchLocalUnit(o);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    static boolean isOwnedByLocal(Object unit) {
        if (unit == null || localTeam == null) return false;
        try {
            Field owner = unit.getClass().getField(UNIT_OWNER_FIELD);
            Object ownerValue = owner.get(unit);
            return ownerValue != null && ownerValue.equals(localTeam);
        } catch (Throwable ignore) {
            return false;
        }
    }

    static boolean maybePatchLocalUnit(Object unit) {
        if (unit == null || localTeam == null || !isOwnedByLocal(unit)) return false;
        boolean done = refreshLocalBuild(unit);
        try {
            for (String typeName : LOCAL_UNIT_TYPE_FIELDS) {
                Field tf;
                try {
                    tf = unit.getClass().getField(typeName);
                } catch (Throwable t) {
                    continue;
                }
                Object originalType = tf.get(unit);
                if (originalType == null) continue;
                if (!CL_UNIT_TYPE.equals(originalType.getClass().getName())) continue;
                Object localClone = getLocalTypeClone(originalType);
                if (localClone != null && localClone != originalType) {
                    tf.set(unit, localClone);
                    log("[local-patch] patched unit type clone for " + DescribeUnit(unit));
                } else if (localClone == null) {
                    done = false;
                }
            }
        } catch (Throwable t) {
            log("[local-patch] failed: " + t);
            return false;
        }
        return done;
    }

    static Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    static void watchBuildUnit(Object unit) {
        if (unit == null) return;
        String clsName = unit.getClass().getName();
        if (clsName.startsWith(CL_UNITS_D)) {
            buildWatchUnits.add(unit);
            return;
        }
        if (CL_UNIT_CUSTOM.equals(clsName)) {
            buildWatchUnits.add(unit);
            return;
        }
        if (CL_UNITS_E_B.equals(clsName)) {
            buildWatchUnits.add(unit);
            return;
        }
    }

    static void sweepBuildWatch() {
        if (buildWatchUnits.isEmpty()) return;
        List<Object> dead = null;
        for (Object u : new ArrayList<Object>(buildWatchUnits)) {
            if (u == null) {
                if (dead == null) dead = new ArrayList<Object>();
                dead.add(u);
                continue;
            }
            try {
                Field deadFlag = findField(u.getClass(), "bV");
                if (deadFlag != null) {
                    deadFlag.setAccessible(true);
                    if (deadFlag.getBoolean(u)) {
                        if (dead == null) dead = new ArrayList<Object>();
                        dead.add(u);
                        continue;
                    }
                }
            } catch (Throwable ignore) {
            }
            if (!isOwnedByLocal(u)) continue;
            refreshLocalBuild(u);
        }
        if (dead != null)
            for (Object d : dead) buildWatchUnits.remove(d);
    }

    static boolean refreshLocalBuild(Object unit) {
        if (unit == null || localTeam == null || !isOwnedByLocal(unit)) return true;
        Class<?> cls = unit.getClass();
        String clsName = cls.getName();
        boolean isNativeFactory = clsName.startsWith(CL_UNITS_D);
        boolean isCustom = CL_UNIT_CUSTOM.equals(clsName);
        Object queue = null;
        try {
            // native factories -> units.d.i.z; custom -> custom.j.dL (both a d.k queue)
            Field qf = isNativeFactory ? findField(cls, "z") : (isCustom ? findField(cls, "dL") : null);
            if (qf != null) {
                qf.setAccessible(true);
                queue = qf.get(unit);
            }
        } catch (Throwable t) {
            log("[local-patch] factory queue lookup failed: " + t + " on " + clsName);
            return true;
        }
        if (queue != null) refreshFactoryQueue(unit, queue);
        finishLocalConstruction(unit);
        if (isNativeFactory) unlockNativeFactoryTier(unit, cls);
        return true;
    }

    static void refreshFactoryQueue(Object factory, Object queue) {
        try {
            Field cf = findField(queue.getClass(), "c");
            if (cf == null) {
                log("[local-patch] factory queue patch failed: java.lang.NoSuchFieldException: c on " + queue.getClass().getName());
                return;
            }
            cf.setAccessible(true);
            Object q = cf.get(queue);
            if (!(q instanceof Collection)) return;
            Collection<?> items = (Collection<?>) q;
            if (items.isEmpty()) return;
            int accelerated = 0;
            for (Object item : items) {
                if (item == null) continue;
                boolean touched = false;
                try {
                    // item.b is also read by the render path; huge values NPE there.
                    Field bf = item.getClass().getField("b");
                    float cur = bf.getFloat(item);
                    if (cur > 0.0f && cur < 100.0f) {
                        bf.setFloat(item, 100.0f);
                        touched = true;
                    }
                } catch (Throwable ignore) {
                }
                if (touched) accelerated++;
            }
            if (accelerated > 0) log("[local-patch] accelerated " + accelerated + " queued item(s) for " + DescribeUnit(factory));
        } catch (Throwable t) {
            log("[local-patch] factory queue patch failed: " + t);
        }
    }

    static void unlockNativeFactoryTier(Object unit, Class<?> cls) {
        try {
            if (CL_UNITS_D_M.equals(cls.getName())) {
                Field gf = findField(cls, "g");
                if (gf == null) return;
                gf.setAccessible(true);
                if (gf.getBoolean(unit)) return;
                gf.setBoolean(unit, true);
                cls.getMethod("S").invoke(unit);
                log("[local-patch] upgraded local factory " + DescribeUnit(unit));
            }
            // d.f must NOT be upgraded: with f=true its d() returns a never-initialised
            // image array, so S() leaves M null and the renderer NPEs.
            else if (CL_UNITS_D_A.equals(cls.getName())) {
                // the air factory: a(2) bumps tier f=1 -> 2
                Field ff = findField(cls, "f");
                if (ff == null) return;
                ff.setAccessible(true);
                if (ff.getType() == int.class&& ff.getInt(unit) >= 2) return;
                if (ff.getType() == float.class&& ff.getFloat(unit) > 0.0f) return;
                try {
                    cls.getMethod("a", int.class).invoke(unit, 2);
                    log("[local-patch] upgraded local factory " + DescribeUnit(unit));
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            log("[local-patch] factory upgrade failed: " + t);
        }
    }

    static boolean isBuildingUnit(Object unit) {
        try {
            Object type = unit.getClass().getMethod("r").invoke(unit);
            if (type == null) return false;
            Object res = type.getClass().getMethod("p").invoke(type);
            return (res instanceof Boolean) && ((Boolean) res).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    // cm < 1 is the engine's own "under construction" test.
    static void finishLocalConstruction(Object unit) {
        if (unit == null || localTeam == null) return;
        try {
            Field cmf = findField(unit.getClass(), "cm");
            if (cmf == null) return;
            cmf.setAccessible(true);
            float cm = cmf.getFloat(unit);
            if (!(cm > 0.0f && cm < 1.0f)) return;
            if (!isOwnedByLocal(unit)) return;
            // native buildings are units.d.*; custom ones report type.p()
            boolean building = unit.getClass().getName().startsWith(CL_UNITS_D)
                || isBuildingUnit(unit);
            if (!building) return;
            cmf.setFloat(unit, 1.0f);
            log("[local-patch] finished construction for " + DescribeUnit(unit));
        } catch (Throwable ignore) {
        }
    }

    static Object getLocalTypeClone(Object originalType) {
        if (originalType == null) return null;
        synchronized (localTypeClones) {
            Object clone = localTypeClones.get(originalType);
            if (clone != null) return clone;
            try {
                clone = shallowClone(originalType);
                cloneWeaponArray(clone, "fR");
                patchLocalType(clone);
                localTypeClones.put(originalType, clone);
                log("[local-patch] created local type clone: " + String.valueOf(clone));
                return clone;
            } catch (Throwable t) {
                log("[local-patch] clone failed: " + t);
                return null;
            }
        }
    }

    static void cloneWeaponArray(Object clonedType, String fieldName) {
        try {
            Field wf = clonedType.getClass().getField(fieldName);
            Object arr = wf.get(clonedType);
            if (!(arr instanceof Object[])) return;
            Object[] src = (Object[]) arr;
            Class<?> component = arr.getClass().getComponentType();
            if (component == null) component = Object.class;
            Object[] dst = (Object[]) java.lang.reflect.Array.newInstance(component, src.length);
            for (int i = 0; i < src.length; i++) {
                Object sourceWeapon = src[i];
                Object weaponClone = shallowClone(sourceWeapon);
                patchWeapon(weaponClone);
                dst[i] = weaponClone;
            }
            wf.set(clonedType, dst);
        } catch (Throwable ignore) {
        }
    }

    static Object getUnsafe() throws Exception {
        if (unsafe == null) {
            synchronized (rw.class) {
                if (unsafe == null) {
                    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                    Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                    theUnsafe.setAccessible(true);
                    unsafe = theUnsafe.get(null);
                }
            }
        }
        return unsafe;
    }

    static Object shallowClone(Object src) throws Exception {
        if (src == null) return null;
        Object u = getUnsafe();
        Class<?> unsafeClass = u.getClass();
        Object dst = unsafeClass.getMethod("allocateInstance", Class.class).invoke(u, src.getClass());
        for (Class<?> c = src.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    long off = ((Long) unsafeClass.getMethod("objectFieldOffset", Field.class).invoke(u, f)).longValue();
                    Class<?> t = f.getType();
                    if (t == int.class) {
                        int value = ((Integer) unsafeClass.getMethod("getInt", Object.class, long.class).invoke(u, src, off)).intValue();
                        unsafeClass.getMethod("putInt", Object.class, long.class, int.class).invoke(u, dst, off, value);
                    } else if (t == float.class) {
                        float value = ((Float) unsafeClass.getMethod("getFloat", Object.class, long.class).invoke(u, src, off)).floatValue();
                        unsafeClass.getMethod("putFloat", Object.class, long.class, float.class).invoke(u, dst, off, value);
                    } else if (t == long.class) {
                        long value = ((Long) unsafeClass.getMethod("getLong", Object.class, long.class).invoke(u, src, off)).longValue();
                        unsafeClass.getMethod("putLong", Object.class, long.class, long.class).invoke(u, dst, off, value);
                    } else if (t == double.class) {
                        double value = ((Double) unsafeClass.getMethod("getDouble", Object.class, long.class).invoke(u, src, off)).doubleValue();
                        unsafeClass.getMethod("putDouble", Object.class, long.class, double.class).invoke(u, dst, off, value);
                    } else if (t == boolean.class) {
                        boolean value = (Boolean) unsafeClass.getMethod("getBoolean", Object.class, long.class).invoke(u, src, off);
                        unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(u, dst, off, value);
                    } else if (t == byte.class) {
                        byte value = (Byte) unsafeClass.getMethod("getByte", Object.class, long.class).invoke(u, src, off);
                        unsafeClass.getMethod("putByte", Object.class, long.class, byte.class).invoke(u, dst, off, value);
                    } else if (t == short.class) {
                        short value = (Short) unsafeClass.getMethod("getShort", Object.class, long.class).invoke(u, src, off);
                        unsafeClass.getMethod("putShort", Object.class, long.class, short.class).invoke(u, dst, off, value);
                    } else if (t == char.class) {
                        char value = (Character) unsafeClass.getMethod("getChar", Object.class, long.class).invoke(u, src, off);
                        unsafeClass.getMethod("putChar", Object.class, long.class, char.class).invoke(u, dst, off, value);
                    } else {
                        Object value = unsafeClass.getMethod("getObject", Object.class, long.class).invoke(u, src, off);
                        unsafeClass.getMethod("putObject", Object.class, long.class, Object.class).invoke(u, dst, off, value);
                    }
                } catch (Throwable ignore) {
                }
            }
        }
        // clone the nested custom.as so nano edits do not leak to other units
        try {
            Field nestedField = dst.getClass().getField("cL");
            Object nested = nestedField.get(dst);
            if (nested != null) {
                Method clone = nested.getClass().getMethod("clone");
                clone.setAccessible(true);
                Object nestedClone = clone.invoke(nested);
                if (nestedClone != null) nestedField.set(dst, nestedClone);
            }
        } catch (Throwable ignore) {
        }
        return dst;
    }

    static String DescribeUnit(Object unit) {
        if (unit == null) return "null";
        try {
            return String.valueOf(unit);
        } catch (Throwable t) {
            return unit.getClass().getName();
        }
    }

    static void patchLocalType(Object u) {
        if (u == null) return;
        try {
            setFloat(u, "ck", 10.0f);
        } catch (Throwable ignore) {
        } // buildSpeed
        try {
            setFloat(u, "bf", 10.0f);
        } catch (Throwable ignore) {
        } // nanoBuildSpeed
        try {
            setFloat(u, "bh", 1.0f);
        } catch (Throwable ignore) {
        } // selfBuildRate
        try {
            setFloat(u, "be", 10.0f);
        } catch (Throwable ignore) {
        } // nanoUnbuildSpeed
        try {
            setFloat(u, "bb", 10.0f);
        } catch (Throwable ignore) {
        } // nanoReclaimSpeed
        try {
            setFloat(u, "cr", 1.0f);
        } catch (Throwable ignore) {
        } // generation_delay
        try {
            setFloat(u, "cs", 40.0f);
        } catch (Throwable ignore) {
        } // derived generation speed
        try {
            setFloatMin(u, "ca", 1.0f);
        } catch (Throwable ignore) {
        } // autoTriggerCooldownTime
        try {
            setFloatMin(u, "eN", 1.0f);
        } catch (Throwable ignore) {
        } // transportUnitsUnloadDelayBetweenEachUnit
        try {
            setFloatMin(u, "dM", 1.0f);
        } catch (Throwable ignore) {
        } // shootDelay
        try {
            setInt(u, "cl", 3);
        } catch (Throwable ignore) {
        } // techLevel
        try {
            setFloat(u, "cF", 5.0f);
        } catch (Throwable ignore) {
        } // resourceRate
        try {
            setNestedFloat(u, "cL.p", 1.0f);
        } catch (Throwable ignore) {
        } // selfRegenRate
        try {
            setNestedFloat(u, "cL.r", 10.0f);
        } catch (Throwable ignore) {
        } // nanoFactorySpeed
        try {
            setNestedFloat(u, "cL.h", 2.0f);
        } catch (Throwable ignore) {
        } // shieldRegen
        try {
            setNestedFloat(u, "cL.j", 20.0f);
        } catch (Throwable ignore) {
        } // moveSpeed
        try {
            setNestedFloat(u, "cL.k", 5.0f);
        } catch (Throwable ignore) {
        } // maxTurnSpeed
        try {
            setNestedFloat(u, "cL.e", 0.2f);
        } catch (Throwable ignore) {
        } // shootDelayMultiplier
        try {
            setNestedFloat(u, "cL.f", 5.0f);
        } catch (Throwable ignore) {
        } // shootDamageMultiplier
        try {
            setNestedFloat(u, "cL.l", 0.0f);
        } catch (Throwable ignore) {
        } // armour
        try {
            setNestedInt(u, "cL.c", 999999);
        } catch (Throwable ignore) {
        } // maxHp
        try {
            setNestedInt(u, "cL.g", 99999);
        } catch (Throwable ignore) {
        } // maxShield
    }

    static void patchWeapon(Object w) {
        if (w == null) return;
        try {
            setIntMul(w, "b", 5);
        } catch (Throwable ignore) {
        } // directDamage
        try {
            setIntMul(w, "c", 5);
        } catch (Throwable ignore) {
        } // areaDamage
        try {
            setFloat(w, "w", 3.0f);
        } catch (Throwable ignore) {
        } // projectileSpeed
        try {
            setFloat(w, "u", 0.0f);
        } catch (Throwable ignore) {
        } // delayedStartTimer
    }

    static Object resolveParent(Object root, String path) throws Exception {
        Object cur = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            Field f = cur.getClass().getField(parts[i]);
            cur = f.get(cur);
            if (cur == null) throw new IllegalStateException("null intermediate field: " + parts[i]);
        }
        return cur;
    }

    static String lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    static void setNestedFloat(Object root, String path, float v) throws Exception {
        Object parent = resolveParent(root, path);
        parent.getClass().getField(lastSegment(path)).setFloat(parent, v);
    }

    static void setNestedInt(Object root, String path, int v) throws Exception {
        Object parent = resolveParent(root, path);
        parent.getClass().getField(lastSegment(path)).setInt(parent, v);
    }

    static void setFloat(Object o, String name, float v) throws Exception {
        Field f = o.getClass().getField(name);
        f.setFloat(o, v);
    }

    static void setFloatMin(Object o, String name, float v) throws Exception {
        Field f = o.getClass().getField(name);
        if (f.getFloat(o) > v) f.setFloat(o, v);
    }

    static void setInt(Object o, String name, int v) throws Exception {
        Field f = o.getClass().getField(name);
        f.setInt(o, v);
    }

    static void setIntMin(Object o, String name, int v) throws Exception {
        Field f = o.getClass().getField(name);
        if (f.getInt(o) > v) f.setInt(o, v);
    }

    static void setIntMul(Object o, String name, int mul) throws Exception {
        Field f = o.getClass().getField(name);
        int cur = f.getInt(o);
        if (cur > 0) f.setInt(o, cur * mul);
    }

    static void addCredits(int n) {}

    static final String GAME_MAIN = PKG + ".java.Main";
    static final int WAIT_DOTS = 30;
    static final Set<String> reportedFailures = new HashSet<String>();
    static final List<String> blockedPids = new ArrayList<String>();

    /** Wipe the in-place waiting line so the next message starts on a line of its own. */
    static void clearLine() {
        StringBuilder pad = new StringBuilder("\r");
        for (int i = 0; i < WAIT_DOTS + 40; i++) {
            pad.append(' ');
        }
        System.out.print(pad.append('\r'));
    }

    /**
     * Attaches this agent to a running game JVM, or with --launch &lt;game folder&gt; starts the
     * game from that folder with the agent on its command line.
     *
     * <p>Run as: java -cp rw.jar rw [--wait [seconds]] | rw --launch &lt;game folder&gt;
     *
     * <p>A single attempt is made unless --wait is given, which polls once a second until the
     * game shows up; --wait N gives up after N seconds. Attaching needs no game folder, the
     * game is found through the attach API, which is reached by reflection so that this side
     * of the class adds no linkage requirement to the agent.
     */
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--launch")) {
            launchGame(args.length > 1 ? args[1] : null);
            return;
        }
        boolean wait = false;
        int waitSeconds = 0;
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals("--wait")) continue;
            wait = true;
            if (i + 1 < args.length && args[i + 1].matches("\\d+")) {
                waitSeconds = Integer.parseInt(args[++i]);
            }
        }
        try {
            File jar = selfJar();
            if (jar == null) {
                System.out.println("[rw] cannot locate rw.jar, run: java -cp rw.jar rw");
                System.exit(2);
            }
            long deadline = waitSeconds > 0 ? System.currentTimeMillis() + waitSeconds * 1000L : Long.MAX_VALUE;
            int ticks = 0;
            while (true) {
                int injected = tryInject(jar);
                if (injected > 0) {
                    System.out.println();
                    System.exit(0);
                }
                if (!blockedPids.isEmpty()) {
                    closeBlocked();
                    System.exit(3);
                }
                if (!wait) {
                    System.out.println("[rw] the game is not running");
                    System.exit(1);
                }
                if (System.currentTimeMillis() >= deadline) {
                    clearLine();
                    System.out.println("[rw] no running game after " + waitSeconds + "s");
                    System.exit(1);
                }
                // redraw one line in place so a long wait never wraps or scrolls
                int dots = ticks++ % WAIT_DOTS + 1;
                StringBuilder line = new StringBuilder("\r[rw] waiting for the game");
                for (int i = 0; i < WAIT_DOTS; i++) {
                    line.append(i < dots ? '.' : ' ');
                }
                System.out.print(line);
                System.out.flush();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                }
            }
        } catch (Throwable t) {
            System.out.println("[rw] error: " + rootCause(t));
            System.exit(2);
        }
    }

    /** Starts the game with this agent on its command line; blocks until it exits. */
    static void launchGame(String gameDir) {
        File dir = gameDir == null ? null : new File(gameDir);
        if (dir == null || !dir.isDirectory()) {
            System.out.println("[rw] cannot find the game folder, set GAME_DIR to it and run this again");
            System.exit(2);
        }
        File jvm = new File(dir, "jvm64/bin/java.exe");
        if (!jvm.isFile()) jvm = new File(dir, "jvm/bin/java.exe");
        if (!jvm.isFile()) {
            System.out.println("[rw] no JVM inside " + dir);
            System.exit(2);
        }
        File jar = selfJar();
        if (jar == null) {
            System.out.println("[rw] cannot locate rw.jar, run: java -cp rw.jar rw");
            System.exit(2);
        }
        System.out.println("[rw] starting the game with the agent loaded...");
        List<String> cmd = new ArrayList<String>();
        cmd.add(jvm.getAbsolutePath());
        cmd.add("-javaagent:" + jar.getAbsolutePath());
        cmd.add("-Xmx1000M");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Djava.library.path=.");
        cmd.add("-cp");
        cmd.add("game-lib.jar;libs/*");
        cmd.add(GAME_MAIN);
        try {
            System.exit(new ProcessBuilder(cmd).directory(dir).inheritIO().start().waitFor());
        } catch (Throwable t) {
            System.out.println("[rw] could not start the game: " + rootCause(t));
            System.exit(2);
        }
    }

    /**
     * One attach attempt. Returns the number of games injected, 0 when no game is running,
     * or -1 when a game was found but could not be attached (32-bit build, for example),
     * which tells the caller to launch the game with -javaagent instead.
     */
    static int tryInject(File jar) {
        int found = 0;
        int injected = 0;
        blockedPids.clear();
        try {
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            List<?> descriptors = (List<?>) vmClass.getMethod("list").invoke(null);
            for (Object descriptor : descriptors) {
                Class<?> dc = descriptor.getClass();
                String id = String.valueOf(dc.getMethod("id").invoke(descriptor));
                String name = String.valueOf(dc.getMethod("displayName").invoke(descriptor));
                if (!name.contains(GAME_MAIN)) continue;
                found++;
                Object vm = null;
                try {
                    vm = vmClass.getMethod("attach", String.class).invoke(null, id);
                    vmClass.getMethod("loadAgent", String.class).invoke(vm, jar.getAbsolutePath());
                    injected++;
                    clearLine();
                    System.out.println("[rw] agent loaded into game pid " + id);
                } catch (Throwable t) {
                    blockedPids.add(id);
                    reportOnce(id + "|" + rootCause(t).getClass().getName(),
                            "[rw] cannot attach to pid " + id + ": " + rootCause(t));
                } finally {
                    if (vm != null) {
                        try {
                            vmClass.getMethod("detach").invoke(vm);
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            reportOnce("api|" + rootCause(t).getClass().getName(),
                    "[rw] attach api unavailable: " + rootCause(t));
            return -1;
        }
        if (found > 0 && injected == 0) return -1;
        return injected;
    }

    /** Print a failure once, so a long wait does not repeat the same line every second. */
    static void reportOnce(String key, String message) {
        if (!reportedFailures.add(key)) return;
        clearLine();
        System.out.println(message);
    }

    /** Closes a game that cannot be attached so the caller can start it again with the agent. */
    static void closeBlocked() {
        for (String pid : blockedPids) {
            clearLine();
            System.out.println("[rw] closing pid " + pid + " so it can be restarted with the agent");
            try {
                new ProcessBuilder("taskkill", "/F", "/PID", pid).redirectErrorStream(true).start().waitFor();
            } catch (Throwable t) {
                clearLine();
                System.out.println("[rw] could not close pid " + pid + ": " + rootCause(t));
            }
        }
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignore) {
        }
    }

    static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    static File selfJar() {
        try {
            File f = new File(rw.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f : null;
        } catch (Throwable ignore) {
            return null;
        }
    }
}
