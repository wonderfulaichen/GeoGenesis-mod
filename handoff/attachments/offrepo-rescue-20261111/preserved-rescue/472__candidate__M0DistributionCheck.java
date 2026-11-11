package com.geogenesis.worldgen.terrain.candidate;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** M0有限前向导出合同，失败抛错/非零；冻结原窗全像素及真实geometry独立对拍。 */
public final class M0DistributionCheck {
    private final List<String> report = new ArrayList<>();
    private int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: M0DistributionCheck NEW_OUT FROZEN_C");
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("output must be new");
        Files.createDirectories(out);
        var c = new M0DistributionCheck();
        try {
            c.frozen(Path.of(args[1])); c.geometry(); c.expandedAndOrder(); c.negative();
            c.report.add("M0 CHECK PASS assertions=" + c.checks);
        } catch (Throwable t) {
            c.report.add("M0 CHECK FAIL " + t);
            Files.write(out.resolve("check.txt"), c.report);
            throw t;
        }
        Files.write(out.resolve("check.txt"), c.report);
        System.out.println(c.report.get(c.report.size() - 1));
    }

    private void frozen(Path frozen) throws Exception {
        var s = new M0DistributionSampler(M0DistributionGallery.SEED);
        byte[] types = Files.readAllBytes(frozen.resolve("type.u8"));
        require(types.length == 1401 * 1401, "frozen type size");
        int sea = 0;
        try (var cells = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(frozen.resolve("cells.i64be"))))) {
            for (int j = 0; j < 1401; j++) for (int i = 0; i < 1401; i++) {
                var p = s.sample(RegionalMorphGallery.X0 + i * 5, RegionalMorphGallery.Z0 + j * 5);
                require(p.cell().key() == cells.readLong(), "frozen cell " + i + "," + j);
                require(p.recipe() == (types[j * 1401 + i] & 255), "frozen recipe " + i + "," + j);
                if (p.recipe() >= 18) sea++;
            }
            require(cells.read() == -1, "frozen cells trailing bytes");
        }
        require(sea > 0 && sea < types.length, "nonempty land/sea");
        report.add("frozen C 1401^2 cells/base recipes bitwise identical; sea=" + sea);
    }

    private void geometry() {
        var context = new LandRegionContext(M0DistributionGallery.SEED, 600);
        var sampler = new M0DistributionSampler(context.seed());
        var distribution = new CoherentRegionDistribution(context.seed());
        for (double[] center : new double[][]{{512,512},{-2560,-1536}}) {
            var g = LocalBoundaryGeometry.of(context, center[0], center[1], 728, 4, .02);
            g.setMaxSeg(2);
            for (int i = 0; i < 101; i++) {
                double x = center[0] - 500 + i * 10, z = center[1] - 400 + i * 8;
                var p = sampler.sample(x, z); var actual = g.exactWinner(x, z);
                require(p.cell().key() == actual.key(), "geometry winner");
                require(p.recipe() == distribution.choose(actual).recipe(), "geometry label");
                require(java.util.Arrays.equals(sampler.forward(x,z), g.warpForward(x,z)), "float forward bits");
            }
        }
        report.add("202 actual geometry winners/labels/float forward bitwise identical (not float3x3 oracle)");
    }

    private void expandedAndOrder() {
        for (long seed : new long[]{M0DistributionGallery.SEED,0,-419,12345}) {
            var s = new M0DistributionSampler(seed); var cold = new M0DistributionSampler(seed);
            var random = new Random(12345); var points = new ArrayList<double[]>();
            var picks = new ArrayList<M0DistributionSampler.Pick>(); int max = 0;
            for (int i = 0; i < 400; i++) {
                double x = random.nextDouble() * 56000 - 28140, z = random.nextDouble() * 56000 - 27863;
                var p = s.sample(x,z); points.add(new double[]{x,z}); picks.add(p);
                var expanded = s.sample(x,z,8);
                require(p.cell().key() == expanded.cell().key() && p.recipe() == expanded.recipe(), "expanded bound");
                max = Math.max(max,p.candidates());
            }
            var order = new ArrayList<Integer>(); for (int i=0;i<points.size();i++) order.add(i);
            Collections.shuffle(order,new Random(101));
            for (int i : order) {
                var p = cold.sample(points.get(i)[0],points.get(i)[1]);
                var expected = picks.get(i);
                require(p.cell().key() == expected.cell().key() && p.recipe() == expected.recipe()
                        && Double.doubleToLongBits(p.c()) == Double.doubleToLongBits(expected.c())
                        && Double.doubleToLongBits(p.probability()) == Double.doubleToLongBits(expected.probability()),
                        "cold shuffled seed=" + seed);
            }
            report.add("seed=" + seed + " expanded+8/cold shuffled 400 samples; max candidates=" + max);
        }
    }

    private void negative() throws Exception {
        byte[] base = Files.readAllBytes(Path.of("build/coherent-shallow-s2/frozen-c/type.u8"));
        byte[] finalIds = Files.readAllBytes(Path.of("build/coherent-morph-s3/frozen-s2/after.u8"));
        int subtype = 0;
        for (int i=0;i<base.length;i++) {
            require((base[i] >= 18) == (finalIds[i] >= 18), "S2 side unchanged");
            if (base[i] != finalIds[i]) subtype++;
        }
        require(subtype > 0, "S2 ocean subtype negative control empty");
        // 抖动必须保留旧float相加；去掉抖动的错误站点须能被实际查询区分。
        var sampler = new M0DistributionSampler(0); int jitterWitness = 0;
        for (int i=0;i<100;i++) {
            var p = sampler.sample(i*37.5-1700, i*23.5-1100);
            if (p.cell().vx() != p.cell().cellX() || p.cell().vz() != p.cell().cellZ()) jitterWitness++;
        }
        require(jitterWitness == 100, "jitter negative witness missing");
        report.add("S2 side unchanged full-window; ocean-subtype substitutions=" + subtype
                + "; no S2 subtype map advertised as final water; jitter witness=100");
    }

    private void require(boolean ok, String why) {
        checks++; if (!ok) throw new AssertionError(why);
    }
}
