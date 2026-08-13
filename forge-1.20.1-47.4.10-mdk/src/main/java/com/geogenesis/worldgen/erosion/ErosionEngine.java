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
 *   <li><b>局部 cascade 级联</b>：侵蚀后对 8 邻居按高度差做 settling，平滑河床底部。
 *       SH 真实感的第二个来源。</li>
 *   <li><b>2026-08-14 河流系统整体清除</b>：StreamTracer/河流雕刻/水面棘轮/河道填水/河流配置
 *       全部移除。侵蚀液滴自身 = 地形刻画（含沟谷），discharge 场（res.discharge）保留为
 *       侵蚀副产品，待河流系统重构时作为单一流量数据源。</li>
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
    // ★ 2026-08-13 寿命 60→40：行程 = LIFE×spdCap(1.0) = 40wu ≤ tile margin 40wu——
    //   供给缺口修复（详见下方 spdCap 注释）。寿命超 margin 时，提取域边缘缺
    //   "远处出生"的液滴 → 邻 tile 双写不一致 → 左/上缘墙（x=-288 实测 2.4-4 块）。
    // ★ 2026-08-13 定案：LIFE_C 60→20（行程 = LIFE×实际spd≈20wu << 缓冲余量 40wu）。
    //   二分实锤（LIFE=40 时 x=-288 仍 3.6 块墙，LIFE=20 时 0.4-1.0 无缝）：
    //   液滴行程超出缓冲余量 → 游走型液滴在 tile 缓冲西/东界被截断（两 tile 截断位置
    //   不同）→ 流经提取域时泥沙/动量历史不同 → delta 双写不一致 → 左缘墙。
    //   行程 ≤ 缓冲余量一半 → 液滴绝不出界 → 双写一致 → 无缝。大尺度形态由骨架层提供。
    private static final int R_C = 7, DROPS_C = 120, LIFE_C = 20;
    private static final float ERODE_C = 0.100f, DEPOSIT_C = 0.010f;

    // M 尺度 (中脊/冲沟)：半径 4，(2*4+1)²=81 邻域，寿命 30 步
    private static final int R_M = 4, DROPS_M = 60, LIFE_M = 30;
    private static final float ERODE_M = 0.120f, DEPOSIT_M = 0.015f;

    // F 尺度 (细沟/微沟壑)：半径 2，(2*2+1)²=25 邻域，寿命 20 步
    private static final int R_F = 2, DROPS_F = 40, LIFE_F = 20;
    private static final float ERODE_F = 0.150f, DEPOSIT_F = 0.020f;

    // XS 尺度 (微侵蚀纹理，2026-08-12)：半径 1wu 十字笔刷（中心+4 邻，5 格）。
    // 目的：填补最小特征尺度空洞——C/M/F 最小笔刷 r2wu=4 块直径，无 1-2 块级细节。
    // HS=2 时 1wu=2 块、HS=1 时=1 块。微步长 SPDCAP_XS=0.6wu → 轨迹细密连贯；
    // ERODE_XS 幅度小（防 r1 单格椒盐）。独立种子流 d*137+3（与 C/M/F 错开）；
    // STALL 阈值按微尺度缩窄（微笔刷单步 delta 天然小，防误判平衡态提前终止）。
    // ★ 2026-08-13 目检回调：小圆坑（用户反馈）——微步长让液滴在 r1 五格内反复打转，
    //   单点重复侵蚀挖出直径 2-4 块浅坑。修复：ERODE_XS 0.045→0.02（挖得浅）、
    //   DEPOSIT_XS 0.01→0.015（填得快，比 4.5:1 → 1.3:1）、DROPS_XS 70→40（覆盖稀疏）。
    // ★ 2026-08-13 二次回调：用户目检"还行"，要求侵蚀略强一点 → ERODE_XS 0.02→0.03；
    //   SPDCAP_XS 0.6→0.8（步长略增，减少原地打转概率，纹理略粗但更自然）。
    private static final int R_XS = 1, DROPS_XS = 40, LIFE_XS = 14;
    private static final float ERODE_XS = 0.030f, DEPOSIT_XS = 0.015f;
    private static final float SPDCAP_XS = 0.8f;
    private static final float STALL_SPEED_XS = 0.12f, STALL_DELTA_XS = 1e-5f;

    /** 最大笔刷半径（pad = R_MAX + 2 = 9），相邻 tile 各 pad 9 + 中心区域重叠 2*9=18 块无缝 */
    private static final int R_MAX = Math.max(R_XS, Math.max(R_C, Math.max(R_M, R_F))); // =7

    // ★ 2026-08-09 优化（光追 RIS 式重要性重采样 / Russian roulette 剪枝）：
    //   SLOPE_MIN_SKIP — 撒点前 1 格差分坡度阈值：低于则跳过该液滴（平坦区侵蚀≈0，纯无效功）。
    //     确定性：坡度是 flat 世界坐标场的确定性函数 → 跳过集合固定 → 无缝保持。
    //     阈值保守：0.002e/格 ≈ 0.5 块/格，只跳过绝对平坦区（海洋平原/谷底），陡坡全量。
    //   ★ 2026-08-12 T3：0.002→0.001（×hs 后≈0.2 块/格），释放缓坡/宽谷边缘的微坡液滴。
    //   ★ 2026-08-12 目检回调：0.001→0.0015（侵蚀略过，小幅回撤至折中值）
    //   ★ 2026-08-12 定案（回基线）：0.002 是唯一安全值。二分实锤：0.0015 放行的微坡液滴
    //     （坡度 [0.0015,0.002)）在近平坦处随机漫步，出生集跨 chunk 边界不连续 →
    //     沿 chunk 边界形成 3-8 块"墙"（DeltaDumpProbe lx=39→40 孤立突变 0.022e）。
    //     STALL 0.25/12 经 BISECT 验证无墙无害，但为与基线完全一致也一并回 0.3/8。
    private static final float SLOPE_MIN_SKIP = 0.002f;
    /** 平衡态判定：spd 低于该值 且 单步笔刷增量低于该值，连续 STALL_MAX 步 → 提前终止 */
    private static final float STALL_SPEED = 0.3f;
    private static final float STALL_DELTA = 1e-4f;
    private static final int STALL_MAX = 8;

    /**
     * 液滴跑满整个 tile（含两侧超区）——超区是相邻 tile 的 blend 数据源（extractFromTile 四边缘
     * 对称读邻居 delta 渐变），不能裁剪。跨 tile 连续性由 extractFromTile 的边缘 blend 保证。
     */
    public ErosionEngine(double dropsMul, int seed) {
        this(dropsMul, seed, false); // 兼容签名：XS 默认关
    }

    public ErosionEngine(double dropsMul, int seed, boolean xsEnabled) {
        // dropsMul / seed 由 CellGenerator 从配置解析后传入；实际放大倍率运行时仍读配置，
        // 此处仅保留兼容签名。xsEnabled = 细纹理层开关（erosionXSEnabled 配置，默认 false）。
        this.xsEnabled = xsEnabled;
    }

    /** 细纹理微侵蚀层开关（2026-08-12，配置 erosionXSEnabled） */
    private boolean xsEnabled;

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
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, null, null, 1f);
    }

    /** 带稳态放电量场输出的入口（河网层复用 discharge 场做 riverMask，取代 D8 流量累积）。 */
    public void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                  int sz, int ox, int oz,
                                  float seaNorm, float str, float[] dischargeOut) {
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, null, dischargeOut, 1f);
    }

    /** ★ 2026-08-13：hs 从 TerrainParams 显式传入（治本）——引擎不再读 GeoGenesisConfig 全局配置。
     *  此前引擎内部读 INSTANCE.horizontalScale，独立探针进程无 Forge 环境 → 恒 1.0，
     *  与游戏 HS=2 不一致 → 探针永远复现不了游戏内断裂（坡度换算/概率闸门全错位）。 */
    public void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                  int sz, int ox, int oz,
                                  float seaNorm, float str, float[] dischargeOut, float hs) {
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, null, dischargeOut, hs);
    }

    private void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                   int sz, int ox, int oz,
                                   float seaNorm, float str, boolean[][] locked) {
        runErosionOnFlat(flat, flatPre, bufSize, sz, ox, oz, seaNorm, str, locked, null, 1f);
    }

    private void runErosionOnFlat(float[] flat, float[] flatPre, int bufSize,
                                   int sz, int ox, int oz,
                                   float seaNorm, float str, boolean[][] locked,
                                   float[] dischargeOut, float hs) {
        if (sz < 16 || str <= 0) return;

        double dropsMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionDropsMul, 1.0);
        double erodeMul  = cfgDbl(GeoGenesisConfig.INSTANCE.erosionErodeMul, 1.0);
        double casStr    = cfgDbl(GeoGenesisConfig.INSTANCE.erosionCascadeStrength, 0.8);
        // 2026-08-10 wu 化修正：坡度/距离阈值按"物理块"标定，wu 空间必须 ×hs 还原
        // （与 RidgeValleyErosion.gradX/gradZ 同型 bug，见 SLOPE_MIN_SKIP / CASCADE_MAXDIFF）。
        // 2026-08-13：hs 由 TerrainParams 传入（上方重载），不再读全局配置。
        if (!(hs > 0.01f && hs != 1.0f)) hs = 1f;
        float strE       = (float) Math.max(0.1, str) * (float) erodeMul;
        // 2026-08-01 两套粒子系统：河流粒子（StreamTracer）与液滴侵蚀彻底分离，
        // 液滴回归纯 SH 微刻（erosionRiver* / erosionLocalChargeWeight 配置保留但引擎不再读取）
        // SH 三件套（2026-08-01）：动量场正反馈 + 多轮迭代 + lrate 场平滑
        float momTransfer = (float) cfgDbl(GeoGenesisConfig.INSTANCE.erosionMomentumTransfer, 1.0);
        int iterations = cfgInt(GeoGenesisConfig.INSTANCE.erosionIterations, 2); // 2026-08-09: 3→2（与 config 默认一致）
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

        // XS 微笔刷（中心+十字 4 邻，d2≤1，独立构建——不动 C/M/F 笔刷的 d2<r2 语义）
        int[] bOffXS = new int[5]; float[] bWgtXS = new float[5];
        int bnXS = buildMicroBrush(bOffXS, bWgtXS, bufSize);

        float[][] savedLocked = null;
        if (locked != null) {
            savedLocked = new float[sz][sz];
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sz; x++)
                    if (locked[z][x]) savedLocked[z][x] = flat[(z + pad) * bufSize + (x + pad)];
        }

        // ===== 确定性播种（2026-08-13 重写：世界坐标连续化，治本消除边界墙）=====
        // 旧方案按 chunk 分组播种（每 chunk 独立 hash + offX/offZ∈[0,16) 整数离散）：
        // 液滴出生集合依赖 chunk 对齐 → delta 场在 chunk 边界突变（lx=39→40 实测 3 块墙，
        // 用户截图 x=-288 即 tile∩chunk 边界）。
        // 新方案：1wu 出生单元网格（全局整数坐标对齐，不依赖任何 tile/chunk 原点），
        // 每单元按世界坐标 hash 判定出生 + 单元内连续偏移 → 任何 tile 看到同一区域 =
        // 同一出生集合 → delta 场全局连续，无缝。密度对齐旧值：每 chunk 256wu² 撒
        // DROPS 个 → 每 wu² 密度 = DROPS/256。
        // 四个尺度用不同 hash 盐错开（C/M/F/XS 独立出生集合）。
        final float densC = DROPS_C / 256f * (float) dropsMul;
        final float densM = DROPS_M / 256f * (float) dropsMul;
        final float densF = DROPS_F / 256f * (float) dropsMul;
        final float densXS = DROPS_XS / 256f * (float) dropsMul;

        // ===== SH 多轮迭代：每轮清 track → 重撒全部液滴 → 轮末 lrate 平滑进稳态场 =====
        for (int it = 0; it < iterations; it++) {
            Arrays.fill(disT, 0f);
            Arrays.fill(momXT, 0f);
            Arrays.fill(momYT, 0f);

            for (int gz = oz; gz < oz + sz; gz++) {
                for (int gx = ox; gx < ox + sz; gx++) {
                    long hC = hash(gx * 131 + 7, gz * 131 + 11);
                    if (((hC >>> 16) & 0xFFFF) / 65536f < densC) {
                        spawnAt(flat, bufSize,
                                pad + (gx - ox) + ((hC >>> 32) & 0xFFFF) / 65536f,
                                pad + (gz - oz) + ((hC >>> 48) & 0xFFFF) / 65536f,
                                pad, gx, gz, bOffC, bWgtC, bnC, locked, sz, dis, disT,
                                momX, momY, momXT, momYT, momTransfer,
                                ERODE_C * strE, DEPOSIT_C, LIFE_C, ox, oz,
                                (float) casStr, seaNorm, hs, 1.0f, STALL_SPEED, STALL_DELTA);
                    }
                    long hM = hash(gx * 131 + 7, gz * 131 + 11) * 31L + 17L;
                    if (((hM >>> 16) & 0xFFFF) / 65536f < densM) {
                        spawnAt(flat, bufSize,
                                pad + (gx - ox) + ((hM >>> 32) & 0xFFFF) / 65536f,
                                pad + (gz - oz) + ((hM >>> 48) & 0xFFFF) / 65536f,
                                pad, gx, gz, bOffM, bWgtM, bnM, locked, sz, dis, disT,
                                momX, momY, momXT, momYT, momTransfer,
                                ERODE_M * strE, DEPOSIT_M, LIFE_M, ox, oz,
                                (float) casStr, seaNorm, hs, 1.0f, STALL_SPEED, STALL_DELTA);
                    }
                    long hF = hash(gx * 131 + 7, gz * 131 + 11) * 97L + 23L;
                    if (((hF >>> 16) & 0xFFFF) / 65536f < densF) {
                        spawnAt(flat, bufSize,
                                pad + (gx - ox) + ((hF >>> 32) & 0xFFFF) / 65536f,
                                pad + (gz - oz) + ((hF >>> 48) & 0xFFFF) / 65536f,
                                pad, gx, gz, bOffF, bWgtF, bnF, locked, sz, dis, disT,
                                momX, momY, momXT, momYT, momTransfer,
                                ERODE_F * strE, DEPOSIT_F, LIFE_F, ox, oz,
                                (float) casStr, seaNorm, hs, 1.0f, STALL_SPEED, STALL_DELTA);
                    }
                    if (xsEnabled) {
                        long hXS = hash(gx * 131 + 7, gz * 131 + 11) * 131L + 41L;
                        if (((hXS >>> 16) & 0xFFFF) / 65536f < densXS) {
                            spawnAt(flat, bufSize,
                                    pad + (gx - ox) + ((hXS >>> 32) & 0xFFFF) / 65536f,
                                    pad + (gz - oz) + ((hXS >>> 48) & 0xFFFF) / 65536f,
                                    pad, gx, gz, bOffXS, bWgtXS, bnXS, locked, sz, dis, disT,
                                    momX, momY, momXT, momYT, momTransfer,
                                    ERODE_XS * strE, DEPOSIT_XS, LIFE_XS, ox, oz,
                                    (float) casStr, seaNorm, hs, SPDCAP_XS, STALL_SPEED_XS, STALL_DELTA_XS);
                        }
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

    /** XS 微笔刷（2026-08-12，2026-08-13 圆化）：中心 + 十字 4 邻（d2≤1，5 格），
     *  权重二次曲线 w = 1 - d2/2（中心 1.0、十字邻 0.5），归一化后中心≈0.4、十字各≈0.15。
     *  ★ 2026-08-13 修复：旧权重 1-d 使十字邻权重=0（归一化后纯单格笔刷）→ 液滴打转时
     *  单点反复侵蚀挖尖坑（用户反馈"尖笔刷"）。圆化后侵蚀向 5 格扩散，单点权重降 60%，
     *  尖坑变平滑微凹。r=1wu 给 1-2 块级微侵蚀纹理；独立构建——不动 C/M/F 的 d2<r2 语义。 */
    private static int buildMicroBrush(int[] bOff, float[] bWgt, int bufSize) {
        int bn = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                float d2 = dx * dx + dy * dy;
                if (d2 <= 1f) {
                    bOff[bn] = dy * bufSize + dx;
                    bWgt[bn] = 1f - d2 * 0.5f;
                    bn++;
                }
            }
        float s = 0;
        for (int i = 0; i < bn; i++) s += bWgt[i];
        for (int i = 0; i < bn; i++) bWgt[i] /= s;
        return bn;
    }

    // ===== 播种与下落 =====

    /** 世界坐标连续播种的单滴入口（2026-08-13 重写）。
     *  出生位置 (px,py) 为缓冲坐标（小数），由调用方按 1wu 出生单元 hash 决定
     *  （单元内连续偏移）→ 出生集合不依赖 chunk/tile 对齐 → delta 场全局连续。 */
    private void spawnAt(float[] flat, int bufSize, float px, float py,
                         int pad, int gx, int gz,
                         int[] bOff, float[] bWgt, int bn,
                         boolean[][] locked, int baseSize,
                         float[] dis, float[] disT,
                         float[] momX, float[] momY, float[] momXT, float[] momYT,
                         float momTransfer,
                         float erodeSpeed, float depositSpeed, int lifetime,
                         int ox, int oz, float cascadeStrength, float seaNorm, float hs,
                         float spdCap, float stallSpeed, float stallDelta) {
        if (px < 1 || px >= bufSize - 1 || py < 1 || py >= bufSize - 1) return;
        if (locked != null) {
            int lz = (int) (py - pad), lx = (int) (px - pad);
            if (lz >= 0 && lz < baseSize && lx >= 0 && lx < baseSize && locked[lz][lx]) return;
        }
        // ★ 2026-08-09 spawn 陆地门控（对齐原版 SH world.h:71-72 "height<0.1 continue"）：
        //   海平面以下不生成粒子。根因：海底 flat 被钳到 -0.05 后液滴在平底随机游走
        //   产生微侵蚀/沉积 → 坑洞。陆地出发的粒子仍可流入海底（水下路径保留，峡湾/河口不回归）。
        //   实测验证（seed=12345, 64 tiles）：跳过 10 万+ 海底粒子，陆地粒子正常侵蚀。
        int ix = (int) px, iy = (int) py;
        float startH = flat[iy * bufSize + ix];
        if (startH < seaNorm) return;
        // ★ 2026-08-09 优化：坡度稀疏化（RIS 式重要性重采样）——平坦区液滴侵蚀≈0（无效功）。
        //   1 格差分坡度（比 3×3 Sobel 便宜），世界坐标确定性函数 → 无缝保持。
        //   px/py 已由上方边界检查保证 ∈ [1, bufSize-2] → ±1 采样安全。
        float slope = Math.max(
            Math.abs(flat[(iy + 1) * bufSize + ix] - flat[(iy - 1) * bufSize + ix]),
            Math.abs(flat[iy * bufSize + ix + 1] - flat[iy * bufSize + ix - 1]));
        // 2026-08-10 wu 化修正：1 格差分坡度 = e/wu，×hs 还原物理坡度（e/块）再比阈值
        // ★ 2026-08-13 平滑概率闸门：硬阈值（slope<h 全跳）在坡度恰卡阈值处 → 1 块内
        //   出生 0/1 跳变 → delta 场突变（游戏内 x=-288 实测 2.6 块墙，基础场完全连续）。
        //   改为 smoothstep(0.5h, 1.5h) 概率出生（出生单元 hash 确定性随机）→ 出生密度
        //   随坡度平滑过渡 → delta 场连续。陡坡（>1.5h）全量出生，行为不变。
        float slopeE = slope * hs;
        float sLo = SLOPE_MIN_SKIP * 0.5f, sHi = SLOPE_MIN_SKIP * 1.5f;
        float prob;
        if (slopeE <= sLo) prob = 0f;
        else if (slopeE >= sHi) prob = 1f;
        else {
            float t = (slopeE - sLo) / (sHi - sLo);
            prob = t * t * (3f - 2f * t);
        }
        if (prob < 1f) {
            // ★ 2026-08-13 修复：概率闸门 hash 必须用世界坐标（gx,gz），不能用缓冲坐标
            //   （ix=px-pad+ox）。同一世界坐标在两个 tile 的缓冲坐标不同 → hash 不同 →
            //   出生集合跨 tile 不一致 → delta 双写断裂（x=-288 实测 3.8 块，骨架层 0.1 块无罪）。
            //   出生判定 hash(gx,gz)（上方 spawn 循环）已是世界坐标，这里必须一致。
            long sh = hash(gx * 131 + 7, gz * 131 + 11);
            if (((sh >>> 32) & 0xFFFF) / 65536f >= prob) return;
        }
        simulateDrop(flat, bufSize, px + 0.5f, py + 0.5f,
                     bOff, bWgt, bn, locked, pad, baseSize,
                     dis, disT, momX, momY, momXT, momYT, momTransfer,
                     erodeSpeed, depositSpeed, lifetime, ox, oz,
                     cascadeStrength, hs, spdCap, stallSpeed, stallDelta);
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
                             float cascadeStrength, float hs,
                             float spdCap, float stallSpeed, float stallDelta) {
        float dirX = 0, dirY = 0, sed = 0, spd = 1f, wat = 1f;

        int spawnIx = (int) posX, spawnIy = (int) posY;
        float startH = flat[spawnIy * bufSize + spawnIx];

        // ★ 2026-08-09 优化：平衡态 early-exit（Russian roulette 剪枝）——平坦/平衡区液滴
        //   速度与笔刷增量双双趋于 0（对地形无贡献），连续 STALL_MAX 步后提前终止。
        //   确定性：判定条件是确定性函数的单调收敛 → 同一世界坐标液滴终止点固定 → 无缝保持。
        // ★ 2026-08-14 湖泊溢出续流（断流修复）：STALL 达阈值不再 return，转 tracking 模式——
        //   液滴流到局部最低点（洼地）后停止侵蚀笔刷/cascade（不挖坑），但照常走 + 写 disT
        //   （流线续流），片流/惯性带它爬向盆周溢出口，梯度恢复（brushDelta 回升）→ 自动恢复侵蚀。
        //   侵蚀液滴自身 = 河道（用户定案），dis 场 = 流量图，不另设硬编码河道。
        int stall = 0;
        boolean tracking = false;
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
            float effD = 0.1f;                       // 松弛率 = depositionRate(=0.1)
            float delta = effD * (c_eq - sed);       // >0=侵蚀, <0=沉积
            float brushDelta = delta;                // 笔刷前增量快照（early-exit 判定用）

            // 笔刷邻域分布：权重须对正负通用
            // delta>0: 从邻域取材料（侵蚀）→ flat[bi] 减小, sed 增大
            // delta<0: 向邻域加材料（沉积）→ flat[bi] 增大, sed 减小
            // ★ 2026-08-14 tracking（洼地溢出追踪）跳过侵蚀笔刷——洼地内打转液滴不再挖坑
            if (!tracking) {
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
            }

            // ---- track 累积（SH water.h:113-117：本轮 discharge + 动量 volume·speed） ----
            // 2026-08-01：discharge 场不再导出给河网（StreamTracer 独立追踪），保留动量场正反馈
            disT[idx] += wat;
            momXT[idx] += wat * dirX * Math.min(spd, spdCap);
            momYT[idx] += wat * dirY * Math.min(spd, spdCap);

            // ---- 局部 cascade（每 CASCADE_INTERVAL 步执行，对齐 SH 每步语义） ----
            // ★ 2026-08-14 tracking 跳过 cascade（洼地内不平滑——保持续流轨迹不扰动地形）
            if (!tracking && cascadeStrength > 0 && st % CASCADE_INTERVAL == 0) {
                cascadeLocal(flat, bufSize, nix, niy, cascadeStrength, hs);
            }

            // NaN/Inf 守卫：陡峭下坡（dh 很负）可能导致 spd² + dh*GRAVITY < 0 → sqrt(NaN)
            // 若溢出则降速为 0.1（允许液滴继续存活的极小速度，非致命）
            float speedSq = spd * spd + dh * GRAVITY;
            spd = speedSq > 1e-12f ? (float) Math.sqrt(speedSq) : 0.1f;
            if (spd <= 0) return;
            // ★ 2026-08-14 湖泊溢出续流：STALL 达阈值 → 转 tracking（不 return，液滴不死在洼地）；
            //   走出洼地梯度恢复（brushDelta 回升）→ else 分支 tracking=false 恢复侵蚀。
            if (spd < stallSpeed && Math.abs(brushDelta) < stallDelta) {
                if (++stall >= STALL_MAX) tracking = true;
            } else {
                stall = 0;
                tracking = false;   // 正常流动 / 找到溢出口 → 恢复侵蚀
            }
            wat *= (1 - EVAP_RATE);
        }
    }

    /** 局部 cascade：8 邻居按高度排序 + 距离加权 settling（SH 原版语义）。
     *  hs：wu→块 换算（dist 为 wu 格距，×hs 还原块距离，2026-08-10 wu 化）。 */
    private void cascadeLocal(float[] flat, int bufSize, int cx, int cz, float strength, float hs) {
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
            float excess = Math.abs(diff) - dist * CASCADE_MAXDIFF * hs;
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
                            // ★ 2026-08-12 T3：只削明显孤峰（突出 >0.003e≈1 块），
                            // 保留 1-3 块级沉积微纹理（旧版无条件平均会抹掉细节）
                            if (diff <= 0.003f) continue;
                            float blend = Math.min((diff - 0.003f) * 3.0f, 0.6f);
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
