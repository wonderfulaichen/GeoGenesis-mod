package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** 对真实生产折线的穿块覆盖与 columnsFor 输出逐列对照，不扩宽、不另画河。 */
public final class ProductionCoverageProbe {
    private static final Path LOG = Path.of("D:/Office software/Development Project/GeoGenesis-mod/debug-agent.log");

    public static void main(String[] args) throws Exception {
        synthetic();
        if (args.length > 0 && args[0].equals("synthetic")) return;
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY()); gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        var polys = new ArrayList<DeterministicSkeleton.Shaped>();
        for (double[] points : provider.shapedNodesWorldForProbe(bx, bz)) {
            double[] x = new double[points.length / 2], z = new double[x.length];
            for (int i = 0; i < x.length; i++) { x[i] = points[2 * i]; z[i] = points[2 * i + 1]; }
            polys.add(new DeterministicSkeleton.Shaped(x, z));
        }
        var index = ModelHydrologyProvider.SegmentIndex.buildExactNodes(polys);
        var csv = new StringBuilder("x,z,distance,half,erosion,fill,lake\n");
        int crossOnly = 0, lost = 0;
        double hs = tp.horizontalScale();
        int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
        for (int a = cx - 2; a <= cx + 2; a++) for (int b = cz - 2; b <= cz + 2; b++) {
            Cell[] cells = new Cell[256];
            for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++)
                cells[i * 16 + j] = gen.sample((a * 16 + i) / hs, (b * 16 + j) / hs);
            for (var c : provider.columnsFor(a, b, cells)) {
                Object hit = index.nearest(c.blockX(), c.blockZ());
                if (hit == null || number(hit, "dist") <= number(hit, "half") || c.lakePlan()) continue;
                crossOnly++;
                if (c.erosion() <= 0) lost++;
                csv.append(c.blockX()).append(',').append(c.blockZ()).append(',')
                        .append(number(hit, "dist")).append(',').append(number(hit, "half")).append(',')
                        .append(c.erosion()).append(',').append(c.fillWater()).append(',').append(c.lakePlan()).append('\n');
            }
        }
        Path out = Path.of("build/lifecycle-diagnosis/coverage-" + seed + "-" + bx + "-" + bz);
        Files.createDirectories(out); Files.writeString(out.resolve("columns.csv"), csv);
        String result = "seed=" + seed + " centre=" + bx + "," + bz + " crossOnly=" + crossOnly + " lost=" + lost;
        System.out.println(result);
        // #region agent log: A 穿块命中在落块调用方再次被 dist<=half 丢弃
        Files.writeString(LOG, "{\"hypothesisId\":\"A\",\"location\":\"ProductionCoverageProbe\",\"message\":\""
                + result + "\"}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        // #endregion
        if (crossOnly == 0) throw new AssertionError("fixture has no crossing-only columns");
        if (lost != 0) throw new AssertionError("production crossing coverage lost: " + lost);
    }

    private static void synthetic() throws Exception {
        var index = ModelHydrologyProvider.SegmentIndex.buildExactNodes(List.of(
                new DeterministicSkeleton.Shaped(new double[]{0, 4}, new double[]{0, 4})));
        Object hit = index.nearest(1, 2);
        if (hit == null || number(hit, "dist") <= number(hit, "half"))
            throw new AssertionError("corner crossing fixture must be covered beyond half-width");
        if (index.nearest(1, 3) != null) throw new AssertionError("unintersected dry block became covered");
        System.out.println("synthetic crossing and dry negative control PASS");
    }

    private static double number(Object hit, String name) throws Exception {
        var method = hit.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return (double) method.invoke(hit);
    }
}
