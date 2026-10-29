package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 候选地貌出图与高度图导出（隔离、只读配置、不接生产）。
 *
 * <p>每个候选产出：{@code <ID>.f64be.raw}（着色图用）+ {@code heightmap-<ID>.{png,f32.raw,json}}
 * （地形软件口径）。所有候选共用同一坐标窗口，便于并排比对。</p>
 *
 * <p>用法：{@code java ... TypeCandidateGallery <toml> <outDir> <size> <stepBlocks> <cx> <cz>}</p>
 */
public final class TypeCandidateGallery {

    /** 一个待出图的候选：id、着色图标签、WU→Y 采样器。 */
    private record Entry(String id, String label, Sampler sampler) { }

    private interface Sampler {
        double heightAt(double x, double z);
    }

    private TypeCandidateGallery() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 6) {
            throw new IllegalArgumentException("usage: <toml> <outDir> <size> <stepBlocks> <cx> <cz>");
        }
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int size = Integer.parseInt(args[2]);
        int step = Integer.parseInt(args[3]);
        double cx = Double.parseDouble(args[4]);
        double cz = Double.parseDouble(args[5]);
        Files.createDirectories(out);

        HeightCurve curve = loadCurve(toml, out);

        // 第 7 个参数选择要出图的地貌族（缺省 = 全部）。
        String group = args.length > 6 ? args[6] : "all";
        List<Entry> entries = new ArrayList<>();

        if (group.equals("all") || group.equals("badlands")) {
            BadlandsCandidate badlands = new BadlandsCandidate();
            badlands.seed(9139912035078620160L);
            entries.add(new Entry("BADLANDS-ref", badlands.label(),
                    (x, z) -> curve.heightFromE(badlands.e(x, z))));
        }
        if (group.equals("all") || group.equals("plains")) {
            for (PlainsCandidate.Variant v : PlainsCandidate.Variant.values()) {
                PlainsCandidate plains = new PlainsCandidate(v);
                plains.seed(9139912035078620160L);
                String id = switch (v) {
                    case PLAINS -> "PLAINS-ref";
                    case STEPPE -> "STEPPE-ref";
                    case PLAINS_GEO -> "PLAINS-geo";
                };
                entries.add(new Entry(id, plains.label(), (x, z) -> curve.heightFromE(plains.e(x, z))));
            }
        }
        if (group.equals("all") || group.equals("hills")) {
            for (HillsCandidate.Variant v : HillsCandidate.Variant.values()) {
                HillsCandidate hills = new HillsCandidate(v);
                hills.seed(9139912035078620160L);
                entries.add(new Entry("HILLS-" + v.id(), hills.label(), (x, z) -> curve.heightFromE(hills.e(x, z))));
            }
            HillsRegionCandidate region = new HillsRegionCandidate();
            region.seed(9139912035078620160L);
            entries.add(new Entry(region.id(), region.label(),
                    (x, z) -> curve.heightFromE(region.e(x, z))));
        }
        if (group.equals("all") || group.equals("mountains")) {
            for (MountainsCandidate.Variant v : MountainsCandidate.Variant.values()) {
                MountainsCandidate m = new MountainsCandidate(v);
                m.seed(9139912035078620160L);
                entries.add(new Entry("MTN-" + v.id(), m.label(), (x, z) -> curve.heightFromE(m.e(x, z))));
            }
            MountainsRegionCandidate region = new MountainsRegionCandidate();
            region.seed(9139912035078620160L);
            entries.add(new Entry(region.id(), region.label(),
                    (x, z) -> curve.heightFromE(region.e(x, z))));
        }
        if (group.equals("all") || group.equals("plateau")) {
            for (PlateauCandidate.Variant v : PlateauCandidate.Variant.values()) {
                PlateauCandidate plateau = new PlateauCandidate(v);
                plateau.seed(9139912035078620160L);
                entries.add(new Entry(v.id(), plateau.label(),
                        (x, z) -> curve.heightFromE(plateau.e(x, z))));
            }
        }
        if (group.equals("all") || group.equals("basin")) {
            for (BasinCandidate.Variant v : BasinCandidate.Variant.values()) {
                BasinCandidate basin = new BasinCandidate(v);
                basin.seed(9139912035078620160L);
                entries.add(new Entry("BASIN-" + v.id(), basin.label(),
                        (x, z) -> curve.heightFromE(basin.e(x, z))));
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("unknown group: " + group);

        // 原点必须与 bakeShaded 完全一致：都用整数中心 size/2（不是 size/2.0），
        // 否则高度图与着色图相差半个像素，两边的统计会对不上。
        double originX = cx - (size / 2) * (double) step;
        double originZ = cz - (size / 2) * (double) step;
        StringBuilder summary = new StringBuilder(
                "candidate,label,minY,maxY,reliefY,meanY,size,step,cx,cz\n");
        StringBuilder variants = new StringBuilder("[");

        for (Entry e : entries) {
            double[] height = bakeShaded(e.sampler(), size, step, cx, cz);
            writeRaw(out.resolve(e.id() + ".f64be.raw"), height);

            double[] r = HeightmapExport.bake(out, "heightmap-" + e.id(), e.sampler()::heightAt,
                    size, step, originX, originZ, -64, 320,
                    e.label() + "; seed=9139912035078620160; unit=block");

            double sum = 0;
            for (double y : height) sum += y;
            summary.append(String.format(Locale.ROOT, "%s,%s,%.3f,%.3f,%.3f,%.3f,%d,%d,%.1f,%.1f%n",
                    e.id(), e.label(), r[0], r[1], r[1] - r[0], sum / height.length, size, step, cx, cz));
            if (variants.length() > 1) variants.append(',');
            variants.append('"').append(e.id()).append('"');
            System.out.printf("%-12s Y=%.2f..%.2f relief=%.2f%n", e.id(), r[0], r[1], r[1] - r[0]);
        }
        variants.append(']');

        Files.writeString(out.resolve("summary.csv"), summary.toString());
        Files.writeString(out.resolve("geometry.json"), String.format(Locale.ROOT,
                "{\"size\":%d,\"stepBlocks\":%d,\"centerBlocks\":[%.1f,%.1f],\"variants\":%s}%n",
                size, step, cx, cz, variants));
        System.out.println("out=" + out.toAbsolutePath());
    }

    private static HeightCurve loadCurve(Path toml, Path out) throws Exception {
        Path copy = out.resolve("config-input.toml");
        Files.copy(toml, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        CommentedFileConfig cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
        cfg.load();
        if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) {
            throw new IllegalStateException("frozen TOML unexpectedly requires correction");
        }
        GeoGenesisConfig.SPEC.setConfig(cfg);
        TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
        return new HeightCurve(params, -64, 320);
    }

    /** 着色图用：块坐标直接进候选（参考的噪声 scale 以方块为单位）。 */
    private static double[] bakeShaded(Sampler sampler, int size, int step, double cx, double cz) {
        double[] out = new double[size * size];
        for (int z = 0; z < size; z++) {
            double wz = cz + (z - size / 2.0) * step;
            for (int x = 0; x < size; x++) {
                double wx = cx + (x - size / 2.0) * step;
                double y = sampler.heightAt(wx, wz);
                if (!Double.isFinite(y)) throw new AssertionError("non-finite height");
                out[z * size + x] = y;
            }
        }
        return out;
    }

    private static void writeRaw(Path path, double[] values) throws Exception {
        try (DataOutputStream stream = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
            for (double v : values) stream.writeDouble(v);
        }
    }
}