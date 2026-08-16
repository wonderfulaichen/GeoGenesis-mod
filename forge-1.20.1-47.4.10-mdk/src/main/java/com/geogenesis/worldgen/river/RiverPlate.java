package com.geogenesis.worldgen.river;

import java.util.List;

/**
 * tile 河板块（确定性几何河网 · Phase 1）。
 *
 * <p>一个 plate = 一个 128wu tile 内**所有可能影响本 tile 的段**：
 * 3×3 basin 邻域内主河链段 + 支流段（段最长跨越相邻 basin，3×3 邻域覆盖足够）。</p>
 *
 * <p>Plate 构建完全确定性（依赖世界坐标 + e 场 + 种子哈希），
 * 任意线程/任意时序构建结果一致 → 采样（Phase 2）结构性无缝。</p>
 *
 * @param tileCX tile 网格 X（basin 单位）
 * @param tileCZ tile 网格 Z（basin 单位）
 * @param segments 本 tile 相关段（去重、顺序确定）
 */
public record RiverPlate(int tileCX, int tileCZ, List<RiverSegment> segments) {

    /** 判断点 (wx,wz) 是否可能命中本 plate（粗查，采样期再精确到段） */
    public boolean covers(double wx, double wz) {
        int tx = (int) Math.floor(wx / RiverNetwork.BASIN_SIZE);
        int tz = (int) Math.floor(wz / RiverNetwork.BASIN_SIZE);
        return Math.abs(tx - tileCX) <= 1 && Math.abs(tz - tileCZ) <= 1;
    }

    public int segmentCount() {
        return segments.size();
    }
}
