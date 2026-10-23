package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JOIN 终点是否真的被另一条已发布链的【格路径】包含。
 *
 * <p>审计的 owner 表是 first-wins，且只看端点；本条改用完整 reach 格路径判归属，
 * 以区分「生产真缺后继」与「审计归属口径问题」。不修改生产。</p>
 */
public final class JoinCellContainmentProbe {

    public static void main(String[] args) {
        System.setProperty("geogenesis.drainageAudit", "true");
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int rx = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int rz = args.length > 4 ? Integer.parseInt(args[4]) : -1;
        int blockX = rx * 512 + 256;
        int blockZ = rz * 512 + 256;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);

        int[] frame = provider.windowFrameForProbe(blockX, blockZ);
        int ox = frame[0], oz = frame[1], step = frame[2], n = frame[3];
        List<RiverCenterlines.Reach> reaches = provider.reachesForProbe(blockX, blockZ);
        var audit = provider.drainageForProbe(rx, rz);
        if (audit == null) throw new AssertionError("audit missing");

        // cell -> 含该格的 reach 下标（全部，不 first-wins）
        Map<Integer, List<Integer>> holders = new HashMap<>();
        for (int r = 0; r < reaches.size(); r++) {
            for (int c : reaches.get(r).cell) {
                holders.computeIfAbsent(c, x -> new ArrayList<>()).add(r);
            }
        }

        int checked = 0, containedByEarlier = 0, containedByLater = 0, containedBySelfOnly = 0, nowhere = 0;
        List<String> rows = new ArrayList<>();
        for (var s : audit.segments()) {
            if (s.end() != DeterministicSkeleton.End.JOIN || s.downstreamSegment() >= 0) continue;
            checked++;
            int i = (int) Math.round((s.mouth().x() - ox) / step);
            int j = (int) Math.round((s.mouth().z() - oz) / step);
            int cell = i * n + j;
            List<Integer> who = holders.getOrDefault(cell, List.of());
            int self = s.id().ordinal();
            List<Integer> others = new ArrayList<>(who);
            others.remove(Integer.valueOf(self));
            String kind;
            if (others.isEmpty() && who.contains(self)) {
                kind = "SELF_ONLY";
                containedBySelfOnly++;
            } else if (others.isEmpty()) {
                kind = "NOWHERE";
                nowhere++;
            } else if (others.stream().anyMatch(r -> r < self)) {
                kind = "EARLIER";
                containedByEarlier++;
            } else {
                kind = "LATER_ONLY";
                containedByLater++;
            }
            if (rows.size() < 14) {
                rows.add("  seg#" + self + " mouth=(" + s.mouth().x() + "," + s.mouth().z()
                        + ") holders=" + who + " => " + kind);
            }
        }
        System.out.println("owner=" + rx + "," + rz + " reaches=" + reaches.size()
                + " JOIN_no_downstream=" + checked);
        System.out.println("  containedByEarlier=" + containedByEarlier
                + " laterOnly=" + containedByLater
                + " selfOnly=" + containedBySelfOnly
                + " nowhere=" + nowhere);
        rows.forEach(System.out::println);
        boolean real = nowhere > 0 || containedByLater > 0;
        System.out.println(real
                ? "VERDICT: 存在真实缺后继（终点不被任何【更早】已发布链包含）"
                : "VERDICT: 终点只被更早链包含，属于审计归属/端点口径，不是生产断流");
    }
}
