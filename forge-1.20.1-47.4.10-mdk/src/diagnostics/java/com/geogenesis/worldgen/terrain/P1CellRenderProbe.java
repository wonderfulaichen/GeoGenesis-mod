package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.GeoGenesisWorldData;
import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** P1 production Cell path; no candidate/Gallery classes and no Minecraft world writes. */
public final class P1CellRenderProbe {
    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401;
    /**
     * ★ 阶段五重立基准（2026-11-16，构造趋势层；schema135）。
     *
     * <p>历史值：51c2626d…（初版 distDiv-age，已否决）；d87b8629…（schema130，
     * 均衡式 eBase）；10f50a68…（schema131，平台律+构造带）；c273a3bc…（schema132，
     * 主动陆缘削减）；671ecd94…（schema133，核链初版小岛网，用户否决）；
     * e0f5e4db…（schema134）。</p>
     *
     * <p>⚠ 本次同时补记<b>两笔欠账</b>：
     * ① FBM 融合（壳值 + 1.6×FBM 塑形，用户裁定「参考 FBM 机制」）当时未重立 P1；
     * ② 阶段五构造趋势（会聚 0.17e 宽带隆起 + 票槽偏置）。
     * 两者都已并入本基准。关趋势（-Dgeogenesis.tectonicTrend=false）产出为
     * 47fe6869…/61bd91f9…，即「仅有 FBM 融合、无趋势」的中间态。</p>
     */
    private static final String EXPECTED =
            "5490eb62d6096012eb97439857615959747c181755ea103798980719af115dbe";

    /**
     * ★ 阶段五重立（2026-11-16）：landCells 503823→586223。
     * 全域陆占比由 ContinentAnchorProbe（0.3385）与 OceanFloorProbe 守护。
     * 标定协议见 {@link #render}。
     */
    private static final String EXPECTED_LAND = "1f9d9065d4cbbc447a454751b43d641cf88a717613b270740be78ca9a61f3a11";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        try (CommentedFileConfig cfg = CommentedFileConfig.of(Path.of(args[0]), TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "frozen config invalid");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
            requireFrozenProducingParams(p, cfg);
            System.out.println("frozenParams=" + p);
            CellGenerator gen = new CellGenerator(p, -64, 320, true);
            GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
            terrain.seed(SEED);
            require(terrain.usesMorphBase(), "morphBase must be on (P1 asset is the morph path)");
            require(!terrain.riversEnabled(), "riverEnabled must be false (P1 asset excludes hydrology)");
            render(terrain, p, out);
            checkChunks(terrain, gen, p, out);
            checkWorldMarkers();
            // 非正典种子/坐标的有限性自检（与 EXPECTED 无关，只证明纯函数不崩）。
            CellGenerator other = new CellGenerator(p, -64, 320, true);
            other.seed(12345L);
            require(Double.isFinite(other.sample(0.5, 0.5).height), "noncanonical sampling");
            CellGenerator third = new CellGenerator(p, -64, 320, true);
            third.seed(12345L);
            require(Double.isFinite(third.sample(10.5, -11.5).height), "noncanonical Cell");
            System.out.println("P1 CELL PASS: HS=2, no external erosion/hydrology, markers roundtrip");
        }
    }

    /**
     * 冻结「驱动产出」的输入，不满足就报**前置条件错误**，而不是误报 "height drift"。
     *
     * <h3>为什么必须显式断言</h3>
     * <p>{@link #EXPECTED} 是在**一组具体参数**下产生的固定资产
     * （见 {@code audit/GeoGenesis-R0-0-baseline-20261105.md} §P1 固定资产）。
     * 若只断言 {@code HS==2}，而用户在 {@code run/config} 里调过
     * {@code continentScale} / {@code peakHeightFraction} 等，探针会跑出另一个 SHA
     * 并报 "P1 height drift from verified P0" —— 让人误判成**地形算法漂移**。
     * 实测踩过：{@code continentScale} 4000→2400、{@code peakHeightFraction} 0.92→0.6
     * 时 SHA 变成 {@code 78ab19a6…}，而算法本身没变。</p>
     *
     * <p>参数集合来自 R0-0 冻结配置（{@code input-p1-frozen.toml}，SHA
     * {@code cc5d985f…}）中**实际影响高度产出**的项；纯诊断/装饰类键不在内。</p>
     */
    private static void requireFrozenProducingParams(TerrainParams p, CommentedFileConfig cfg) {
        // ---- 地形形态与量纲 ----
        require(p.horizontalScale() == 2.0,
                "frozen input: horizontalScale must be 2.0 (got " + p.horizontalScale() + ")");
        require(p.continentScale() == 4000.0,
                "frozen input: continentScale must be 4000.0 (got " + p.continentScale() + ")");
        require(p.continentFbmPersistence() == 0.6,
                "frozen input: continentFbmPersistence must be 0.6 (got "
                        + p.continentFbmPersistence() + ")");
        require(p.continentBias() == 0.31,
                "frozen input: continentBias must be 0.31 (got " + p.continentBias() + ")");
        require(p.peakHeightFraction() == 0.92,
                "frozen input: peakHeightFraction must be 0.92 (got "
                        + p.peakHeightFraction() + ")");
        require(p.snowLine() == 0.7,
                "frozen input: snowLine must be 0.7 (got " + p.snowLine() + ")");
        require(p.verticalScale() == 1.0,
                "frozen input: verticalScale must be 1.0 (got " + p.verticalScale() + ")");
        require(p.seaLevel() == 63 && p.minY() == -64 && p.maxY() == 320,
                "frozen input: world height must be sea=63 minY=-64 maxY=320 (got "
                        + p.seaLevel() + "/" + p.minY() + "/" + p.maxY() + ")");

        // ---- 过程开关：P1 资产明确排除侵蚀与水文 ----
        // 侵蚀在 generateChunkBody 里是**无条件**执行的（不受 riverEnabled 控制），
        // 只有 erosionEnabled=false 时 delta 才恒 0；否则 checkChunks 的
        // `c.height == reference.height` 必然失败。
        require(tomlBool(cfg, "erosionEnabled") == Boolean.FALSE,
                "frozen input: erosionEnabled must be false (P1 asset excludes particle erosion)");
        require(tomlBool(cfg, "riverEnabled") == Boolean.FALSE,
                "frozen input: riverEnabled must be false (P1 asset excludes hydrology)");
    }

    /**
     * 读 TOML 里的布尔开关；缺失返回 {@code null}（由调用方判负）。
     *
     * <p>用 Forge 规范的 {@link com.electronwill.nightconfig.core.Config#getOrElse} 按
     * <b>扁平路径</b>取，而不是自己遍历嵌套 map —— NightConfig 的
     * {@code valueMap()} 上层是节名字符串、下层是节内 Config，且子节（如
     * {@code [..Erosion]}）的键并不落在父节 map 里，手写遍历容易漏。</p>
     */
    private static Boolean tomlBool(CommentedFileConfig cfg, String key) {
        Object raw = findLeaf(cfg, key);
        return raw instanceof Boolean b ? b : null;
    }

    private static Object findLeaf(com.electronwill.nightconfig.core.Config cfg, String key) {
        // 深度优先：先看本层直接键，再递归子节；同时容忍扁平路径键 "a.b.c"。
        for (var e : cfg.valueMap().entrySet()) {
            if (key.equals(e.getKey())) return e.getValue();
            if (e.getValue() instanceof com.electronwill.nightconfig.core.Config sub) {
                Object v = findLeaf(sub, key);
                if (v != null) return v;
            }
        }
        for (var e : cfg.entrySet()) {
            String path = e.getKey();
            if (path != null && (path.equals(key) || path.endsWith("." + key))) return e.getValue();
        }
        return null;
    }

    private static void render(GeoGenesisTerrain terrain, TerrainParams p, Path out) throws Exception {
        Path file = out.resolve("p1-cell-heights.f64be");
        // ★ 桥接 S2（2026-11-13）：陆侧逐位不变的双断言——全图 SHA（海侧变更后=新基准）
        //   与 陆侧 SHA（e>0 的单元，开关切换前后必须逐位一致）。
        MessageDigest landDigest = MessageDigest.getInstance("SHA-256");
        long landCells = 0;
        try (DataOutputStream s = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = -3642.5 + i * 5.0, z = -3365.5 + j * 5.0;
                    Cell c = terrain.sampleCellCoarse(x * p.horizontalScale(), z * p.horizontalScale());
                    require(Double.isFinite(c.height), "nonfinite height");
                    require(c.eClimate == c.e && c.typeWeights.length == TerrainClass.COUNT, "Cell metadata");
                    require(c.terrainType.isOcean() == (c.e < 0), "sea classification");
                    double sum = 0;
                    for (double w : c.typeWeights) { require(w >= 0 && Double.isFinite(w), "weight"); sum += w; }
                    require(Math.abs(sum - 1) < 1e-6, "weight sum");
                    s.writeDouble(c.height);
                    if (c.e > 0) {
                        landCells++;
                        landDigest.update(java.nio.ByteBuffer.allocate(8).putDouble(c.height).array());
                    }
                }
                if (j % 200 == 0) System.out.println("P1 row=" + j);
            }
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        System.out.println("productionCellSha=" + sha);
        String landSha = HexFormat.of().formatHex(landDigest.digest());
        System.out.println("landCells=" + landCells + " landCellSha=" + landSha);
        require(sha.equals(EXPECTED), "P1 height drift from verified P0");
        // EXPECTED_LAND 为空 = 首次标定跑（先记值）；非空 = 断言陆侧逐位不变。
        if (!EXPECTED_LAND.isEmpty()) {
            require(landSha.equals(EXPECTED_LAND), "P1 LAND-side drift (S2 must keep land bit-identical)");
        }
    }

    private static void checkChunks(GeoGenesisTerrain terrain, CellGenerator gen, TerrainParams p, Path out) throws Exception {
        MessageDigest metadata = MessageDigest.getInstance("SHA-256");
        MorphTerrainSampler reference = new MorphTerrainSampler(SEED, p, -64, 320);
        int count = 0;
        float maxSlope = 0;
        for (int[] pos : new int[][]{{0, 0}, {-401, -233}, {207, 286}, {420, 33}}) {
            Cell[] cells = terrain.getChunkCells(pos[0], pos[1]);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                double bx = pos[0] * 16 + x, bz = pos[1] * 16 + z;
                double wx = bx / p.horizontalScale(), wz = bz / p.horizontalScale();
                Cell c = cells[x * 16 + z];
                require(c.height == reference.height(wx, wz), "complete chunk height drift");
                require(c.height == terrain.sampleHeightNonBlocking(bx, bz), "B nonblocking height drift");
                require(gen.terrainEQuick(wx, wz) == c.e && c.eClimate == c.e, "quick/eClimate drift");
                require(gen.erosionDeltaE(wx, wz) == 0 && gen.erodedHeight(wx, wz) == c.height, "external delta");
                require(c.riverType == 0 && !c.isLake && !c.lakeMask, "hydrology leaked");
                require(Float.isFinite(c.gradient) && c.gradient >= 0, "gradient");
                maxSlope = Math.max(maxSlope, c.gradient);
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(256);
                for (double v : new double[]{c.height, c.e, c.eClimate, c.eLand, c.continent,
                        c.temperature, c.humidity, c.precipitation, c.snowLineE, c.rockTilt, c.oasisNoise}) b.putDouble(v);
                b.putFloat(c.gradient);
                for (int v : new int[]{c.terrainType.ordinal(), c.variantTerrain.ordinal(), c.biomeType.ordinal(),
                        c.rockLayer, c.rockTypeId, c.rockSeqPacked, c.isSnow ? 1 : 0}) b.putInt(v);
                metadata.update(b.array());
                for (double w : c.typeWeights) metadata.update(java.nio.ByteBuffer.allocate(8).putDouble(w).array());
                count++;
            }
        }
        require(maxSlope > 0, "missing slope");
        String hash = HexFormat.of().formatHex(metadata.digest());
        Files.writeString(out.resolve("cell-metadata.sha256"), hash + "\n");
        System.out.println("completeChunkCells=" + count + " bitDifferent=0 maxSlope=" + maxSlope);
        System.out.println("cellMetadataSha=" + hash);
    }

    private static void checkWorldMarkers() {
        GeoGenesisWorldData fresh = new GeoGenesisWorldData();
        fresh.initializeAlgorithm(true);
        require(fresh.usesMorphBase(), "new world not morph");
        GeoGenesisWorldData reopened = GeoGenesisWorldData.load(fresh.save(new net.minecraft.nbt.CompoundTag()));
        reopened.initializeAlgorithm(false);
        require(reopened.usesMorphBase(), "new world reopen switched algorithm");
        GeoGenesisWorldData legacy = GeoGenesisWorldData.load(new net.minecraft.nbt.CompoundTag());
        legacy.initializeAlgorithm(true);
        require(!legacy.usesMorphBase(), "unmarked save upgraded");
        GeoGenesisWorldData oldNoData = new GeoGenesisWorldData();
        oldNoData.initializeAlgorithm(false);
        require(!oldNoData.usesMorphBase(), "existing world without saved data upgraded");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
