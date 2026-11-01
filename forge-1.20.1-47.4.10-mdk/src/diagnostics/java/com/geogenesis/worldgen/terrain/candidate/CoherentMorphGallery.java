package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** S3同布局双臂真实Java高度导出。S2原始赢家逐像素冻结对拍。 */
public final class CoherentMorphGallery {
    private static final int N = RegionalMorphGallery.SIZE;
    private static final double X0 = RegionalMorphGallery.X0, Z0 = RegionalMorphGallery.Z0;
    private static final double STEP = RegionalMorphGallery.STEP, END = (N - 1) * STEP;
    private final CoherentMorphWorld world;

    public CoherentMorphGallery() { this(false); }

    private CoherentMorphGallery(boolean context) {
        world = context ? new CoherentMorphWorld(RegionalMorphGallery.SEED) : new CoherentMorphWorld();
    }

    private final HeightCurve curve = new HeightCurve(TerrainParams.defaults(), -64, 320);

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path frozen = Path.of(args[0]), out = Path.of(args[1]); Files.createDirectories(out);
        if (args.length > 2 && !args[2].equals("context")) throw new IllegalArgumentException("expected context");
        var gallery = new CoherentMorphGallery(args.length > 2);
        gallery.export(frozen, out); gallery.profiles(out);
        Files.writeString(out.resolve("final-stats.txt"), gallery.world.stats());
        System.out.println("COHERENT MORPH GALLERY PASS");
    }

    private void export(Path frozen, Path out) throws Exception {
        byte[] winner = Files.readAllBytes(frozen.resolve("after.u8"));
        ByteBuffer cells = ByteBuffer.wrap(Files.readAllBytes(frozen.resolve("cells.i64be")));
        if (winner.length != N * N || cells.remaining() != N * N * 8) throw new AssertionError("frozen size");
        long start = System.nanoTime();
        try (Arm a = new Arm(out.resolve("arm-before")); Arm b = new Arm(out.resolve("arm-after"))) {
            for (int j = 0; j < N; j++) {
                double z = Z0 + j * STEP;
                for (int i = 0; i < N; i++) {
                    double x = X0 + i * STEP;
                    var tile = world.tileAt(x, z);
                    int win = tile.original().winnerIndex(x, z);
                    if (tile.original().geometry().sites().get(win).key() != cells.getLong())
                        throw new AssertionError("cell geometry drift " + i + "," + j);
                    var pair = world.sample(tile, x, z);
                    if (pair.winner() != (winner[j * N + i] & 255)) throw new AssertionError("S2 label drift");
                    a.write(curve.heightFromE(pair.beforeE()), pair.before());
                    b.write(curve.heightFromE(pair.afterE()), pair.after());
                }
                if (j % 100 == 0) System.out.println("S3 row=" + j);
            }
        }
        Files.write(out.resolve("winner.u8"), winner);
        Files.writeString(out.resolve("sampling-stats.txt"), world.stats() + "exportNanos=" + (System.nanoTime() - start) + "\n");
    }

    private void profiles(Path out) throws Exception {
        List<String> csv = new ArrayList<>(List.of("line,x,z,a,b,distance"));
        for (int k = 0; k < 6; k++) {
            int row = (int) ((k + .5) / 6 * N);
            for (int i = 0; i <= (int) END; i++) point(csv, "full-" + row, X0 + i, Z0 + row * STEP, i);
        }
        for (int z : new int[]{-2200, -1800, -1300})
            for (int x = -3500; x <= -800; x++) point(csv, "local-" + z, x, z, x + 3500);
        Files.write(out.resolve("profiles.csv"), csv);
    }

    private void point(List<String> csv, String line, double x, double z, double distance) {
        var pair = world.sample(x, z);
        double a = curve.heightFromE(pair.beforeE()), b = curve.heightFromE(pair.afterE());
        if (!Double.isFinite(a) || !Double.isFinite(b)) throw new AssertionError("profile nonfinite");
        csv.add(line + "," + x + "," + z + "," + a + "," + b + "," + distance);
    }

    private static final class Arm implements AutoCloseable {
        private final DataOutputStream height, type, active, sea;
        Arm(Path out) throws Exception {
            Files.createDirectories(out);
            height = stream(out, "height.f64be"); type = stream(out, "type.u8");
            active = stream(out, "active.u8"); sea = stream(out, "sea-weight.f64be");
            Files.writeString(out.resolve("meta.json"), "{\"seed\":\"" + RegionalMorphGallery.SEED
                    + "\",\"size\":1401,\"step\":5,\"origin\":[-3642.5,-3365.5],\"center\":[-140,137],"
                    + "\"scale\":600,\"macroScale\":1200,\"seaLevel\":63,\"bandTotal\":240,\"coastProcessing\":false,"
                    + "\"jvmProperties\":[\"file.encoding=UTF-8\"],\"layout\":\"frozen S2\"}");
        }
        void write(double y, double[] weights) throws Exception {
            if (!Double.isFinite(y)) throw new AssertionError("nonfinite height");
            double sum = 0, water = 0;
            for (int k = 0; k < weights.length; k++) {
                if (!Double.isFinite(weights[k]) || weights[k] < 0) throw new AssertionError("illegal weight");
                sum += weights[k]; if (k >= RegionalMorphWorld.LAND_COUNT) water += weights[k];
            }
            if (Math.abs(sum - 1) > 1e-12) throw new AssertionError("weight sum");
            height.writeDouble(y); type.writeByte(FullMorphLand.dominant(weights));
            active.writeByte(FullMorphLand.activeCount(weights)); sea.writeDouble(water);
        }
        @Override public void close() throws Exception { height.close(); type.close(); active.close(); sea.close(); }
        private static DataOutputStream stream(Path out, String name) throws Exception {
            return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name)), 1 << 16));
        }
    }
}
