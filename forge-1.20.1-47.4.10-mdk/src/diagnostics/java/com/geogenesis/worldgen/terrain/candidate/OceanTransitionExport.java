package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * <b>地形拼图出图导出器</b>（内部类 / 过渡类分层）。
 *
 * <h3>导出内容</h3>
 * <pre>
 *   内部类·海岸 5   coast-0..4
 *   内部类·海洋 5   inner-0..4
 *   海↔海过渡  10   sea00..09
 *   陆↔海岸过渡 8   lc00..07
 *   海岸↔海洋过渡 9  cs00..08   ← 本次重点
 * </pre>
 *
 * <p>窗口与 1401² 大图一致（step=5、中心 (-140,137)）。</p>
 */
public final class OceanTransitionExport {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/ocean-transition-export");
        Files.createDirectories(out);

        TerrainParams tp = TerrainParams.defaults();
        HeightCurve curve = new HeightCurve(tp, -64, 320);

        CoastRecipeCatalog coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        OceanRecipeCatalog ocean = new OceanRecipeCatalog(
                SEED + OceanRecipeCatalog.SEED_OFFSET, true);
        OceanTransitionCatalog trans = new OceanTransitionCatalog(
                SEED + OceanRecipeCatalog.SEED_OFFSET, ocean, coast);

        // ---------- 内部类·海岸 5
        for (CoastRecipeCatalog.Recipe r : CoastRecipeCatalog.Recipe.values()) {
            final CoastRecipeCatalog.Recipe rf = r;
            writeGrid(out, "coast-" + r.id, curve, (x, z) -> coast.height01(rf, x, z));
        }
        // ---------- 内部类·海洋 5
        for (OceanRecipeCatalog.Recipe r : OceanRecipeCatalog.Recipe.values()) {
            final OceanRecipeCatalog.Recipe rf = r;
            writeGrid(out, "inner-" + r.id, curve, (x, z) -> ocean.height01(rf, x, z));
        }

        // ---------- 海↔海 10
        List<OceanTransitionCatalog.Transition> seaSea = trans.seaSeaPairs();
        for (int i = 0; i < seaSea.size(); i++) {
            final OceanTransitionCatalog.Transition t = seaSea.get(i);
            writeGrid(out, String.format(Locale.ROOT, "sea%02d", i), curve,
                    (x, z) -> t.height01(x, z));
        }
        // ---------- 陆↔海岸 8
        List<OceanTransitionCatalog.Transition> landCoast = trans.landCoastPairs();
        for (int i = 0; i < landCoast.size(); i++) {
            final OceanTransitionCatalog.Transition t = landCoast.get(i);
            writeGrid(out, String.format(Locale.ROOT, "lc%02d", i), curve,
                    (x, z) -> t.height01(x, z));
        }
        // ---------- 海岸↔海洋 9（本次重点）
        List<OceanTransitionCatalog.Transition> coastSea = trans.coastSeaPairs();
        for (int i = 0; i < coastSea.size(); i++) {
            final OceanTransitionCatalog.Transition t = coastSea.get(i);
            writeGrid(out, String.format(Locale.ROOT, "cs%02d", i), curve,
                    (x, z) -> t.height01(x, z));
        }

        // ---------- meta
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "{\"seed\":\"%d\",\"size\":%d,\"step\":%d,\"center\":[%.0f,%.0f],"
                        + "\"sampleOrigin\":[%.1f,%.1f],\"jvmProperties\":[\"file.encoding=UTF-8\"],%n",
                SEED, SIZE, STEP, CX, CZ, CX - SIZE / 2.0 * STEP, CZ - SIZE / 2.0 * STEP));
        sb.append(" \"coastInternal\":[");
        CoastRecipeCatalog.Recipe[] cr = CoastRecipeCatalog.Recipe.values();
        for (int i = 0; i < cr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(cr[i].name()).append('"');
        }
        sb.append("],\n \"oceanInternal\":[");
        OceanRecipeCatalog.Recipe[] or = OceanRecipeCatalog.Recipe.values();
        for (int i = 0; i < or.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(or[i].name()).append('"');
        }
        sb.append("],\n \"seaSea\":[");
        for (int i = 0; i < seaSea.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ascii(seaSea.get(i).name())).append('"');
        }
        sb.append("],\n \"landCoast\":[");
        for (int i = 0; i < landCoast.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ascii(landCoast.get(i).name())).append('"');
        }
        sb.append("],\n \"coastSea\":[");
        for (int i = 0; i < coastSea.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ascii(coastSea.get(i).name())).append('"');
        }
        sb.append("]}\n");
        Files.writeString(out.resolve("meta.json"), sb.toString());

        System.out.println("EXPORT PASS coast=" + cr.length
                + " ocean=" + or.length
                + " sea-sea=" + seaSea.size()
                + " land-coast=" + landCoast.size()
                + " coast-sea=" + coastSea.size()
                + " grid=" + SIZE + "x" + SIZE + " step=" + STEP
                + " out=" + out.toAbsolutePath());
    }

    /** 名字转 ASCII（箭头符号会破坏 CSV/JSON 的编码）。 */
    private static String ascii(String s) {
        return s.replace("↔", "<->").replace("→", "->");
    }

    private static void writeGrid(Path out, String name, HeightCurve curve,
                                  java.util.function.DoubleBinaryOperator eFn) throws Exception {
        Path p = out.resolve("Y-" + name + ".f64be");
        try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(p), 1 << 16))) {
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = CX + (i - SIZE / 2.0) * STEP;
                    double z = CZ + (j - SIZE / 2.0) * STEP;
                    os.writeDouble(curve.heightFromE(eFn.applyAsDouble(x, z)));
                }
            }
        }
    }

    private OceanTransitionExport() { }
}
