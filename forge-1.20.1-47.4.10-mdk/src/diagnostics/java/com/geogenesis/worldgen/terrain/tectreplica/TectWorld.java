package com.geogenesis.worldgen.terrain.tectreplica;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.geogenesis.worldgen.noise.Simplex;

/**
 * 世界容器（World）—— tectonics.h:288-497 的移植。
 *
 * <p>索引约定（两套并存，与原版一致）：
 * <ul>
 * <li>热场 {@code heatmap} <b>x-major</b>：{@code [x*SIZE+y]}——生长
 *     {@code hm[ip.y+ip.x*SIZE]}（tectonics.h:211）与力场
 *     {@code ff[(ix±1)*SIZE+iy]}（tectonics.h:236-237）的公式按原样成立；</li>
 * <li>索引图 {@code indexmap} / 高度图 {@code heightmap} <b>y-major</b>：
 *     {@code [y*SIZE+x]}（cluster.h:126）。heightmap 由本复刻定义
 *     （替代被跳过的 GPU 沙堆级联），故自选 y-major 与 indexmap 同系。</li>
 * </ul></p>
 *
 * <p>热场语义（tectonics.h:298-300,366-374）：初始 = 8 倍频、频率 2、持久度 0.5 的
 * 梯度噪声（原版 libnoise Perlin3 以 SEED 作 z 坐标；本复刻用项目自带
 * {@link Simplex} 每倍频独立实例，属已记录偏差），min-max 归一化到 [0,1]。
 * 热区≈洋脊（生长慢、密度低），冷区≈俯冲带方向（快长、增厚成陆）。</p>
 */
final class TectWorld {

    static final int SIZE = 256;
    static final int K = 8192;
    static final int NPLATES = 12;
    static final double DT = 0.025;
    /** NDC 单位段半径：2·sqrt(4/(π·K))（SimpleTectonics.cpp:18）。 */
    static final double R = 2.0 * Math.sqrt(4.0 / (Math.PI * K));
    /** 碰撞半径系数（SimpleTectonics.cpp:19）。 */
    static final double CR = R / 14.0;
    /** 板块视图垂直放大（tectonics.h:328）。 */
    static final double SCALE = 50.0;

    final List<TectPlate> plates = new ArrayList<>();
    final TectCluster cluster = new TectCluster();

    final double[] heatmap = new double[SIZE * SIZE];
    final double[] heightmap = new double[SIZE * SIZE];

    private final TectRng rng;
    private final Simplex[] octaves;
    private final double[] heatScratch = new double[SIZE * SIZE];

    TectWorld(long seed) {
        this.rng = new TectRng(seed);
        // 每倍频独立 Simplex（ContinentField 先例 7000+i*97；seed(worldSeed, level) 再掺一层）
        octaves = new Simplex[8];
        for (int i = 0; i < octaves.length; i++) {
            octaves[i] = new Simplex(7000 + i * 97);
            octaves[i].seed(seed, i);
        }
        // 原版顺序：cluster（含泊松采样）先于 World 构造体初始化（C++ 成员构造序）
        cluster.init(rng);
        initialize(seed);
    }

    // ===== 初始化（tectonics.h:358-423）=====

    private void initialize(long seed) {
        double min = 1.0, max = -1.0;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                double v = fbm((double) x / SIZE, (double) y / SIZE);
                heatmap[x * SIZE + y] = v;
                if (v > max) {
                    max = v;
                }
                if (v < min) {
                    min = v;
                }
            }
        }
        for (int i = 0; i < SIZE * SIZE; i++) {
            heatmap[i] = (heatmap[i] - min) / (max - min);
        }
        Arrays.fill(heightmap, 0.31);

        // 12 个板块质心：rand()%SIZE ×2（tectonics.h:400-401）
        for (int i = 0; i < NPLATES; i++) {
            plates.add(new TectPlate(rng.nextInt(SIZE), rng.nextInt(SIZE)));
        }
        // 段按最近板块指派（tectonics.h:404-418）
        for (TectSegment s : cluster.segs) {
            double dist = SIZE * SIZE;
            TectPlate nearest = null;
            for (TectPlate p : plates) {
                double dx = p.posX - s.x, dy = p.posY - s.y;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < dist) {
                    dist = d;
                    nearest = p;
                }
            }
            nearest.segs.add(s);
            s.parent = nearest;
        }
        for (TectPlate p : plates) {
            p.recenter();
        }
    }

    /** libnoise Perlin(f=2, lac=2 默认, persistence=0.5, 8 octaves) 的 Simplex 等价。 */
    private double fbm(double u, double v) {
        double sum = 0;
        double freq = 2.0;
        double amp = 1.0;
        for (Simplex oct : octaves) {
            sum += oct.compute(u * freq, v * freq) * amp;
            freq *= 2.0;
            amp *= 0.5;
        }
        return sum;
    }

    // ===== 热场更新（tectonics.h:499-539，GPU 双 pass 的 CPU 版）=====

    /**
     * 每步 n=25 次交替：{4 邻域 Jacobi 扩散 D=0.2（diffusion.fs）}
     * → {背景像素 ×0.9 冷却、死段 Voronoi 格向 1.0 混 0.4（subduction.fs）}。
     * <p>★ 8-bit 量化必须保留（非噪声）：原版热场每步 25 次往返 RGBA8 贴图，
     * &lt;1/255 的扩散变化被抹掉 ⇒ 热场块状滞留 ⇒ 持久梯度 ⇒ 持久对流推力。
     * 曾改 double 连续值（动力学偏弱），已回退为每级输出 quantize()。</p>
     * <p>边缘行为：diffusion.fs 的 textureOffset 取 TinyEngine 纹理包装方式，
     * 无法从本仓库核实——按 CLAMP_TO_EDGE 处理（已记录假设）。</p>
     */
    void subductHeat(int n) {
        int[] dead = new int[cluster.segs.size()];
        for (int i = 0; i < cluster.segs.size(); i++) {
            dead[i] = cluster.segs.get(i).alive ? 0 : 1;
        }
        double[] a = heatmap;
        double[] b = heatScratch;
        for (int iter = 0; iter < n; iter++) {
            diffuse(a, b);
            subduct(b, a, dead);
        }
    }

    private void diffuse(double[] src, double[] dst) {
        double d = 0.2;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                int i = x * SIZE + y;
                double c = src[i];
                double xl = src[xl(x) * SIZE + y];
                double xr = src[xr(x) * SIZE + y];
                double yd = src[x * SIZE + yd(y)];
                double yu = src[x * SIZE + yu(y)];
                dst[i] = quantize(c + d * ((xl - c) + (xr - c) + (yd - c) + (yu - c)));
            }
        }
    }

    /** RGBA8 往返的 8-bit 量化（round-to-nearest）；见 subductHeat 的偏差说明。 */
    private static double quantize(double v) {
        return Math.rint(Math.min(1.0, Math.max(0.0, v)) * 255.0) / 255.0;
    }

    private int xl(int x) {
        return Math.max(0, x - 1);
    }

    private int xr(int x) {
        return Math.min(SIZE - 1, x + 1);
    }

    private int yd(int y) {
        return Math.max(0, y - 1);
    }

    private int yu(int y) {
        return Math.min(SIZE - 1, y + 1);
    }

    private void subduct(double[] src, double[] dst, int[] dead) {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                int p = y * SIZE + x;   // indexmap 是 y-major
                int idx = cluster.indexmap[p];
                double v = src[x * SIZE + y];   // src 是 x-major 热场
                if (idx < 0) {
                    // 背景（原版白像素）：mix(cold, 0.1)（subduction.fs:27-28）
                    v *= 0.9;
                } else if (dead[idx] == 1) {
                    // 死段格：mix(warm, 0.4)（subduction.fs:31-33）
                    v = v * 0.6 + 0.4;
                }
                dst[x * SIZE + y] = quantize(v);
            }
        }
    }

    // ===== 世界整理（tectonics.h:425-491）=====

    void updateWorld() {
        // 出界（[-SIZE, 2SIZE-1] 之外）判死 + 从板块移除 + recenter（tectonics.h:427-444）
        for (TectPlate p : plates) {
            boolean erased = false;
            for (int i = 0; i < p.segs.size(); i++) {
                TectSegment s = p.segs.get(i);
                if (s.x < -SIZE || s.x > 2 * SIZE - 1 || s.y < -SIZE || s.y > 2 * SIZE - 1) {
                    s.alive = false;
                }
                if (!s.alive) {
                    p.segs.remove(i);
                    i--;
                    erased = true;
                }
            }
            if (erased) {
                p.recenter();
            }
        }
        // 删空板块（tectonics.h:446-452）
        for (int i = 0; i < plates.size(); i++) {
            if (plates.get(i).segs.isEmpty()) {
                plates.remove(i);
                i--;
            }
        }
        cluster.removeDead();
        fillGaps();
        cluster.update();
    }

    /**
     * 间隙填充 = 洋脊增生（tectonics.h:458-487）：每板块每步最多 1 段；扫描角只有
     * 100 档离散值（rand()%100，如实照抄）；新段用默认字段（mass=thickness=0.1，
     * density 停在 0.5——见 {@link TectSegment} 的移植说明）。
     */
    private void fillGaps() {
        for (TectPlate p : plates) {
            int n0 = p.segs.size();   // 原版遍历中 push_back（UB 但 break 兜底）；按原 size 遍历等价
            for (int i = 0; i < n0; i++) {
                TectSegment s = p.segs.get(i);
                double angle = rng.nextInt(100) / 100.0 * 2.0 * Math.PI;
                double sx = s.x + SIZE * R / 2.0 * Math.cos(angle);
                double sy = s.y + SIZE * R / 2.0 * Math.sin(angle);
                if (sx < 0 || sx >= SIZE || sy < 0 || sy >= SIZE) {
                    continue;
                }
                if (cluster.sample(sx, sy) < 0) {
                    TectSegment ns = new TectSegment(sx, sy);
                    cluster.segs.add(ns);
                    p.segs.add(ns);
                    ns.parent = p;
                    p.recenter();
                    break;
                }
            }
        }
    }

    // ===== 地形高度图（替代被跳过的沙堆级联）=====

    /**
     * cascading.fs 的 heightcol 语义：每像素 = 最近段高度，背景 = 0；随后用 3 次
     * 3×3 盒式模糊近似 25×2 次级联的平滑效果（已记录的替代方案，非逐位等价）。
     * 调用时机：批量模式结束、渲染地形视图前（对应 SimpleTectonics.cpp:57-84
     * 注释掉的 500 步流程末尾那一次 sediment(25)）。
     */
    void buildHeightmap() {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                int idx = cluster.indexmap[y * SIZE + x];
                heightmap[y * SIZE + x] = idx >= 0 ? cluster.segs.get(idx).height : 0.0;
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            blurOnce();
        }
    }

    private void blurOnce() {
        double[] tmp = new double[SIZE * SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                double sum = 0;
                int cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = Math.min(SIZE - 1, Math.max(0, x + dx));
                        int ny = Math.min(SIZE - 1, Math.max(0, y + dy));
                        sum += heightmap[ny * SIZE + nx];
                        cnt++;
                    }
                }
                tmp[y * SIZE + x] = sum / cnt;
            }
        }
        System.arraycopy(tmp, 0, heightmap, 0, tmp.length);
    }

    // ===== 状态摘要（SHA 与统计）=====

    /** 最终状态数组的 SHA-256 输入流（确定性对拍用）。 */
    void digestInto(java.security.MessageDigest md) {
        byte[] buf = new byte[8];
        feed(md, buf, heatmap);
        feed(md, buf, heightmap);
        for (TectSegment s : cluster.segs) {
            putLong(md, buf, Double.doubleToLongBits(s.x));
            putLong(md, buf, Double.doubleToLongBits(s.y));
            putLong(md, buf, Double.doubleToLongBits(s.mass));
            putLong(md, buf, Double.doubleToLongBits(s.thickness));
            putLong(md, buf, Double.doubleToLongBits(s.area));
            putLong(md, buf, Double.doubleToLongBits(s.density));
            putLong(md, buf, Double.doubleToLongBits(s.height));
            putLong(md, buf, s.alive ? 1 : 0);
            putLong(md, buf, s.colliding ? 1 : 0);
        }
        putLong(md, buf, plates.size());
    }

    private static void feed(java.security.MessageDigest md, byte[] buf, double[] arr) {
        for (double v : arr) {
            putLong(md, buf, Double.doubleToLongBits(v));
        }
    }

    private static void putLong(java.security.MessageDigest md, byte[] buf, long v) {
        for (int i = 0; i < 8; i++) {
            buf[i] = (byte) (v >>> (56 - 8 * i));
        }
        md.update(buf);
    }
}
