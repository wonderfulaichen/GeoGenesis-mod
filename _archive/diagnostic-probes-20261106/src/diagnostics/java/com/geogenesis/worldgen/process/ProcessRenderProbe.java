package com.geogenesis.worldgen.process;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.config.ParticleErosionConfigAdapter;
import com.geogenesis.worldgen.core.BaseWorldSettings;
import java.nio.file.*;
import java.io.*;
import java.lang.management.ManagementFactory;

/** Configuration adapter for an isolated exactP export; not an alternate erosion implementation. */
public final class ProcessRenderProbe {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int size = Integer.parseInt(args[1]);
        double step = Double.parseDouble(args[2]), ox = Double.parseDouble(args[3]), oz = Double.parseDouble(args[4]);
        String name = args[5];
        Path data = out.resolve(name + ".csv");
        if (Files.exists(data)) throw new IllegalStateException("never overwrite render evidence");
        try (var config = CommentedFileConfig.of(out.resolve("config-input.toml"), TomlFormat.instance())) {
            config.load();
            if (!GeoGenesisConfig.SPEC.isCorrect(config)) throw new IllegalStateException("config invalid");
            GeoGenesisConfig.SPEC.setConfig(config);
            var c = GeoGenesisConfig.INSTANCE;
            var params = c.buildParams();
            var ctx = new ProcessContext(new ProcessContext.Definition(9139912035078620160L,
                    "P1-frozen-20261105", params,
                    new BaseWorldSettings(c.tempInfluence.get(), c.humidityInfluence.get(), c.continentInfluence.get()),
                    -64, 320, ProcessContext.Mode.PARTICLE, ParticleErosionConfigAdapter.snapshot(),
                    c.erosionXSEnabled.get(), c.erosionStrength.get().floatValue(), true,
                    "config-input.toml;loaded;sha256=cc5d985f7554e4cfb316708e88373530241b9520e4ce983f745a6defcf50c16f"));
            Files.writeString(out.resolve(name + "-definition.txt"), ctx.identity() + "\n"
                    + "seed=9139912035078620160; HS=" + params.horizontalScale()
                    + "; grid=1wu; Y=continuous MC Y; render step=" + step + "; size=" + size
                    + "; origin=" + ox + "," + oz + "; no geogenesis/hydro JVM properties\n"
                    + ManagementFactory.getRuntimeMXBean().getInputArguments() + "\n"
                    + System.getProperty("java.version") + "\n" + System.getProperty("java.class.path"));
            long start = System.nanoTime(); double maxDelta = 0; long changed = 0, land = 0;
            try (var runtime = new ProcessRuntime(ctx, new ProcessRuntime.Budget(2, 16, 128));
                 var writer = Files.newBufferedWriter(data, StandardOpenOption.CREATE_NEW)) {
                writer.write("x,z,eB,yB,eP,yP,deltaE,deltaY\n");
                for (int j = 0; j < size; j++) {
                    for (int i = 0; i < size; i++) {
                        double x = ox + i * step, z = oz + j * step;
                        var p = runtime.exactP(x, z);
                        if (p.base().eB() >= 0) land++;
                        if (Math.abs(p.deltaY()) > 1e-8) changed++;
                        maxDelta = Math.max(maxDelta, Math.abs(p.deltaY()));
                        writer.write(x + "," + z + "," + p.base().eB() + "," + p.base().yB() + ","
                                + p.eP() + "," + p.yP() + "," + p.deltaE() + "," + p.deltaY() + "\n");
                    }
                    if (j % 20 == 0) System.out.println("row=" + j + " seconds=" + (System.nanoTime()-start)/1e9 + " " + runtime.metrics());
                }
                String performance = "seconds=" + (System.nanoTime()-start)/1e9 + "; changed=" + changed
                        + "; land=" + land + "; maxAbsDeltaY=" + maxDelta + "; " + runtime.metrics();
                Files.writeString(out.resolve(name + "-performance.txt"), performance);
                System.out.println(performance);
                if (changed == 0 || land == 0) throw new AssertionError("empty physical land control");
            }
        }
    }
}
