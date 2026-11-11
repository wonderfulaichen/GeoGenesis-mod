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

/** M1a无构造A臂：0.30真实S3完整高度，仅Java生成，不覆盖旧S3。 */
public final class M1ThresholdHeightGallery {
    private static final int N = RegionalMorphGallery.SIZE;
    private static final double X0 = RegionalMorphGallery.X0, Z0 = RegionalMorphGallery.Z0;
    private final CoherentMorphWorld world = new CoherentMorphWorld(
            new LandRegionContext(RegionalMorphGallery.SEED, 600),
            new CoherentRegionDistribution(RegionalMorphGallery.SEED, .30));
    private final HeightCurve curve = new HeightCurve(TerrainParams.defaults(), -64, 320);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("frozenThresholdLocal newOutput");
        Path out = Path.of(args[1]); Files.createDirectory(out);
        var gallery = new M1ThresholdHeightGallery();
        gallery.export(Path.of(args[0]), out);
        gallery.profiles(out);
        Files.writeString(out.resolve("sampling.txt"), gallery.world.stats());
        System.out.println("M1 THRESHOLD HEIGHT GALLERY PASS");
    }

    private void export(Path frozen, Path out) throws Exception {
        byte[] base = Files.readAllBytes(frozen.resolve("type.u8"));
        byte[] side = Files.readAllBytes(frozen.resolve("side.u8"));
        ByteBuffer cells = ByteBuffer.wrap(Files.readAllBytes(frozen.resolve("cells.i64be")));
        if (base.length != N * N || side.length != base.length || cells.remaining() != N * N * 8)
            throw new AssertionError("frozen dimensions");
        long water = 0, changedSeaRecipes = 0;
        try (var height = stream(out, "height.f64be"); var e = stream(out, "e.f64be");
             var type = stream(out, "type.u8"); var active = stream(out, "active.u8");
             var sea = stream(out, "sea-weight.f64be"); var winner = stream(out, "winner.u8")) {
            for (int j = 0; j < N; j++) {
                double z = Z0 + j * 5;
                for (int i = 0; i < N; i++) {
                    double x = X0 + i * 5; int k = j * N + i;
                    var tile = world.tileAt(x, z); int win = tile.original().winnerIndex(x, z);
                    if (tile.original().geometry().sites().get(win).key() != cells.getLong())
                        throw new AssertionError("frozen geometry drift " + k);
                    var p = world.sample(tile, x, z);
                    if ((p.winner() >= RegionalMorphWorld.LAND_COUNT ? 1 : 0) != side[k])
                        throw new AssertionError("S2 side drift " + k);
                    if (side[k] == 0 && p.winner() != (base[k] & 255))
                        throw new AssertionError("S2 land drift " + k);
                    if (side[k] != 0 && p.winner() != (base[k] & 255)) changedSeaRecipes++;
                    double y = curve.heightFromE(p.afterE()), sum = 0, sw = 0;
                    if (!Double.isFinite(y)) throw new AssertionError("nonfinite height");
                    for (int a = 0; a < p.after().length; a++) {
                        double v = p.after()[a];
                        if (!Double.isFinite(v) || v < 0) throw new AssertionError("invalid weight");
                        sum += v; if (a >= RegionalMorphWorld.LAND_COUNT) sw += v;
                    }
                    if (Math.abs(sum - 1) > 1e-12) throw new AssertionError("weight sum");
                    e.writeDouble(p.afterE()); height.writeDouble(y); sea.writeDouble(sw);
                    type.writeByte(FullMorphLand.dominant(p.after()));
                    active.writeByte(FullMorphLand.activeCount(p.after())); winner.writeByte(p.winner());
                    if (y < 63) water++;
                }
                if (j % 100 == 0) System.out.println("M1a row=" + j);
            }
        }
        Files.write(out.resolve("side.u8"), side);
        Files.copy(frozen.resolve("cells.i64be"), out.resolve("cells.i64be"));
        Files.writeString(out.resolve("counts.txt"), "samples=" + N * N + "\nyBelow63=" + water
                + "\nS2SeaRecipeChanges=" + changedSeaRecipes + "\n");
        Files.writeString(out.resolve("meta.json"), "{\"seed\":\"" + RegionalMorphGallery.SEED
                + "\",\"size\":1401,\"step\":5,\"origin\":[-3642.5,-3365.5],\"center\":[-140,137],"
                + "\"scale\":600,\"macroScale\":1200,\"seaLevel\":63,\"bandTotal\":240,"
                + "\"landThreshold\":0.30,\"tectonics\":false,\"coastProcessing\":false,"
                + "\"jvmProperties\":[\"file.encoding=UTF-8\"],\"layout\":\"explicit threshold S2/S3\"}");
    }

    private void profiles(Path out) throws Exception {
        List<String> rows = new ArrayList<>(List.of("line,x,z,e,y,distance"));
        for (int k = 0; k < 6; k++) {
            int row = (int) ((k + .5) / 6 * N);
            for (int i = 0; i <= 7000; i++) point(rows, "full-" + row, X0 + i, Z0 + row * 5, i);
        }
        for (int z : new int[]{-2200, -1800, -1300})
            for (int x = -3500; x <= -800; x++) point(rows, "local-" + z, x, z, x + 3500);
        if (rows.size() != 50110) throw new AssertionError("profile count");
        Files.write(out.resolve("profiles.csv"), rows);
    }

    private void point(List<String> rows, String line, double x, double z, double distance) {
        var p = world.sample(x, z); double y = curve.heightFromE(p.afterE());
        if (!Double.isFinite(y)) throw new AssertionError("nonfinite profile");
        rows.add(line + "," + x + "," + z + "," + p.afterE() + "," + y + "," + distance);
    }

    private static DataOutputStream stream(Path out, String file) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(file)), 1 << 16));
    }
}
