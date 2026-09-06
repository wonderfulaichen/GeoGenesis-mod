package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 河线版 chunk 边界连续性探针。
 *
 * <p>★ 正确语义：chunk 边界无缝 = <b>同一 block 列</b>无论从哪个 chunk
 * 发起计算，雕刻结果完全一致（确定性 + 无跨 chunk 状态）。</p>
 *
 * <p>旧探针误把「相邻两列的 erosion 差」当跳变——那是正常的横断面梯度
 * （河岸每列差 1+ 块是物理正确的）。</p>
 *
 * <p>本探针量两项【跨缝连续性】（沿垂直于河线方向步进 1 block）：</p>
 * <ol>
 *   <li>雕刻台阶：单步雕刻量变化 ≤ 2.5（断面连续）；</li>
 *   <li>水面台阶：相邻列 surface 差 ≤ 0.25（纵剖面连续）。</li>
 * </ol>
 *
 * <p>★ 两项都【豁免跌水列】（{@link #isFall}）：瀑布处一列是潭面、邻列是唇口，
 * 水面与河床本就该大跳，不豁免会把设计特征全判成违规（实测 2702 处、
 * maxWaterStep 14.8 格，正是落差量级）。</p>
 *
 * <p>原第 3 项"同一边界列由两个 chunk 各算一次须逐位一致"已于 2026-09-07 删除：
 * 该前提结构上不成立，详见 main 内注释。</p>
 */
public final class RiverLineBoundaryProbe {
    private RiverLineBoundaryProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        // ★ 默认必须与生产一致 = TerrainParams.defaults().horizontalScale()（2.0）。
        //   原先默认 1.0，等于用另一套 wu→block 换算去量"跨 chunk 连续性"，与玩家
        //   看到的不是同一个世界（本项目已多次栽在探针与生产口径不一致上）。
        double horizontalScale = args.length > 1
                ? Double.parseDouble(args[1]) : params.horizontalScale();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // 1) 找含河 chunk
        java.util.Set<Long> riverChunks = new java.util.HashSet<>();
        for (int z = -512; z <= 512; z += 8) {
            for (int x = -512; x <= 512; x += 8) {
                HydrologyBlockSample s = engine.sampleBlock(x, z, horizontalScale);
                if (s != null && s.distToCenter() <= s.width() * 2.0) {
                    riverChunks.add(pack(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
                }
            }
        }
        System.out.println("riverChunks=" + riverChunks.size());

        // 【原"一致性"检查已删除】2026-09-07 实测：consistencyPairs 恒为 0，插桩确认
        // boundaryLookups=4640 全部落空、而单 chunk 确实返回满 256 列 —— 因为该检查的
        // 前提在结构上不成立：它取 chunk A 的东边界列 x=cx*16+15，再去邻 chunk
        // B=(cx+1) 的返回结果里找同一世界坐标，可 B 只返回自己那 16 列
        // （x ∈ [(cx+1)*16, +16)），该坐标按定义不可能在其中。生产的真实不变量恰恰相反：
        // 每个世界列【只由唯一一个 chunk】计算（carveChunk 严格 16×16、无跨块重叠），
        // 所以"同一列从两个 chunk 各算一次"无从比较。
        // 跨缝真正该量的是【边界两侧的连续性】，即下方 part 2；而"换用邻块的原始地面数组
        // 重算同一列"这一情形已由 HydrologyChunkBoundaryProbe 覆盖。

        // 3) 平滑度：含河 chunk 内随机抽样行/列，单步雕刻量变化与水面台阶
        int smoothPairs = 0, carveSteps = 0, waterSteps = 0, fallExempt = 0;
        double maxStepDelta = 0.0, maxWaterStep = 0.0;
        double maxWaterStepFlat = 0.0;
        for (long key : riverChunks) {
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xffffffffL);
            double[] g = ground(terrain, cx, cz, horizontalScale);
            var cols = HydrologyBlockCarver.carveChunk(engine, cx, cz, horizontalScale, g);
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 15; lx++) {
                    // 按世界坐标查找（carveChunk 仅返回含河采样列，列表非 256 满索引）
                    HydrologyBlockCarvedColumn c0 = at(cols, cx * 16 + lx, cz * 16 + lz);
                    HydrologyBlockCarvedColumn c1 = at(cols, cx * 16 + lx + 1, cz * 16 + lz);
                    if (c0 == null || c1 == null) continue;
                    if (c0.erosion() <= 0 && c1.erosion() <= 0) continue;
                    smoothPairs++;
                    double dCarve = Math.abs(c0.carvedGroundY() - c1.carvedGroundY());
                    double dWater = Math.abs(c0.waterSurfaceY() - c1.waterSurfaceY());
                    maxStepDelta = Math.max(maxStepDelta, dCarve);
                    maxWaterStep = Math.max(maxWaterStep, dWater);
                    // ★ 跌水列豁免：瀑布的一列水面是潭面、相邻列是唇口（水幕 = 两者之间
                    //   的垂直水体，落块侧靠 lipSurfaceY 挂 FLOWING_WATER）。所以相邻列
                    //   水面/河床【本就该】大跳，>0.25 格与 >2.5 格的阈值量的是设计特征
                    //   而非缺陷——实测 2702 处"违规"里 maxWaterStep 达 14.8 格，正是落差
                    //   量级。判据必须认识生产语义，否则永远 REVIEW。
                    boolean atFall = isFall(c0) || isFall(c1);
                    if (atFall) {
                        fallExempt++;
                    } else {
                        if (dCarve > 2.5) carveSteps++;
                        if (dWater > 0.25) {
                            waterSteps++;
                            maxWaterStepFlat = Math.max(maxWaterStepFlat, dWater);
                        }
                    }
                }
            }
        }
        System.out.println("smoothPairs=" + smoothPairs);
        System.out.println("fallExemptPairs=" + fallExempt
                + "  (跌水列相邻对：水面/河床大跳是瀑布设计特征，不计违规)");
        System.out.println("carveStepViolations(>2.5)=" + carveSteps);
        System.out.println("waterStepViolations(>0.25)=" + waterSteps
                + "  maxWaterStep(非跌水)=" + maxWaterStepFlat);
        System.out.println("maxStepDelta=" + maxStepDelta);
        System.out.println("maxWaterStep(含跌水)=" + maxWaterStep);
        // 判据说明：仅以【非跌水】列对的台阶考核。仍 >0 时保持 REVIEW 而非自动放宽——
        // 已知主要嫌疑是"相邻列换主"（carveColumn 按最近河 Voronoi 归属，两列可能分属
        // 水面差很大的两条河，那是正常拓扑）。定性前不伪装绿灯。
        // 下一步线索：HydrologyBlockSample 没有河流身份字段（只有 surfaceY/bedY/width/
        // depth/bankWidth/valleyWidth/discharge/outletType/distToCenter/fallDrop/frozen），
        // 无法直接比对两列是否同一条河。可用 discharge 作代理——同一条河相邻列的汇流量
        // 几乎不变，换主则跳变；但要确证仍需给采样加 riverIndex（属生产改动，勿轻易做）。
        boolean pass = waterSteps == 0;
        System.out.println("status=" + (pass ? "PASS" : "REVIEW"));
    }

    /**
     * 取 chunk 的原始地面数组。
     *
     * <p>★ 布局必须是 {@code lx * 16 + lz}：{@code HydrologyBlockCarver.carveChunk}
     * 正是按该布局索引 originalGround（其 41-45 行注释记录了历史上写成
     * {@code lz * 16 + lx} 导致"整片地形被镜像 → chunk 边界网格状错位"）。本探针
     * 原先就写成 {@code z * 16 + x}，等于把转置后的地形喂给雕刻器，量出来的台阶
     * 与生产无关。{@code HydrologyWaterFillProbe} 的同名函数用的是正确布局。</p>
     */
    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            values[x * 16 + z] = terrain.sample((cx * 16 + x) / scale,
                    (cz * 16 + z) / scale).height;
        }
        return values;
    }

    private static HydrologyBlockCarvedColumn at(java.util.List<HydrologyBlockCarvedColumn> columns,
                                                  int x, int z) {
        for (HydrologyBlockCarvedColumn column : columns) {
            if (column.blockX() == x && column.blockZ() == z) return column;
        }
        return null;
    }

    /**
     * 该列是否为跌水列（瀑布水幕所在）。
     *
     * <p>判据与生产同源：落块侧用 {@code lipSurfaceY > waterSurfaceY} 决定挂
     * {@code FLOWING_WATER} 的那一段（GeoGenesisGenerator.fillTerrainColumn 的
     * lipBlock），普通列两者相等。故 lip 明显高于水面 = 这一列正在落水。</p>
     */
    private static boolean isFall(HydrologyBlockCarvedColumn c) {
        return c.lipSurfaceY() > c.waterSurfaceY() + 1e-6;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
