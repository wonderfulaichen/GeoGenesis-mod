package com.geogenesis.worldgen.terrain;

/**
 * 灌水层的<b>补洞</b>规则：把"被水体包围、地面又低于邻面"的干列并入水体。
 *
 * <h3>要治的缺陷（已量化）</h3>
 * <p>{@code runWaterFillGapProbe}（seed 9139912035078620160）在 region(0,-2) 实测：
 * <b>干洞 75 列</b>。这些列的计划是"干"（{@code riverType==0}），地面却比周围水面低
 * 0.5~0.6 块 —— 落块按整数 {@code floor(waterTop)} 做，而
 * {@code floor(waterTop) <= floor(ground)} ⇒ 灌水循环一次都不执行 ⇒
 * 实机看到"水中间缺一格"。</p>
 *
 * <h3>不做什么（重要）</h3>
 * <p><b>不做"跨列取最大水面"</b>。曾经试过按 chunk 内连通分量统一整数水面：
 * 结果是每个 chunk 各取自己的最大值，反而<b>在 chunk 边界制造新台阶</b>
 * （实测台阶 417 → 565）。要真正消除"水面不一致"，必须让同一水体的水位在
 * <b>水文计划层</b>就是常量（需要水体/盆地身份，灌水层拿不到）。</p>
 *
 * <p>另：河面逐段下降 1 块是<b>正常现象</b>（河流有坡降），不是缺陷；
 * 探针已把"台阶"拆成「湖内」与「河面」两栏，只有湖内台阶算缺陷。
 * 实测两区<b>湖内台阶 = 0</b>。</p>
 *
 * <p><b>确定性</b>：只看本 chunk 的 8 邻与本列地面，分量/顺序无关。</p>
 */
public final class WaterSurfaceUnifier {

    /** 一个 chunk 的列数（16×16）。 */
    public static final int COLUMNS = 256;

    private WaterSurfaceUnifier() { }

    /**
     * @param cells       本 chunk 的 256 个 Cell（索引 = lx*16+lz，与 {@code fillTerrainColumn} 一致）
     * @param seaLevel    海平面 Y
     * @param hydrologyOn 水文是否启用（关闭时返回全 -1，调用方保持旧行为）
     * @return 256 项：&gt;=0 = 该列整数水面（Y）；-1 = 不灌水
     */
    public static int[] unifiedTops(Cell[] cells, double seaLevel, boolean hydrologyOn) {
        int[] top = new int[COLUMNS];
        java.util.Arrays.fill(top, -1);
        if (!hydrologyOn || cells == null || cells.length != COLUMNS) {
            return top;
        }
        for (int k = 0; k < COLUMNS; k++) {
            Cell cell = cells[k];
            if (cell == null) {
                continue;
            }
            boolean river = cell.riverType != 0 && cell.riverSurfaceY > cell.height;
            boolean ocean = cell.height < seaLevel;
            if (!river && !ocean) {
                continue;
            }
            double plane = ocean ? Math.max(cell.riverSurfaceY, seaLevel) : cell.riverSurfaceY;
            top[k] = (int) Math.floor(plane);
        }
        // 补洞：8 邻 ≥5 列有水、且地面低于邻面 ⇒ 按邻面灌水
        for (int k = 0; k < COLUMNS; k++) {
            if (top[k] >= 0 || cells[k] == null) {
                continue;
            }
            int cx = k / 16, cz = k % 16;
            int wetN = 0;
            int hi = Integer.MIN_VALUE;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (di == 0 && dj == 0) {
                        continue;
                    }
                    int nx = cx + di, nz = cz + dj;
                    if (nx < 0 || nz < 0 || nx >= 16 || nz >= 16) {
                        continue;
                    }
                    int nk = nx * 16 + nz;
                    if (top[nk] >= 0) {
                        wetN++;
                        hi = Math.max(hi, top[nk]);
                    }
                }
            }
            if (wetN >= 5 && hi > cells[k].height + 1e-9) {
                top[k] = hi;
            }
        }
        return top;
    }
}