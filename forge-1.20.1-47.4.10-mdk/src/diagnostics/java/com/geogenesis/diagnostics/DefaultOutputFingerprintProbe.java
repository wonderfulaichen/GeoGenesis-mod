package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>默认配置下的生产产出指纹</b>：证明"只改配置面板 / 内置预设"<b>不改变</b>新世界的地形产出。
 *
 * <h2>为什么需要</h2>
 * <p>配置面板重构、文案修正、预设改写都属于"UI 层"，按设计<b>不该</b>触碰地形。
 * 但本项目反复出现"顺手改了别处"的事故（如默认值漂移、buildParams 顺序错位）。
 * 只靠"我改的是 client/"这句自述不够 —— 必须有一条能跑出差异的命令。</p>
 *
 * <h2>判据</h2>
 * <ul>
 *   <li>{@code --emit <file>}：把当前产出写成 TSV（基准）。</li>
 *   <li>{@code --compare <file>}：与基准逐行比较，<b>任一行不同即 {@code System.exit(1)}</b>。</li>
 * </ul>
 * <p>比较的是<b>逐位</b>量：{@code height} 用 {@link Double#toHexString}（十进制尾数会掩盖 1ulp 差）。</p>
 *
 * <h2>口径</h2>
 * <p>走生产快路径 {@link GeoGenesisTerrain#sampleCellLight}（= {@code generator.sample} +
 * 精确 P 增量 + 河距），<b>不是</b>探针专用旁路。morphBase=true（新世界）、
 * {@link TerrainParams#defaults()}、不读 TOML（探针进程无 Forge 环境）。</p>
 */
public final class DefaultOutputFingerprintProbe {

    /** 固定采样点：覆盖多气候带、多大陆性，且块坐标是 16 的倍数（与 chunk 网格对齐）。 */
    private static final int[][] POINTS = {
            {0, 0}, {256, 0}, {-256, 0}, {0, 256}, {0, -256},
            {512, 512}, {-512, 512}, {512, -512}, {-512, -512},
            {1024, 0}, {-1024, 0}, {0, 1024}, {0, -1024},
            {768, -768}, {-768, 768}, {1536, 512}, {-1536, -512},
            {2048, 0}, {0, 2048}, {-2048, 0}, {0, -2048},
            {2560, 1280}, {-2560, -1280}, {3200, 640},
    };

    private static final long SEED = 12345L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String emit = null;
        String compare = null;
        for (int i = 0; i < args.length; i++) {
            if ("--emit".equals(args[i]) && i + 1 < args.length) emit = args[++i];
            else if ("--compare".equals(args[i]) && i + 1 < args.length) compare = args[++i];
        }

        List<String> rows = sample();

        System.out.println("=== DEFAULT OUTPUT FINGERPRINT ===");
        System.out.println("  seed     : " + SEED);
        System.out.println("  params   : TerrainParams.defaults()");
        System.out.println("  morphBase: true（新世界）");
        System.out.println("  采样点   : " + POINTS.length + "（生产快路径 sampleCellLight）");
        System.out.println("  首行     : " + (rows.isEmpty() ? "-" : rows.get(0)));

        if (emit != null) {
            Path out = Path.of(emit);
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            Files.write(out, rows, StandardCharsets.UTF_8);
            System.out.println("  已写出基准: " + out.toAbsolutePath());
            System.out.println("FINGERPRINT EMITTED");
            return;
        }

        if (compare != null) {
            Path ref = Path.of(compare);
            if (!Files.isRegularFile(ref)) {
                System.out.println("FAIL: 基准文件不存在：" + ref.toAbsolutePath());
                System.exit(1);
                return;
            }
            List<String> base = Files.readAllLines(ref, StandardCharsets.UTF_8);
            int diff = 0;
            for (int i = 0; i < Math.max(base.size(), rows.size()); i++) {
                String a = i < base.size() ? base.get(i) : "<missing>";
                String b = i < rows.size() ? rows.get(i) : "<missing>";
                if (!a.equals(b)) {
                    diff++;
                    if (diff <= 10) {
                        System.out.println("  DIFF [" + i + "]");
                        System.out.println("    基准: " + a);
                        System.out.println("    当前: " + b);
                    }
                }
            }
            System.out.println("  基准行数 " + base.size() + " / 当前行数 " + rows.size()
                    + " / 逐位不同 " + diff);
            if (diff != 0) {
                System.out.println("FAIL: 默认产出与基准不逐位相同 —— 本轮改动泄漏进了生产路径");
                System.exit(1);
                return;
            }
            System.out.println("PASS 默认产出与基准逐位相同（面板/预设/文案改动未影响生产）");
            return;
        }

        System.out.println("（未指定 --emit / --compare：仅打印，不判定）");
    }

    private static List<String> sample() {
        GeoGenesisTerrain terrain =
                new GeoGenesisTerrain(new CellGenerator(TerrainParams.defaults(), -64, 320, true));
        terrain.seed(SEED);
        List<String> rows = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int[] p : POINTS) {
            Cell c = terrain.sampleCellLight(p[0], p[1]);
            StringBuilder sb = new StringBuilder();
            sb.append(p[0]).append('\t').append(p[1]).append('\t');
            sb.append(Double.toHexString(c.height)).append('\t');
            sb.append(Double.toHexString(c.e)).append('\t');
            sb.append(Double.toHexString(c.eLand)).append('\t');
            sb.append(Double.toHexString(c.snowLineE)).append('\t');
            sb.append(c.terrainType).append('\t');
            sb.append(c.biomeType).append('\t');
            sb.append(c.rockTypeId).append('\t');
            sb.append(c.rockLayer).append('\t');
            sb.append(c.isSnow ? 1 : 0);
            rows.add(sb.toString());
        }
        System.out.printf(Locale.ROOT, "  采样耗时 : %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        return rows;
    }

    private DefaultOutputFingerprintProbe() { }
}
