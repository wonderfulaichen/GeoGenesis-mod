package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Contract test for the single property that licenses reusing a plain {@link WeakHashMap} as an
 * identity-keyed cache: <b>{@code RefNoise.Perlin} must not override {@code equals} or
 * {@code hashCode}</b>.
 *
 * <p>If a future edit adds either, parameter-equal {@code Perlin} instances would begin sharing one
 * cache entry. That would not crash — it would silently return a wrapper built from a different
 * instance with the same parameters, which happens to be numerically identical today but stops
 * being identity-keyed. So this must fail loudly rather than be inferred from a comment.</p>
 *
 * <p>Every assertion has a negative control: the same probe machinery is pointed at a type that
 * <i>does</i> override {@code equals}, proving the detector actually bites.</p>
 */
public final class ConcPerlinIdentityContract {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);

        // ---- 1) Perlin must not declare equals/hashCode anywhere in its hierarchy above Object
        check("no-equals-override", !declaresAboveObject(RefNoise.Perlin.class, "equals"),
                declaredOn(RefNoise.Perlin.class, "equals"));
        check("no-hashcode-override", !declaresAboveObject(RefNoise.Perlin.class, "hashCode"),
                declaredOn(RefNoise.Perlin.class, "hashCode"));

        // ---- 2) class is final, so it cannot be subclassed into overriding them later
        check("perlin-is-final", Modifier.isFinal(RefNoise.Perlin.class.getModifiers()),
                "modifiers=" + Modifier.toString(RefNoise.Perlin.class.getModifiers()));

        // ---- 3) behaviour, not just declarations: equal parameters must stay unequal as keys
        RefNoise.Perlin a = RefNoise.Perlin.ofScale(4242, 200f, 2);
        RefNoise.Perlin b = RefNoise.Perlin.ofScale(4242, 200f, 2);
        check("equalParamsNotEqual", !a.equals(b) && a != b,
                "equals=" + a.equals(b));
        check("hashesDiffer", a.hashCode() != b.hashCode(),
                "hashA=" + a.hashCode() + " hashB=" + b.hashCode());

        // ---- 4) the property the cache actually relies on: two entries, not one
        Map<RefNoise.Perlin, String> weak = new WeakHashMap<>();
        weak.put(a, "A");
        weak.put(b, "B");
        check("weakhashmap-keeps-distinct", weak.size() == 2,
                "size=" + weak.size() + " (1 would mean parameter-equal keys collided)");

        // ---- 5) identity round-trip through the real cache
        PerlinScalarCache cache = new PerlinScalarCache();
        DoublePerlinCandidate wa = cache.get(a);
        DoublePerlinCandidate wb = cache.get(b);
        DoublePerlinCandidate wa2 = cache.get(a);
        check("cache-key-identity", wa != wb && wa == wa2 && cache.liveEntries() == 2,
                "distinct=" + (wa != wb) + " stable=" + (wa == wa2)
                        + " live=" + cache.liveEntries());
        // ...and parameter-equal instances must still compute identical numbers.
        int same = 0;
        for (int i = 0; i < 32; i++) {
            double x = 71.5 + i * 13.25, z = -900.5 + i * 7.75;
            if (Double.doubleToRawLongBits(wa.compute(x, z))
                    == Double.doubleToRawLongBits(wb.compute(x, z))) same++;
        }
        check("equal-params-same-numbers", same == 32, "agreeingSamples=" + same + "/32");

        // ---- negative controls: the detector must catch a type that DOES override equals
        check("control-detector-bites",
                declaresAboveObject(OverridingKey.class, "equals")
                        && declaresAboveObject(OverridingKey.class, "hashCode"),
                declaredOn(OverridingKey.class, "equals"));
        Map<Object, String> collision = new WeakHashMap<>();
        collision.put(new OverridingKey(7), "A");
        collision.put(new OverridingKey(7), "B");
        check("control-equals-collision-detected", collision.size() == 1,
                "size=" + collision.size() + " (1 proves equals-equal keys collapse; "
                        + "Perlin must never behave this way)");

        System.out.println("VERDICT " + (failures == 0 ? "PASS" : "FAIL") + " failures=" + failures);
        if (failures != 0) System.exit(2);
    }

    /** True when {@code type} or any superclass below Object declares the method. */
    static boolean declaresAboveObject(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return true;
            }
        }
        return false;
    }

    static String declaredOn(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return name + " declared on " + c.getSimpleName();
            }
        }
        return name + " inherited from Object";
    }

    /** Negative-control key that deliberately overrides equals/hashCode. */
    static final class OverridingKey {
        final int v;
        OverridingKey(int v) { this.v = v; }
        @Override public boolean equals(Object o) { return o instanceof OverridingKey k && k.v == v; }
        @Override public int hashCode() { return v; }
    }

    private ConcPerlinIdentityContract() { }
}
