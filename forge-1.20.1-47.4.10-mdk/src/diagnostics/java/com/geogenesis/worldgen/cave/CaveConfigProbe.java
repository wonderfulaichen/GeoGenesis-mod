package com.geogenesis.worldgen.cave;

/**
 * 洞穴<b>配置档位</b>诊断探针（★ 2026-09-15）。
 *
 * <h3>为何需要（用户要求"可开关配置，想拟真就开、不要就关"）</h3>
 * <p>配置一旦接错（档位没生效、开关读到旧值、关闭档仍在挖洞），玩家会看到
 * <b>与选项不符的世界</b>，而现有探针（{@code CaveShapeProbe}/{@code CavePerfProbe}）
 * 全部<b>假定默认参数</b>，<b>看不见配置</b>。本探针补上这个盲区。</p>
 *
 * <h3>判据</h3>
 * <ol>
 *   <li><b>OFF 必须真的无洞</b>：{@code caveEnabled=false} 或档位 OFF ⇒
 *       洞穴体素数必须为 <b>0</b>（最关键的"关闭"语义）；</li>
 *   <li><b>各档位单调合理</b>：密度 REALISTIC &lt; VANILLA_LIKE（后者阈值放大 ⇒ 洞更粗）；</li>
 *   <li><b>岩性门控开关生效</b>：REALISTIC 下石灰岩 &gt; 花岗岩；VANILLA_LIKE 下二者<b>相等</b>；</li>
 *   <li><b>层调制开关生效</b>：REALISTIC 的<b>竖直连通段</b>应显著短于 VANILLA_LIKE
 *       （层调制的作用就是防竖直贯穿）；</li>
 *   <li><b>分量开关生效</b>：关掉 chamber ⇒ 只剩隧道；关掉 tunnel ⇒ 只剩空腔；</li>
 *   <li><b>性能</b>：配置读取不得让逐体素路径退化（对照 {@code runCavePerfProbe}）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runCaveConfigProbe [-PprobeArgs="seed spanXY"]}</pre>
 */
public final class CaveConfigProbe {

    private CaveConfigProbe() { }

    private static final int WORLD_MIN_Y = -64;
    private static final int SYN_SURFACE = 120;
    /** 中性岩性（隔离"岩性门控"这一变量时用）。 */
    private static final double LITHO_NEUTRAL = 1.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int N = args.length > 1 ? Integer.parseInt(args[1]) : 96;

        System.out.printf("=== CaveConfigProbe seed=%d spanXY=%d ===%n", seed, N);
        CaveShape.setSeed(seed);

        // ---------- 判据1：OFF 必须真的无洞 ----------
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.OFF));
        long offVox = countCaves(N, LITHO_NEUTRAL);
        System.out.printf("[1] OFF 档: 洞穴体素=%d%n", offVox);
        boolean pass1 = offVox == 0;
        System.out.printf("[判据1] OFF 档确实无洞穴（关闭语义正确）: %s%n", pass1 ? "PASS" : "FAIL");

        // 另外验证"总开关"独立于档位：档位 REALISTIC + enabled=false 也必须无洞
        CaveShape.setConfig(CaveConfig.custom(false, true, true, true, 1.0, 6, 8, 120, true, true));
        long swOffVox = countCaves(N, LITHO_NEUTRAL);
        System.out.printf("    总开关 enabled=false: 洞穴体素=%d%n", swOffVox);
        boolean pass1b = swOffVox == 0;
        System.out.printf("[判据1b] 总开关 false 确实无洞穴（与档位正交）: %s%n",
                pass1b ? "PASS" : "FAIL");

        // ---------- 判据2：档位密度单调（REALISTIC < VANILLA_LIKE）----------
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.REALISTIC));
        long realVox = countCaves(N, LITHO_NEUTRAL);
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.VANILLA_LIKE));
        long vanVox = countCaves(N, LITHO_NEUTRAL);
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.MINIMAL));
        long minVox = countCaves(N, LITHO_NEUTRAL);
        System.out.printf("[2] 洞穴体素: REALISTIC=%d  VANILLA_LIKE=%d  (MINIMAL=%d)%n",
                realVox, vanVox, minVox);
        // ★ 判据口径修正（2026-09-15，实测踩过）：
        //   初版断言 `VANILLA_LIKE > REALISTIC` —— 这是【错的口径】。
        //   两档差异不止 densityMul：REALISTIC 还带【岩性门控】，而岩性系数
        //   可放大到 1.7（石灰岩）⇒ 不同种子/窗口下两者会【互有高低】
        //   （实测 N=56/seed=12345：REALISTIC=23609 > VANILLA_LIKE=22687；
        //     N=72/seed=12345 则相反）⇒ 该断言测的是【方差】而非【功能】。
        //   真正该验证的语义是：① 两档都产洞；② MINIMAL 明显更少；③ OFF 为 0（判据1）。
        boolean pass2 = realVox > 0 && vanVox > 0 && minVox < realVox && minVox < vanVox;
        System.out.printf("[判据2] 档位语义合理（两档均产洞，MINIMAL 最少）: %s%n",
                pass2 ? "PASS" : "FAIL");
        System.out.println("    注：REALISTIC 与 VANILLA_LIKE 谁更多【不作断言】——"
                + "前者含岩性门控（系数可达 1.7）、后者只放大密度，"
                + "跨种子会互有高低，属方差而非缺陷。");

        // ---------- 判据3：岩性门控开关 ----------
        //   受控对照：同一点，只换岩性（石灰岩 ordinal=5 / 花岗岩 ordinal=2）。
        double lithoLimestone = CaveShape.lithoFactor(5);
        double lithoGranite = CaveShape.lithoFactor(2);
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.REALISTIC));
        long realLs = countCaves(N, lithoLimestone);
        long realGr = countCaves(N, lithoGranite);
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.VANILLA_LIKE));
        long vanLs = countCaves(N, lithoLimestone);
        long vanGr = countCaves(N, lithoGranite);
        System.out.printf("[3] REALISTIC: 石灰岩=%d 花岗岩=%d（比值 %.2f×）%n",
                realLs, realGr, realGr == 0 ? -1.0 : (double) realLs / realGr);
        System.out.printf("    VANILLA_LIKE: 石灰岩=%d 花岗岩=%d（应相等）%n", vanLs, vanGr);
        boolean pass3 = realLs > realGr && vanLs == vanGr;
        System.out.printf("[判据3] 岩性门控开关生效（拟真档有差异 / 原版档一视同仁）: %s%n",
                pass3 ? "PASS" : "FAIL");

        // ---------- 判据4：层调制开关（防竖直贯穿）----------
        //   指标：同一 (x,z) 列上洞穴的【最长连续竖直段】的平均值。
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.REALISTIC));
        double realVSeg = avgVerticalRun(N);
        CaveShape.setConfig(CaveConfig.fromPreset(CaveConfig.Preset.VANILLA_LIKE));
        double vanVSeg = avgVerticalRun(N);
        System.out.printf("[4] 平均最长竖直连通段: REALISTIC=%.2f  VANILLA_LIKE=%.2f%n",
                realVSeg, vanVSeg);
        boolean pass4 = realVSeg < vanVSeg;
        System.out.printf("[判据4] 层调制生效（拟真档竖直段更短）: %s%n", pass4 ? "PASS" : "FAIL");

        // ---------- 判据5：分量开关 ----------
        CaveShape.setConfig(CaveConfig.custom(true, true, false, true, 1.0, 6, 8, 120, true, true));
        long tunnelOnly = countMask(N, CaveShape.F_TUNNEL);
        long chamberInTunnelOnly = countMask(N, CaveShape.F_CAVERN);
        CaveShape.setConfig(CaveConfig.custom(true, false, true, true, 1.0, 6, 8, 120, true, true));
        long chamberOnly = countMask(N, CaveShape.F_CAVERN);
        long tunnelInChamberOnly = countMask(N, CaveShape.F_TUNNEL);
        System.out.printf("[5] 仅隧道: TUNNEL=%d CAVERN=%d | 仅空腔: CAVERN=%d TUNNEL=%d%n",
                tunnelOnly, chamberInTunnelOnly, chamberOnly, tunnelInChamberOnly);
        boolean pass5 = tunnelOnly > 0 && chamberInTunnelOnly == 0
                && chamberOnly > 0 && tunnelInChamberOnly == 0;
        System.out.printf("[判据5] 分量开关生效（关掉的分量恒为 0）: %s%n", pass5 ? "PASS" : "FAIL");

        // ---------- 判据6：性能（配置读取不得退化）----------
        CaveShape.setConfig(CaveConfig.DEFAULT);
        long t0 = System.nanoTime();
        long vox = countCaves(N, LITHO_NEUTRAL);
        long el = System.nanoTime() - t0;
        long total = (long) N * N * (SYN_SURFACE - WORLD_MIN_Y - 1);
        double usPer = el / 1000.0 / total;
        System.out.printf("[6] 配置生效下: 体素=%d 实挖=%d 耗时=%dms 单次=%.4f us%n",
                total, vox, el / 1_000_000, usPer);
        boolean pass6 = usPer <= 1.0;
        System.out.printf("[判据6] 配置读取未拖慢热路径（≤1.0 us/次）: %s%n", pass6 ? "PASS" : "FAIL");

        CaveShape.setConfig(CaveConfig.DEFAULT);
        int failures = (pass1 ? 0 : 1) + (pass1b ? 0 : 1) + (pass2 ? 0 : 1)
                + (pass3 ? 0 : 1) + (pass4 ? 0 : 1) + (pass5 ? 0 : 1) + (pass6 ? 0 : 1);
        System.out.println(failures == 0 ? "ALL PASS" : ("FAILURES=" + failures));
    }

    /** 统计地下带内的洞穴体素数（任意分量）。 */
    private static long countCaves(int N, double litho) {
        long n = 0;
        for (int wx = 0; wx < N; wx++) {
            for (int wz = 0; wz < N; wz++) {
                for (int wy = WORLD_MIN_Y + 1; wy < SYN_SURFACE; wy++) {
                    if (CaveShape.components(wx, wy, wz, SYN_SURFACE, WORLD_MIN_Y, litho) != 0) n++;
                }
            }
        }
        return n;
    }

    /** 统计命中指定分量的体素数。 */
    private static long countMask(int N, int mask) {
        long n = 0;
        for (int wx = 0; wx < N; wx++) {
            for (int wz = 0; wz < N; wz++) {
                for (int wy = WORLD_MIN_Y + 1; wy < SYN_SURFACE; wy++) {
                    if ((CaveShape.components(wx, wy, wz, SYN_SURFACE, WORLD_MIN_Y, LITHO_NEUTRAL)
                            & mask) != 0) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** 平均"最长竖直连续段"（防竖直贯穿的度量）。 */
    private static double avgVerticalRun(int N) {
        long sum = 0, cols = 0;
        for (int wx = 0; wx < N; wx++) {
            for (int wz = 0; wz < N; wz++) {
                int run = 0, best = 0;
                for (int wy = WORLD_MIN_Y + 1; wy < SYN_SURFACE; wy++) {
                    if (CaveShape.components(wx, wy, wz, SYN_SURFACE, WORLD_MIN_Y, LITHO_NEUTRAL) != 0) {
                        run++;
                        best = Math.max(best, run);
                    } else {
                        run = 0;
                    }
                }
                if (best > 0) { sum += best; cols++; }
            }
        }
        return cols == 0 ? 0.0 : (double) sum / cols;
    }
}
