package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.ArrayList;
import java.util.List;

/**
 * JOIN 归属核验：审计用的 owner 表是 first-wins，必须确认 BROKEN_JOIN 不是审计自身造成的。
 * 不修改生产、不重新定义汇流语义。
 */
public final class JoinOwnershipProbe {

    public static void main(String[] args) {
        System.setProperty("geogenesis.drainageAudit", "true");
        int rx = args.length > 3 ? Integer.parseInt(args[3]) : -1;
        int rz = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        var audit = provider.drainageForProbe(rx, rz);
        if (audit == null) throw new AssertionError("production audit missing");

        // 审计里 join == 自己 ⇒ -1；这里改成"任一其他段的 mouth/source 命中该格"的独立判据。
        int broken = 0, resolvedElsewhere = 0;
        List<String> rows = new ArrayList<>();
        for (var s : audit.segments()) {
            if (s.end() != DeterministicSkeleton.End.JOIN || s.downstreamSegment() >= 0) continue;
            broken++;
            // 找另一个段以同一点为 mouth 或 source 的段（不依赖 owner 顺序）
            int alt = -1;
            for (var other : audit.segments()) {
                if (other.id().ordinal() == s.id().ordinal()) continue;
                if (other.mouth().equals(s.mouth()) || other.source().equals(s.mouth())) {
                    alt = other.id().ordinal();
                    break;
                }
            }
            if (alt >= 0) {
                resolvedElsewhere++;
                if (rows.size() < 12) {
                    rows.add("  seg#" + s.id().ordinal() + " mouth=(" + s.mouth().x() + "," + s.mouth().z()
                            + ") auditDownstream=" + s.downstreamSegment() + " altReceiver=#" + alt);
                }
            }
        }
        System.out.println("owner=" + rx + "," + rz + " segments=" + audit.segments().size()
                + " JOIN_without_downstream=" + broken
                + " of which have a real receiver segment=" + resolvedElsewhere);
        rows.forEach(System.out::println);
        if (broken > 0 && resolvedElsewhere == 0) {
            System.out.println("VERDICT: 真实缺后继（无任何段承载该汇流点）");
        } else if (resolvedElsewhere == broken) {
            System.out.println("VERDICT: 审计 first-wins 归属缺陷，不是生产断流");
        } else {
            System.out.println("VERDICT: 混合，需逐条分类");
        }
    }
}
