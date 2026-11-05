package com.geogenesis.worldgen.erosion;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline single-version pre-erosion experiment; no Minecraft or JSON dependencies. */
public final class PreErosionGuideProbe {
    private PreErosionGuideProbe() {}

    private record Raster(double[] values, double x, double z, double step, int rows, int cols) {
        double at(double wx, double wz) {
            double cx = Math.max(0, Math.min(cols - 1, (wx - x) / step));
            double cz = Math.max(0, Math.min(rows - 1, (wz - z) / step));
            int ix = (int) cx, iz = (int) cz;
            int jx = Math.min(ix + 1, cols - 1), jz = Math.min(iz + 1, rows - 1);
            double tx = cx - ix, tz = cz - iz;
            double a = values[iz * cols + ix] * (1 - tx) + values[iz * cols + jx] * tx;
            double b = values[jz * cols + ix] * (1 - tx) + values[jz * cols + jx] * tx;
            return a * (1 - tz) + b * tz;
        }
    }

    private record Model(Raster density, Raster fade, double hu, double vu, double yproxy,
                         double guideDepth, double gradStep, long seed,
                         RuneErosionFilter.Settings settings) {
        double guide(Raster h, double x, double z) {
            return h.at(x, z) * yproxy - guideDepth * clamp01(density.at(x, z));
        }

        double evaluate(Raster h, double x, double z, double h0) {
            double xl = Math.max(h.x(), x - gradStep);
            double xr = Math.min(h.x() + (h.cols() - 1) * h.step(), x + gradStep);
            double zl = Math.max(h.z(), z - gradStep);
            double zr = Math.min(h.z() + (h.rows() - 1) * h.step(), z + gradStep);
            double gx = (guide(h, xr, z) - guide(h, xl, z)) / (xr - xl) * hu / vu;
            double gz = (guide(h, x, zr) - guide(h, x, zl)) / (zr - zl) * hu / vu;
            double g = clamp01(density.at(x, z));
            double ft = Math.max(-1, Math.min(1, fade.at(x, z)));
            double delta = RuneErosionFilter.delta(x / hu, z / hu, gx, gz, ft, settings, seed);
            double result = (h0 * yproxy - guideDepth * g + vu * delta * (.25 + .75 * g)) / yproxy;
            if (!Double.isFinite(result)) throw new IllegalStateException("Nonfinite output");
            return result;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: PreErosionGuideProbe config.json");
        Map<?, ?> config = object(new Json(Files.readString(Path.of(args[0]))).parse());
        Model model = model(config);
        Map<String, Raster> rasters = new LinkedHashMap<>();
        for (Object item : list(config.get("rasters"))) {
            Map<?, ?> spec = object(item);
            Raster h = readRaster(spec, "in");
            double[] result = new double[h.values().length];
            for (int row = 0; row < h.rows(); row++) {
                for (int col = 0; col < h.cols(); col++) {
                    int i = row * h.cols() + col;
                    result[i] = model.evaluate(h, h.x() + col * h.step(), h.z() + row * h.step(),
                                               h.values()[i]);
                }
            }
            write(Path.of(text(spec, "out")), result);
            rasters.put(text(spec, "out"), h);
            System.out.println("raster " + spec.get("out") + " points=" + result.length);
        }
        // Optional saved irregular sections: exact saved H0; gradient uses a declared 2D raster.
        for (Object item : list(config.containsKey("pointSets") ? config.get("pointSets") : List.of())) {
            Map<?, ?> spec = object(item);
            double[] h0 = read(Path.of(text(spec, "in")), integer(spec, "count"));
            double[] xz = read(Path.of(text(spec, "xz")), h0.length * 2);
            Raster gradient = rasters.get(text(spec, "gradientRasterOut"));
            if (gradient == null) throw new IllegalArgumentException("Unknown gradient raster");
            double[] result = new double[h0.length];
            for (int i = 0; i < h0.length; i++) {
                result[i] = model.evaluate(gradient, xz[2 * i], xz[2 * i + 1], h0[i]);
            }
            write(Path.of(text(spec, "out")), result);
            System.out.println("section " + spec.get("out") + " points=" + result.length);
        }
    }

    private static Model model(Map<?, ?> config) throws IOException {
        Map<?, ?> p = object(config.get("params"));
        RuneErosionFilter.Settings settings = new RuneErosionFilter.Settings(
                number(p, "scale"), number(p, "strength"), number(p, "cellScale"), integer(p, "octaves"),
                number(p, "gain"), number(p, "lacunarity"), number(p, "normalization"),
                number(p, "gullyWeight"), number(p, "detail"), number(p, "ridgeRounding"),
                number(p, "creaseRounding"), number(p, "inputRounding"), number(p, "octaveRounding"),
                number(p, "inputOnset"), number(p, "octaveOnset"), number(p, "assumedSlope"),
                number(p, "assumedSlopeMix"));
        return new Model(readRaster(object(config.get("density")), "path"),
                readRaster(object(config.get("ftRaster")), "path"), number(p, "HU"), number(p, "VU"),
                number(p, "Yproxy"), number(p, "guideDepthY"), number(p, "gradStepWu"),
                Long.parseLong(p.get("seed").toString()), settings);
    }

    private static Raster readRaster(Map<?, ?> spec, String key) throws IOException {
        int rows = integer(spec, "rows"), cols = integer(spec, "cols");
        double step = number(spec, "step");
        if (rows < 2 || cols < 2 || !(step > 0)) throw new IllegalArgumentException("Invalid raster");
        return new Raster(read(Path.of(text(spec, key)), Math.multiplyExact(rows, cols)),
                number(spec, "originX"), number(spec, "originZ"), step, rows, cols);
    }

    private static double[] read(Path path, int count) throws IOException {
        if (Files.size(path) != 8L * count) throw new IOException("Wrong byte count: " + path);
        double[] values = new double[count];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int i = 0; i < count; i++) {
                values[i] = input.readDouble();
                if (!Double.isFinite(values[i])) throw new IOException("Nonfinite input: " + path);
            }
        }
        return values;
    }

    private static void write(Path path, double[] values) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            for (double value : values) output.writeDouble(value);
        }
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    private static Map<?, ?> object(Object v) {
        if (v instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected JSON object");
    }
    private static List<?> list(Object v) {
        if (v instanceof List<?> items) return items;
        throw new IllegalArgumentException("Expected JSON array");
    }
    private static String text(Map<?, ?> m, String k) { return (String) m.get(k); }
    private static double number(Map<?, ?> m, String k) { return ((Number) m.get(k)).doubleValue(); }
    private static int integer(Map<?, ?> m, String k) { return ((Number) m.get(k)).intValue(); }

    /** Small strict JSON reader; integer tokens stay long to preserve the exact seed. */
    private static final class Json {
        private final String source;
        private int pos;
        Json(String source) { this.source = source; }
        Object parse() {
            Object result = value();
            space();
            if (pos != source.length()) throw new IllegalArgumentException("Trailing JSON");
            return result;
        }
        private void space() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
        }
        private char take() { return source.charAt(pos++); }
        private void expect(char c) {
            space();
            if (take() != c) throw new IllegalArgumentException("Expected " + c);
        }
        private Object value() {
            space();
            char c = source.charAt(pos);
            if (c == '{') return map();
            if (c == '[') return array();
            if (c == '"') return string();
            for (String token : List.of("true", "false", "null")) {
                if (source.startsWith(token, pos)) {
                    pos += token.length();
                    if (token.equals("null")) return null;
                    return Boolean.valueOf(token);
                }
            }
            int start = pos;
            while (pos < source.length() && "-+0123456789.eE".indexOf(source.charAt(pos)) >= 0) pos++;
            String token = source.substring(start, pos);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.valueOf(token);
            }
            return Long.valueOf(token);
        }
        private Map<String, Object> map() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (source.charAt(pos) == '}') { pos++; return result; }
            while (true) {
                space();
                String key = string();
                expect(':');
                result.put(key, value());
                space();
                char c = take();
                if (c == '}') return result;
                if (c != ',') throw new IllegalArgumentException("Expected comma");
            }
        }
        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            space();
            if (source.charAt(pos) == ']') { pos++; return result; }
            while (true) {
                result.add(value());
                space();
                char c = take();
                if (c == ']') return result;
                if (c != ',') throw new IllegalArgumentException("Expected comma");
            }
        }
        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (true) {
                char c = take();
                if (c == '"') return result.toString();
                if (c == '\\') {
                    c = take();
                    if (c == 'u') {
                        c = (char) Integer.parseInt(source.substring(pos, pos + 4), 16);
                        pos += 4;
                    } else {
                        c = switch (c) {
                            case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                            case 'b' -> '\b'; case 'f' -> '\f';
                            case '"', '\\', '/' -> c;
                            default -> throw new IllegalArgumentException("Invalid JSON escape");
                        };
                    }
                }
                result.append(c);
            }
        }
    }
}
