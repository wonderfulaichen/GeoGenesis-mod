package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxPalette;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * <b>沙盘图层接线检查</b>（★ 2026-11-08）。
 *
 * <h2>为什么需要它</h2>
 * <p>用户反馈：「没考虑我们的原来的需求，温度、湿度、高程、群系等等原来渲染窗口的内容」。
 * 核实确认：{@code GeoPalette} 的 11 个图层、{@code PreviewDisplay.setLayer()} 都完好，
 * <b>但沙盘渲染器把 layer 参数丢了</b> —— 等于砍掉原有能力去换"像参考项目"。
 * 更糟的是这类缺陷<b>编译通过、探针全绿</b>：颜色错了没有任何断言会红。</p>
 *
 * <p>本探针把「图层是否真的生效」变成可判定的断言：
 * 对每个图层，在<b>同一份真实地形数据</b>上采样若干格，
 * 检查</p>
 * <ol>
 *   <li><b>非空</b>：该图层能产出颜色（不抛异常、不全黑）；</li>
 *   <li><b>有区分度</b>：不同格点之间颜色<b>不完全相同</b>
 *       —— 若整幅同色，说明该图层实际没接上数据（退回常量）；</li>
 *   <li><b>图层互异</b>：至少有一个格点在两个不同图层下颜色不同
 *       —— 若所有图层都出同一个颜色，说明 layer 参数被忽略。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLayerProbe}</p>
 */
public final class SandboxLayerProbe {

    private static final int ORIGIN_X = -1400;
    private static final int ORIGIN_Z = -1400;
    private static final int SPAN = 3072;
    private static final long SEED = 12345L;
    /** 取色网格（在真实采样数据上等间隔取样）。 */
    private static final int SAMPLE = 24;
    /** 一个图层至少要产出这么多种不同颜色（低于此值视为"没接上数据"）。 */
    private static final int MIN_DISTINCT_COLORS = 4;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        // 用 128 网格取样本就够（本探针不验密度，只验接线）
        TerrainFieldData d = TerrainFieldData.sample(terrain, ORIGIN_X, ORIGIN_Z,
                SPAN, SPAN, 128, 128, 1L);
        int minY = params.minY(), maxY = params.maxY();
        double sea = params.seaLevel();

        System.out.println("=== 沙盘图层接线检查 ===");
        System.out.printf("数据: %dx%d，取样 %dx%d，海平面 %.0f%n%n",
                d.gridW, d.gridH, SAMPLE, SAMPLE, sea);

        Cell reuse = new Cell();
        boolean pass = true;
        // 记录每个图层在第一格的颜色，用于"图层互异"判定
        int[] firstColor = new int[GeoPalette.PreviewLayer.values().length];

        System.out.printf("%-18s %-10s %-10s %s%n", "图层", "不同色数", "首格颜色", "判定");
        for (GeoPalette.PreviewLayer layer : GeoPalette.PreviewLayer.values()) {
            Set<Integer> colors = new java.util.HashSet<>();
            int first = 0;
            boolean got = false;
            for (int sx = 0; sx < SAMPLE; sx++) {
                for (int sz = 0; sz < SAMPLE; sz++) {
                    int gx = sx * (d.gridW - 1) / Math.max(1, SAMPLE - 1);
                    int gz = sz * (d.gridH - 1) / Math.max(1, SAMPLE - 1);
                    int i = gx * d.gridH + gz;
                    Cell c = d.toDisplayCell(gx, gz, reuse);
                    if (c == null) continue;
                    double surface = d.e[i] < 0.0 ? sea : d.height[i];
                    int rgb = SandboxPalette.colorForLayer(layer, c, d.biomeClassId[i],
                            1.0, surface, sea, d.snow[i], minY, maxY) & 0xFFFFFF;
                    colors.add(rgb);
                    if (!got) { first = rgb; got = true; }
                }
            }
            firstColor[layer.ordinal()] = first;

            boolean ok = true;
            String why = "";
            // VEIN_MAP 是全局未建模图层（GeoPalette 无默认色、Cell 无字段），
            // 不纳入判据；其余图层至少要"有区分度"。
            boolean exempt = layer == GeoPalette.PreviewLayer.VEIN_MAP;
            if (exempt) {
                System.out.printf("%-18s %-10d #%06X    SKIP（全局未建模，GeoPalette 无默认色）%n",
                        layer.name(), colors.size(), first);
                continue;
            }
            // 判据按图层性质分类（"不同色数"本身受<b>视口跨度</b>限制，不是接线质量）：
            //   连续气候场（高程/温度/…）视口内本该丰富 → 要求 ≥4 色
            //   稀疏/类别场（流量、河型、纬度、气候带）视口内类别少是<b>正常</b> → 要求 ≥2 色
            boolean sparse = layer == GeoPalette.PreviewLayer.RIVER_NETWORK
                    || layer == GeoPalette.PreviewLayer.RIVER_TYPE
                    || layer == GeoPalette.PreviewLayer.LATITUDE
                    || layer == GeoPalette.PreviewLayer.CLIMATE_ZONE;
            int need = sparse ? 2 : MIN_DISTINCT_COLORS;
            if (!got) { ok = false; why = "无输出"; }
            else if (colors.size() < need) {
                ok = false;
                why = "只有 " + colors.size() + " 色（<" + need + "，疑似没接上数据）";
            }
            System.out.printf("%-18s %-10d #%06X    %s%n", layer.name(), colors.size(), first,
                    ok ? "OK" : "FAIL " + why);
            if (!ok) pass = false;
        }

        // ---- 图层互异：至少要有一格在两个图层下颜色不同 ----
        System.out.println();
        int[][] pairs = {
                {GeoPalette.PreviewLayer.ELEVATION.ordinal(), GeoPalette.PreviewLayer.BIOME.ordinal()},
                {GeoPalette.PreviewLayer.TEMPERATURE.ordinal(), GeoPalette.PreviewLayer.HUMIDITY.ordinal()},
                {GeoPalette.PreviewLayer.PRECIPITATION.ordinal(), GeoPalette.PreviewLayer.HUMIDITY.ordinal()},
        };
        for (int[] p : pairs) {
            String a = GeoPalette.PreviewLayer.values()[p[0]].name();
            String b = GeoPalette.PreviewLayer.values()[p[1]].name();
            boolean differ = firstColor[p[0]] != firstColor[p[1]];
            System.out.printf("图层互异 %s vs %s: #%06X vs #%06X -> %s%n",
                    a, b, firstColor[p[0]], firstColor[p[1]], differ ? "OK" : "FAIL（同色）");
            if (!differ) pass = false;
        }

        // ---- 坡度光照必须仍然生效（沙盘立体感来源）----
        //   ★ 2026-11-08：colorForLayer 不再自己乘光照（改为调用方最后乘），
        //   所以这里直接验 SandboxPalette.scale()。
        System.out.println();
        int c0 = 0x6FAE4A;
        int base = SandboxPalette.scale(c0, 1.0);
        int dark = SandboxPalette.scale(c0, 0.6);
        boolean litOk = base != dark;
        System.out.printf("坡度光照生效: light=1.0 #%06X vs light=0.6 #%06X -> %s%n",
                base & 0xFFFFFF, dark & 0xFFFFFF, litOk ? "OK" : "FAIL");
        if (!litOk) pass = false;

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxLayerProbe() { }
}
