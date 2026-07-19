package com.geogenesis.worldgen.terrain;

/**
 * 地形断裂专项诊断 v2 — 在玩家坐标处检查 terrainType/block 突变。
 *
 * 运行：gradlew.bat runDiscontinuityProbe
 */
public final class TerrainDiscontinuityProbe {

    public static void main(String[] args) {
        long seed = -9076282657783003134L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        int[][] coords = {{124, -290, 18, -18}, {203, 497, 12, 31}};
        String[] labels = {"图1(直线断裂)", "图2(弯曲断裂)"};

        for (int ci = 0; ci < coords.length; ci++) {
            int baseX = coords[ci][0];
            int baseZ = coords[ci][1];
            int chunkX = coords[ci][2];
            int chunkZ = coords[ci][3];
            System.out.println("=== " + labels[ci] + " @ (" + baseX + ", " + baseZ + ") ===");

            // 扫描 3×3 chunks，专门找 type/height 跳变
            int startX = (chunkX - 1) * 16;
            int startZ = (chunkZ - 1) * 16;
            int size = 48;

            Cell[][] grid = new Cell[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    grid[i][j] = gen.sample(startX + i, startZ + j);
                }
            }

            // A) 统计 terrainType 突变（相邻 cell 不同 type 且 height 差小 => 表面材质断裂）
            System.out.println("  [A] terrainType 突变（相邻 cell 不同 type）:");
            int typeChanges = 0;
            for (int i = 0; i < size - 1; i++) {
                for (int j = 0; j < size; j++) {
                    Cell c1 = grid[i][j];
                    Cell c2 = grid[i+1][j];
                    if (c1.terrainType != c2.terrainType) {
                        double hDiff = Math.abs(c1.height - c2.height);
                        typeChanges++;
                        if (hDiff < 3.0) { // 高度差小但类型变了 -> 表面材质断裂
                            System.out.printf("    邻接X @(%d,%d) %s(%d) -> %s(%d) hDiff=%.1f%n",
                                startX+i, startZ+j,
                                c1.terrainType.name().substring(0, Math.min(8, c1.terrainType.name().length())),
                                (int)c1.terrainType.ordinal(),
                                c2.terrainType.name().substring(0, Math.min(8, c2.terrainType.name().length())),
                                (int)c2.terrainType.ordinal(),
                                hDiff);
                        }
                    }
                }
            }

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size - 1; j++) {
                    Cell c1 = grid[i][j];
                    Cell c2 = grid[i][j+1];
                    if (c1.terrainType != c2.terrainType) {
                        double hDiff = Math.abs(c1.height - c2.height);
                        typeChanges++;
                        if (hDiff < 3.0) {
                            System.out.printf("    邻接Z @(%d,%d) %s(%d) -> %s(%d) hDiff=%.1f%n",
                                startX+i, startZ+j,
                                c1.terrainType.name().substring(0, Math.min(8, c1.terrainType.name().length())),
                                (int)c1.terrainType.ordinal(),
                                c2.terrainType.name().substring(0, Math.min(8, c2.terrainType.name().length())),
                                (int)c2.terrainType.ordinal(),
                                hDiff);
                        }
                    }
                }
            }
            System.out.println("    总type变化次数: " + typeChanges);
            System.out.println();

            // B) 统计高度差 > 3 块的相邻 cell（真正的可见悬崖）
            System.out.println("  [B] 高度差 > 3 块的相邻 cell（可见悬崖）:");
            int cliffCount = 0;
            double maxDiff = 0;
            int maxI = 0, maxJ = 0, maxDir = 0;
            for (int i = 0; i < size - 1; i++) {
                for (int j = 0; j < size; j++) {
                    double d = Math.abs(grid[i][j].height - grid[i+1][j].height);
                    if (d > maxDiff) { maxDiff = d; maxI = i; maxJ = j; maxDir = 0; }
                    if (d > 3) { cliffCount++; 
                        System.out.printf("    邻接X @(%d,%d) h1=%.1f h2=%.1f diff=%.1f t1=%s t2=%s%n",
                            startX+i, startZ+j, grid[i][j].height, grid[i+1][j].height, d,
                            grid[i][j].terrainType, grid[i+1][j].terrainType);
                    }
                }
            }
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size - 1; j++) {
                    double d = Math.abs(grid[i][j].height - grid[i][j+1].height);
                    if (d > maxDiff) { maxDiff = d; maxI = i; maxJ = j; maxDir = 1; }
                    if (d > 3) { cliffCount++;
                        System.out.printf("    邻接Z @(%d,%d) h1=%.1f h2=%.1f diff=%.1f t1=%s t2=%s%n",
                            startX+i, startZ+j, grid[i][j].height, grid[i][j+1].height, d,
                            grid[i][j].terrainType, grid[i][j+1].terrainType);
                    }
                }
            }
            System.out.println("    总可见悬崖: " + cliffCount);
            System.out.println("    最大高度差: " + String.format("%.1f", maxDiff) + " @ (" + (startX+maxI) + "," + (startZ+maxJ) + ") dir=" + (maxDir==0?"X":"Z"));
            System.out.println();

            // C) 检查 cell boundary 处的 typeWeights 跳变
            System.out.println("  [C] typeWeights 最大跳变（检查 typeWeights 非连续过渡）:");
            double maxWtJump = 0;
            int wtJumpI = 0, wtJumpJ = 0, wtJumpDir = 0;
            String wtJumpType = "";
            for (int i = 0; i < size - 1; i++) {
                for (int j = 0; j < size; j++) {
                    double[] tw1 = grid[i][j].typeWeights;
                    double[] tw2 = grid[i+1][j].typeWeights;
                    if (tw1 == null || tw2 == null) continue;
                    for (int t = 0; t < Math.min(tw1.length, TerrainClass.COUNT); t++) {
                        double d = Math.abs(tw1[t] - tw2[t]);
                        if (d > maxWtJump) {
                            maxWtJump = d;
                            wtJumpI = i; wtJumpJ = j; wtJumpDir = 0;
                            wtJumpType = TerrainClass.values()[t].toString();
                        }
                    }
                }
            }
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size - 1; j++) {
                    double[] tw1 = grid[i][j].typeWeights;
                    double[] tw2 = grid[i][j+1].typeWeights;
                    if (tw1 == null || tw2 == null) continue;
                    for (int t = 0; t < Math.min(tw1.length, TerrainClass.COUNT); t++) {
                        double d = Math.abs(tw1[t] - tw2[t]);
                        if (d > maxWtJump) {
                            maxWtJump = d;
                            wtJumpI = i; wtJumpJ = j; wtJumpDir = 1;
                            wtJumpType = TerrainClass.values()[t].toString();
                        }
                    }
                }
            }

            System.out.println("    最大typeWeight跳变: " + wtJumpType + " diff=" + String.format("%.4f", maxWtJump)
                + " @ (" + (startX+wtJumpI) + "," + (startZ+wtJumpJ) + ") dir=" + (wtJumpDir==0?"X":"Z"));
            
            if (maxWtJump > 0.3) {
                System.out.println("    *** 大于 0.3，可能造成 eLand 突变！");
                int ci2 = wtJumpI, cj2 = wtJumpJ;
                Cell ca = grid[ci2][cj2];
                Cell cb = wtJumpDir == 0 ? grid[ci2+1][cj2] : grid[ci2][cj2+1];
                System.out.println("    Cell A: eLand=" + String.format("%.4f", ca.eLand) + " e=" + String.format("%.4f", ca.e));
                System.out.println("    Cell B: eLand=" + String.format("%.4f", cb.eLand) + " e=" + String.format("%.4f", cb.e));
            }

            System.out.println();
        }
    }
}
