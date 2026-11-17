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
     *
     * <p>★ 阶段六修正重立（2026-11-17，schema137）并入三笔：
     * ① FBM 融合补账（shapedShell 恢复 amp 1.6 + 第 5 次全域锚 {-3.327,…,2.342}）；
     * ② 火山归位类型层覆盖族（VolcanoRecipe：R=120wu / amp=0.24e / 火口 / 裙摆 /
     *    冲沟——用户目检定稿），修掉初版 {@code edifice()} 双重缩放（57 格被压成 12 格）；
     * ③ 陆上门控接线（ContinentField 原始 c &gt; 0.004 = 全可见线；缺位时 ~90% 锥顶
     *    被海岸混合带淹没）。关火山（-Dgeogenesis.tectonicFeatures=false）逐位复现
     *    schema136 中间态 4a1c19ed…/0f04e118…（landCells 585430；实测已验证——
     *    与阶段五差两笔：第 5 次锚重标 + FBM amp 1.6，不属火山开关的回退范围）。</p>
     *
     * <p>★ 阶段六修正二重立（2026-11-17，schema138；用户"像从地面挤出来，
     * 真实火山是堆积起来的"）：① 堆积基底 apron（3.4R/0.24·amp/轮廓±18%）；
     * ② 火山类型平滑过渡（VOLCANO 进 typeWeights 加权竞争 + 归一化）。
     * 137 基准留档 d7ead4d1…/63b4e7a7…（landCells 585441）；本次 landCells 585461
     * （+20：堆积扇把近岸格抬过水面）。关火山仍应复现 4a1c19ed…/0f04e118…。</p>
     *
     * <p>★ 阶段六修正三重立（2026-11-17，schema139）：海山/洋中脊归位到洋底几何层
     * （特征增量并入 crustProfile 的 deep 锚）。全图 SHA 变，<b>陆侧 SHA 与 138
     * 逐位一致</b>（585461 / 5dd95cec…）——证明归位只动海洋、陆侧零扰动。
     * 138 基准留档 1f28553a…/5dd95cec…。</p>
     *
     * <p>★ 阶段七 A+C1 重立（2026-11-17，schema140）：洋中脊体形构造化（幅度
     * 0.18→0.05，脊带落差交给年龄律）+ 沉积平滑（丘陵振幅随 age 衰减）。
     * 全图 SHA 变，陆侧 SHA 仍与 139 逐位一致（585461 / 5dd95cec…）。
     * 139 基准留档 5032f9a5…/5dd95cec…。</p>
     *
     * <p>★ 阶段七 B 重立（2026-11-17，schema141）：脊轴蜿蜒（共享 RidgeWander ±90wu，
     * 脊带+裂谷同偏移）+ 轴向高度分段（1100wu/−40%~+40%）。
     * 全图 SHA 变，陆侧 SHA 仍与 140 逐位一致（585461 / 5dd95cec…）。
     * 140 基准留档 f91c4f39…/5dd95cec…。</p>
     *
     * <p>★ 阶段七 B 修正（2026-11-17，schema142）：脊轴隆起 0.05→0.09e、σ 320→200
     * （「山脊都没一点痕迹」）。全图 SHA 变，陆侧仍与 141 逐位一致。
     * 141 基准留档 8b1a42ba…/5dd95cec…。</p>
     *
     * <p>★ 阶段七 B 二次修正（2026-11-17，schema143）：剖面图工具 + 自动选点证实
     * 洋中脊全在大陆坡斜坡上；脊轴幅度 0.09→0.15e + 峰值感知自适应压缩。
     * 全图 SHA 变，陆侧仍与 142 逐位一致。142 基准留档 fca9d346…/5dd95cec…。</p>
     *
     * <p>★ 阶段七 B 最终落地（2026-11-17，schema144）：RidgeProfile 两段式剖面
     * （U 形地堑 half 80wu/深 0.13e + 脊肩 @300wu/0.19e + 翼部至 800wu）+
     * 删 CrustField 独立裂谷 + 删无符号距离位置偏移（会消失/裂双谷）+
     * 类型判定改用 ridgeEnvelope（地堑也算脊带成员）。
     * 全图 SHA 变，陆侧仍与 143 逐位一致。143 基准留档 dddf3171…/5dd95cec…。</p>
     *
     * <p>★ 阶段七 B 三次修正（2026-11-17，schema145）：脊带尺度重标定
     * （420wu 半宽，匹配板块格子 2000wu）+ constitution 真值修复
     * （smoothPos 返回应力值而非 0~1）+ 地堑壁 C0 连续。
     * 全图 SHA 变，陆侧仍与 144 逐位一致。144 基准留档 dd2f20fa…/5dd95cec…。</p>
     */
    private static final String EXPECTED =
            "41bd1cd5aa9c5715220ee8a2e1a8023be1d642bda5a9b49324391e0ce4c67fdd";

    /**
     * ★ 阶段六修正重立（2026-11-17）：landCells 586223→585441
     * （第 5 次锚把陆占比 0.3385→0.3227；火山覆盖只影响局部 ±，净变化不大）。
     * 全域陆占比由 ContinentAnchorProbe（0.3227±0.02）与 OceanFloorProbe 守护。
     * 标定协议见 {@link #render}。
     */
    private static final String EXPECTED_LAND = "5dd95cec86eac1112feacaa63737ea67f267c1ee77cb873bc1ef1a515b7705a8";

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
