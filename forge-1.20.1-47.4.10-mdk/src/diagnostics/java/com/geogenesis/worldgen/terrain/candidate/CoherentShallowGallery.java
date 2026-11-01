package com.geogenesis.worldgen.terrain.candidate;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** S2使用冻结C赢家细胞，仅更改海洋标签；未求高度、未过滤同类边。 */
public final class CoherentShallowGallery {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), out = Path.of(args[1]); Files.createDirectories(out);
        byte[] before = Files.readAllBytes(source.resolve("type.u8"));
        byte[] rawCells = Files.readAllBytes(source.resolve("cells.i64be"));
        if (before.length != 1401 * 1401 || rawCells.length != before.length * 8) throw new AssertionError("input size");
        ByteBuffer cells = ByteBuffer.wrap(rawCells);
        TreeSet<Long> selected = new TreeSet<>();
        for (int i = 0; i < before.length; i++) selected.add(cells.getLong());
        List<String> adjacency = Files.readAllLines(source.resolve("adjacency.csv"));
        for (String row : adjacency.subList(1, adjacency.size())) {
            String[] p = row.split(","); selected.add(Long.parseLong(p[0])); selected.add(Long.parseLong(p[1]));
        }
        ShallowRegionMatcher matcher = new ShallowRegionMatcher(RegionalMorphGallery.SEED);
        CoherentRegionDistribution base = new CoherentRegionDistribution(RegionalMorphGallery.SEED);
        Map<Long, ShallowRegionMatcher.Match> results = new LinkedHashMap<>();
        List<String> csv = new ArrayList<>(List.of("cell,cellX,cellZ,before,after,distance,margin,boundarySegments"));
        int done = 0;
        for (long key : selected) {
            var cell = cell(key); var m = matcher.match(cell); results.put(key, m);
            csv.add(key + "," + cell.cellX() + "," + cell.cellZ() + "," + m.before() + "," + m.after()
                    + "," + m.distance() + "," + m.margin() + "," + m.boundarySegments());
            if (++done % 20 == 0) System.out.println("S2 cells=" + done + "/" + selected.size() + " patchesBuilt=" + matcher.builds());
        }
        byte[] after = new byte[before.length], changed = new byte[before.length]; cells.rewind();
        for (int i = 0; i < before.length; i++) {
            var m = results.get(cells.getLong());
            if ((before[i] & 255) != m.before()) throw new AssertionError("frozen C identity drift at pixel " + i);
            after[i] = (byte) m.after(); changed[i] = (byte) (after[i] == before[i] ? 0 : 1);
        }
        Files.write(out.resolve("before.u8"), before); Files.write(out.resolve("after.u8"), after);
        Files.write(out.resolve("changed.u8"), changed); Files.write(out.resolve("cells.i64be"), rawCells);
        Files.write(out.resolve("sites.csv"), csv);
        checkAdjacency(adjacency, results, out);
        Files.writeString(out.resolve("meta.json"), "{\"seed\":\"" + RegionalMorphGallery.SEED + "\",\"size\":1401,\"step\":5,"
                + "\"origin\":[-3642.5,-3365.5],\"roi\":[0,0,620,660],\"microScale\":600,\"macroScale\":1200,"
                + "\"reach\":240,\"shallowMatching\":true,\"internalEdgeSuppression\":false,\"heightEvaluated\":false,"
                + "\"jvmProperties\":[\"file.encoding=UTF-8\"],\"distanceGeometry\":\"world polyline approximation; far=Infinity\"}");
        Files.writeString(out.resolve("runtime.txt"), "patchesBuilt=" + matcher.builds() + "\npatchesCached=" + matcher.cachedPatches() + "\n");
        if (base.seed() != RegionalMorphGallery.SEED) throw new AssertionError("seed");
        System.out.println("COHERENT SHALLOW GALLERY PASS");
    }

    static LocalBoundaryGeometry.Cell cell(long key) {
        int x = (int) (key >> 32), z = (int) key;
        double[] p = CoherentRegionDistribution.site(CoherentRegionDistribution.base(RegionalMorphGallery.SEED) + 23, x, z);
        return new LocalBoundaryGeometry.Cell(x, z, p[0], p[1], 0);
    }

    private static void checkAdjacency(List<String> rows, Map<Long, ShallowRegionMatcher.Match> results, Path out) throws Exception {
        int before = 0, after = 0; List<String> csv = new ArrayList<>(List.of("cellA,cellB,beforeA,beforeB,afterA,afterB"));
        for (String row : rows.subList(1, rows.size())) {
            String[] p = row.split(","); long a = Long.parseLong(p[0]), b = Long.parseLong(p[1]);
            var ma = results.get(a); var mb = results.get(b);
            if (bad(ma.before(), mb.before())) before++;
            if (bad(ma.after(), mb.after())) after++;
            csv.add(a + "," + b + "," + ma.before() + "," + mb.before() + "," + ma.after() + "," + mb.after());
        }
        Files.write(out.resolve("adjacency.csv"), csv);
        Files.writeString(out.resolve("adjacency-check.txt"), "landNonShelfBefore=" + before + "\nlandNonShelfAfter=" + after + "\n");
        if (before == 0 || after != 0) throw new AssertionError("adjacency regression before=" + before + " after=" + after);
    }

    private static boolean bad(int a, int b) {
        return a < 18 && b >= 18 && b != ShallowRegionMatcher.SHELF || b < 18 && a >= 18 && a != ShallowRegionMatcher.SHELF;
    }
}
