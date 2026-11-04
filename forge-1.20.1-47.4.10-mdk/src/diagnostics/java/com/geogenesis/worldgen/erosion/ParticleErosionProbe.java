package com.geogenesis.worldgen.erosion;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Offline raster adapter; deliberately does not alter the production erosion kernel. */
public final class ParticleErosionProbe {
    private static final Path ROOT = Path.of("build/particle-preeorder-20261104").toAbsolutePath().normalize();

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    private static void run(String[] a) throws Exception {
        if (a.length != 8) throw new IllegalArgumentException(
                "<inHeight.f64be> <outHeight.f64be> <size> <originX> <originZ> <stepWu> <hs> <tomlCopy>");
        Path input = Path.of(a[0]), output = safe(Path.of(a[1])), toml = safe(Path.of(a[7]));
        int size = Integer.parseInt(a[2]), n = size + 18;
        double ox = Double.parseDouble(a[3]), oz = Double.parseDouble(a[4]);
        double step = Double.parseDouble(a[5]);
        float hs = Float.parseFloat(a[6]);
        if (size < 16 || !(step > 0) || hs != 2f || Files.size(input) != 8L * size * size)
            throw new IllegalArgumentException("Invalid raster/step; experiment requires hs=2");
        String sha = ErosionScratchProbe.sha256File(toml);
        Path run = Files.createTempDirectory(output.getParent(), "engine-run-");
        Path effective = run.resolve("config-effective.toml");
        Files.copy(toml, effective);
        try (CommentedFileConfig config = CommentedFileConfig.of(effective, TomlFormat.instance())) {
            config.load();
            boolean corrected = !GeoGenesisConfig.SPEC.isCorrect(config);
            GeoGenesisConfig.SPEC.setConfig(config);
            if (!GeoGenesisConfig.SPEC.isLoaded()) throw new IllegalStateException("Config not loaded");
            GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
            String settings = "dropsMul=" + c.erosionDropsMul.get() + " erodeMul=" + c.erosionErodeMul.get()
                    + " cascadeStrength=" + c.erosionCascadeStrength.get()
                    + " momentumTransfer=" + c.erosionMomentumTransfer.get()
                    + " iterations=" + c.erosionIterations.get() + " lrate=" + c.erosionLrate.get()
                    + " xs=" + c.erosionXSEnabled.get();
            config.save();
            Files.writeString(run.resolve("config-provenance.txt"), settings + "\nloaded=true\ncorrectedByForge="
                    + corrected + "\nfingerprint=" + GeoGenesisConfig.configFingerprint() + "\nsha256=" + sha);
            float[] flat = padded(input, size, n);
            Path classes = ROOT.resolve("engine-classes");
            String error = ErosionScratchProbe.Compile.engine(Path.of(
                    "src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java"), classes);
            if (error != null) throw new IllegalStateException(error);
            // The driver independently compiles the current engine once, using Compile.engine conventions.
            var first = ErosionScratchProbe.EngineRef.load(classes);
            var second = ErosionScratchProbe.EngineRef.load(classes);
            try (var l1 = (java.net.URLClassLoader) first.type.getClassLoader();
                 var l2 = (java.net.URLClassLoader) second.type.getClassLoader()) {
                if (first.type == second.type || first.type.getClassLoader() == second.type.getClassLoader()
                        || first.type.getClassLoader().loadClass(GeoGenesisConfig.class.getName()) != GeoGenesisConfig.class)
                    throw new IllegalStateException("Loader isolation/shared config check failed");
                float[] discharge = new float[n * n];
                long start = System.nanoTime();
                first.pub10.invoke(first.newEngine(c.erosionXSEnabled.get()), flat, flat.clone(), n, size,
                        (int) Math.round(ox), (int) Math.round(oz), 0f, 1f, discharge, hs);
                double seconds = (System.nanoTime() - start) / 1e9;
                writeCenter(output, flat, size, n);
                writeCenter(safe(Path.of(output + ".discharge.f64be")), discharge, size, n);
                String text = String.format(Locale.ROOT,
                        "engineSeconds=%.9f%nsize=%d%noriginWu=%.15f,%.15f%nengineIntegerOrigin=%d,%d%nstepWu=%.15f%nhs=2 seaNorm=0 str=1 seedCtor=12345 (ignored by engine)%n",
                        seconds, size, ox, oz, Math.round(ox), Math.round(oz), step)
                        + settings + "\nconfigLoaded=true correctedByForge=" + corrected
                        + "\nconfigFingerprint=" + GeoGenesisConfig.configFingerprint() + "\ntomlSha256=" + sha
                        + "\ninputSha256=" + ErosionScratchProbe.sha256File(input)
                        + "\nengineClassSha256=" + ErosionScratchProbe.sha256File(classes.resolve(ErosionScratchProbe.CLASS_REL))
                        + "\npad=9 edge-clamp approximation; production has true neighbouring heights"
                        + "\nIMPORTANT: engine has no step argument: one raster cell is one engine unit; stepWu is metadata/render spacing, not used to recalibrate brush, spawn density or thresholds. NOT physical-scale production equivalence."
                        + "\nhardnessProvider=null; no production geology injection; isolated raw engine call"
                        + "\ndualChildFirstLoaders=true sharedParentConfig=true\n";
                Files.writeString(safe(Path.of(output + ".provenance.txt")), text, StandardOpenOption.CREATE_NEW);
                System.out.println(text);
            }
            if (!sha.equals(ErosionScratchProbe.sha256File(toml))) throw new IOException("TOML copy changed");
        }
    }

    private static Path safe(Path p) {
        p = p.toAbsolutePath().normalize();
        if (!p.startsWith(ROOT)) throw new IllegalArgumentException("Write/config outside experiment: " + p);
        return p;
    }

    private static float[] padded(Path input, int size, int n) throws IOException {
        float[] center = new float[size * size], flat = new float[n * n];
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(input)))) {
            for (int i = 0; i < center.length; i++) {
                center[i] = (float) in.readDouble();
                if (!Float.isFinite(center[i])) throw new IOException("Nonfinite input");
            }
        }
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++)
            flat[z * n + x] = center[Math.max(0, Math.min(size - 1, z - 9)) * size + Math.max(0, Math.min(size - 1, x - 9))];
        return flat;
    }

    private static void writeCenter(Path file, float[] data, int size, int n) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)))) {
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                float value = data[(z + 9) * n + x + 9];
                if (!Float.isFinite(value)) throw new IOException("Nonfinite output");
                out.writeDouble(value);
            }
        }
    }
}
