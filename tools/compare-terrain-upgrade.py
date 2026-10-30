#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Compare two TerrainUpgradeProbe render directories (baseline A vs candidate B).

Inputs are produced by:

    TerrainUpgradeProbe render --seed=... --cx=... --cz=... --size=... --step=... --out=<dir>

Each directory must contain ``stats.json``, ``hillshade.png`` and ``height.f32.raw`` /
``height.f64.raw``.  This script

* asserts the two arms share geometry (size/step/center) and the *fixed* colour scale,
* writes a side-by-side hillshade comparison with A/B labels,
* writes a signed height-difference image (fixed symmetric scale),
* writes ``compare-stats.json`` with numeric agreement statistics.

Exit codes: 0 = compared, 1 = comparison assertion failed, 2 = usage / IO error.

Everything is offline: Pillow only, no network, no writes outside ``--out``.
"""

import argparse
import array
import json
import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - environment guard
    sys.stderr.write("Pillow is required (pip install pillow)\n")
    raise SystemExit(2)

PREFERRED_RAW = ("height.f32.raw", "height.f64.raw")
DTYPE_OF = {"height.f32.raw": "f", "height.f64.raw": "d"}
NEUTRAL = (245, 245, 245)
POS_COLOR = (200, 30, 30)
NEG_COLOR = (30, 60, 200)


class CompareError(Exception):
    """Any comparison/IO failure that must produce a non-zero exit."""


# ----------------------------------------------------------------------
# IO helpers
# ----------------------------------------------------------------------

def load_json(path):
    if not os.path.isfile(path):
        raise CompareError("missing file: %s" % path)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except ValueError as exc:
        raise CompareError("invalid JSON in %s: %s" % (path, exc))


def render_of(stats):
    """Extract the geometry block, tolerating either layout (top-level or nested)."""
    if not isinstance(stats, dict):
        raise CompareError("stats.json is not an object")
    render = stats.get("render")
    if not isinstance(render, dict):
        raise CompareError("stats.json has no 'render' block (wrong probe?)")
    for key in ("size", "step", "center"):
        if key not in render:
            raise CompareError("stats.json render.%s missing" % key)
    return render


def coord_of(size, center, step, index):
    """Pixel index -> world coordinate; mirrors TerrainUpgradeRender.coordOf."""
    return center + (index - size // 2) * step


def pixel_of(size, center, step, coord):
    """World coordinate -> pixel index (inverse of coord_of)."""
    return int(round((coord - center) / float(step))) + size // 2


def read_raw(path, size, dtype):
    """Read a little-endian float32/float64 raw height grid."""
    if not os.path.isfile(path):
        raise CompareError("missing raw height file: %s" % path)
    itemsize = 4 if dtype == "f" else 8
    expected = size * size * itemsize
    with open(path, "rb") as handle:
        data = handle.read()
    if len(data) != expected:
        raise CompareError("%s: expected %d bytes for %dx%d, got %d"
                           % (path, expected, size, size, len(data)))
    values = array.array(dtype)
    values.frombytes(data)
    if sys.byteorder != "little":
        values.byteswap()
    return values


def pick_raw(directory):
    for name in PREFERRED_RAW:
        if os.path.isfile(os.path.join(directory, name)):
            return name
    raise CompareError("no %s in %s" % (" / ".join(PREFERRED_RAW), directory))


# ----------------------------------------------------------------------
# Assertions
# ----------------------------------------------------------------------

def check_geometry(a_render, b_render, allow_mismatch=False):
    problems = []
    for key in ("size", "step"):
        if int(a_render[key]) != int(b_render[key]):
            problems.append("%s: A=%s B=%s" % (key, a_render[key], b_render[key]))
    for axis in ("x", "z"):
        if float(a_render["center"][axis]) != float(b_render["center"][axis]):
            problems.append("center.%s: A=%s B=%s"
                            % (axis, a_render["center"][axis], b_render["center"][axis]))
    if problems:
        message = "geometry differs (pixel-wise comparison impossible): " + "; ".join(problems)
        if allow_mismatch:
            sys.stderr.write("WARN %s\n" % message)
            return False
        raise CompareError(message)
    return True


def check_colorscale(a_stats, b_stats, allow_mismatch=False):
    a_scale = a_stats.get("colorScale")
    b_scale = b_stats.get("colorScale")
    if not isinstance(a_scale, dict) or not isinstance(b_scale, dict):
        raise CompareError("stats.json has no colorScale block")
    problems = []
    for key in ("min", "max"):
        if float(a_scale[key]) != float(b_scale[key]):
            problems.append("%s: A=%s B=%s" % (key, a_scale[key], b_scale[key]))
    a_ramp = [list(row) for row in a_scale.get("ramp", [])]
    b_ramp = [list(row) for row in b_scale.get("ramp", [])]
    if a_ramp != b_ramp:
        problems.append("ramp differs")
    if problems:
        message = "fixed colour scale differs, side-by-side shading is not comparable: " + \
                  "; ".join(problems)
        if allow_mismatch:
            sys.stderr.write("WARN %s\n" % message)
            return False
        raise CompareError(message)
    return True


# ----------------------------------------------------------------------
# Images
# ----------------------------------------------------------------------

def default_font():
    try:
        return ImageFont.load_default()
    except Exception:  # pragma: no cover - Pillow always ships the bitmap font
        return None


def text_size(draw, text, font):
    try:
        return draw.textsize(text, font=font)  # Pillow < 10
    except AttributeError:  # pragma: no cover
        box = draw.textbbox((0, 0), text, font=font)
        return box[2] - box[0], box[3] - box[1]


def header_height(minimum=22):
    """Label band height; always tall enough for the bitmap font (11px on Pillow 7)."""
    try:
        draw = ImageDraw.Draw(Image.new("RGB", (1, 1)))
        _, text_height = text_size(draw, "Ag", default_font())
        return max(minimum, text_height + 4)
    except Exception:  # pragma: no cover - never fail just for a label band
        return max(minimum, 16)


def compose_side_by_side(image_a, image_b, label_a, label_b, gap=12, header=22):
    """Place A and B on one canvas with a label band; keeps both at native size."""
    if image_a.size != image_b.size:
        raise CompareError("image sizes differ: A=%s B=%s" % (image_a.size, image_b.size))
    width, height = image_a.size
    header = header_height(header)
    canvas = Image.new("RGB", (width * 2 + gap, height + header), (255, 255, 255))
    canvas.paste(image_a.convert("RGB"), (0, header))
    canvas.paste(image_b.convert("RGB"), (width + gap, header))
    draw = ImageDraw.Draw(canvas)
    font = default_font()
    for label, x0 in ((label_a, 0), (label_b, width + gap)):
        tw, th = text_size(draw, label, font)
        draw.text((x0 + max(0, (width - tw) // 2), max(0, (header - th) // 2)),
                  label, fill=(0, 0, 0), font=font)
    draw.line([(width + gap // 2, 0), (width + gap // 2, height + header)], fill=(210, 210, 210))
    return canvas


def diff_color(value, limit):
    """Zero -> neutral, positive -> red, negative -> blue, clipped at +-limit."""
    if limit <= 0:
        return NEUTRAL
    t = max(-1.0, min(1.0, value / float(limit)))
    if t >= 0:
        return (int(NEUTRAL[0] + (POS_COLOR[0] - NEUTRAL[0]) * t),
                int(NEUTRAL[1] + (POS_COLOR[1] - NEUTRAL[1]) * t),
                int(NEUTRAL[2] + (POS_COLOR[2] - NEUTRAL[2]) * t))
    t = -t
    return (int(NEUTRAL[0] + (NEG_COLOR[0] - NEUTRAL[0]) * t),
            int(NEUTRAL[1] + (NEG_COLOR[1] - NEUTRAL[1]) * t),
            int(NEUTRAL[2] + (NEG_COLOR[2] - NEUTRAL[2]) * t))


def write_diff_image(path, a_values, b_values, size, limit):
    """Signed difference image (A - B) with a fixed symmetric scale."""
    buffer = bytearray(size * size * 3)
    offset = 0
    for i in range(size * size):
        red, green, blue = diff_color(a_values[i] - b_values[i], limit)
        buffer[offset] = red
        buffer[offset + 1] = green
        buffer[offset + 2] = blue
        offset += 3
    Image.frombytes("RGB", (size, size), bytes(buffer)).save(path)


# ----------------------------------------------------------------------
# Statistics
# ----------------------------------------------------------------------

def percentile(sorted_values, q):
    if not sorted_values:
        raise CompareError("percentile of empty sample")
    index = int(round(q * (len(sorted_values) - 1)))
    return sorted_values[max(0, min(len(sorted_values) - 1, index))]


def diff_stats(a_values, b_values, size):
    """Numeric agreement between two height grids (same length, row-major)."""
    if len(a_values) != len(b_values):
        raise CompareError("height arrays differ in length: %d vs %d" % (len(a_values), len(b_values)))
    diffs = [a - b for a, b in zip(a_values, b_values)]
    absolute = [abs(v) for v in diffs]
    ordered = sorted(absolute)
    total = float(len(diffs))
    mean = sum(diffs) / total
    mean_a = sum(a_values) / total
    mean_b = sum(b_values) / total
    var_a = sum((v - mean_a) ** 2 for v in a_values) / total
    var_b = sum((v - mean_b) ** 2 for v in b_values) / total
    cov = sum((a - mean_a) * (b - mean_b) for a, b in zip(a_values, b_values)) / total
    correlation = cov / (var_a ** 0.5 * var_b ** 0.5) if var_a > 0 and var_b > 0 else None
    b_min = min(b_values)
    b_max = max(b_values)
    return {
        "pixels": int(len(diffs)),
        "size": size,
        "identicalBitPatterns": all(a == b for a, b in zip(a_values, b_values)),
        "meanDiff": mean,
        "rmsDiff": (sum(v * v for v in diffs) / total) ** 0.5,
        "maxAbsDiff": ordered[-1],
        "p50AbsDiff": percentile(ordered, 0.50),
        "p95AbsDiff": percentile(ordered, 0.95),
        "p99AbsDiff": percentile(ordered, 0.99),
        "changedPixels": sum(1 for v in absolute if v > 0.0),
        "gt0_5": sum(1 for v in absolute if v > 0.5),
        "gt1": sum(1 for v in absolute if v > 1.0),
        "gt2": sum(1 for v in absolute if v > 2.0),
        "aMin": min(a_values),
        "aMax": max(a_values),
        "bMin": b_min,
        "bMax": b_max,
        "correlation": correlation,
    }


def image_check(path, size):
    if not os.path.isfile(path):
        raise CompareError("missing image: %s" % path)
    try:
        with Image.open(path) as handle:
            if handle.size != (size, size):
                raise CompareError("%s: expected %dx%d, got %dx%d"
                                   % (path, size, size, handle.size[0], handle.size[1]))
            handle.convert("RGB").load()
    except CompareError:
        raise
    except Exception as exc:
        raise CompareError("cannot read image %s: %s" % (path, exc))


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------

def compare(a_dir, b_dir, out_dir, label_a, label_b, diff_limit,
            allow_geometry_mismatch=False, allow_colorscale_mismatch=False):
    a_stats = load_json(os.path.join(a_dir, "stats.json"))
    b_stats = load_json(os.path.join(b_dir, "stats.json"))
    a_render = render_of(a_stats)
    b_render = render_of(b_stats)
    geometry_ok = check_geometry(a_render, b_render, allow_geometry_mismatch)
    scale_ok = check_colorscale(a_stats, b_stats, allow_colorscale_mismatch)

    size = int(a_render["size"])
    step = int(a_render["step"])
    image_check(os.path.join(a_dir, "hillshade.png"), size)
    image_check(os.path.join(b_dir, "hillshade.png"), size)

    raw_name = pick_raw(a_dir)
    if not os.path.isfile(os.path.join(b_dir, raw_name)):
        raise CompareError("B arm is missing %s (A has it)" % raw_name)
    dtype = DTYPE_OF[raw_name]
    a_values = read_raw(os.path.join(a_dir, raw_name), size, dtype)
    b_values = read_raw(os.path.join(b_dir, raw_name), size, dtype)

    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)

    with Image.open(os.path.join(a_dir, "hillshade.png")) as handle:
        image_a = handle.convert("RGB")
    with Image.open(os.path.join(b_dir, "hillshade.png")) as handle:
        image_b = handle.convert("RGB")
    compose_side_by_side(image_a, image_b, label_a, label_b).save(
        os.path.join(out_dir, "compare-hillshade.png"))

    type_note = "absent in at least one arm"
    a_type = os.path.join(a_dir, "type.png")
    b_type = os.path.join(b_dir, "type.png")
    if os.path.isfile(a_type) and os.path.isfile(b_type):
        with Image.open(a_type) as handle:
            type_a = handle.convert("RGB")
        with Image.open(b_type) as handle:
            type_b = handle.convert("RGB")
        if type_a.size == type_b.size:
            compose_side_by_side(type_a, type_b, label_a + " types",
                                 label_b + " types").save(
                os.path.join(out_dir, "compare-type.png"))
            type_note = "compare-type.png"
        else:
            type_note = "size mismatch, skipped"

    write_diff_image(os.path.join(out_dir, "diff-height.png"), a_values, b_values, size, diff_limit)

    stats = diff_stats(a_values, b_values, size)
    result = {
        "tool": "compare-terrain-upgrade",
        "armA": {"dir": os.path.abspath(a_dir), "label": label_a,
                 "seed": a_render.get("seed"), "center": a_render.get("center"),
                 "step": step, "size": size,
                 "height": {"min": stats["aMin"], "max": stats["aMax"]}},
        "armB": {"dir": os.path.abspath(b_dir), "label": label_b,
                 "seed": b_render.get("seed"), "center": b_render.get("center"),
                 "height": {"min": stats["bMin"], "max": stats["bMax"]}},
        "geometryConsistent": geometry_ok,
        "colorScaleConsistent": scale_ok,
        "colorScale": a_stats.get("colorScale"),
        "rawFile": raw_name,
        "diffImage": {"file": "diff-height.png", "limit": diff_limit,
                      "rule": "A-B; neutral grey = 0; red = A higher; blue = A lower; clipped at +-limit"},
        "diff": stats,
        "artifacts": {"sideBySide": "compare-hillshade.png", "type": type_note,
                      "diff": "diff-height.png"},
        "notes": [
            "Both arms are pre-erosion base height diagnostics; differences here are NOT final "
            "block-placement differences.",
            "Height values come from height.f32.raw unless absent (then f64).",
        ],
    }
    with open(os.path.join(out_dir, "compare-stats.json"), "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    return result


def print_summary(result):
    diff = result["diff"]
    print("A: %s" % result["armA"]["dir"])
    print("B: %s" % result["armB"]["dir"])
    print("geometryConsistent=%s colorScaleConsistent=%s raw=%s"
          % (result["geometryConsistent"], result["colorScaleConsistent"], result["rawFile"]))
    print("pixels=%d changed=%d gt0.5=%d gt1=%d gt2=%d"
          % (diff["pixels"], diff["changedPixels"], diff["gt0_5"], diff["gt1"], diff["gt2"]))
    print("meanDiff=%.6f rms=%.6f maxAbs=%.6f p95=%.6f p99=%.6f correlation=%s"
          % (diff["meanDiff"], diff["rmsDiff"], diff["maxAbsDiff"],
             diff["p95AbsDiff"], diff["p99AbsDiff"], diff["correlation"]))
    print("A height=[%.3f,%.3f] B height=[%.3f,%.3f]"
          % (diff["aMin"], diff["aMax"], diff["bMin"], diff["bMax"]))
    print("artifacts: compare-hillshade.png, %s, diff-height.png, compare-stats.json"
          % result["artifacts"]["type"])


# ----------------------------------------------------------------------
# Self-test (no external files needed)
# ----------------------------------------------------------------------

def self_test():
    failures = []
    checks = []

    # 1) negative coordinate axis: index <-> world round trip
    size, step = 11, 4
    for center in (-140.0, 137.0, 0.0, -1234.5):
        for index in (0, size // 2, size - 1):
            coord = coord_of(size, center, step, index)
            back = pixel_of(size, center, step, coord)
            if back != index:
                failures.append("coord round-trip center=%s index=%d -> %d" % (center, index, back))
    if coord_of(11, -140.0, 4, 0) != -160.0:
        failures.append("coord_of negative origin")
    if pixel_of(11, -140.0, 4, -160.0) != 0:
        failures.append("pixel_of negative origin")
    checks.append("negative coordinate axis round-trip")

    # 2) raw reader: little-endian layout and truncation detection
    values = array.array("f", [1.5, -7.25, 0.0, 3.0])
    if sys.byteorder != "little":
        values.byteswap()
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_selftest.raw")
    try:
        with open(path, "wb") as handle:
            handle.write(values.tobytes())
        loaded = read_raw(path, 2, "f")
        if abs(loaded[1] + 7.25) > 1e-6:
            failures.append("raw read value mismatch: %r" % (loaded[1],))
        try:
            read_raw(path, 3, "f")
            failures.append("truncated raw was accepted")
        except CompareError:
            checks.append("raw truncation rejected")
    finally:
        if os.path.isfile(path):
            os.remove(path)

    # 3) image index: B must start exactly at A.width + gap, pixels preserved
    image_a = Image.new("RGB", (4, 3), (10, 20, 30))
    image_b = Image.new("RGB", (4, 3), (200, 100, 50))
    image_a.putpixel((3, 2), (1, 2, 3))
    image_b.putpixel((0, 0), (9, 8, 7))
    canvas = compose_side_by_side(image_a, image_b, "A", "B", gap=2, header=6)
    header = header_height(6)
    if canvas.size != (4 * 2 + 2, 3 + header):
        failures.append("compose size %r" % (canvas.size,))
    if canvas.getpixel((3, header + 2)) != (1, 2, 3):
        failures.append("A pixel not preserved at (3, bottom)")
    if canvas.getpixel((4 + 2, header)) != (9, 8, 7):
        failures.append("B origin not at width+gap")
    checks.append("side-by-side image index")

    # 4) diff colour ramp endpoints + sign
    if diff_color(0.0, 2.0) != NEUTRAL:
        failures.append("diff_color(0) is not neutral")
    if diff_color(99.0, 2.0) != POS_COLOR:
        failures.append("diff_color(+clip) is not positive colour")
    if diff_color(-99.0, 2.0) != NEG_COLOR:
        failures.append("diff_color(-clip) is not negative colour")
    checks.append("diff colour ramp")

    # 5) stats on a known pair
    a_values = array.array("f", [1.0, 2.0, 3.0, 4.0])
    b_values = array.array("f", [1.0, 2.0, 3.0, 2.0])
    stats = diff_stats(a_values, b_values, 2)
    if abs(stats["maxAbsDiff"] - 2.0) > 1e-6:
        failures.append("maxAbsDiff %r" % (stats["maxAbsDiff"],))
    if abs(stats["meanDiff"] - 0.5) > 1e-6:
        failures.append("meanDiff %r" % (stats["meanDiff"],))
    if stats["changedPixels"] != 1 or stats["gt1"] != 1 or stats["gt2"] != 0:
        failures.append("counters %r" % (stats,))
    checks.append("diff statistics")

    # 6) fault reverse control: each assertion must fire on bad input
    bad_geometry = {"size": 11, "step": 4, "center": {"x": 0, "z": 0}}
    other_geometry = {"size": 21, "step": 4, "center": {"x": 0, "z": 0}}
    try:
        check_geometry(bad_geometry, other_geometry)
        failures.append("geometry mismatch was accepted")
    except CompareError:
        checks.append("geometry mismatch rejected")
    scale_a = {"min": -20, "max": 240, "ramp": [[1, 2, 3]]}
    scale_b = {"min": -20, "max": 240, "ramp": [[4, 5, 6]]}
    try:
        check_colorscale({"colorScale": scale_a}, {"colorScale": scale_b})
        failures.append("colour scale mismatch was accepted")
    except CompareError:
        checks.append("colour scale mismatch rejected")
    try:
        compose_side_by_side(Image.new("RGB", (4, 3)), Image.new("RGB", (5, 3)), "A", "B")
        failures.append("image size mismatch was accepted")
    except CompareError:
        checks.append("image size mismatch rejected")

    for check in checks:
        print("[self-test] " + check)
    for failure in failures:
        print("[self-test] FAIL " + failure)
    if failures:
        print("FAIL compare-terrain-upgrade self-test failures=%d" % len(failures))
        return 1
    print("PASS compare-terrain-upgrade self-test checks=%d" % len(checks))
    return 0


# ----------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------

def build_parser():
    parser = argparse.ArgumentParser(
        description="Compare two TerrainUpgradeProbe render directories (A = baseline, B = candidate).")
    parser.add_argument("a_dir", nargs="?", help="baseline render directory (contains stats.json)")
    parser.add_argument("b_dir", nargs="?", help="candidate render directory")
    parser.add_argument("--out", default=None, help="output directory (default: <b_dir>/compare)")
    parser.add_argument("--label-a", default="A schema119", help="label drawn above the A image")
    parser.add_argument("--label-b", default="B candidate", help="label drawn above the B image")
    parser.add_argument("--diff-limit", type=float, default=2.0,
                        help="symmetric clip for the difference image, in world-Y blocks (default 2.0)")
    parser.add_argument("--allow-geometry-mismatch", action="store_true",
                        help="warn instead of failing when size/step/center differ")
    parser.add_argument("--allow-colorscale-mismatch", action="store_true",
                        help="warn instead of failing when the fixed colour scale differs")
    parser.add_argument("--self-test", action="store_true", help="run internal self-test and exit")
    return parser


def main(argv):
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.a_dir or not args.b_dir:
        parser.print_usage(sys.stderr)
        sys.stderr.write("error: two render directories are required\n")
        return 2
    out_dir = args.out or os.path.join(args.b_dir, "compare")
    try:
        result = compare(args.a_dir, args.b_dir, out_dir, args.label_a, args.label_b,
                         args.diff_limit, args.allow_geometry_mismatch,
                         args.allow_colorscale_mismatch)
    except CompareError as exc:
        sys.stderr.write("FAIL %s\n" % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write("IO ERROR %s\n" % exc)
        return 2
    print_summary(result)
    return 0 if (result["geometryConsistent"] and result["colorScaleConsistent"]) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
