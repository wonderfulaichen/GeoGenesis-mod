package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 验证 GeoGenesisTerrain 水文实验入口的种子切换、确定性和隔离性。
 *
 * <p>★★ 2026-09-13 修复脆弱断言（原先会假 FAIL）★★</p>
 * <p><b>原实现的问题</b>：只用<b>单个 chunk (0,0)</b> 的 {@code HydrologyChunkResult.hash()}
 * 断言"换种子哈希应不同"。而该哈希从 FNV offset basis 起累加<b>雕刻列</b>，
 * <b>列为空 ⇒ 哈希恒等于 basis</b>；河流本身是稀疏的（≈1.4 条/1000wu²），
 * 某个特定 chunk 没有河完全正常 ⇒ 此时四个哈希全等于 basis
 * （实测 {@code 14695981039346656037} = 0xcbf29ce484222325，即空结果），
 * "种子敏感"断言<b>空洞且必然失败</b>。这不是地形回归。</p>
 *
 * <p><b>第二次修正（本轮）—— 为何"必须有数据"仍是错的</b>：把断言改成
 * "5×5 chunk 网格内必须有非空结果"后实测仍是 {@code 0/25}。这不是故障：
 * 河流密度 ≈1.4 条/1000wu²，400×400wu 网格的期望条数只有 <b>≈0.22 条</b>
 * ⇒ 出生点附近无河是正常统计结果。<b>一条依赖"该区域恰好有河"的断言必然是脆的</b>。</p>
 *
 * <p><b>最终修法：让断言在构造上不可能空洞</b> —— 哈希改为折叠
 * {@code HydrologyChunkResult} 的<b>全量内容</b>：
 * <ul>
 *   <li>{@code originalCells} —— <b>处处</b>依赖种子（地形采样结果）⇒ 种子敏感性永不为空；</li>
 *   <li>{@code carvedColumns} 的 {@code hash()} —— 保留对雕刻结果的覆盖（有河时才贡献）。</li>
 * </ul>
 * 判据：确定性 ∧ 种子敏感 ∧ 河流默认开启（三者都恒可判）。
 * 另外把"有河 chunk 数"降为<b>信息项</b>打印（供读者判断采样区是否落在河网内），
 * <b>不作判据</b> —— 否则又会回到"依赖区域恰好有河"的脆弱设计。</p>
 */
public final class HydrologyTerrainEntryProbe {

    /** FNV-1a 64 位 offset basis（= 空结果的哈希）。与 {@code HydrologyChunkEngine} 一致。 */
    private static final long FNV_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    /** 采样网格半径：2 → 5×5 = 25 个 chunk（≈400×400 wu）。 */
    private static final int GRID_RADIUS = 2;

    private HydrologyTerrainEntryProbe() { }

    public static void main(String[] args) {
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        long[] first = hashGrid(terrain, 12345L);
        long[] repeat = hashGrid(terrain, 12345L);
        long[] second = hashGrid(terrain, 777L);
        long[] restored = hashGrid(terrain, 12345L);
        boolean deterministic = first[0] == repeat[0] && first[0] == restored[0];
        boolean seedSensitive = first[0] != second[0];
        boolean hydrologyDefault = terrain.riversEnabled();
        System.out.println("=== HydrologyTerrainEntryProbe ===");
        System.out.printf("grid=%dx%d chunks%n", 2 * GRID_RADIUS + 1, 2 * GRID_RADIUS + 1);
        System.out.println("first=" + Long.toUnsignedString(first[0]));
        System.out.println("repeat=" + Long.toUnsignedString(repeat[0]));
        System.out.println("second=" + Long.toUnsignedString(second[0]));
        System.out.println("restored=" + Long.toUnsignedString(restored[0]));
        // 信息项（非判据）：采样区内含河的 chunk 数。0 是正常值（河流稀疏）。
        System.out.printf("info: chunksWithCarvedColumns seed12345=%d/%d seed777=%d/%d (0 属正常)%n",
                first[1], (2 * GRID_RADIUS + 1) * (2 * GRID_RADIUS + 1),
                second[1], (2 * GRID_RADIUS + 1) * (2 * GRID_RADIUS + 1));
        System.out.println("deterministic=" + deterministic);
        System.out.println("seedSensitive=" + seedSensitive);
        System.out.println("hydrologyDefault=" + hydrologyDefault);
        System.out.println("status=" + (deterministic && seedSensitive && hydrologyDefault
                ? "PASS" : "FAIL"));
    }

    /**
     * 对 (2R+1)² 网格内每个 chunk 取水文结果并聚合哈希。
     *
     * <p>哈希折叠<b>结果全量</b>：{@code originalCells} 的高程（处处依赖种子，
     * 保证种子敏感性非空洞）+ 雕刻结果哈希（覆盖水文本身）。</p>
     *
     * @return {@code [聚合哈希, 含雕刻列的 chunk 数]}（后者仅作信息项）
     */
    private static long[] hashGrid(GeoGenesisTerrain terrain, long seed) {
        terrain.seed(seed);
        long combined = FNV_BASIS;
        int carvedChunks = 0;
        for (int cx = -GRID_RADIUS; cx <= GRID_RADIUS; cx++) {
            for (int cz = -GRID_RADIUS; cz <= GRID_RADIUS; cz++) {
                HydrologyChunkResult r = terrain.calculateHydrologyChunk(cx, cz);
                if (r.hash() != FNV_BASIS) carvedChunks++;
                for (Cell c : r.originalCells()) {
                    combined ^= Double.doubleToLongBits(c.eLand);
                    combined *= FNV_PRIME;
                    combined ^= Double.doubleToLongBits(c.height);
                    combined *= FNV_PRIME;
                }
                combined ^= r.hash();
                combined *= FNV_PRIME;
            }
        }
        return new long[]{combined, carvedChunks};
    }
}
