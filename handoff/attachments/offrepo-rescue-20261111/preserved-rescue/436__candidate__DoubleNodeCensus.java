package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enumerates every RefNoise.Node reachable from the 18 land entries and the two coverage nodes,
 * then evaluates each distinct instance through DoubleTreeFixed.eval. Reports one line per node type.
 * A node type is reported UNSUPPORTED only when eval actually throws for it.
 */
public final class DoubleNodeCensus {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        RecipeMorphCatalog cat = new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED);
        List<RefNoise.Node> roots = new ArrayList<>();
        for (RecipeMorphCatalog.Entry e : cat.entries()) {
            if (e.rawNode() != null) roots.add(e.rawNode());
            if (e.selector() != null) roots.add(e.selector());
            if (e.surface() != null) roots.add(e.surface());
            if (e.terrace() != null) roots.add(e.terrace());
            if (e.direction() != null) roots.add(e.direction());
            if (e.warpX() != null) roots.add(e.warpX());
            if (e.warpZ() != null) roots.add(e.warpZ());
            if (e.scaler() != null) roots.add(e.scaler());
        }
        // Engine-internal nodes (terrace / steps / warps) are private to DoubleMorphEngine; add them
        // by rebuilding the same objects the engine would create.
        roots.add(new RefNoise.Terrace(new DualCoordinateCall.DualNode((v, z) -> v, (v, z) -> v),
                .9f, .15f, .35f, .4f, 4, 0, .75f * .6f + .3f + .15f, false));
        roots.add(new RefNoise.Steps(new DualCoordinateCall.DualNode((v, z) -> v, (v, z) -> v),
                4, .6f, .7f, false));
        roots.add(new RefNoise.Steps(new DualCoordinateCall.DualNode((v, z) -> v, (v, z) -> v),
                10, .6f, .7f, false));
        roots.add(new RefNoise.Steps(new DualCoordinateCall.DualNode((v, z) -> v, (v, z) -> v),
                4, .65f, .75f, true));

        if (args.length > 0 && args[0].equals("negative-lambda")) {
            RefNoise.Node unknown = (v, z) -> v + 1f;
            roots.add(unknown);
            roots.add(new RefNoise.Terrace(unknown, .9f, .15f, .35f, .4f, 4, 0, .9f, false));
        }
        Map<String, String> verdicts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<RefNoise.Node> queue = new ArrayDeque<>(roots);
        double[][] points = {{130.25, 17.75}, {1_875_000.5, 1_875_000.5}, {-7_654_321.5, 12_345.25}};
        while (!queue.isEmpty()) {
            RefNoise.Node n = queue.poll();
            if (n == null || !seen.add(n)) continue;
            String type = n.getClass().getSimpleName();
            counts.merge(type, 1, Integer::sum);
            {
                String verdict = "OK";
                for (double[] p : points) {
                    try {
                        double v = DoubleTreeFixed.eval(n, p[0], p[1]);
                        if (!Double.isFinite(v)) verdict = "NON-FINITE at " + p[0] + "," + p[1];
                    } catch (RuntimeException ex) {
                        verdict = "UNSUPPORTED " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                        break;
                    }
                }
                if (!verdict.equals("OK") || !verdicts.containsKey(type)) verdicts.put(type, verdict);
            }
            enqueueChildren(n, queue, seen);
        }
        int unsupported = 0;
        System.out.println("node type                        instances  verdict");
        for (Map.Entry<String, String> e : verdicts.entrySet()) {
            if (!e.getValue().equals("OK")) unsupported++;
            System.out.printf(Locale.ROOT, "%-32s %9d  %s%n", e.getKey(), counts.get(e.getKey()), e.getValue());
        }
        System.out.println("distinctTypes=" + verdicts.size() + " unsupportedTypes=" + unsupported);
        System.exit(unsupported == 0 ? 0 : 2);
    }

    /** Pushes every RefNoise.Node reachable through declared fields (records included). */
    private static void enqueueChildren(Object node, ArrayDeque<RefNoise.Node> queue, Set<Object> seen) {
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                // DualNode.legacy is the float-only tail of an already-declared pair; the double
                // path never calls it, so enqueueing it would report a false UNSUPPORTED.
                if (node instanceof DualCoordinateCall.DualNode && "legacy".equals(f.getName())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(node);
                    if (v instanceof RefNoise.Node child) queue.add(child);
                    else if (v instanceof RefNoise.Node[] arr) Collections.addAll(queue, arr);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    throw new IllegalStateException("Cannot enumerate " + f, ignored);
                }
            }
        }
    }

    private DoubleNodeCensus() { }
}
