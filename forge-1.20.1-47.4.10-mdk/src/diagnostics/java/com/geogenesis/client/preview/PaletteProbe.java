package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.ClimateZone.Zone;
import com.geogenesis.worldgen.terrain.TerrainClass;

import java.lang.reflect.Field;

/**
 * 离散图层一致性自检（★ 2026-09-11，TERRAIN_TYPE 修复后补上的防护网）。
 *
 * <p>GeoGenesis 的离散图层靠<b>手工平行表</b>驱动：颜色数组、名称数组、语言 key 必须三方对齐。
 * 历史上已出过两次漂移：
 * <ol>
 *   <li>TERRAIN_TYPE 颜色 17 项 vs 名称 14 项 → 图例少 3 条（SNOW/VOLCANO/VOLCANIC_FIELD 有颜色无图例），
 *       且 {@code discreteLabelKey(id≥14)} 会 ArrayIndexOutOfBoundsException；</li>
 *   <li>RIVER_TYPE 图例 key 写成 big/medium/small，语言文件实际是 main/mouth/trib → 图例显示原始 key。</li>
 * </ol>
 *
 * <p>本探针三方交叉验证，把"枚举增删后忘了同步某张表"拦截在运行期而非目检。</p>
 *
 * <p><b>设计注意</b>：本探针<b>不</b>引用 {@code BiomeClass} / {@code Biomes.*}（需 MC 注册表引导），
 * 故 BIOME 层只用反射读颜色数组长度、与硬编码期望值（= 当前枚举项数）比较。
 * 若给 BiomeClass 增删项，须同步更新 {@link #BIOME_EXPECTED}。</p>
 *
 * <p>用法：{@code gradlew runPaletteProbe}</p>
 */
public final class PaletteProbe {

    /** BiomeClass 当前枚举项数（2026-09-11 = 35）。增删枚举项时须同步此值。 */
    private static final int BIOME_EXPECTED = 35;

    private PaletteProbe() {}

    public static void main(String[] args) {
        boolean all = true;

        // ---- [1] TERRAIN_TYPE：颜色 == 名称 == TerrainClass 枚举（三方对齐）
        int tColor = intArrayLen("T_TERRAIN_TYPE");
        int tName  = strArrayLen("TERRAIN_TYPE_NAMES");
        int tEnum  = TerrainClass.values().length;
        boolean p1 = tColor == tName && tName == tEnum;
        all &= p1;
        System.out.printf("[1] TERRAIN_TYPE: color=%d name=%d enum=%d %s%n",
            tColor, tName, tEnum, p1 ? "PASS" : "FAIL");

        // ---- [2] CLIMATE_ZONE：颜色 == Zone 枚举
        int zColor = intArrayLen("T_CLIMATE_ZONE");
        int zEnum  = Zone.values().length;
        boolean p2 = zColor == zEnum;
        all &= p2;
        System.out.printf("[2] CLIMATE_ZONE: color=%d enum=%d %s%n",
            zColor, zEnum, p2 ? "PASS" : "FAIL");

        // ---- [3] RIVER_TYPE：id 域 0..3 全部有颜色（非品红）
        int rWithColor = 0;
        for (int id = 0; id <= 3; id++) {
            int rgb = GeoPalette.discrete(GeoPalette.PreviewLayer.RIVER_TYPE, id);
            if ((rgb & 0x00FFFFFF) != 0x00FF00FF) rWithColor++;
        }
        boolean p3 = rWithColor == 4;
        all &= p3;
        System.out.printf("[3] RIVER_TYPE: colored=%d/4 %s%n",
            rWithColor, p3 ? "PASS" : "FAIL");

        // ---- [4] BIOME：颜色数组长度 == 期望枚举项数
        int bColor = intArrayLen("T_BIOME");
        boolean p4 = bColor == BIOME_EXPECTED;
        all &= p4;
        System.out.printf("[4] BIOME: color=%d (expect %d) %s%n",
            bColor, BIOME_EXPECTED, p4 ? "PASS" : "FAIL");

        // ---- [5] 图例条目数 == 数据域（图例不能比枚举少——否则"有颜色无图例"）
        //    TERRAIN_TYPE 图例隐藏 BEACH/PEAK/LAKE/RIVER（用户决策），所以 = 17 - 4 = 13
        int legendTerrain = GeoPalette.discreteEntries(GeoPalette.PreviewLayer.TERRAIN_TYPE).size();
        boolean p5a = legendTerrain == (tEnum - 4);
        all &= p5a;
        System.out.printf("[5a] TERRAIN_TYPE 图例条目=%d (enum %d - 隐藏4 = %d) %s%n",
            legendTerrain, tEnum, tEnum - 4, p5a ? "PASS" : "FAIL");

        int legendZone = GeoPalette.discreteEntries(GeoPalette.PreviewLayer.CLIMATE_ZONE).size();
        boolean p5b = legendZone == zEnum;
        all &= p5b;
        System.out.printf("[5b] CLIMATE_ZONE 图例条目=%d (enum %d) %s%n",
            legendZone, zEnum, p5b ? "PASS" : "FAIL");

        int legendRiver = GeoPalette.discreteEntries(GeoPalette.PreviewLayer.RIVER_TYPE).size();
        boolean p5c = legendRiver == 4;
        all &= p5c;
        System.out.printf("[5c] RIVER_TYPE 图例条目=%d (expect 4) %s%n",
            legendRiver, p5c ? "PASS" : "FAIL");

        // ---- [6] 图例 key 必须能在英文表中解析（否则显示原始 key）
        //    只检查不依赖 MC 注册表的图层（TERRAIN_TYPE / CLIMATE_ZONE / RIVER_TYPE）。
        //    BIOME 的 key 由枚举名直接生成、且 ENGLISH 表已全量收录，不在此重复。
        boolean p6 = true;
        int unresolved = 0;
        for (GeoPalette.PreviewLayer layer : new GeoPalette.PreviewLayer[]{
                GeoPalette.PreviewLayer.TERRAIN_TYPE,
                GeoPalette.PreviewLayer.CLIMATE_ZONE,
                GeoPalette.PreviewLayer.RIVER_TYPE}) {
            for (GeoPalette.LegendEntry e : GeoPalette.discreteEntries(layer)) {
                if (GeoPalette.englishLabel(e.labelKey).equals(e.labelKey)) {
                    p6 = false;
                    unresolved++;
                    System.out.println("    缺失英文回退: " + layer + " / " + e.labelKey);
                }
            }
        }
        all &= p6;
        System.out.printf("[6] 图例 key 英文回退覆盖: unresolved=%d %s%n",
            unresolved, p6 ? "PASS" : "FAIL");

        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }

    private static int intArrayLen(String name) { return arrayLen(name, "[I"); }
    private static int strArrayLen(String name) { return arrayLen(name, "[Ljava.lang.String;"); }

    private static int arrayLen(String name, String desc) {
        try {
            Field f = GeoPalette.class.getDeclaredField(name);
            f.setAccessible(true);
            Object arr = f.get(null);
            if (arr == null) throw new RuntimeException(name + " is null");
            return java.lang.reflect.Array.getLength(arr);
        } catch (Exception e) {
            throw new RuntimeException("无法读取 GeoPalette." + name + " (" + desc + ")", e);
        }
    }
}
