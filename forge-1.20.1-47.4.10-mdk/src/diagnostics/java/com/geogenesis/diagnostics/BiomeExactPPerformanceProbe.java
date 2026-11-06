package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.terrain.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual production sampleCellLight (exact P), without MC registry bootstrap or Holder.
 * Separate cold engines per arm. Defaults only, no TOML. Not a game loading benchmark. */
public final class BiomeExactPPerformanceProbe {
    private record Point(int x, int z) { }
    private static String scalar(Cell c) {
        int packed = (((int)Math.floor(c.height) + 129) & 0xffff)
                | ((c.rockTypeId & 15) << 16) | ((c.biomeType.ordinal() & 31) << 20);
        return Double.toHexString(c.height) + "\t" + c.terrainType + "\t" + c.biomeType
                + "\t" + c.rockTypeId + "\t" + packed;
    }
    private static List<String> arm(long seed, List<Point> points, boolean parallel) throws Exception {
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(
                new CellGenerator(TerrainParams.defaults(), -64, 320, true));
        terrain.seed(seed);
        ExecutorService pool = Executors.newFixedThreadPool(parallel ? 4 : 1);
        long start = System.nanoTime();
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (Point p : points) tasks.add(() -> scalar(terrain.sampleCellLight(p.x, p.z)));
            List<String> result = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(tasks)) result.add(f.get());
            System.out.printf(Locale.ROOT, "EXACT_P seed=%d arm=%s points=%d elapsedMs=%.3f%n",
                    seed, parallel ? "parallel4" : "serial", points.size(), (System.nanoTime()-start)/1e6);
            return result;
        } finally { pool.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "build/biome-concurrency-test/exact-p-scalars.tsv" : args[0]);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 8; i++) points.add(new Point((i-4)*256, (i%3-1)*256));
        List<String> rows = new ArrayList<>();
        rows.add("seed\tx\tz\theightHex\tterrainType\tbiomeType\trockTypeId\tcavePacked");
        long[] seeds = args.length > 1
                ? Arrays.stream(Arrays.copyOfRange(args, 1, args.length)).mapToLong(Long::parseLong).toArray()
                : new long[]{12345, 0, 9139912035078620160L};
        for (long seed : seeds) {
            List<String> serial = arm(seed, points, false), parallel = arm(seed, points, true);
            if (!serial.equals(parallel)) throw new AssertionError("exact P scalar mismatch seed=" + seed);
            for (int i = 0; i < points.size(); i++) rows.add(seed + "\t" + points.get(i).x
                    + "\t" + points.get(i).z + "\t" + serial.get(i));
        }
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.write(output, rows);
        System.out.println("PASS actual exact-P scalar/packed serial-parallel equality. NOT classifier keys/Holder: " + output);
    }
}
