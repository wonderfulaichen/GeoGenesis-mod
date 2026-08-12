package com.geogenesis.worldgen.terrain;

/**
 * Chunk 边界断裂诊断（2026-08-13 v2：HS=2 引擎对齐游戏 toml）。
 *
 * <p>v1 用 TerrainParams.defaults()（horizontalScale=1）+ 块坐标直当 wu 用 →
 * 与游戏内（HS=2）扫的不是同一世界位置，结论无效。</p>
 *
 * <p>v2：withHs 反射复制 defaults 换 horizontalScale=2；所有坐标 块/hs → wu。
 * 游戏内 chunk 边界 16 块 = 8wu；tile 边界 48wu = 96 块。</p>
 *
 * 用法：gradlew runChunkBorderProbe
 */
public final class ChunkBorderProbe {
    private static final int ERODE_TILE_CENTER = 48; // wu
    private static final double HS = 2.0;            // 对齐游戏 toml horizontalScale

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        int bx = -280;   // 块坐标
        int bz = -380;
        int radius = 32; // 块
        boolean noriver = args.length > 0 && "noriver".equals(args[0]);

        // ★ 2026-08-13 toml 参数对齐（探针 vs 游戏差异根因）：游戏 buildParams() 读
        // run/config/geogenesis-common.toml（用户配置屏调过：erosionRidgeStrength=0.75），
        // 探针 defaults() 用默认 2.0 → 骨架强度差 2.7 倍 → 地形不同 → 断裂永远复现不了。
        java.util.Map<String, String> toml = parseToml("run/config/geogenesis-common.toml");
        System.out.println("--- toml 关键参数 ---");
        String[] keys = {"horizontalScale", "erosionStrength", "erosionRidgeStrength",
            "erosionCascadeStrength", "erosionDropsMul", "erosionErodeMul"};
        for (String k : keys) System.out.printf("  %-24s toml=%s%n", k, toml.getOrDefault(k, "(无)"));
        System.out.println();

        TerrainParams p = withHs(TerrainParams.defaults(), HS);
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        if (noriver) gen.setRiversEnabled(false);
        // 2026-08-13：按 toml 覆盖骨架参数（探针无 Forge 环境，cfg=null → 默认 2.0 与游戏不符）
        com.geogenesis.worldgen.erosion.RidgeValleyErosion.RidgeConfig rcfg = new com.geogenesis.worldgen.erosion.RidgeValleyErosion.RidgeConfig();
        rcfg.strength = parseF(toml, "erosionRidgeStrength", rcfg.strength);
        rcfg.octaves = (int) parseF(toml, "erosionRidgeOctaves", rcfg.octaves);
        rcfg.gullyWeight = parseF(toml, "erosionRidgeGullyWeight", rcfg.gullyWeight);
        rcfg.cellWorldSize = parseF(toml, "erosionRidgeScale", rcfg.cellWorldSize);
        rcfg.stripeFreq = parseF(toml, "erosionRidgeCellScale", rcfg.stripeFreq);
        rcfg.detail = parseF(toml, "erosionRidgeDetail", rcfg.detail);
        rcfg.horizontalScale = (float) HS;
        gen.setRidgeConfig(rcfg);
        // ★ 2026-08-13 二分：skeleton 模式分离骨架 vs 液滴贡献（第二个 arg = "skeleton"）
        if (args.length > 1 && "skeleton".equals(args[1])) CellGenerator.PROBE_SKELETON_ONLY = true;
        // 第三个 arg = "noringe"：关骨架只跑液滴（分离骨架+液滴耦合）
        if (args.length > 2 && "noringe".equals(args[2])) rcfg.enabled = false;
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen); // 与游戏内完全相同的门面调用链

        System.out.printf("=== ChunkBorderProbe v2 (HS=%.0f) seed=%d center=(%d,%d) r=%d ===%n",
            HS, seed, bx, bz, radius);

        // chunk 边界：块 16k → wu 8k
        int chunkBoundaryWX = (int)(Math.floorDiv(bx, 16) * 16 / HS); // wu
        int chunkBoundaryWZ = (int)(Math.floorDiv(bz, 16) * 16 / HS);
        double rw = radius / HS; // wu 半径

        System.out.println("--- X chunk boundary (块 x=-288) 门面调用链（与游戏一致）---");
        System.out.printf("%-8s %-10s %-10s %-8s %-10s %-10s %-8s%n", "z块", "hL含蚀", "hR含蚀", "含蚀差", "hL基础", "hR基础", "基础差");
        double maxErosionJump = 0; int worstZ = 0;
        for (int z = bz - radius; z <= bz + radius; z++) {
            double hL = terrain.sampleHeight(-289, z); // 块坐标，与游戏内一致
            double hR = terrain.sampleHeight(-288, z);
            double erosionDiff = Math.abs(hR - hL);
            // 基础场（无侵蚀）：sample() 直接调（地形基础，不含液滴 delta）
            double bL = gen.sample(-289.0 / HS, z / HS).height;
            double bR = gen.sample(-288.0 / HS, z / HS).height;
            double baseDiff = Math.abs(bR - bL);
            if (erosionDiff > maxErosionJump) { maxErosionJump = erosionDiff; worstZ = z; }
            if (erosionDiff > 1.0 || baseDiff > 1.0)
                System.out.printf("%-8d %-10.3f %-10.3f %-8.2f %-10.3f %-10.3f %-8.2f%n",
                    z, hL, hR, erosionDiff, bL, bR, baseDiff);
        }
        System.out.printf("  Max含蚀差=%.2f @z=%d%n", maxErosionJump, worstZ);
        // 用户实测断裂处细节
        System.out.printf("  (细节) z=-392..-385 含蚀差: ");
        for (int z = -392; z <= -385; z++) {
            double hL = terrain.sampleHeight(-289, z);
            double hR = terrain.sampleHeight(-288, z);
            System.out.printf("%.1f ", Math.abs(hR - hL));
        }
        System.out.println();
        // 沿 z=-385 固定剖面 x=-296..-280：判断是真实侵蚀谷（右侧连续低）还是边界伪影
        System.out.println("  (剖面 z=-385, x=-296..-280 含蚀高度):");
        for (int x = -296; x <= -280; x++) {
            double h = terrain.sampleHeight(x, -385);
            System.out.printf("%5d:%.1f ", x, h);
        }
        System.out.println();
        // 基础场（无侵蚀）同剖面——对比侵蚀前地形是否已有台阶
        System.out.println("  (基础剖面 z=-385, x=-296..-280):");
        for (int x = -296; x <= -280; x++) {
            double h = gen.sample(x / HS, -385.0 / HS).height;
            System.out.printf("%5d:%.1f ", x, h);
        }
        System.out.println("\n");

        System.out.println("--- Z chunk boundary (块 z=-384) 门面调用链 ---");
        double maxErosionJumpZ = 0; int worstX = 0;
        for (int x = bx - radius; x <= bx + radius; x++) {
            double hT = terrain.sampleHeight(x, -385);
            double hB = terrain.sampleHeight(x, -384);
            double erosionDiff = Math.abs(hB - hT);
            if (erosionDiff > maxErosionJumpZ) { maxErosionJumpZ = erosionDiff; worstX = x; }
        }
        System.out.printf("  Max侵蚀差=%.2f @x=%d%n%n", maxErosionJumpZ, worstX);

        // tile 边界：48wu = 96 块
        System.out.println("--- Tile X boundary (块 x=-288) 门面调用链 ---");
        double maxTileErosionJump = 0; int worstTileZ = 0;
        for (int z = bz - radius; z <= bz + radius; z++) {
            double hL = terrain.sampleHeight(-289, z);
            double hR = terrain.sampleHeight(-288, z);
            double erosionDiff = Math.abs(hR - hL);
            if (erosionDiff > maxTileErosionJump) { maxTileErosionJump = erosionDiff; worstTileZ = z; }
        }
        System.out.printf("  Max侵蚀差=%.2f @z=%d%n%n", maxTileErosionJump, worstTileZ);

        System.out.println("=== v2 done ===");
    }

    /** 反射复制 record，仅替换 horizontalScale（JDK 21 record 无自动 with 方法，探针专用）。 */
    private static TerrainParams withHs(TerrainParams p, double hs) {
        try {
            var comps = TerrainParams.class.getRecordComponents();
            Object[] vals = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                vals[i] = comps[i].getName().equals("horizontalScale")
                    ? hs : comps[i].getAccessor().invoke(p);
            }
            return (TerrainParams) TerrainParams.class.getConstructors()[0].newInstance(vals);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** toml 键 → float（解析失败回退默认）。 */
    private static float parseF(java.util.Map<String, String> toml, String key, float dflt) {
        String v = toml.get(key);
        if (v == null) return dflt;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return dflt; }
    }

    /** 简易 TOML 键值解析（探针专用）：读 key = value 行，忽略注释/数组/嵌套表头。 */
    private static java.util.Map<String, String> parseToml(String path) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) continue;
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                m.put(k, v);
            }
        } catch (Exception e) {
            System.out.println("[warn] toml parse failed: " + e.getMessage());
        }
        return m;
    }
}
