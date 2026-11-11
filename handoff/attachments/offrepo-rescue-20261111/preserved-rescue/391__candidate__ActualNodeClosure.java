package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.*;
import java.util.*;

/** Audit of explicitly selected live roots and their complete RefNoise child graphs. */
public final class ActualNodeClosure {
    static final Map<RefNoise.Node,String> roots = new IdentityHashMap<>();
    static final Set<Object> visitedEntries = Collections.newSetFromMap(new IdentityHashMap<>());
    static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        Object value = f.get(owner);
        if (value == null) throw new IllegalStateException("null required field " + name);
        return value;
    }
    static void add(Object obj, String path) {
        if (!(obj instanceof RefNoise.Node node)) throw new IllegalStateException("not a node: " + path);
        roots.putIfAbsent(node, path);
        System.out.println("ROOT " + path + " " + node.getClass().getSimpleName());
    }
    static void entry(RecipeMorphCatalog.Entry e, String path) throws Exception {
        if (!visitedEntries.add(e)) return;
        for (Field f : e.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            if (RefNoise.Node.class.isAssignableFrom(f.getType())) {
                Object v = f.get(e);
                if (v != null) add(v, path + "." + f.getName());
            } else if (f.getType() == RecipeMorphCatalog.Entry.class) {
                Object v = f.get(e);
                if (v != null) entry((RecipeMorphCatalog.Entry)v, path + "." + f.getName());
            }
        }
    }
    static void objectNodeFields(Object o, String path) throws Exception {
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !RefNoise.Node.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object v = f.get(o);
            if (v != null) add(v, path + "." + f.getName());
        }
    }
    static Object coverage(Object family) throws Exception {
        if (family.getClass().isSynthetic()) {
            Field[] fields = family.getClass().getDeclaredFields();
            if (fields.length != 1) throw new IllegalStateException("unexpected coverage capture");
            fields[0].setAccessible(true);
            family = fields[0].get(family);
        }
        if (!(family instanceof PlateauCandidate)) throw new IllegalStateException("unexpected coverage " + family);
        return family;
    }
    static void children(RefNoise.Node n, ArrayDeque<RefNoise.Node> q) throws Exception {
        for (Class<?> c = n.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                if (n instanceof DualCoordinateCall.DualNode && f.getName().equals("legacy")) continue;
                f.setAccessible(true);
                Object v = f.get(n);
                if (v instanceof RefNoise.Node child) q.add(child);
                else if (v instanceof RefNoise.Node[] array) for (RefNoise.Node child : array) if (child != null) q.add(child);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        long seed = RecipeMorphCatalog.GALLERY_SEED;
        RecipeMorphCatalog catalog = new RecipeMorphCatalog(seed);
        DoubleGalleryCandidate gallery = new DoubleGalleryCandidate(catalog, (x,z) -> 0, seed);
        Object engine = field(gallery.land, "engine");
        objectNodeFields(engine, "engine");
        MorphRecipe[] recipes = (MorphRecipe[])field(engine, "recipes");
        for (int i=0; i<recipes.length; i++) {
            Object recipe = recipes[i];
            if (!(recipe instanceof DoubleLandRecipe)) throw new IllegalStateException("unexpected recipe " + recipe);
            entry((RecipeMorphCatalog.Entry)field(recipe, "entry"), "engine.recipes[" + i + "].entry");
        }
        add(field(gallery.land, "maskNode"), "land.maskNode");
        Object old = coverage(field(gallery.land, "oldCoverageFamily"));
        add(field(old, "height"), "land.oldCoverageFamily.height");
        objectNodeFields(gallery.ocean, "ocean");
        if (args.length > 0 && args[0].equals("negative")) {
            RefNoise.Node unknown = (x,z) -> x + 1;
            add(new RefNoise.Terrace(unknown, .9f,.15f,.35f,.4f,4,0,.9f,false), "negative.terrace");
        }
        Set<RefNoise.Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<RefNoise.Node> queue = new ArrayDeque<>(roots.keySet());
        Map<String,Integer> counts = new TreeMap<>(); int failed = 0;
        double[][] points = {{.375, .125}, {130.25,17.75}, {1875000.5,1875000.5}, {-7654321.5,12345.25}};
        while (!queue.isEmpty()) {
            RefNoise.Node n = queue.remove();
            if (!seen.add(n)) continue;
            counts.merge(n.getClass().getSimpleName(),1,Integer::sum);
            for (double[] p : points) {
                try {
                    double v = DoubleTreeFixed.eval(n,p[0],p[1]);
                    if (!Double.isFinite(v)) throw new IllegalStateException("nonfinite " + v);
                } catch (RuntimeException ex) {
                    System.out.println("FAIL " + n.getClass().getName() + " root=" + roots.get(n) + " " + ex);
                    failed++; break;
                }
            }
            children(n,queue);
        }
        counts.forEach((k,v)->System.out.println("TYPE " + k + " " + v));
        System.out.println("SUMMARY roots=" + roots.size() + " nodes=" + seen.size() + " failures=" + failed);
        if (failed != 0) System.exit(2);
    }
}
