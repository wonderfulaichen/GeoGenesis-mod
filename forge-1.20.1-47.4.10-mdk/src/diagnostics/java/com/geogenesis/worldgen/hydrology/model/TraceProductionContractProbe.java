package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 只读真实 provider：逐位列指纹 + 同一 bed[] 重建场核验；不把候选接入落块。 */
public final class TraceProductionContractProbe {
    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        Path out = Path.of(args.length > 1 ? args[1] : "build/multiphysics-contract/production");
        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        var p = provider.pipeForProbe(ViewSpec.CENTER_X, ViewSpec.CENTER_Z);
        int[] frame = provider.windowFrameForProbe(ViewSpec.CENTER_X, ViewSpec.CENTER_Z);
        double hs = Math.max(0.01, tp.horizontalScale());
        int spacing = frame[2];
        if (frame[0] % spacing != 0 || frame[1] % spacing != 0)
            throw new AssertionError("world lattice phase mismatch");
        int bx0 = Math.floorDiv(frame[0], spacing), bz0 = Math.floorDiv(frame[1], spacing);
        var field = new TraceBedField(spacing / hs, (x, z) -> {
            int i = x - bx0, j = z - bz0;
            if (i < 0 || j < 0 || i >= p.nx() || j >= p.nz())
                throw new IllegalStateException("window is not world authority beyond known bed");
            return p.bed()[i * p.nz() + j];
        });
        int sampled = 0;
        double maxGradientError = 0, maxOvershoot = 0;
        for (int i = 3; i < p.nx() - 3; i += 19) for (int j = 3; j < p.nz() - 3; j += 19) {
            double x = (frame[0] + i * spacing) / hs, z = (frame[1] + j * spacing) / hs;
            if (Double.doubleToLongBits(field.at(x, z).height()) != Double.doubleToLongBits(p.bed()[i * p.nz() + j]))
                throw new AssertionError("node does not preserve production bed");
            x += 0.37 * spacing / hs; z += 0.29 * spacing / hs;
            double e = 1e-4, h = field.at(x, z).height();
            double numeric = (field.at(x + e, z).height() - field.at(x - e, z).height()) / (2 * e);
            maxGradientError = Math.max(maxGradientError, Math.abs(numeric - field.at(x, z).dx()));
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
                double v = p.bed()[(i + a) * p.nz() + j + b];
                low = Math.min(low, v); high = Math.max(high, v);
            }
            maxOvershoot = Math.max(maxOvershoot, Math.max(low - h, h - high));
            sampled++;
        }
        if (maxGradientError > 1e-5) throw new AssertionError("real-bed gradient mismatch " + maxGradientError);
        String columns = columns(provider, gen, hs);
        String report = "seed=" + seed + "\nframe=" + java.util.Arrays.toString(frame)
                + "\nhorizontalScale=" + hs + "\nnodeAndGradientSamples=" + sampled
                + "\nmaxGradientError=" + Double.toHexString(maxGradientError)
                + "\nmaxCornerRangeOvershoot=" + Double.toHexString(maxOvershoot)
                + "\n" + columns + "\nCANDIDATE_NOT_CONNECTED; overshoot is measured, not a basin-safety certificate\n";
        Files.createDirectories(out);
        Files.writeString(out.resolve("metrics.txt"), report);
        System.out.print(report);
    }

    private static String columns(ModelHydrologyProvider provider, CellGenerator gen, double hs) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var bytes = ByteBuffer.allocate(128);
        long count = 0, wet = 0, lake = 0;
        int cx = Math.floorDiv(ViewSpec.CENTER_X, 16), cz = Math.floorDiv(ViewSpec.CENTER_Z, 16);
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) {
            Cell[] original = new Cell[256];
            for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++)
                original[i * 16 + j] = gen.sample((x * 16 + i) / hs, (z * 16 + j) / hs);
            for (var c : provider.columnsFor(x, z, original)) {
                bytes.clear();
                bytes.putInt(c.blockX()).putInt(c.blockZ());
                bytes.putLong(Double.doubleToRawLongBits(c.originalGroundY()));
                bytes.putLong(Double.doubleToRawLongBits(c.carvedGroundY()));
                bytes.putLong(Double.doubleToRawLongBits(c.waterSurfaceY()));
                bytes.putLong(Double.doubleToRawLongBits(c.lipSurfaceY()));
                bytes.putLong(Double.doubleToRawLongBits(c.erosion()));
                bytes.putLong(Double.doubleToRawLongBits(c.erosionMask()));
                bytes.putLong(Double.doubleToRawLongBits(c.lakeLevelY()));
                bytes.put((byte) (c.fillWater() ? 1 : 0)).put((byte) (c.lakePlan() ? 1 : 0));
                if (c.lakeNode() != null) throw new AssertionError("unexpected legacy payload");
                digest.update(bytes.array(), 0, bytes.position());
                count++;
                if (c.lakePlan()) lake++;
                if (c.fillWater() && c.waterSurfaceY() > c.carvedGroundY()) wet++;
            }
        }
        return "columns=" + count + "\nwetColumns=" + wet + "\nlakeColumns=" + lake
                + "\ncolumnRawSHA256=" + HexFormat.of().formatHex(digest.digest());
    }
}
