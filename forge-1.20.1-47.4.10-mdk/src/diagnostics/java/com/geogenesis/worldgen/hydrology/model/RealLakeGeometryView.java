package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Real-bed potential storage diagnostic. Explicit provisional coastal seeds, not an actual lake simulation. */
public final class RealLakeGeometryView {
    public static void main(String[] args) throws Exception {
        long seed = 9139912035078620160L;
        int radius = 700, step = 2, n = 2 * radius / step + 1;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY()); gen.seed(seed);
        double[] bed = new double[n * n];
        boolean[] coastal = new boolean[bed.length];
        for (int x = 0; x < n; x++) {
            if (x % 100 == 0) System.out.printf("sample %d/%d%n", x, n);
            for (int z = 0; z < n; z++) bed[x * n + z] = gen.erodedHeightForRouting(
                    (-140 - radius + x * step) / p.horizontalScale(),
                    (137 - radius + z * step) / p.horizontalScale());
        }
        WaterGrid g = WaterGrid.of(n, n, step, bed, WaterGrid.Boundary.CLOSED, gen.seaLevel());
        // Connected-to-frame low terrain is provisional, not a certified global ocean classification.
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int k = 0; k < bed.length; k++) if (g.isBorder(g.ix(k), g.iz(k)) && bed[k] < gen.seaLevel()) {
            coastal[k] = true; queue.add(k);
        }
        while (!queue.isEmpty()) {
            int k = queue.remove();
            for (int[] d : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                int x = g.ix(k) + d[0], z = g.iz(k) + d[1];
                if (!g.inside(x, z)) continue;
                int next = g.idx(x, z);
                if (!coastal[next] && bed[next] < gen.seaLevel()) { coastal[next] = true; queue.add(next); }
            }
        }
        LakeBasinGeometry geo = new LakeBasinGeometry(g, coastal, gen.seaLevel());
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int resolved = 0, pending = 0;
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
            int k = g.idx(x, z), color = shade(g, x, z);
            if (coastal[k]) color = 0x1F4E79;
            else if (geo.potentialDepth(k) > 0) {
                if (geo.certified(k)) { color = 0x4A90E2; resolved++; }
                else { color = 0xB47C40; pending++; }
            }
            image.setRGB(x, z, color);
        }
        Path dir = Path.of("build/hydro-reassessment/lake-geometry-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(image, "png", dir.resolve("top.png").toFile());
        exportBed(g, dir);
        markOutlet(g, geo, coastal, image, dir);
        String metrics = "REAL BED; 2 blocks/pixel; potential storage, NOT actual water.\n"
                + "Blue=locally certified relative to provisional coastal terminals; orange=unresolved.\n"
                + "seed=" + seed + " hs=" + p.horizontalScale() + " sea=" + gen.seaLevel()
                + " certifiedCells=" + resolved + " pendingCells=" + pending + "\n";
        Files.writeString(dir.resolve("metrics.txt"), metrics);
        System.out.print(metrics); System.out.println("IMAGE=" + dir.resolve("top.png").toAbsolutePath());
    }

    private static void exportBed(WaterGrid g, Path dir) throws Exception {
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(dir.resolve("bed.bin")))) {
            out.writeInt(g.nx); out.writeInt(g.nz); out.writeDouble(g.cellSize);
            for (double h : g.bed) out.writeDouble(h);
        }
    }

    private static void markOutlet(WaterGrid g, LakeBasinGeometry geo, boolean[] coastal, BufferedImage image, Path dir) throws Exception {
        int seed = -1;
        for (int k = 0; k < g.cells(); k++) if (!coastal[k] && geo.certified(k) && geo.potentialDepth(k) > 0
                && (seed < 0 || geo.potentialDepth(k) > geo.potentialDepth(seed))) seed = k;
        if (seed < 0) throw new IllegalStateException("no resolved storage candidate for outlet section");
        java.util.ArrayList<Integer> path = new java.util.ArrayList<>();
        int k = seed, saddle = seed;
        while (k >= 0) {
            if (path.size() >= g.cells()) throw new IllegalStateException("cyclic outlet path");
            path.add(k);
            if (g.bed[k] > g.bed[saddle]) saddle = k;
            k = geo.downstream(k);
        }
        if (path.size() < 2 || !coastal[path.get(path.size() - 1)]) {
            throw new IllegalStateException("empty or unterminated outlet section");
        }
        var gr = image.createGraphics(); gr.setColor(java.awt.Color.YELLOW);
        gr.drawOval(g.ix(saddle) - 5, g.iz(saddle) - 5, 10, 10); gr.dispose();
        ImageIO.write(image, "png", dir.resolve("outlet_top.png").toFile());
        BufferedImage section = new BufferedImage(Math.max(600, path.size()), 300, BufferedImage.TYPE_INT_RGB);
        var s = section.createGraphics(); s.setColor(java.awt.Color.DARK_GRAY); s.fillRect(0, 0, section.getWidth(), 300);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int cell : path) { min = Math.min(min, g.bed[cell]); max = Math.max(max, g.bed[cell]); }
        double range = Math.max(1, max - min);
        for (int t = 1; t < path.size(); t++) {
            int x0 = (t - 1) * (section.getWidth() - 1) / (path.size() - 1), x1 = t * (section.getWidth() - 1) / (path.size() - 1);
            s.setColor(java.awt.Color.LIGHT_GRAY);
            s.drawLine(x0, 270 - (int) ((g.bed[path.get(t - 1)] - min) / range * 230),
                    x1, 270 - (int) ((g.bed[path.get(t)] - min) / range * 230));
        }
        s.setColor(java.awt.Color.CYAN);
        int y = 270 - (int) ((geo.escapeLevel(seed) - min) / range * 230);
        s.drawLine(0, y, section.getWidth() - 1, y);
        s.setColor(java.awt.Color.WHITE); s.drawString("BED PATH TO COAST / CYAN=ESCAPE ELEVATION, NOT ACTUAL WATER", 5, 20);
        s.dispose(); ImageIO.write(section, "png", dir.resolve("outlet_section.png").toFile());
        Files.writeString(dir.resolve("outlet.txt"), "candidate=" + seed + " saddleBlock=("
                + (-840 + g.ix(saddle) * 2) + "," + (-563 + g.iz(saddle) * 2) + ") escape="
                + geo.escapeLevel(seed) + " pathCells=" + path.size() + "\n");
    }

    private static int shade(WaterGrid g, int x, int z) {
        int x0 = Math.max(0, x - 1), x1 = Math.min(g.nx - 1, x + 1);
        int z0 = Math.max(0, z - 1), z1 = Math.min(g.nz - 1, z + 1);
        double dx = (g.bed[g.idx(x1, z)] - g.bed[g.idx(x0, z)]) / ((x1 - x0) * g.cellSize);
        double dz = (g.bed[g.idx(x, z1)] - g.bed[g.idx(x, z0)]) / ((z1 - z0) * g.cellSize);
        double light = (0.5 * dx + 0.7 - 0.51 * dz) / Math.sqrt((dx * dx + 1 + dz * dz) * (0.25 + 0.49 + 0.2601));
        int c = (int) (60 + 175 * Math.max(0, light));
        return (c << 16) | (c << 8) | c;
    }
}
