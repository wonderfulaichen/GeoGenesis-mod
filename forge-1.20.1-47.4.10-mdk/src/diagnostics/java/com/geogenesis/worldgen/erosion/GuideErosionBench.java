package com.geogenesis.worldgen.erosion;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Same-process timing/raster runner for the full engine and its reduced-droplet copies.
 *
 * <p>Why reflect over a copy: {@code runErosionOnFlat} reads droplet density from private
 * constants, so "reduce the particle count" cannot be expressed through the public API.
 * A byte-for-byte copy with only DROPS_* changed is the honest way to measure real savings.</p>
 *
 * <p>Sizing/origin/hs are explicit. One raster cell is one engine unit; there is no step
 * parameter in the kernel, so this is a fixed-raster comparison, not production equivalence.</p>
 */
public final class GuideErosionBench {

    /** Child-first on one class name only; config/Forge/log4j stay on the parent loader. */
    static final class Loader extends URLClassLoader {
        private final String prefix;
        Loader(URL url, ClassLoader parent, String prefix) {
            super(new URL[]{url}, parent);
            this.prefix = prefix;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(prefix)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try { c = findClass(name); }
                    catch (ClassNotFoundException e) { c = super.loadClass(name, false); }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    /** One loaded engine recipe (class + its own dir/loader). */
    static final class Engine {
        final Class<?> type;
        final Constructor<?> ctor;
        final Method run;
        final Loader loader;
        Engine(Path dir, String className) throws Exception {
            loader = new Loader(dir.toUri().toURL(), GuideErosionBench.class.getClassLoader(), className);
            type = Class.forName(className, true, loader);
            Constructor<?> selected;
            try { selected = type.getConstructor(boolean.class, java.util.function.Supplier.class, int.class); }
            catch (NoSuchMethodException legacy) {
                selected = type.getConstructor(double.class, int.class, boolean.class);
            }
            ctor = selected;
            run = type.getMethod("runErosionOnFlat", float[].class, float[].class, int.class, int.class,
                    int.class, int.class, float.class, float.class, float[].class, float.class);
            if (type.getClassLoader().loadClass(GeoGenesisConfig.class.getName()) != GeoGenesisConfig.class)
                throw new IllegalStateException("config must come from the parent loader");
        }
        Object instance() {
            try {
                if (ctor.getParameterTypes()[0] == boolean.class) {
                    return ctor.newInstance(false,
                            (java.util.function.Supplier<ParticleErosionParams>)
                                    com.geogenesis.config.ParticleErosionConfigAdapter::snapshot,
                            8);
                }
                return ctor.newInstance(1.0d, 12345, false);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    private static void run(String[] a) throws Exception {
        if (a.length != 9) throw new IllegalArgumentException(
                "<toml> <size> <originX> <originZ> <hs> <reps> <warmup> <specFile> <outDir>");
        Path toml = Path.of(a[0]);
        int size = Integer.parseInt(a[1]);
        int ox = Integer.parseInt(a[2]), oz = Integer.parseInt(a[3]);
        float hs = Float.parseFloat(a[4]);
        int reps = Integer.parseInt(a[5]), warm = Integer.parseInt(a[6]);
        Path spec = Path.of(a[7]), outDir = Path.of(a[8]);
        int n = size + 18;

        String configSha = sha(toml);
        try (CommentedFileConfig cfg = CommentedFileConfig.of(toml, TomlFormat.instance())) {
            cfg.load();
            boolean corrected = !GeoGenesisConfig.SPEC.isCorrect(cfg);
            GeoGenesisConfig.SPEC.setConfig(cfg);
            if (!GeoGenesisConfig.SPEC.isLoaded()) throw new IllegalStateException("config not loaded");
            GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
            System.out.println("config|dropsMul=" + c.erosionDropsMul.get() + "|erodeMul=" + c.erosionErodeMul.get()
                    + "|cascade=" + c.erosionCascadeStrength.get() + "|momentum=" + c.erosionMomentumTransfer.get()
                    + "|iterations=" + c.erosionIterations.get() + "|lrate=" + c.erosionLrate.get()
                    + "|xs=" + c.erosionXSEnabled.get() + "|corrected=" + corrected
                    + "|fingerprint=" + GeoGenesisConfig.configFingerprint() + "|tomlSha=" + configSha);
            Map<String, Engine> cache = new LinkedHashMap<>();
            for (String raw : Files.readAllLines(spec, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\|");
                String name = f[0], engineDir = f[1], className = f[2], input = f[3], out = f[4];
                Engine engine = cache.computeIfAbsent(engineDir + "|" + className, k -> {
                    try { return new Engine(Path.of(engineDir), className); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                });
                float[] template = padded(Path.of(input), size, n);
                List<Double> seconds = new ArrayList<>();
                float[] kept = null;
                float[] dischargeKept = null;
                String firstSha = null;
                for (int i = 0; i < warm + reps; i++) {
                    float[] flat = template.clone();
                    float[] discharge = new float[n * n];
                    Object eng = engine.instance();
                    long t0 = System.nanoTime();
                    engine.run.invoke(eng, flat, template.clone(), n, size, ox, oz, 0f, 1f, discharge, hs);
                    double dt = (System.nanoTime() - t0) / 1e9;
                    String s = shaCenter(flat, size, n);
                    if (firstSha == null) {
                        firstSha = s; kept = flat; dischargeKept = discharge;
                    } else if (!firstSha.equals(s)) {
                        throw new IllegalStateException("nondeterministic output across reps: " + name);
                    }
                    if (i >= warm) seconds.add(dt);
                    System.out.println("rep|" + name + "|" + i + "|" + String.format(Locale.ROOT, "%.6f", dt));
                }
                Collections.sort(seconds);
                double median = seconds.get(seconds.size() / 2);
                writeCenter(outDir.resolve(out + ".f64be"), kept, size, n);
                writeCenter(outDir.resolve(out + ".discharge.f64be"), dischargeKept, size, n);
                System.out.println("arm|" + name + "|" + engine.type.getName() + "|" + engineDir
                        + "|size=" + size + "|origin=" + ox + "," + oz + "|hs=" + hs
                        + "|warm=" + warm + "|reps=" + reps
                        + "|medianSec=" + String.format(Locale.ROOT, "%.6f", median)
                        + "|allSec=" + seconds
                        + "|inputSha=" + sha(Path.of(input))
                        + "|outputSha=" + firstSha
                        + "|deterministicAcrossReps=true"
                        + "|pod=one JVM, cell=one engine unit, pad9 clamped, no hardness, not production");
            }
            System.out.println("DONE");
        }
    }

    private static float[] padded(Path input, int size, int n) throws IOException {
        if (Files.size(input) != 8L * size * size) throw new IOException("bad raster size: " + input);
        float[] center = new float[size * size];
        float[] flat = new float[n * n];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(input)))) {
            for (int i = 0; i < center.length; i++) {
                center[i] = (float) in.readDouble();
                if (!Float.isFinite(center[i])) throw new IOException("non-finite input");
            }
        }
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++)
            flat[z * n + x] = center[Math.max(0, Math.min(size - 1, z - 9)) * size
                    + Math.max(0, Math.min(size - 1, x - 9))];
        return flat;
    }

    private static void writeCenter(Path file, float[] data, int size, int n) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)))) {
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                float v = data[(z + 9) * n + x + 9];
                if (!Float.isFinite(v)) throw new IOException("non-finite output");
                out.writeDouble(v);
            }
        }
    }

    private static String shaCenter(float[] data, int size, int n) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int bits = Float.floatToRawIntBits(data[(z + 9) * n + x + 9]);
            md.update((byte) (bits >>> 24)); md.update((byte) (bits >>> 16));
            md.update((byte) (bits >>> 8)); md.update((byte) bits);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String sha(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            for (int r; (r = in.read(buf)) > 0; ) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}