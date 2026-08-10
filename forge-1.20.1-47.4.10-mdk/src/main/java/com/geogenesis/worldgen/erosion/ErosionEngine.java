package com.geogenesis.worldgen.erosion;

import com.geogenesis.config.GeoGenesisConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * 本地确定性液滴侵蚀引擎 —— 三尺度层叠 + localCharge 正反馈 + 局部 cascade 级联。
 *
 * <p><b>升级概要（相对备份基线）</b>：
 * <ul>
 *   <li><b>三尺度层叠</b>（R=8/4/2，取代旧 R=6/4）：宏观山谷→中脊→微冲沟，覆盖更广特征尺度。</li>
 *   <li><b>localCharge 正反馈</b>：每粒子携带自起点累计高差 localCharge，容量公式乘以
 *       {@code (1 + α·localCharge)}，使长程深流粒子雕得深、短程浅流雕得浅 → 主流深支流浅，
 *       几何涌现尺度多样性，不依赖全局统计（完全本地确定 → 天然无缝）。</li>
 *   <li><b>河流粒子状态机（2026-08-01）</b>：粒子按双闸门（lc 累计落差 × 路径放电量）平滑切换
 *       到河流模式——强侵蚀 × 弱堆积（沉积×0.2）× 蒸发抑制 × 流速放宽，主流道涌现深窄 V 形槽，
 *       坡度变缓处集中沉积 → 河口冲积扇。阈值/强度全配置化（erosionRiver* 系列）。</li>
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

    private static final float INERTIA = 0.005f, GRAVITY = 2.5f, EVAP_RATE = 0.001f;
    private static final float ENTRAINMENT = 10f;
    /** 汇聚门控尺度：flowGate = ld/(ld+FLOW_SCALE)。基于实测 dis 分布（非零93%、主质量2-8）定 4，
     *  源头(ld<2)弱、汇聚(ld≥8)全强。仅改侵蚀公式（ErosionEngine），不影响预览磁盘缓存
     *  （勿 bump CACHE_SCHEMA_VERSION——15 曾导致磁盘缓存失效 → QUARTER-only 网格伪影暴露）。 */
    private static final float FLOW_SCALE = 4f;
    /** 放电量偏置系数（旧动量偏置 a = MOMENTUM·ld/(ld+10)，沿梯度加速，保留） */
    private static final float MOMENTUM = 1.0f;
    private static final float DEPOSIT_SPEED = 0.02f;
    /** SimpleHydrology 型统一松弛率（原 depositionRate=0.1），高度差驱动的平衡浓度 → 侵蚀/沉积双向 */
    private static final float RELAX_RATE = 0.1f;
    /** cascade 局部每 N 步执行（2026-08-01 对齐原版每步，河床平滑更充分；8 邻居小邻域性能可控） */
    private static final int CASCADE_INTERVAL = 1;
    private static final float CASCADE_MAXDIFF = 0.01f;

    // ===== 三尺度配置 (v3 - 强侵蚀 + 河谷协同) =====
    // 提升：spacing=4 插值场平滑度高，需要更大参数。河网雕刻后侵蚀液滴沿河谷走廊进一步增强。
    // C 尺度 (宏观山谷/山脉脊线)：笔刷半径 7 覆盖 225 邻域，DROPS_C=120 每区块约 0.5 滴/格，寿命 60 步
    private static final int R_C = 7, DROPS_C = 120, LIFE_C = 60;
    private static final float ERODE_C = 0.100f, DEPOSIT_C = 0.010f;

    // M 尺度 (中脊/冲沟)：半径 4，(2*4+1)²=81 邻域，寿命 30 步
    private static final int R_M = 4, DROPS_M = 60, LIFE_M = 30;
    private static final float ERODE_M = 0.120f, DEPOSIT_M = 0.015f;

    // F 尺度 (细沟/微沟壑)：半径 3，(2*3+1)²=49 邻域，寿命 20 步
    // 2026-08-10: R_F 2→3——原 2（直径 5 格）太窄，片流液滴在 ≤5 格区域反复侵蚀 → 小坑。
    // 3（直径 7 格）与 wu 化后特征尺度匹配更好，坑变浅槽。
    private static final int R_F = 3, DROPS_F = 40, LIFE_F = 20;
    private static final float ERODE_F = 0.150f, DEPOSIT_F = 0.020f;

    /** 最大笔刷半径（pad = R_MAX + 2 = 9），相邻 tile 各 pad 9 + 中心区域重叠 2*9=18 块无缝 */
    private static final int R_MAX = Math.max(R_C, Math.max(R_M, R_F)); // =7

    // ★ 2026-08-09 优化（光追 RIS 式重要性重采样 / Russian roulette 剪枝）：
    //   SLOPE_MIN_SKIP — 撒点前 1 格差分坡度阈值：低于则跳过该液滴（绝对平坦区侵蚀≈0，纯无效功）。
    //     确定性：坡度是 flat 世界坐标场的确定性函数 → 跳过集合固定 → 无缝保持。
    //     2026-08-10 从 0.002 降到 0.0001：SimpleHydrology 参考无此阈值，平原微小坡度（e/wu ≈ 0.0003）
    //     被原阈值跳过 → 无侵蚀冲刷痕迹。新阈值 0.0001 ≈ 0.025 块/格，只跳过绝对平坦区。
    private static final float SLOPE_MIN_SKIP = 0.0001f;
    /** 平衡态判定：spd 低于该值 且 单步笔刷增量低于该值，连续 STALL_MAX 步 → 提前终止 */
    private static final float STALL_SPEED = 0.3f;
    private static final float STALL_DELTA = 1e-4f;
    private static final int STALL_MAX = 8;

    /**
     * 液滴跑满整个 tile（含两侧超区）——超区是相邻 tile 的 blend 数据源（extractFromTile 四边缘
     * 对称读邻居 delta 渐变），不能裁剪。跨 tile 连续性由 extractFromTile 的边缘 blend 保证。
     */
    public ErosionEngine(double dropsMul, int seed) {
        // dropsMul / seed 由 CellGenerator 从配置解析后传入；实际放大倍率运行时仍读配置，
        // 此处仅保留兼容签名。
    }

    // ===== 公共入口 =====

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
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, (boolean[][]) null);
    }

    /** 带稳态放电量场输出的入口（河网层复用 discharge 场做 riverMask，取代 D8 流量累积）。 */
    public void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                  int sz, int ox, int oz,
                                  float seaNorm, float str, float[] dischargeOut) {
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, null, dischargeOut);
    }

    private void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                   int sz, int ox, int oz,
                                   float seaNorm, float str, boolean[][] locked) {
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, locked, null);
    }

    private void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                   int sz, int ox, int oz,
                                   float seaNorm, float str, boolean[][] locked,
                                   float[] dischargeOut) {
        if (sz < 16 || str <= 0) return;

        double dropsMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionDropsMul, 1.0);
        double erodeMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionErodeMul, 1.0);
        double casStr    = cfgDbl(GeoGenesisConfig.INSTANCE.erosionCascadeStrength, 0.8);
        // 2026-08-10 wu 化修正：坡度/距离阈值按"物理块"标定，wu 空间必须 ×hs 还原
        // （与 RidgeValleyErosion.gradX/gradZ 同型 bug，见 SLOPE_MIN_SKIP / CASCADE_MAXDIFF）。
        // HS=1（或独立预览默认）时 hs=1 → 与 wu 化前逐位一致。
        double hsD = cfgDbl(GeoGenesisConfig.INSTANCE.horizontalScale, 1.0);
        float hs = (float) (hsD > 0.01 && hsD != 1.0 ? hsD : 1.0);
        float strE       = (float) Math.max(0.1, str) * (float) erodeMul;
        // 2026-08-01 两套粒子系统：河流粒子（StreamTracer）与液滴侵蚀彻底分离，
        // 液滴回归纯 SH 微刻（erosionRiver* / erosionLocalChargeWeight 配置保留但引擎不再读取）
        // SH 三件套（2026-08-01）：动量场正反馈 + 多轮迭代 + lrate 场平滑
        float momTransfer = (float) cfgDbl(GeoGenesisConfig.INSTANCE.erosionMomentumTransfer, 1.0);
        // 2026-08-10：2→5——平原 discharge 累积需要更多轮（SLOPE_MIN_SKIP=0.0001 后平原液滴
        // 被允许通过，但源头 ld≈0 需要多轮累积才能让 flowGate 显著；SimpleHydrology 默认 30 轮）。
        int iterations = cfgInt(GeoGenesisConfig.INSTANCE.erosionIterations, 5);
        float lrate = (float) cfgDbl(GeoGenesisConfig.INSTANCE.erosionLrate, 0.1);

        int pad = R_MAX + 2;
        float[] dis = new float[bufSize * bufSize];   // 稳态放电量场（跨轮累积）
        float[] momX = new float[bufSize * bufSize];  // 稳态动量场 X
        float[] momY = new float[bufSize * bufSize];  // 稳态动量场 Y
        float[] disT = new float[bufSize * bufSize];  // 本轮 track：放电量
        float[] momXT = new float[bufSize * bufSize]; // 本轮 track：动量 X
        float[] momYT = new float[bufSize * bufSize]; // 本轮 track：动量 Y

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

        // ===== SH 多轮迭代：每轮清 track → 重撒全部液滴 → 轮末 lrate 平滑进稳态场 =====
        for (int it = 0; it < iterations; it++) {
            Arrays.fill(disT, 0f);
            Arrays.fill(momXT, 0f);
            Arrays.fill(momYT, 0f);

            for (int d = 0; d < maxD; d++) {
                for (long[] ck : chunkList) {
                    int wcx = (int) ck[0], wcz = (int) ck[1];
                    int relX = wcx * 16 - ox;
                    int relZ = wcz * 16 - oz;

                    if (d < (int) (DROPS_C * dropsMul)) {
                        spawnAndSim(flat, bufSize, wcx, wcz, d, relX, relZ, pad,
                                    bOffC, bWgtC, bnC, locked, sz, dis, disT,
                                    momX, momY, momXT, momYT, momTransfer,
                                    ERODE_C * strE, DEPOSIT_C, LIFE_C, ox, oz,
                                    (float) casStr, seaNorm, hs);
                    }
                    if (d < (int) (DROPS_M * dropsMul)) {
                        spawnAndSim(flat, bufSize, wcx, wcz, d * 137 + 1, relX, relZ, pad,
                                    bOffM, bWgtM, bnM, locked, sz, dis, disT,
                                    momX, momY, momXT, momYT, momTransfer,
                                    ERODE_M * strE, DEPOSIT_M, LIFE_M, ox, oz,
                                    (float) casStr, seaNorm, hs);
                    }
                    if (d < (int) (DROPS_F * dropsMul)) {
                        spawnAndSim(flat, bufSize, wcx, wcz, d * 137 + 2, relX, relZ, pad,
                                    bOffF, bWgtF, bnF, locked, sz, dis, disT,
                                    momX, momY, momXT, momYT, momTransfer,
                                    ERODE_F * strE, DEPOSIT_F, LIFE_F, ox, oz,
                                    (float) casStr, seaNorm, hs);
                    }
                }
            }

            // ===== SH 场平滑（world.h:80-86）：(1-lrate)·old + lrate·track =====
            for (int i = 0; i < bufSize * bufSize; i++) {
                dis[i] = (1f - lrate) * dis[i] + lrate * disT[i];
                momX[i] = (1f - lrate) * momX[i] + lrate * momXT[i];
                momY[i] = (1f - lrate) * momY[i] + lrate * momYT[i];
            }
        }

        // ===== 双平滑（仅全部迭代完成后做一次） =====
        smoothErosionResult(flat, bufSize, pad, sz, seaNorm);
        smoothDepositionZones(flat, flatPre, bufSize, pad, sz);

        // ===== 恢复 locked 格点 =====
        if (savedLocked != null) {
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sz; x++)
                    if (locked[z][x])
                        flat[(z + pad) * bufSize + (x + pad)] = savedLocked[z][x];
        }

        // ===== 导出稳态放电量场（河网层 riverMask 用） =====
        if (dischargeOut != null)
            System.arraycopy(dis, 0, dischargeOut, 0, dis.length);
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
                             float[] dis, float[] disT,
                             float[] momX, float[] momY, float[] momXT, float[] momYT,
                             float momTransfer,
                             float erodeSpeed, float depositSpeed, int lifetime,
                             int ox, int oz, float cascadeStrength, float seaNorm, float hs) {
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
        // ★ 2026-08-09 spawn 陆地门控（对齐原版 SH world.h:71-72 "height<0.1 continue"）：
        //   海平面以下不生成粒子。根因：海底 flat 被钳到 -0.05 后液滴在平底随机游走
        //   产生微侵蚀/沉积 → 坑洞。陆地出发的粒子仍可流入海底（水下路径保留，峡湾/河口不回归）。
        //   实测验证（seed=12345, 64 tiles）：跳过 10 万+ 海底粒子，陆地粒子正常侵蚀。
        float startH = flat[py * bufSize + px];
        if (startH < seaNorm) return;
        // ★ 2026-08-09 优化：坡度稀疏化（RIS 式重要性重采样）——平坦区液滴侵蚀≈0（无效功）。
        //   1 格差分坡度（比 3×3 Sobel 便宜），世界坐标确定性函数 → 无缝保持。
        //   px/py 已由上方边界检查保证 ∈ [1, bufSize-2] → ±1 采样安全。
        float slope = Math.max(
            Math.abs(flat[(py + 1) * bufSize + px] - flat[(py - 1) * bufSize + px]),
            Math.abs(flat[py * bufSize + px + 1] - flat[py * bufSize + px - 1]));
        // 2026-08-10 wu 化修正：1 格差分坡度 = e/wu，×hs 还原物理坡度（e/块）再比阈值
        if (slope * hs < SLOPE_MIN_SKIP) return;
        simulateDrop(flat, bufSize, px + 0.5f, py + 0.5f,
                     bOff, bWgt, bn, locked, pad, baseSize,
                     dis, disT, momX, momY, momXT, momYT, momTransfer,
                     erodeSpeed, depositSpeed, lifetime, ox, oz,
                     cascadeStrength, seaNorm, hs);
    }

    /** SH 对齐液滴下落。关键改进：动量场正反馈 + erf 放电反馈 + 局部 cascade + 片流 + 水下 */
    private void simulateDrop(float[] flat, int bufSize, float posX, float posY,
                              int[] bOff, float[] bWgt, int bn,
                              boolean[][] locked, int pad, int baseSize,
                              float[] dis, float[] disT,
                              float[] momX, float[] momY, float[] momXT, float[] momYT,
                              float momTransfer,
                             float erodeSpeed, float depositSpeed, int lifetime,
                             int ox, int oz,
                             float cascadeStrength, float seaNorm, float hs) {
        float dirX = 0, dirY = 0, sed = 0, spd = 1f, wat = 1f;

        int spawnIx = (int) posX, spawnIy = (int) posY;
        float startH = flat[spawnIy * bufSize + spawnIx];

        // ★ 2026-08-09 优化：平衡态 early-exit（Russian roulette 剪枝）——平坦/平衡区液滴
        //   速度与笔刷增量双双趋于 0（对地形无贡献），连续 STALL_MAX 步后提前终止。
        //   确定性：判定条件是确定性函数的单调收敛 → 同一世界坐标液滴终止点固定 → 无缝保持。
        int stall = 0;
        for (int st = 0; st < lifetime; st++) {
            int ix = (int) posX, iy = (int) posY;
            if (ix < 1 || ix >= bufSize - 2 || iy < 1 || iy >= bufSize - 2) return;
            int idx = iy * bufSize + ix;

            float fx = posX - ix, fy = posY - iy;
            float hNW = flat[idx], hNE = flat[idx + 1];
            float hSW = flat[idx + bufSize], hSE = flat[idx + bufSize + 1];
            float h0 = hNW * (1 - fx) * (1 - fy) + hNE * fx * (1 - fy)
                     + hSW * (1 - fx) * fy + hSE * fx * fy;
            // ---- 方向（3D 法线，3×3 Sobel 邻域，对齐 SH 抗噪法线语义） ----
            // 原 2×2 双线性梯度对单格尖峰敏感（粒子被噪声点弹开、轨迹抖动）；
            // 3×3 Sobel（对角+边加权）平滑噪声 → 侵蚀方向更自然稳定。
            float hN = flat[idx - bufSize], hS = flat[idx + bufSize];
            float hW = flat[idx - 1], hE = flat[idx + 1];
            float hNW2 = flat[idx - bufSize - 1], hNE2 = flat[idx - bufSize + 1];
            float hSW2 = flat[idx + bufSize - 1], hSE2 = flat[idx + bufSize + 1];
            float gx = (hSE2 + 2 * hE + hNE2 - hSW2 - 2 * hW - hNW2) / 8f;
            float gy = (hSW2 + 2 * hS + hSE2 - hNW2 - 2 * hN - hNE2) / 8f;
            float glen = (float) Math.sqrt(gx * gx + gy * gy);

            if (glen < 1e-12f) {
                // 片流模式：梯度≈0 → 噪声驱动方向（确定性、无缝）
                if (dirX == 0 && dirY == 0 && st > 2) {
                    // 2026-08-02：片流 hash 用世界坐标（ox+ix-pad）——旧局部坐标导致
                    // 相邻 tile 重叠区同一世界坐标的液滴方向不同 → postErosion 重叠区
                    // 逐格不一致 → StreamTracer 两侧追踪场不同 → 提取区 seam 断流。
                    long nh = hash((ox + ix - pad) * 31 + 7, (oz + iy - pad) * 73 + 13);
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

            // ---- 动量场正反馈（SH water.h:97-99） ----
            // speed += lodsize·momentumTransfer·dot(normalize(fspeed),normalize(speed))/(volume+discharge)·fspeed
            // 本地近似：当前移动方向 ≈ normalize(speed)，叠加后统一重新归一化。
            float fmx = momX[idx], fmy = momY[idx];
            float flen = (float) Math.sqrt(fmx * fmx + fmy * fmy);
            if (flen > 1e-12f && momTransfer > 0f) {
                float k = momTransfer * (fmx * dirX + fmy * dirY) / flen / (wat + ld);
                dirX += k * fmx;
                dirY += k * fmy;
            }

            float dlen = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (dlen < 1e-12f) return;
            dirX /= dlen; dirY /= dlen;

            spd = (float) Math.sqrt(spd + Math.abs(flat[idx] - h0) * GRAVITY);
            float spdCap = 1.5f;
            posX += dirX * Math.min(spd, spdCap);
            posY += dirY * Math.min(spd, spdCap);
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
            // 2026-08-09 汇聚门控（最终形态）：纯 flowGate = ld/(ld+4)，线性无饱和。
            //   源头弱化（ld<2 弱、汇聚 ld≥8 全强）；水下坑洞由 spawn 陆地门控解决
            //   （spawnAndSim startH<seaNorm return），不再需要公式侧混合。
            float flowGate = ld / (ld + FLOW_SCALE);
            float depthBoost = Math.max(0f, -h0) * 0.03f;  // 水深贡献的"等效下坡高度差"（3%/e, ≈ 7.7 块/100e 深度）
            float heightDrop = Math.max(0f, -dh) + depthBoost;
            // 2026-08-01 两套粒子系统：液滴回归纯 SH 平衡浓度公式（河流职责移交 StreamTracer）
            float c_eq = (1f + ENTRAINMENT * flowGate) * heightDrop;  // 平衡浓度
            // 2026-08-10: 0.1→0.15——最初提到 0.3 与 ENTRAINMENT=10 叠加 → 汇聚处 16.5× 挖坑（小坑复现）。
            // 0.15 = 1.5×（保留平原增强，减半挖坑力度）。若小坑仍明显再降回 0.1。
            float effD = 0.15f;
            float delta = effD * (c_eq - sed);       // >0=侵蚀, <0=沉积
            float brushDelta = delta;                // 笔刷前增量快照（early-exit 判定用）

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

            // ---- track 累积（SH water.h:113-117：本轮 discharge + 动量 volume·speed） ----
            // 2026-08-01：discharge 场不再导出给河网（StreamTracer 独立追踪），保留动量场正反馈
            disT[idx] += wat;
            momXT[idx] += wat * dirX * Math.min(spd, spdCap);
            momYT[idx] += wat * dirY * Math.min(spd, spdCap);

            // ---- 局部 cascade（每 CASCADE_INTERVAL 步执行，对齐 SH 每步语义） ----
            if (cascadeStrength > 0 && st % CASCADE_INTERVAL == 0) {
                cascadeLocal(flat, bufSize, nix, niy, cascadeStrength, seaNorm, hs);
            }

            // NaN/Inf 守卫：陡峭下坡（dh 很负）可能导致 spd² + dh*GRAVITY < 0 → sqrt(NaN)
            // 若溢出则降速为 0.1（允许液滴继续存活的极小速度，非致命）
            float speedSq = spd * spd + dh * GRAVITY;
            spd = speedSq > 1e-12f ? (float) Math.sqrt(speedSq) : 0.1f;
            if (spd <= 0) return;
            // early-exit：低速 + 近零增量连续 STALL_MAX 步 → 平衡态，对地形已无贡献
            if (spd < STALL_SPEED && Math.abs(brushDelta) < STALL_DELTA) {
                if (++stall >= STALL_MAX) return;
            } else {
                stall = 0;
            }
            wat *= (1 - EVAP_RATE);
        }
    }

    /** 局部 cascade：8 邻居按高度排序 + 距离加权 settling（SH 原版语义）。
     *  hs：wu→块 换算（dist 为 wu 格距，×hs 还原块距离，2026-08-10 wu 化）。
     *  seaNorm：海底免阈值（对齐 SH world.h:144-148 "h<0.1 时 excess=|diff| 无 maxdiff"）——
     *  海平面以下全量沉降，消除海床/水下峡谷的锐利棱角。 */
    private void cascadeLocal(float[] flat, int bufSize, int cx, int cz, float strength, float seaNorm, float hs) {
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
            // 2026-08-10 wu 化修正：dist 为 wu 格距，×hs 还原"块"距离 → 阈值不随 HS 漂移
            // 2026-08-10 海底免阈值（对齐 SH world.h:144-148）：当前格低于海平面时 excess=|diff|
            // 全量沉降 → 海床平滑，水下峡谷不产生棱角锯齿
            float excess;
            if (curH < seaNorm) {
                excess = Math.abs(diff);
            } else {
                excess = Math.abs(diff) - dist * CASCADE_MAXDIFF * hs;
            }
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
            // ★ 2026-08-09 无伤优化：平滑并行化（每行只读 flat、写 smoothed 私有行 → 输出逐点一致）
            int rowsPerTask = Math.max(1, (end - start) / com.geogenesis.worldgen.terrain.CellGenerator.TILE_PARALLELISM);
            int tasks = (end - start + rowsPerTask - 1) / rowsPerTask;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(tasks);
            for (int t = 0; t < tasks; t++) {
                final int z0 = start + t * rowsPerTask, z1 = Math.min(end, z0 + rowsPerTask);
                com.geogenesis.worldgen.terrain.CellGenerator.TILE_SAMPLER.execute(() -> {
                    try {
                        for (int z = z0; z < z1; z++) {
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
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try { latch.await(); } catch (InterruptedException e) { throw new CancellationException("erosion aborted"); }
            System.arraycopy(smoothed, 0, flat, 0, flat.length);
        }
    }

    private void smoothDepositionZones(float[] flat, float[] flatPre, int bufSize, int pad, int sz) {
        int start = pad + 1;
        int end = pad + sz - 1;
        float[] smoothed = flat.clone();
        // ★ 2026-08-09 无伤优化：沉积区平滑并行化（每行只读 flat/flatPre、写 smoothed 私有行 → 输出逐点一致）
        int rowsPerTask = Math.max(1, (end - start) / com.geogenesis.worldgen.terrain.CellGenerator.TILE_PARALLELISM);
        int tasks = (end - start + rowsPerTask - 1) / rowsPerTask;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(tasks);
        for (int t = 0; t < tasks; t++) {
            final int z0 = start + t * rowsPerTask, z1 = Math.min(end, z0 + rowsPerTask);
            com.geogenesis.worldgen.terrain.CellGenerator.TILE_SAMPLER.execute(() -> {
                try {
                    for (int z = z0; z < z1; z++) {
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
                } finally {
                    latch.countDown();
                }
            });
        }
        try { latch.await(); } catch (InterruptedException e) { throw new CancellationException("erosion aborted"); }
        System.arraycopy(smoothed, 0, flat, 0, flat.length);
    }

    // NOTE: 解析流功率侵蚀（applyStreamPower / thermalDiffuse / HeightQuery）已于 2026-07-31 删除：
    // 属未接入生成管线的死代码（流功率方案未完成、早已回退液滴模拟）。多侵蚀特性后续重做。

    // ===== 工具 =====

    private static double cfgDbl(ForgeConfigSpec.DoubleValue v, double fallback) {
        try { return v.get(); } catch (IllegalStateException e) { return fallback; }
    }

    private static int cfgInt(ForgeConfigSpec.IntValue v, int fallback) {
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
