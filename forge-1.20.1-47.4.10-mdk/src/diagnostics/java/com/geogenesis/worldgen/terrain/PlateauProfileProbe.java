package com.geogenesis.worldgen.terrain;

/**
 * 高原形态诊断：定位一个 PLATEAU 主导细胞，沿 2D 网格打印 eLand 热力 + 地形类型字母，
 * 确认"中间低、四周高"的环形山伪形机制（还是正常的桌山地貌）。
 *
 * 运行：gradlew runPlateauProbe
 */
public final class PlateauProfileProbe {

    public static void main(String[] args) {
        TerrainParams p = TerrainParams.defaults();
        long seed = 12345L;
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        // 1. 扫描找一个 PLATEAU 主导细胞（dominantFromWeights == PLATEAU）
        int bestX = 0, bestZ = 0;
        boolean found = false;
        outer:
        for (int x = 0; x < 4000; x += 8) {
            for (int z = 0; z < 4000; z += 8) {
                Cell c = gen.sample(x, z);
                if (c.typeWeights == null) continue;
                TerrainClass dom = TypeLandShape.dominantFromWeights(c.typeWeights);
                if (dom == TerrainClass.PLATEAU) {
                    bestX = x; bestZ = z; found = true;
                    break outer;
                }
            }
        }
        if (!found) {
            System.out.println("未找到 PLATEAU 主导细胞，seed=" + seed);
            return;
        }
        System.out.println("=== PlateauProfileProbe ===");
        System.out.println("Seed=" + seed + "  PLATEAU 主导细胞中心≈(" + bestX + "," + bestZ + ")");
        System.out.println();

        // 2. 在中心打印类型权重 + 计算 typePosition
        Cell cc = gen.sample(bestX, bestZ);
        printWeights("中心", cc);

        // 3. 2D 网格热力图（step=8 块，31×31）
        final int R = 15;          // 半径（格）
        final int STEP = 8;        // 每格 8 块
        System.out.println();
        System.out.println("eLand 热力网格（数值=eLand×100，字母=地形类型；T=PLATEAU M=MOUNTAINS ^=PEAK S=SNOW H=HILLS P=PLAIN B=BASIN）");
        System.out.println("中心位于网格正中央 (" + R + "," + R + ")");
        for (int dz = -R; dz <= R; dz++) {
            StringBuilder line = new StringBuilder();
            for (int dx = -R; dx <= R; dx++) {
                int wx = bestX + dx * STEP;
                int wz = bestZ + dz * STEP;
                Cell c = gen.sample(wx, wz);
                int ev = (int) Math.round(c.eLand * 100);
                char t = typeLetter(c.terrainType);
                line.append(String.format("%3d%c", ev, t));
            }
            System.out.println(line);
        }

        // 4. 四条径向剖面（eLand）
        System.out.println();
        System.out.println("径向剖面 eLand（中心→外，每 40 块一步）：");
        for (String dir : new String[]{"+X", "-X", "+Z", "-Z"}) {
            StringBuilder sb = new StringBuilder(dir + ": ");
            int bx = 0, bz = 0;
            if (dir.equals("+X")) bx = 1; else if (dir.equals("-X")) bx = -1;
            else if (dir.equals("+Z")) bz = 1; else bz = -1;
            for (int s = 0; s <= 12; s++) {
                int wx = bestX + bx * s * 40;
                int wz = bestZ + bz * s * 40;
                Cell c = gen.sample(wx, wz);
                sb.append(String.format("%3d ", (int) Math.round(c.eLand * 100)));
            }
            System.out.println(sb);
        }
    }

    private static void printWeights(String tag, Cell c) {
        double[] tw = c.typeWeights;
        System.out.println(tag + " typeWeights: "
            + "PLAIN=" + f(tw[TerrainClass.PLAIN.ordinal()])
            + " HILLS=" + f(tw[TerrainClass.HILLS.ordinal()])
            + " MOUNTAINS=" + f(tw[TerrainClass.MOUNTAINS.ordinal()])
            + " PLATEAU=" + f(tw[TerrainClass.PLATEAU.ordinal()])
            + " BASIN=" + f(tw[TerrainClass.BASIN.ordinal()]));
        // 计算 typePosition（与 TypeLandShape.sampleFromUnifiedSpline 同公式）
        double pos = 0, twt = 0;
        TerrainClass[] lands = TypeNoiseProvider.LAND_TYPES;
        for (int i = 0; i < lands.length; i++) {
            double w = tw[lands[i].ordinal()];
            if (w > 0.001) { pos += w * ((double) i / (lands.length - 1)); twt += w; }
        }
        if (twt > 0) pos /= twt;
        System.out.println(tag + " typePosition=" + f(pos) + "  eLand=" + f(c.eLand)
            + "  height(Y)=" + String.format("%.1f", c.height) + "  terrainType=" + c.terrainType);
    }

    private static char typeLetter(TerrainClass t) {
        if (t == null) return '?';
        return switch (t) {
            case PLAIN -> 'P';
            case HILLS -> 'H';
            case PLATEAU -> 'T';
            case MOUNTAINS -> 'M';
            case PEAK -> '^';
            case SNOW -> 'S';
            case BASIN -> 'B';
            case BEACH -> 'b';
            default -> '.';
        };
    }

    private static String f(double v) { return String.format("%.3f", v); }
}
