package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/** 生产最终保留链审计；回滚原因另由 lifecycleDiag 记录，不将外部端口当合法海湖终点。 */
public final class ProductionLifecycleProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("geogenesis.drainageAudit", "true");
        System.setProperty("geogenesis.lifecycleDiag", "true");
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY()); gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        var audit = provider.drainageForProbe(rx, rz);
        if (audit == null) throw new AssertionError("production audit missing");
        var ends = new EnumMap<ProductionDrainageAudit.Terminal, Integer>(ProductionDrainageAudit.Terminal.class);
        StringBuilder csv = new StringBuilder("ordinal,sourceX,sourceZ,mouthX,mouthZ,end,outlet,frame,downstream,chainTerminal\n");
        int dead = 0;
        for (var s : audit.segments()) {
            var chain = audit.chains().get(s.id().ordinal());
            ends.merge(chain.terminal(), 1, Integer::sum);
            if (s.end() == DeterministicSkeleton.End.DEAD_END) dead++;
            csv.append(s.id().ordinal()).append(',').append(s.source().x()).append(',').append(s.source().z())
                    .append(',').append(s.mouth().x()).append(',').append(s.mouth().z()).append(',').append(s.end())
                    .append(',').append(s.fromOutlet()).append(',').append(s.frame()).append(',').append(s.downstreamSegment())
                    .append(',').append(chain.terminal()).append('\n');
        }
        Path out = Path.of("build/lifecycle-diagnosis/census-" + seed + "-" + rx + "-" + rz);
        Files.createDirectories(out); Files.writeString(out.resolve("segments.csv"), csv);
        String report = "seed=" + seed + " owner=" + rx + "," + rz + " retained=" + audit.segments().size()
                + " dead=" + dead + " filterEnabled=" + audit.filtered() + "\nchainCandidates=" + ends
                + "\nEXTERNAL remains pending; lake identity uses local pipeline, not global certification\n";
        Files.writeString(out.resolve("metrics.txt"), report); System.out.print(report);
        if (dead > 0) throw new AssertionError("retained production dead ends=" + dead);
    }
}
