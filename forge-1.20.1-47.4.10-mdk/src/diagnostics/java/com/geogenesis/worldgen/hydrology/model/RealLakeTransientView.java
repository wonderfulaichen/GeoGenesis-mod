package com.geogenesis.worldgen.hydrology.model;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Prescribed initial-water experiment on frozen real bed; closed crop is not a world boundary. */
public final class RealLakeTransientView {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("frozen bed directory");
        WaterGrid full;
        try (var in = new java.io.DataInputStream(Files.newInputStream(Path.of(args[0], "bed.bin")))) {
            int nx = in.readInt(), nz = in.readInt(); double size = in.readDouble();
            double[] bed = new double[nx * nz];
            for (int k = 0; k < bed.length; k++) bed[k] = in.readDouble();
            full = WaterGrid.of(nx, nz, size, bed, WaterGrid.Boundary.CLOSED, 0);
        }
        int portX = 181, portZ = 149;
        double crest = full.bed[full.idx(portX, portZ)];
        BasinStorage selected = null;
        for (int[] d : new int[][]{{1,0}, {-1,0}, {0,1}, {0,-1}}) {
            int seed = full.idx(portX + d[0], portZ + d[1]);
            if (full.bed[seed] >= crest) continue;
            BasinStorage s = new BasinStorage(full, seed, crest);
            if (s.complete() && (selected == null || s.members().size() > selected.members().size())) selected = s;
        }
        if (selected == null) throw new IllegalStateException("no storage beside observed saddle");
        BasinStorage receiving = null;
        java.util.HashSet<Integer> selectedCells = new java.util.HashSet<>(selected.members());
        for (int[] d : new int[][]{{1,0}, {-1,0}, {0,1}, {0,-1}}) {
            int seed = full.idx(portX + d[0], portZ + d[1]);
            if (full.bed[seed] < crest && !selectedCells.contains(seed)) receiving = new BasinStorage(full, seed, crest);
        }
        if (receiving == null) throw new IllegalStateException("no receiving component beside saddle");
        int minX = portX, maxX = portX, minZ = portZ, maxZ = portZ;
        for (int k : selected.members()) {
            minX = Math.min(minX, full.ix(k)); maxX = Math.max(maxX, full.ix(k));
            minZ = Math.min(minZ, full.iz(k)); maxZ = Math.max(maxZ, full.iz(k));
        }
        for (int k : receiving.members()) {
            minX = Math.min(minX, full.ix(k)); maxX = Math.max(maxX, full.ix(k));
            minZ = Math.min(minZ, full.iz(k)); maxZ = Math.max(maxZ, full.iz(k));
        }
        minX = Math.max(0, minX - 20); minZ = Math.max(0, minZ - 20);
        maxX = Math.min(full.nx - 1, maxX + 20); maxZ = Math.min(full.nz - 1, maxZ + 20);
        int nx = maxX - minX + 1, nz = maxZ - minZ + 1;
        double[] crop = new double[nx * nz];
        for (int x = 0; x < nx; x++) for (int z = 0; z < nz; z++) crop[x * nz + z] = full.bed[full.idx(x + minX, z + minZ)];
        WaterGrid g = WaterGrid.of(nx, nz, full.cellSize, crop, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel model = new DiffusiveWaveModel(g, ModelParams.still().withCellSize(g.cellSize).withDtMax(0.05));
        for (int k : selected.members()) model.fillTo(full.ix(k) - minX, full.iz(k) - minZ, crest + 0.2);
        double volume = model.storage();
        Path dir = Path.of("build/hydro-reassessment/real-transient-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        BufferedImage strip = new BufferedImage(nx * 3 * 3, nz * 3 + 40, BufferedImage.TYPE_INT_RGB);
        StringBuilder log = new StringBuilder("prescribed initial crest+0.2; CLOSED crop; no rain/loss; NOT world solution\n");
        int elapsed = 0;
        for (int panel = 0; panel < 3; panel++) {
            int target = new int[]{0, 100, 1000}[panel];
            while (elapsed < target) { model.step(); elapsed++; }
            double residual = model.storage() - volume;
            if (Math.abs(residual) > 1e-7 * Math.max(1, volume)) throw new AssertionError("volume ledger");
            render(strip, panel, g, model.depth(), portX - minX, portZ - minZ, model.time());
            double received = storedIn(receiving, full, model, minX, minZ);
            if (elapsed > 0 && received <= 0) throw new AssertionError("no water reached receiving basin");
            log.append("step=").append(elapsed).append(" time=").append(model.time())
                    .append(" volume=").append(model.storage()).append(" residual=").append(residual)
                    .append(" receivingVolume=").append(received)
                    .append(" crestDepth=").append(model.depth(portX - minX, portZ - minZ)).append('\n');
            System.out.println("completed snapshot " + elapsed);
        }
        ImageIO.write(strip, "png", dir.resolve("stages.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log); System.out.println("IMAGE=" + dir.resolve("stages.png").toAbsolutePath());
    }

    private static double storedIn(BasinStorage s, WaterGrid full, DiffusiveWaveModel model, int ox, int oz) {
        double volume = 0;
        for (int k : s.members()) volume += model.depth(full.ix(k) - ox, full.iz(k) - oz) * full.cellArea();
        return volume;
    }

    private static void render(BufferedImage out, int panel, WaterGrid g, double[] depth, int px, int pz, double time) {
        var graphics = out.createGraphics();
        int offset = panel * g.nx * 3;
        graphics.setColor(Color.BLACK); graphics.fillRect(offset, 0, g.nx * 3, out.getHeight());
        for (int x = 0; x < g.nx; x++) for (int z = 0; z < g.nz; z++) {
            int x0 = Math.max(0,x-1), x1 = Math.min(g.nx-1,x+1), z0 = Math.max(0,z-1), z1 = Math.min(g.nz-1,z+1);
            double dx = (g.bed[g.idx(x1,z)]-g.bed[g.idx(x0,z)])/((x1-x0)*g.cellSize);
            double dz = (g.bed[g.idx(x,z1)]-g.bed[g.idx(x,z0)])/((z1-z0)*g.cellSize);
            int gray = (int)(60+175*Math.max(0,(0.5*dx+0.7-0.51*dz)/Math.sqrt((dx*dx+1+dz*dz)*1.0001)));
            graphics.setColor(depth[g.idx(x,z)] > 0.001 ? new Color(0x299DDF) : new Color(gray,gray,gray));
            graphics.fillRect(offset+x*3,40+z*3,3,3);
        }
        graphics.setColor(Color.YELLOW); graphics.drawOval(offset+px*3-7,40+pz*3-7,14,14);
        graphics.setColor(Color.WHITE); graphics.drawString(String.format("t=%.2f; yellow=(-478,-265)",time),offset+5,20);
        graphics.dispose();
    }
}
