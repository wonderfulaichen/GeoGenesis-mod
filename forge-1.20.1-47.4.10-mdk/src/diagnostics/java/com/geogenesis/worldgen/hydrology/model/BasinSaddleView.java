package com.geogenesis.worldgen.hydrology.model;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Reuses a frozen real bed. Marks geometric saddles, not water or an approved reference image. */
public final class BasinSaddleView {
    public static void main(String[] args) throws Exception {
        verifyNested();
        verifyStorage();
        if (args.length != 1) throw new IllegalArgumentException("frozen bed directory required");
        Path input = Path.of(args[0]);
        WaterGrid grid;
        try (var in = new java.io.DataInputStream(Files.newInputStream(input.resolve("bed.bin")))) {
            int nx = in.readInt(), nz = in.readInt(); double step = in.readDouble();
            double[] bed = new double[nx * nz];
            for (int k = 0; k < bed.length; k++) bed[k] = in.readDouble();
            grid = WaterGrid.of(nx, nz, step, bed, WaterGrid.Boundary.CLOSED, 0);
        }
        BasinMergeTree tree = new BasinMergeTree(grid);
        BufferedImage image = ImageIO.read(input.resolve("top.png").toFile());
        var graphics = image.createGraphics();
        StringBuilder csv = new StringBuilder("id,xBlock,zBlock,height,childMinima,unknownBoundary\n");
        int id = 0, marked = 0;
        for (var saddle : tree.saddles()) {
            id++;
            int k = saddle.plateau().get(0);
            csv.append(id).append(',').append(-840 + grid.ix(k) * grid.cellSize).append(',')
                    .append(-563 + grid.iz(k) * grid.cellSize).append(',').append(saddle.level()).append(',')
                    .append('"').append(saddle.childMinima()).append('"').append(',')
                    .append(saddle.touchesUnknownBoundary()).append('\n');
            if (saddle.touchesUnknownBoundary()) continue;
            graphics.setColor(Color.YELLOW);
            for (int cell : saddle.plateau()) image.setRGB(grid.ix(cell), grid.iz(cell), Color.YELLOW.getRGB());
            graphics.drawOval(grid.ix(k) - 3, grid.iz(k) - 3, 6, 6);
            marked++;
        }
        graphics.dispose();
        Path dir = Path.of("build/hydro-reassessment/saddles-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(image, "png", dir.resolve("saddles_top.png").toFile());
        Files.writeString(dir.resolve("saddles.csv"), csv);
        renderStorage(grid, tree, input, dir);
        System.out.printf("saddleEvents=%d interiorMarked=%d; yellow=terrain merge saddle, NOT actual outflow%n", id, marked);
        System.out.println("IMAGE=" + dir.resolve("saddles_top.png").toAbsolutePath());
    }

    private static void verifyStorage() {
        double[] bed = new double[25]; java.util.Arrays.fill(bed, 10);
        bed[12] = 0; bed[13] = 1;
        WaterGrid g = WaterGrid.of(5, 5, 2, bed, WaterGrid.Boundary.CLOSED, 0);
        BasinStorage s = new BasinStorage(g, 12, 3);
        if (!s.complete() || s.volumeAt(3) != 20 || s.levelForVolume(12) != 2) {
            throw new AssertionError("piecewise exact connected storage curve");
        }
        boolean rejected = false;
        try { s.levelForVolume(21); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("overflow cannot silently vanish");
        bed[10] = 0; bed[11] = 0;
        if (new BasinStorage(g, 12, 3).complete()) throw new AssertionError("truncated storage must be pending");
        double[] nested = new double[35]; java.util.Arrays.fill(nested, 10);
        nested[11] = 0; nested[16] = 3; nested[21] = 0;
        BasinStorage joined = new BasinStorage(WaterGrid.of(7, 5, 1, nested, WaterGrid.Boundary.CLOSED, 0), 11, 7);
        boolean separate = false;
        try { joined.levelForVolume(2); } catch (IllegalStateException expected) { separate = true; }
        if (!separate) throw new AssertionError("sub-saddle disconnected lakes cannot share a water level");
        System.out.println("[PASS] storage curve, overflow/truncation rejection and disconnected sub-basin guard");
    }

    private static void renderStorage(WaterGrid g, BasinMergeTree tree, Path input, Path dir) throws Exception {
        BasinStorage best = null; BasinMergeTree.Saddle selected = null;
        // Select a visible interior candidate for inspection only; do not drop smaller basins from the model.
        for (var saddle : tree.saddles()) {
            if (saddle.touchesUnknownBoundary()) continue;
            for (int minimum : saddle.childMinima()) {
                if (g.bed[minimum] >= saddle.level()) continue;
                BasinStorage s = new BasinStorage(g, minimum, saddle.level());
                if (!s.complete()) continue;
                try { s.levelForVolume(0.5 * s.volumeAt(s.crest())); }
                catch (IllegalStateException disconnected) { continue; }
                if (best == null || s.members().size() > best.members().size()) {
                    best = s; selected = saddle;
                }
            }
        }
        if (best == null) throw new IllegalStateException("no complete storage component");
        double capacity = best.volumeAt(best.crest());
        java.util.HashSet<Integer> memberSet = new java.util.HashSet<>(best.members());
        java.util.TreeSet<Integer> ports = new java.util.TreeSet<>();
        for (var face : best.lowestBoundaryFaces()) {
            if (face.height() != best.crest()) throw new AssertionError("merge event differs from actual lowest boundary");
            ports.add(face.outside());
        }
        if (ports.isEmpty()) throw new AssertionError("no lowest boundary faces");
        BasinStorage receiving = null;
        for (int minimum : selected.childMinima()) if (!memberSet.contains(minimum) && g.bed[minimum] < best.crest()) {
            receiving = new BasinStorage(g, minimum, best.crest()); break;
        }
        if (receiving == null) throw new AssertionError("saddle must connect another lower component");
        renderConnection(g, best, receiving, ports, dir);
        StringBuilder portCsv = new StringBuilder("xBlock,zBlock,crest\n");
        for (int k : ports) portCsv.append(-840 + g.ix(k) * g.cellSize).append(',')
                .append(-563 + g.iz(k) * g.cellSize).append(',').append(best.crest()).append('\n');
        Files.writeString(dir.resolve("selected_ports.csv"), portCsv);
        BufferedImage base = terrainImage(g);
        BufferedImage out = new BufferedImage(g.nx * 3, g.nz + 30, BufferedImage.TYPE_INT_RGB);
        var graphics = out.createGraphics();
        for (int panel = 0; panel < 3; panel++) {
            graphics.drawImage(base, panel * g.nx, 30, null);
            double volume = capacity * new double[]{0, 0.5, 1}[panel];
            double level = best.levelForVolume(volume);
            for (int k : best.members()) {
                int color = g.bed[k] < level ? 0x299DDF : 0x798079;
                out.setRGB(panel * g.nx + g.ix(k), 30 + g.iz(k), color);
            }
            graphics.setColor(Color.YELLOW);
            for (int k : ports) {
                graphics.drawOval(panel * g.nx + g.ix(k) - 4, 30 + g.iz(k) - 4, 8, 8);
                graphics.drawLine(panel * g.nx + g.ix(k) - 5, 30 + g.iz(k), panel * g.nx + g.ix(k) + 5, 30 + g.iz(k));
            }
            graphics.setColor(Color.WHITE);
            graphics.drawString(String.format("PRESCRIBED V=%.0f; eta=%.3f", volume, level), panel * g.nx + 8, 20);
        }
        graphics.dispose(); ImageIO.write(out, "png", dir.resolve("storage_levels.png").toFile());
        Files.writeString(dir.resolve("storage.txt"), "controlled volumes, NOT generated rainfall/lake state\n"
                + "cells=" + best.members().size() + " crest=" + best.crest() + " capacity=" + capacity
                + " saddleCells=" + selected.plateau() + "\nreceivingCells=" + receiving.members().size()
                + " receivingComplete=" + receiving.complete() + "\n");
        System.out.println("STORAGE_IMAGE=" + dir.resolve("storage_levels.png").toAbsolutePath());
    }

    private static void renderConnection(WaterGrid g, BasinStorage a, BasinStorage b,
                                         java.util.Set<Integer> ports, Path dir) throws Exception {
        BufferedImage image = terrainImage(g);
        for (int k : a.members()) image.setRGB(g.ix(k), g.iz(k), 0x299DDF);
        for (int k : b.members()) image.setRGB(g.ix(k), g.iz(k), 0xCF8B50);
        var gr = image.createGraphics(); gr.setColor(Color.YELLOW);
        for (int k : ports) {
            gr.drawOval(g.ix(k) - 5, g.iz(k) - 5, 10, 10);
            gr.drawLine(g.ix(k) - 7, g.iz(k), g.ix(k) + 7, g.iz(k));
            gr.drawString(String.format("(%.0f,%.0f) Y=%.3f", -840 + g.ix(k) * g.cellSize,
                    -563 + g.iz(k) * g.cellSize, a.crest()), g.ix(k) + 10, g.iz(k) - 8);
        }
        gr.dispose(); ImageIO.write(image, "png", dir.resolve("basin_connection.png").toFile());
    }

    private static BufferedImage terrainImage(WaterGrid g) {
        BufferedImage image = new BufferedImage(g.nx, g.nz, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < g.nx; x++) for (int z = 0; z < g.nz; z++) {
            int x0 = Math.max(0, x - 1), x1 = Math.min(g.nx - 1, x + 1);
            int z0 = Math.max(0, z - 1), z1 = Math.min(g.nz - 1, z + 1);
            double dx = (g.bed[g.idx(x1, z)] - g.bed[g.idx(x0, z)]) / ((x1 - x0) * g.cellSize);
            double dz = (g.bed[g.idx(x, z1)] - g.bed[g.idx(x, z0)]) / ((z1 - z0) * g.cellSize);
            double light = (0.5 * dx + 0.7 - 0.51 * dz) / Math.sqrt((dx * dx + 1 + dz * dz) * 1.0001);
            int gray = (int) (60 + 175 * Math.max(0, light));
            image.setRGB(x, z, (gray << 16) | (gray << 8) | gray);
        }
        return image;
    }

    private static void verifyNested() {
        // Two minima first join at 3; their union meets a third minimum at 7.
        WaterGrid grid = WaterGrid.of(7, 3, 1,
                new double[]{10,10,10, 10,0,10, 10,3,10, 10,1,10, 10,7,10, 10,2,10, 10,10,10},
                WaterGrid.Boundary.CLOSED, 0);
        var saddles = new BasinMergeTree(grid).saddles();
        if (saddles.size() < 2 || saddles.get(0).level() != 3 || saddles.get(1).level() != 7) {
            throw new AssertionError("nested local saddles must precede global escape height");
        }
        WaterGrid plateau = WaterGrid.of(6, 3, 1,
                new double[]{10,10,10, 10,0,10, 10,3,10, 10,3,10, 10,1,10, 10,10,10},
                WaterGrid.Boundary.CLOSED, 0);
        var first = new BasinMergeTree(plateau).saddles().get(0);
        if (first.level() != 3 || first.plateau().size() != 2 || first.childMinima().size() != 2) {
            throw new AssertionError("connected equal-height plateau must be one saddle event");
        }
        System.out.println("[PASS] nested local saddles 3 then 7; connected equal-height saddle plateau retained");
    }
}
