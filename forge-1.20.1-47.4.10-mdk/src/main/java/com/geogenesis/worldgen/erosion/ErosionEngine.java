package com.geogenesis.worldgen.erosion;

import com.geogenesis.config.GeoGenesisConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地确定性液滴侵蚀引擎 —— 三尺度层叠 + localCharge 正反馈 + 局部 cascade 级联。
 *
 * <p><b>升级概要（相对备份基线）</b>：
 * <ul>
 *   <li><b>三尺度层叠</b>（R=8/4/2，取代旧 R=6/4）：宏观山谷→中脊→微冲沟，覆盖更广特征尺度。</li>
 *   <li><b>localCharge 正反馈</b>：每粒子携带自起点累计高差 localCharge，容量公式乘以
 *       {@code (1 + α·localCharge)}，使长程深流粒子雕得深、短程浅流雕得浅 → 主流深支流浅，
 *       几何涌现尺度多样性，不依赖全局统计（完全本地确定 → 天然无缝）。</li>
 *   <li><b>局部 cascade 级联</b>：侵蚀后对 8 邻居按高度差做 settling，平滑河床底部。
 *       SH 真实感的第二个来源。</li>
 *   <li>其余无缝机制（本地算子 + 确定性逐区块播种 + 双平滑抹微断裂）全部保留。</li>
 * </ul>
 *
 * <p><b>无缝原理（不变）</b>：
 * 某点侵蚀结果只取决于它周围一小圈笔刷邻域（R_MAX=8）与下落液滴轨迹；
 * 对提取中心 16×16 而言，笔刷邻域全落在 tile 的 border 内 → 结果只与世界坐标有关，
 * 与"用哪个 tile 算"无关 → 两相邻 tile 算出来一致 → 无缝（仅 smoothing 边缘微断裂）。
 * 关键：液滴 <b>按 chunk 哈希确定性播种</b>，spawn 世界坐标 = tileOrigin + (chunkX*16 - tileOrigin) + offX，
 * tileOrigin 抵消 → 诞生世界坐标固定，轨迹是高度场的确定性函数 → 无缝。</p>
 */
public class ErosionEngine {

    private static final float INERTIA = 0.005f, GRAVITY = 2.5f, EVAP_RATE = 0.001f;
    private static final float ENTRAINMENT = 10f;
    /** 动量传递：1.0 对齐 SH 原版（原 0.6 导致河流不会自我增强） */
    private static final float MOMENTUM = 1.0f;
    private static final float DEPOSIT_SPEED = 0.02f;
    /** SimpleHydrology 型统一松弛率（原 depositionRate=0.1），高度差驱动的平衡浓度 → 侵蚀/沉积双向 */
    private static final float RELAX_RATE = 0.1f;
    /** cascade 局部每 N 步执行（原版每步，此处每 3 步平衡性能） */
    private static final int CASCADE_INTERVAL = 3;
    private static final float CASCADE_MAXDIFF = 0.01f;

    // ===== 三尺度配置 (v2 - 可见侵蚀) =====
    // 提升逻辑：spacing=4 双三次插值场比真实 terrainE 平滑很多 → 坡度 slp 变小 → 容量 cap 变小 → 需更大参数补偿。
    // C 尺度 (宏观河谷/山谷)：笔刷半径 7 覆盖 (2*7+1)²=225 邻域，DROPS_C=80 每区块约 0.3 滴/格，寿命 40 步
    private static final int R_C = 7, DROPS_C = 80, LIFE_C = 40;
    private static final float ERODE_C = 0.060f, DEPOSIT_C = 0.015f;

    // M 尺度 (中脊/山沟)：半径 4，(2*4+1)²=81 邻域，寿命 25 步
    private static final int R_M = 4, DROPS_M = 50, LIFE_M = 25;
    private static final float ERODE_M = 0.080f, DEPOSIT_M = 0.020f;

    // F 尺度 (细沟/微冲沟)：半径 2，(2*2+1)²=25 邻域，寿命 15 步
    private static final int R_F = 2, DROPS_F = 30, LIFE_F = 15;
    private static final float ERODE_F = 0.100f, DEPOSIT_F = 0.025f;

    /** 最大笔刷半径（pad = R_MAX + 2 = 9），相邻 tile 各 pad 9 + 中心区域重叠 2*9=18 块无缝 */
    private static final int R_MAX = Math.max(R_C, Math.max(R_M, R_F)); // =7

    public ErosionEngine(double dropsMul, int seed) {
        // dropsMul / seed 由 CellGenerator 从配置解析后传入；实际放大倍率运行时仍读配置，
        // 此处仅保留兼容签名。
    }

    // ===== 公共入口 =====

    public void applyErosionNormalized(float[][] h, int sz, int ox, int oz, float seaNorm, float str) {
        applyErosionNormalized(h, sz, ox, oz, seaNorm, str, null);
    }

    private void applyErosionNormalized(float[][] h, int sz, int ox, int oz,
                                        float seaNorm, float str, boolean[][] locked) {
        if (sz < 16 || str <= 0) return;

        double dropsMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionDropsMul, 1.0);
        double erodeMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionErodeMul, 1.0);
        double lcWeight  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionLocalChargeWeight, 1.0);
        double casStr    = cfgDbl(GeoGenesisConfig.INSTANCE.erosionCascadeStrength, 0.8);
        float strE       = (float) Math.max(0.1, str) * (float) erodeMul;

        int pad = R_MAX + 2;
        int bufSize = sz + pad * 2;
        float[] dis = new float[bufSize * bufSize];

        float[] flat = new float[bufSize * bufSize];
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++) {
                int srcZ = Math.max(0, Math.min(sz - 1, z - pad));
                int srcX = Math.max(0, Math.min(sz - 1, x - pad));
                flat[z * bufSize + x] = h[srcZ][srcX];
            }
        float[] flatPre = flat.clone();

        // ===== 构建三层笔刷 =====
        int r2C = R_C * R_C;
        int maxBC = (2 * R_C + 1) * (2 * R_C + 1);
        int[] bOffC = new int[maxBC]; float[] bWgtC = new float[maxBC];
        int bnC = buildBrush(bOffC, bWgtC, R_C, r2C, bufSize);

        int r2M = R_M * R_M;
        int maxBM = (2 * R_M + 1) * (2 * R_M + 1);
        int[] bOffM = new int[maxBM]; float[] bWgtM = new float[maxBM];
        int bnM = buildBrush(bOffM, bWgtM, R_M, r2M, bufSize);

        int r2F = R_F * R_F;
        int maxBF = (2 * R_F + 1) * (2 * R_F + 1);
        int[] bOffF = new int[maxBF]; float[] bWgtF = new float[maxBF];
        int bnF = buildBrush(bOffF, bWgtF, R_F, r2F, bufSize);

        float[][] savedLocked = null;
        if (locked != null) {
            savedLocked = new float[sz][sz];
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sz; x++)
                    if (locked[z][x]) savedLocked[z][x] = h[z][x];
        }

        // ===== 确定性逐区块播种 =====
        int wcmX = Math.floorDiv(ox, 16);
        int wcMX = Math.floorDiv(ox + sz - 1, 16);
        int wcmZ = Math.floorDiv(oz, 16);
        int wcMZ = Math.floorDiv(oz + sz - 1, 16);
        List<long[]> chunkList = new ArrayList<>();
        for (int cz = wcmZ; cz <= wcMZ; cz++)
            for (int cx = wcmX; cx <= wcMX; cx++)
                chunkList.add(new long[]{cx, cz});
        chunkList.sort((a, b) -> {
            if (a[1] != b[1]) return Long.compare(a[1], b[1]);
            return Long.compare(a[0], b[0]);
        });

        int maxD = (int) (Math.max(DROPS_C, Math.max(DROPS_M, DROPS_F)) * dropsMul);

        // ===== 三尺度液滴模拟（interleaved，与旧双尺度结构相同） =====
        for (int d = 0; d < maxD; d++) {
            for (long[] ck : chunkList) {
                int wcx = (int) ck[0], wcz = (int) ck[1];
                int relX = wcx * 16 - ox;
                int relZ = wcz * 16 - oz;

                if (d < (int) (DROPS_C * dropsMul)) {
                    spawnAndSim(flat, bufSize, wcx, wcz, d, relX, relZ, pad,
                                bOffC, bWgtC, bnC, locked, sz, dis,
                                ERODE_C * strE, DEPOSIT_C, LIFE_C, ox, oz,
                                (float) lcWeight, (float) casStr);
                }
                if (d < (int) (DROPS_M * dropsMul)) {
                    spawnAndSim(flat, bufSize, wcx, wcz, d * 137 + 1, relX, relZ, pad,
                                bOffM, bWgtM, bnM, locked, sz, dis,
                                ERODE_M * strE, DEPOSIT_M, LIFE_M, ox, oz,
                                (float) lcWeight, (float) casStr);
                }
                if (d < (int) (DROPS_F * dropsMul)) {
                    spawnAndSim(flat, bufSize, wcx, wcz, d * 137 + 2, relX, relZ, pad,
                                bOffF, bWgtF, bnF, locked, sz, dis,
                                ERODE_F * strE, DEPOSIT_F, LIFE_F, ox, oz,
                                (float) lcWeight, (float) casStr);
                }
            }
        }

        // ===== 双平滑 =====
        smoothErosionResult(flat, bufSize, pad, sz, seaNorm);
        smoothDepositionZones(flat, flatPre, bufSize, pad, sz);

        // ===== 写回 =====
        // 输出范围 [-1, 1]：海床负值（-0.5~0）和陆地正值（0~1）都保留
        // 让海洋也能被侵蚀（深海洋虽平坦但仍可微雕，海山/海沟/海渠在 -0.5 范围）
        for (int z = 0; z < sz; z++)
            for (int x = 0; x < sz; x++) {
                float v = flat[(z + pad) * bufSize + (x + pad)];
                h[z][x] = (Float.isNaN(v) || Float.isInfinite(v)) ? 0.5f : clampF(v, -1, 1);
            }

        if (savedLocked != null) {
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sz; x++)
                    if (locked[z][x]) h[z][x] = savedLocked[z][x];
        }
    }

    // ===== 笔刷构建工具 =====

    /** 建圈内各格点偏移 + 归一化权重。返回笔刷内的格点数 bn。 */
    private static int buildBrush(int[] bOff, float[] bWgt, int radius, int r2, int bufSize) {
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx * dx + dy * dy;
                if (d2 < r2) {
                    bOff[bn] = dy * bufSize + dx;
                    bWgt[bn] = 1f - (float) Math.sqrt(d2) / radius;
                    bn++;
                }
            }
        float s = 0;
        for (int i = 0; i < bn; i++) s += bWgt[i];
        for (int i = 0; i < bn; i++) bWgt[i] /= s;
        return bn;
    }

    // ===== 播种与下落 =====

    private void spawnAndSim(float[] flat, int bufSize, int worldCX, int worldCZ, int d,
                             int relX, int relZ, int pad,
                             int[] bOff, float[] bWgt, int bn,
                             boolean[][] locked, int baseSize,
                             float[] dis, float erodeSpeed, float depositSpeed, int lifetime,
                             int ox, int oz, float localChargeWeight, float cascadeStrength) {
        long chunkSeed = hash(worldCX * 31 + 7, worldCZ * 73 + 13);
        int cs = (int) (chunkSeed ^ (chunkSeed >>> 32));
        long ps1 = hash(cs + d * 17, d * 31 + cs);
        long ps2 = hash(d * 53 + cs, cs + d * 79);
        int offX = (int) ((ps1 & 0xFFFF) / 65536f * 16);
        int offZ = (int) ((ps2 & 0xFFFF) / 65536f * 16);
        int px = pad + relX + offX;
        int py = pad + relZ + offZ;
        if (px < 1 || px >= bufSize - 1 || py < 1 || py >= bufSize - 1) return;
        if (locked != null) {
            int lz = py - pad, lx = px - pad;
            if (lz >= 0 && lz < baseSize && lx >= 0 && lx < baseSize && locked[lz][lx]) return;
        }
        simulateDrop(flat, bufSize, px + 0.5f, py + 0.5f,
                     bOff, bWgt, bn, locked, pad, baseSize, dis,
                     erodeSpeed, depositSpeed, lifetime, ox, oz,
                     localChargeWeight, cascadeStrength);
    }

    /** SH 对齐液滴下落。关键改进：erf 放电反馈 + 局部 cascade + 片流 + 水下 */
    private void simulateDrop(float[] flat, int bufSize, float posX, float posY,
                              int[] bOff, float[] bWgt, int bn,
                              boolean[][] locked, int pad, int baseSize,
                              float[] dis, float erodeSpeed, float depositSpeed, int lifetime,
                              int ox, int oz,
                              float localChargeWeight, float cascadeStrength) {
        float dirX = 0, dirY = 0, sed = 0, spd = 1f, wat = 1f;

        int spawnIx = (int) posX, spawnIy = (int) posY;
        float startH = flat[spawnIy * bufSize + spawnIx];

        for (int st = 0; st < lifetime; st++) {
            int ix = (int) posX, iy = (int) posY;
            if (ix < 1 || ix >= bufSize - 2 || iy < 1 || iy >= bufSize - 2) return;
            int idx = iy * bufSize + ix;

            float fx = posX - ix, fy = posY - iy;
            float hNW = flat[idx], hNE = flat[idx + 1];
            float hSW = flat[idx + bufSize], hSE = flat[idx + bufSize + 1];
            float h0 = hNW * (1 - fx) * (1 - fy) + hNE * fx * (1 - fy)
                     + hSW * (1 - fx) * fy + hSE * fx * fy;
            // ---- 方向（含片流：梯度≈0 时确定性噪声驱动） ----
            float gx = (hNE - hNW) * (1 - fy) + (hSE - hSW) * fy;
            float gy = (hSW - hNW) * (1 - fx) + (hSE - hNE) * fx;
            float glen = (float) Math.sqrt(gx * gx + gy * gy);

            if (glen < 1e-12f) {
                // 片流模式：梯度≈0 → 噪声驱动方向（确定性、无缝）
                if (dirX == 0 && dirY == 0 && st > 2) {
                    long nh = hash(ix * 31 + 7, iy * 73 + 13);
                    dirX = ((float)(nh & 0xFFFF) / 65536f) * 0.6f - 0.3f;
                    dirY = ((float)((nh >>> 16) & 0xFFFF) / 65536f) * 0.6f - 0.3f;
                } else if (dirX == 0 && dirY == 0) return;
                // 有前次惯性方向则使用
            } else {
                dirX = dirX * INERTIA - gx * (1 - INERTIA);
                dirY = dirY * INERTIA - gy * (1 - INERTIA);
            }

            // ---- 动量偏置（放电量场） ----
            float ld = dis[idx];
            if (ld > 1f) {
                float a = MOMENTUM * ld / (ld + 10f);
                dirX += a * gx; dirY += a * gy;
            }

            float dlen = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (dlen < 1e-12f) return;
            dirX /= dlen; dirY /= dlen;

            spd = (float) Math.sqrt(spd + Math.abs(flat[idx] - h0) * GRAVITY);
            posX += dirX * Math.min(spd, 1.5f);
            posY += dirY * Math.min(spd, 1.5f);
            if (posX < 1 || posX >= bufSize - 2 || posY < 1 || posY >= bufSize - 2) return;

            // ---- 新位置 ---- 
            int nix = (int) posX, niy = (int) posY;
            float nfx = posX - nix, nfy = posY - niy;
            int nidx = niy * bufSize + nix;
            float nH0 = flat[nidx] * (1 - nfx) * (1 - nfy) + flat[nidx + 1] * nfx * (1 - nfy)
                      + flat[nidx + bufSize] * (1 - nfx) * nfy + flat[nidx + bufSize + 1] * nfx * nfy;
            // ==== SimpleHydrology 型统一平衡浓度公式（水下深度增强版） ====
            // 原版：c_eq = (1 + e·discharge) · heightDrop
            // 增强：加 depthBoost = max(0, -h0) · 0.01，模拟浊流物理：
            //       水深越大→悬浮物沉降越慢→平衡浓度越高→水下侵蚀延续
            //       浅水 (<5块): 无影响（heightDrop 主导）
            //       中等水 (~25块): 略微提升（~0.25块/液滴寿命）
            //       深水 (>50块): 显著（~5块/液滴寿命）
            float dh = nH0 - h0;          // 保留供 NaN 守卫（line 340）使用
            float erfVal = 1f - (float) Math.exp(-0.16f * ld * ld);
            float depthBoost = Math.max(0f, -h0) * 0.01f;  // 水深贡献的"等效下坡高度差"
            float heightDrop = Math.max(0f, -dh) + depthBoost;
            float c_eq = (1f + ENTRAINMENT * erfVal) * heightDrop;  // 平衡浓度
            float effD = 0.1f;                       // 松弛率 = depositionRate(=0.1)
            float delta = effD * (c_eq - sed);       // >0=侵蚀, <0=沉积

            // 笔刷邻域分布：权重须对正负通用
            // delta>0: 从邻域取材料（侵蚀）→ flat[bi] 减小, sed 增大
            // delta<0: 向邻域加材料（沉积）→ flat[bi] 增大, sed 减小
            for (int b = 0; b < bn; b++) {
                int bi = idx + bOff[b];
                if (bi < 0 || bi >= flat.length) continue;
                int bz = bi / bufSize, bx = bi % bufSize;
                if (locked != null) {
                    int bzL = bz - pad, bxL = bx - pad;
                    if (bzL >= 0 && bzL < baseSize && bxL >= 0 && bxL < baseSize && locked[bzL][bxL]) continue;
                }
                float weigh = delta * bWgt[b];
                if (weigh == 0f) continue;
                flat[bi] -= weigh;
                sed += weigh;
            }

            // ---- 放电量累积 ----
            dis[idx] += wat;

            // ---- 局部 cascade（每 CASCADE_INTERVAL 步执行，对齐 SH 每步语义） ----
            if (cascadeStrength > 0 && st % CASCADE_INTERVAL == 0) {
                cascadeLocal(flat, bufSize, nix, niy, cascadeStrength);
            }

            // NaN/Inf 守卫：陡峭下坡（dh 很负）可能导致 spd² + dh*GRAVITY < 0 → sqrt(NaN)
            // 若溢出则降速为 0.1（允许液滴继续存活的极小速度，非致命）
            float speedSq = spd * spd + dh * GRAVITY;
            spd = speedSq > 1e-12f ? (float) Math.sqrt(speedSq) : 0.1f;
            if (spd <= 0) return;
            wat *= (1 - EVAP_RATE);
        }
    }

    /** 局部 cascade：8 邻居按高度排序 + 距离加权 settling（SH 原版语义）。 */
    private void cascadeLocal(float[] flat, int bufSize, int cx, int cz, float strength) {
        // 收集 8 邻居
        float[] nh = new float[8];
        int[] ni = new int[8];
        int num = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                int nidx = (cz + dz) * bufSize + (cx + dx);
                if (nidx < 0 || nidx >= flat.length) continue;
                nh[num] = flat[nidx];
                ni[num] = nidx;
                num++;
            }
        }
        if (num < 3) return;

        // 冒泡排序（num=8 足够了）
        for (int i = 0; i < num; i++)
            for (int j = i + 1; j < num; j++)
                if (nh[i] > nh[j]) {
                    float th = nh[i]; nh[i] = nh[j]; nh[j] = th;
                    int ti = ni[i]; ni[i] = ni[j]; ni[j] = ti;
                }

        float curH = flat[cz * bufSize + cx];
        for (int i = 0; i < num; i++) {
            float diff = curH - nh[i];
            if (Math.abs(diff) < 0.001f) continue;

            float ddx = (ni[i] % bufSize) - cx;
            float ddz = (ni[i] / bufSize) - cz;
            float dist = (float) Math.sqrt(ddx * ddx + ddz * ddz);

            // SH 公式：excess = |diff| - dist * maxdiff
            float excess = Math.abs(diff) - dist * CASCADE_MAXDIFF;
            if (excess <= 0) continue;

            // SH 公式：transfer = settling * excess / 2 (capped at 40% of diff)
            float transfer = strength * excess * 0.5f;
            transfer = Math.min(transfer, Math.abs(diff) * 0.4f);

            if (diff > 0) {
                flat[cz * bufSize + cx] -= transfer;
                flat[ni[i]] += transfer;
            } else {
                flat[cz * bufSize + cx] += transfer;
                flat[ni[i]] -= transfer;
            }
        }
    }

    // ===== 双平滑（保留，抹微断裂） =====

    private void smoothErosionResult(float[] flat, int bufSize, int pad, int sz, float seaNorm) {
        int radius = 3;
        float radiusSq = radius * radius;
        float smoothingRate = 0.4f;
        int passes = 1;
        int start = pad + radius;
        int end = pad + sz - radius;

        for (int pass = 0; pass < passes; pass++) {
            float[] smoothed = flat.clone();
            for (int z = start; z < end; z++) {
                for (int x = start; x < end; x++) {
                    int idx = z * bufSize + x;
                    float h = flat[idx];
                    float heightModifier;
                    if (h <= seaNorm) heightModifier = 0.0f;   // 水下不平滑——保留侵蚀痕迹（沟壑/水下峡谷）
                    else if (h >= seaNorm + 0.25f) heightModifier = 0.0f;
                    else heightModifier = 1.0f - (h - seaNorm) / 0.25f;
                    if (heightModifier <= 0.01f) continue;

                    float total = 0, weights = 0;
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dx = -radius; dx <= radius; dx++) {
                            float dist2 = dx * dx + dz * dz;
                            if (dist2 <= radiusSq) {
                                int ni_z = Math.max(0, Math.min(bufSize - 1, z + dz));
                                int ni_x = Math.max(0, Math.min(bufSize - 1, x + dx));
                                int ni = ni_z * bufSize + ni_x;
                                float weight = 1.0f - dist2 / radiusSq;
                                total += flat[ni] * weight;
                                weights += weight;
                            }
                        }
                    }
                    if (weights > 0) {
                        float avg = total / weights;
                        float diff = h - avg;
                        smoothed[idx] = h - diff * smoothingRate * heightModifier;
                    }
                }
            }
            System.arraycopy(smoothed, 0, flat, 0, flat.length);
        }
    }

    private void smoothDepositionZones(float[] flat, float[] flatPre, int bufSize, int pad, int sz) {
        int start = pad + 1;
        int end = pad + sz - 1;
        float[] smoothed = flat.clone();
        for (int z = start; z < end; z++) {
            for (int x = start; x < end; x++) {
                int idx = z * bufSize + x;
                float h = flat[idx];
                float hPre = flatPre[idx];
                if (h <= 0.05f) continue;
                float deposition = h - hPre;
                if (deposition <= 0.005f) continue;
                float sum = 0;
                int count = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int ni_z = Math.max(0, Math.min(bufSize - 1, z + dz));
                        int ni_x = Math.max(0, Math.min(bufSize - 1, x + dx));
                        int ni = ni_z * bufSize + ni_x;
                        sum += flat[ni];
                        count++;
                    }
                }
                float avg = sum / count;
                float diff = h - avg;
                float blend = Math.min(diff * 3.0f, 0.6f);
                smoothed[idx] = h * (1f - blend) + avg * blend;
            }
        }
        System.arraycopy(smoothed, 0, flat, 0, flat.length);
    }

    // ===== 工具 =====

    private static double cfgDbl(ForgeConfigSpec.DoubleValue v, double fallback) {
        try { return v.get(); } catch (IllegalStateException e) { return fallback; }
    }

    private static float clampF(float v, float mn, float mx) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return mn;
        return Math.max(mn, Math.min(mx, v));
    }

    private static long hash(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }
}
