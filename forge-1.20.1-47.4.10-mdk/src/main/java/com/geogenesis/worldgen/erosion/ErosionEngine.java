package com.geogenesis.worldgen.erosion;

import com.geogenesis.config.GeoGenesisConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final Logger LOGGER = LogManager.getLogger("geogenesis/erosion");
    /** 诊断计数器（每 N 次调用打印一次） */
    private static int erosionDiagCounter = 0;
    private static final int EROSION_DIAG_INTERVAL = 10;

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

    // ===== 三尺度配置 (v3 - 强侵蚀 + 河谷协同) =====
    // 提升：spacing=4 插值场平滑度高，需要更大参数。河网雕刻后侵蚀液滴沿河谷走廊进一步增强。
    // C 尺度 (宏观山谷/山脉脊线)：笔刷半径 7 覆盖 225 邻域，DROPS_C=120 每区块约 0.5 滴/格，寿命 60 步
    private static final int R_C = 7, DROPS_C = 120, LIFE_C = 60;
    private static final float ERODE_C = 0.100f, DEPOSIT_C = 0.010f;

    // M 尺度 (中脊/冲沟)：半径 4，(2*4+1)²=81 邻域，寿命 30 步
    private static final int R_M = 4, DROPS_M = 60, LIFE_M = 30;
    private static final float ERODE_M = 0.120f, DEPOSIT_M = 0.015f;

    // F 尺度 (细沟/微沟壑)：半径 2，(2*2+1)²=25 邻域，寿命 20 步
    private static final int R_F = 2, DROPS_F = 40, LIFE_F = 20;
    private static final float ERODE_F = 0.150f, DEPOSIT_F = 0.020f;

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

        int pad = R_MAX + 2;
        int bufSize = sz + pad * 2;

        // 构建 flat 缓冲区（镜像填充 -> 向后兼容）
        float[] flat = new float[bufSize * bufSize];
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++) {
                int srcZ = Math.max(0, Math.min(sz - 1, z - pad));
                int srcX = Math.max(0, Math.min(sz - 1, x - pad));
                flat[z * bufSize + x] = h[srcZ][srcX];
            }
        float[] flatPre = flat.clone();

        // 运行侵蚀引擎主循环
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, locked);

        // ===== 写回 =====
        for (int z = 0; z < sz; z++)
            for (int x = 0; x < sz; x++) {
                float v = flat[(z + pad) * bufSize + (x + pad)];
                h[z][x] = (Float.isNaN(v) || Float.isInfinite(v)) ? 0.5f : clampF(v, -1, 1);
            }

        if (locked != null) {
            // locked 的恢复在 runErosionOnFlat 内已处理，无需重复
        }
    }

    /**
     * 在预填充的 flat/flatPre 缓冲区上运行侵蚀引擎主循环。
     * flat 和 flatPre 由调用方提供；flatPre 是 flat 的初始快照（用于沉积平滑参考）。
     * 侵蚀后结果留在 flat[] 中，调用方从 flat 中心读取。
     *
     * @param flat     高度缓冲区（侵蚀原地修改）
     * @param flatPre  flat 初始快照
     * @param bufSize  flat 缓冲区边长
     * @param sz       本 tile 有效数据边长（输出到 h[sz][sz]）
     * @param ox/oz    tile 原点（世界坐标）
     * @param seaNorm  海平面归一化高度
     * @param str      侵蚀强度乘数
     */
    public void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                  int sz, int ox, int oz,
                                  float seaNorm, float str) {
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, null);
    }

    private void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                   int sz, int ox, int oz,
                                   float seaNorm, float str, boolean[][] locked) {
        if (sz < 16 || str <= 0) return;

        double dropsMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionDropsMul, 1.0);
        double erodeMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionErodeMul, 1.0);
        double lcWeight  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionLocalChargeWeight, 1.0);
        double casStr    = cfgDbl(GeoGenesisConfig.INSTANCE.erosionCascadeStrength, 0.8);
        float strE       = (float) Math.max(0.1, str) * (float) erodeMul;

        int pad = R_MAX + 2;
        float[] dis = new float[bufSize * bufSize];

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
                    if (locked[z][x]) savedLocked[z][x] = flat[(z + pad) * bufSize + (x + pad)];
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

        // ===== 原始三尺度液滴模拟（interleaved） =====
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

        // ===== 恢复 locked 格点 =====
        if (savedLocked != null) {
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sz; x++)
                    if (locked[z][x])
                        flat[(z + pad) * bufSize + (x + pad)] = savedLocked[z][x];
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
            float depthBoost = Math.max(0f, -h0) * 0.03f;  // 水深贡献的"等效下坡高度差"（3%/e, ≈ 7.7 块/100e 深度）
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

    // ===== 解析流功率侵蚀（替代 droplet 模拟，天然连续无缝） =====

    /**
     * 世界坐标高度查询接口：当前 tile 内取 h[][]，越界时读邻居 postErosion 或 terrainE 回退。
     */
    @FunctionalInterface
    public interface HeightQuery {
        float getHeight(int worldX, int worldZ);
    }

    /**
     * 解析流功率侵蚀 + 热力扩散（替代 droplet 模拟，带边界高度查询）。
     *
     * <p>此重载接受 {@link HeightQuery}，用于 tile 边界 cell 读取隔壁 tile 的侵蚀后高度，
     * 消除因越界跳过边界 cell 导致的断层。</p>
     *
     * @see #applyStreamPower(float[][], int, int[][], float[][], float, float, double, double, double, double)
     */
    public void applyStreamPower(float[][] h, int N, int[][] flowDir, float[][] discharge,
                                  float seaNorm, float str,
                                  double K, double m, double n, double Kd) {
        applyStreamPower(h, N, flowDir, discharge, seaNorm, str, K, m, n, Kd, null, null);
    }

    public void applyStreamPower(float[][] h, int N, int[][] flowDir, float[][] discharge,
                                  float seaNorm, float str,
                                  double K, double m, double n, double Kd,
                                  HeightQuery heightQuery) {
        applyStreamPower(h, N, flowDir, discharge, seaNorm, str, K, m, n, Kd, heightQuery, null);
    }

    /**
     * 解析流功率侵蚀 + 热力扩散（全参重载，heightLookup 支持越界查询）。
     *
     * <p>核心公式（Stream Power Law, Braun & Willett 2013, Schott et al. 2024）：
     * <pre>
     *   Δh = -K · A^m · |∇h|^n
     * </pre>
     * 其中 A = D8 流量累积（discharge），∇h = 到下游坡度。
     *
     * @param h           全分辨率高度场（N×N，原位读写）
     * @param N           tile 边长
     * @param flowDir     D8 流向矩阵（0-7，-1 无流出）
     * @param discharge   D8 流量累积矩阵
     * @param seaNorm     海平面归一化高度
     * @param str         侵蚀强度乘数
     * @param K           erosivity 系数
     * @param m           面积指数（默认 0.5）
     * @param n           坡度指数（默认 1.0）
     * @param Kd          热力扩散系数，0=关闭
     * @param heightQuery 越界高度查询（null = 跳过越界 cell，等同于原行为）
     * @param origin      tile 原点（世界坐标），与 heightQuery 配合使用；null = 跳过越界
     */
    public void applyStreamPower(float[][] h, int N, int[][] flowDir, float[][] discharge,
                                  float seaNorm, float str,
                                  double K, double m, double n, double Kd,
                                  HeightQuery heightQuery, int[] origin) {
        // ========== 局部最低点下蚀（替代流功率公式）==========
        // 核心：每个 cell 在 5×5 窗口内找最小高度，按 (h - local_min) 比例下蚀。
        // - 谷底几乎不变（已经是局部最低）
        // - 远谷底处的 cell 下蚀最多
        // - 形成 V 形谷（dh 越大、下蚀越多）
        //
        // 多趟迭代：每趟下蚀一点，地形渐渐接近新的局部最低。
        // K 控下蚀速率；Kd 控热力扩散（让 V 谷底部变圆 U 谷）。
        //
        // 之前的流功率公式 dh * alpha/(1+alpha) 在 bicubic 平滑的地形上 dh 全近似
        // → 形成"剥皮"（均匀下蚀），无 V 形。新方法直接以局地最低为参照，保留 V 形。

        int winR = 5; // 5x5 窗口半径 2
        int winHalf = winR / 2;
        int nIters = 6;
        float Klocal = (float) Math.max(0.05, K * 0.5); // 转换为 0.025-0.5 范围

        double maxEro = 0, sumEro = 0;
        int erodedCount = 0, totalCount = 0;

        // 完全跳过边界 2 圈（5×5 窗口半径=2，跨界的窗口会引发 bicubic vs terrainE 不一致）
        // 提取的 chunk 在 tile 中心（offset 40-87），远在 [2, N-2] 内，不受影响
        int margin = winHalf; // = 2
        for (int iter = 0; iter < nIters; iter++) {
            for (int z = margin; z < N - margin; z++) {
                for (int x = margin; x < N - margin; x++) {
                    float h0 = h[z][x];
                    if (h0 <= seaNorm) continue;

                    // 在 5×5 窗口内找局地最低（窗口完全在 tile 内，不会跨界）
                    float localMin = h0;
                    for (int dz = -winHalf; dz <= winHalf; dz++) {
                        int nz = z + dz;
                        for (int dx = -winHalf; dx <= winHalf; dx++) {
                            int nx = x + dx;
                            if (h[nz][nx] < localMin) localMin = h[nz][nx];
                        }
                    }

                    float dh = h0 - localMin;
                    if (dh <= 1e-6f) continue;

                    double erosion = Math.min(Klocal * dh, 0.10);
                    if (erosion <= 1e-6) continue;

                    if (iter == nIters - 1) {
                        maxEro = Math.max(maxEro, erosion);
                        sumEro += erosion;
                        erodedCount++;
                    }
                    totalCount++;
                    h[z][x] = h0 - (float) erosion;
                }
            }
        }

        // 诊断日志
        erosionDiagCounter++;
        if (erosionDiagCounter % EROSION_DIAG_INTERVAL == 0) {
            double avgEro = erodedCount > 0 ? sumEro / erodedCount : 0;
            LOGGER.info("[LOCAL-MIN EROSION] tile " + N + "x" + N + " x" + nIters + "iters"
                + " | eroded=" + erodedCount + "/" + totalCount
                + " | maxEro=" + String.format("%.6f", maxEro) + " e (~" + String.format("%.1f", maxEro * 384) + " blk)"
                + " | avgEro=" + String.format("%.6f", avgEro) + " e"
                + " | K=" + String.format("%.3f", Klocal));
        }

        // ---- Phase 2: 热力扩散（让 V 谷底部变圆 → U 谷） ----
        if (Kd > 0) {
            thermalDiffuse(h, N, (float) Kd, seaNorm);
        }

        // ---- Phase 3: 末尾 clamp ----
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                h[z][x] = clampF(h[z][x], -1, 1);
    }

    /** 热力扩散（山坡平滑，Laplacian 局部算子）。 */
    private static void thermalDiffuse(float[][] h, int N, float Kd, float seaNorm) {
        float KdClamp = Math.min(Kd, 0.25f);
        float[][] copy = new float[N][N];
        for (int z = 0; z < N; z++) System.arraycopy(h[z], 0, copy[z], 0, N);

        for (int z = 1; z < N - 1; z++)
            for (int x = 1; x < N - 1; x++) {
                if (h[z][x] <= seaNorm) continue; // 水下不扩散
                float lap = copy[z - 1][x] + copy[z + 1][x] + copy[z][x - 1] + copy[z][x + 1]
                          - 4f * copy[z][x];
                h[z][x] += KdClamp * lap;
            }
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
