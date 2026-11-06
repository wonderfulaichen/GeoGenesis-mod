package com.geogenesis.worldgen.process;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;

/** Probe-only javaagent: exact shallow sizes plus retained component graph on this JVM. */
public final class BInputBlockCacheMemoryProbe {
    private static Instrumentation instrumentation;
    public static void premain(String args, Instrumentation agent) { instrumentation = agent; }
    private static long retained(Object root) throws Exception {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var pending = new ArrayDeque<Object>(); pending.add(root); long bytes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.remove();
            if (!seen.add(value)) continue;
            bytes += instrumentation.getObjectSize(value);
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (Object element : (Object[]) value) if (element != null) pending.add(element);
                }
            } else for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true); Object child = field.get(value);
                    if (child != null) pending.add(child);
                }
            }
        }
        return bytes;
    }
    public static void main(String[] args) throws Exception {
        if (instrumentation == null) throw new AssertionError("requires probe-only javaagent");
        for (int capacity : new int[]{1,2,4,4096}) {
            var cache = new BInputBlockCache(capacity);
            long empty = retained(cache);
            for (int i = 0; i < capacity; i++) {
                cache.copy(i * 32, 0, 1, 1, (x,z) -> .25, (x,z,v) -> {}, () -> false);
            }
            long full = retained(cache);
            if (full > 36L * 1024 * 1024) throw new AssertionError("component exceeds 36MiB");
            cache.close(); long closed = retained(cache);
            if (closed != empty) throw new AssertionError("closed payload retained");
            System.out.printf("capacity=%d emptyBytes=%d fullRetainedBytes=%d closedBytes=%d payloadBytes=%d%n",
                    capacity, empty, full, closed, capacity * 8320L);
        }
        System.out.println("B INPUT MEMORY PASS; component only, not total heap; no queued waiters in this layout sample");
    }
}
