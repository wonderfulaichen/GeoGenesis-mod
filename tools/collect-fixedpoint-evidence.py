# -*- coding: utf-8 -*-
"""定点取证：从一次实机的 Minecraft 日志里，抽取指定坐标窗口的
水文裁定 + 性能数据，输出人类可读报告与机器可读 JSON。

只读：不改 run/config、不改世界、不碰源码。

用法（在仓库根或任意位置都行，路径按仓库结构自动定位）：

    python -B tools/collect-fixedpoint-evidence.py
    python -B tools/collect-fixedpoint-evidence.py --center -570,-672 --size 1200
    python -B tools/collect-fixedpoint-evidence.py --center 294,-928 --size 512 \
                                                  --center -1436,-416
    python -B tools/collect-fixedpoint-evidence.py --logs path/to/run/logs --out build/fp

默认抓两个 P0-B 锚点周围各 512 块的窗口。

输出：<out>/report.txt（UTF-8，给人看）、<out>/report.json（给程序看）、
<out>/NEXT-STEPS.txt（还缺什么信息）。

跑之前请确保：
  1) run/config/geogenesis-common.toml 里 worldgenProfilerEnabled = true
     （worldgenProfilerEveryChunks 建议 50；该 profiler 零开销、产出逐位不变）
  2) 进世界后走到目标区域，让窗口内所有区块都生成过一遍
  3) 正常退出游戏（日志才会落盘）
"""

import argparse
import gzip
import io
import json
import os
import re
import statistics
import sys
import time

REGION = 512       # ModelHydrologyProvider.REGION
HALO = 96          # ModelHydrologyProvider.HALO（窗口原点 = region*REGION - HALO）
DEFAULT_CENTERS = [(294, -928), (-1436, -416)]
DEFAULT_SIZE = 512

RE_BOUNDARY = re.compile(
    r"\[HYDRO-BOUNDARY\] origin\((-?\d+),(-?\d+)\) role=(\w+) roleConflict=(\w+) ports=(\d+)"
    r" anchorExact=(\d+) pendingExits=(\d+) wiredIncoming=(\d+) wireRejects=(\d+)"
    r" mergedIncoming=(\d+) mergeEvidence=(\d+) unsatisfied=(\d+)")
RE_SKELETON = re.compile(
    r"\[HYDRO-SKELETON\] traces=(\d+) sources=(\d+) droppedBySpacing=(\d+) outletStarts=(\d+)"
    r" incomingStarts=(\d+) ends: lake=(\d+) sea=(\d+) join=(\d+) unresolved=(\d+)"
    r" DEAD_END=(\d+) BOUNDARY_EXIT=(\d+) exitSeeds=(\d+) ports=(\d+)")
RE_MODEL = re.compile(
    r"\[HYDRO-MODEL\] region \((-?\d+),(-?\d+)\) solved in (\d+) ms \(model=(\w+), hier=(\w+),"
    r" grid=(\S+), reaches=(\d+), lake=(\d+), channel=(\d+), margin=(\d+)ms,"
    r" resolved=(\d+)/(\d+) basins=(\d+) dry=(\d+)\)")
RE_REJECT = re.compile(
    r"\[HYDRO-WIRE-REJECT\] reject\[(\w+) cell=(\d+) anchor=\((-?\d+),(-?\d+)\)\]")
RE_ENC = re.compile(r"\[HYDRO-WIRE-(MERGE|UNSATISFIED)\] anchor=(\d+) next=(-?\d+) (\S+) steps=(\d+)")
RE_RECEIVER = re.compile(
    r"\[HYDRO-RECEIVER\] region\((-?\d+),(-?\d+)\) (-?\d+),(-?\d+)@([^:\s]+):(\S*):(\d+):mouth=(-?\d+),(-?\d+)")
RE_PERF = re.compile(
    r"\[PERF-TERRAIN\] chunk\((-?\d+),(-?\d+)\): sample=(\d+)ms extract=(\d+)ms"
    r" hydro=(\d+)ms total=(\d+)ms")
RE_PROCESS = re.compile(r"\[PROCESS\] 物理侵蚀运行时就绪 mode=(\w+).*?identity 前缀=(\S+)")
RE_STAMP = re.compile(r"^\[(\d{2})(\d{2})月(\d{4}) (\d{2}):(\d{2}):(\d{2})")


def read_lines(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as fh:
        return fh.read().splitlines()


def find_logs(logs_dir, include_gz):
    if not os.path.isdir(logs_dir):
        return []
    out = []
    for name in sorted(os.listdir(logs_dir)):
        if not name.endswith(".log") and not (include_gz and name.endswith(".log.gz")):
            continue
        p = os.path.join(logs_dir, name)
        if os.path.isfile(p):
            out.append(p)
    return out


def floor_div(a, b):
    return a // b


def inside_any(x, z, centers, size):
    for cx, cz in centers:
        if abs(x - cx) <= size and abs(z - cz) <= size:
            return True
    return False


def pct(values, q):
    if not values:
        return None
    s = sorted(values)
    idx = min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))
    return s[idx]


def summarize(values):
    if not values:
        return None
    return {
        "count": len(values),
        "median": statistics.median(values),
        "p90": pct(values, 0.90),
        "p99": pct(values, 0.99),
        "max": max(values),
    }


def parse(path, centers, size):
    lines = read_lines(path)
    ev = {
        "file": path,
        "lines": len(lines),
        "first_stamp": None,
        "last_stamp": None,
        "process": [],
        "boundary": [],
        "skeleton": [],
        "model": [],
        "reject": [],
        "encode": [],
        "receiver": [],
        "perf": [],
        "wgp": [],
        "wgp_enabled": None,
    }
    pending_skeleton = None
    for i, line in enumerate(lines, 1):
        m = RE_STAMP.match(line)
        if m:
            stamp = "%s-%s-%s %s:%s:%s" % (m.group(3), m.group(2), m.group(1),
                                           m.group(4), m.group(5), m.group(6))
            if ev["first_stamp"] is None:
                ev["first_stamp"] = stamp
            ev["last_stamp"] = stamp

        m = RE_PROCESS.search(line)
        if m:
            ev["process"].append({"line": i, "mode": m.group(1), "identity": m.group(2)})

        m = RE_BOUNDARY.search(line)
        if m:
            ox, oz = int(m.group(1)), int(m.group(2))
            rx, rz = floor_div(ox + HALO, REGION), floor_div(oz + HALO, REGION)
            rec = {
                "line": i, "origin": [ox, oz], "region": [rx, rz],
                "role": m.group(3), "roleConflict": m.group(4) == "true",
                "ports": int(m.group(5)), "anchorExact": int(m.group(6)),
                "pendingExits": int(m.group(7)), "wiredIncoming": int(m.group(8)),
                "wireRejects": int(m.group(9)), "mergedIncoming": int(m.group(10)),
                "mergeEvidence": int(m.group(11)), "unsatisfied": int(m.group(12)),
            }
            if pending_skeleton is not None and pending_skeleton[0] >= i - 8 \
                    and pending_skeleton[1]["ports"] == rec["ports"]:
                rec["skeleton"] = pending_skeleton[1]
                pending_skeleton = None
            if inside_any(ox + REGION // 2, oz + REGION // 2, centers, size):
                ev["boundary"].append(rec)
            continue

        m = RE_SKELETON.search(line)
        if m:
            sk = {
                "traces": int(m.group(1)), "sources": int(m.group(2)),
                "incomingStarts": int(m.group(5)),
                "ends": {
                    "lake": int(m.group(6)), "sea": int(m.group(7)),
                    "join": int(m.group(8)), "unresolved": int(m.group(9)),
                    "dead_end": int(m.group(10)), "boundary_exit": int(m.group(11)),
                },
                "ports": int(m.group(13)),
            }
            pending_skeleton = (i, sk)
            ev["skeleton"].append({"line": i, **sk})
            continue

        m = RE_MODEL.search(line)
        if m:
            rx, rz = int(m.group(1)), int(m.group(2))
            rec = {
                "line": i, "region": [rx, rz], "ms": int(m.group(3)),
                "model": m.group(4), "grid": m.group(6), "reaches": int(m.group(7)),
                "lake": int(m.group(8)), "channel": int(m.group(9)),
                "resolved": "%s/%s" % (m.group(11), m.group(12)),
                "basins": int(m.group(13)), "dry": int(m.group(14)),
            }
            if inside_any(rx * REGION + REGION // 2, rz * REGION + REGION // 2, centers, size):
                ev["model"].append(rec)
            continue

        m = RE_REJECT.search(line)
        if m:
            ax, az = int(m.group(3)), int(m.group(4))
            if inside_any(ax, az, centers, size):
                ev["reject"].append({"line": i, "status": m.group(1),
                                     "cell": int(m.group(2)), "anchor": [ax, az]})
            continue

        m = RE_ENC.search(line)
        if m:
            ev["encode"].append({"line": i, "kind": m.group(1), "anchorCell": int(m.group(2)),
                                 "next": int(m.group(3)), "resolution": m.group(4),
                                 "steps": int(m.group(5))})
            continue

        m = RE_RECEIVER.search(line)
        if m:
            rx, rz = int(m.group(1)), int(m.group(2))
            ax, az = int(m.group(3)), int(m.group(4))
            if inside_any(ax, az, centers, size):
                ev["receiver"].append({"line": i, "region": [rx, rz], "anchor": [ax, az],
                                       "status": m.group(5), "end": m.group(6) or None,
                                       "nodes": int(m.group(7)),
                                       "mouth": [int(m.group(8)), int(m.group(9))]})
            continue

        m = RE_PERF.search(line)
        if m:
            ev["perf"].append({"line": i, "chunk": [int(m.group(1)), int(m.group(2))],
                               "sample": int(m.group(3)), "extract": int(m.group(4)),
                               "hydro": int(m.group(5)), "total": int(m.group(6))})
            continue

        if "[WGP]" in line:
            ev["wgp"].append(line.strip())
            if "已启用" in line:
                ev["wgp_enabled"] = True
            elif "已关闭" in line:
                ev["wgp_enabled"] = False
    return ev


def config_snapshot(mdk_dir):
    toml = os.path.join(mdk_dir, "run", "config", "geogenesis-common.toml")
    keys = ("erosionEnabled", "erosionStrength", "erosionXSEnabled", "riverEnabled",
            "hydrologyModelPipelineEnabled", "horizontalScale", "seaLevel",
            "worldgenProfilerEnabled", "worldgenProfilerEveryChunks")
    out = {}
    if not os.path.isfile(toml):
        return {"path": toml, "exists": False}
    pat = re.compile(r"^\s*(%s)\s*=\s*(.+?)\s*$" % "|".join(keys))
    with io.open(toml, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = pat.match(line)
            if m:
                out[m.group(1)] = m.group(2)
    return {"path": toml, "exists": True, "values": out}


def main(argv):
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--center", action="append", default=None,
                    help="x,z 可重复，默认两个 P0-B 锚点")
    ap.add_argument("--size", type=int, default=DEFAULT_SIZE, help="窗口半宽（块），默认 512")
    ap.add_argument("--logs", default=None, help="日志目录，默认 <mdk>/run/logs")
    ap.add_argument("--out", default=None, help="输出目录，默认 build/fixedpoint-<时间>")
    ap.add_argument("--include-gz", action="store_true", help="把历史 debug-*.log.gz 也解析")
    args = ap.parse_args(argv)

    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    mdk = os.path.join(root, "forge-1.20.1-47.4.10-mdk")
    logs_dir = args.logs or os.path.join(mdk, "run", "logs")

    centers = []
    if args.center:
        for c in args.center:
            parts = c.split(",")
            centers.append((int(parts[0]), int(parts[1])))
    else:
        centers = list(DEFAULT_CENTERS)

    out_dir = args.out or os.path.join(root, "build",
                                       "fixedpoint-" + time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out_dir, exist_ok=True)

    files = find_logs(logs_dir, args.include_gz)
    if not files:
        sys.stderr.write("没找到日志：%s\n" % logs_dir)
        return 2

    parsed = [parse(p, centers, args.size) for p in files]

    report = {
        "generatedFrom": logs_dir,
        "windows": [{"center": list(c), "size": args.size} for c in centers],
        "regionBlocks": REGION,
        "halo": HALO,
        "config": config_snapshot(mdk),
        "logs": [],
    }
    lines = []
    add = lines.append

    add("=== 定点取证报告 ===")
    add("日志目录: %s" % logs_dir)
    for c in centers:
        add("窗口: 中心(%d,%d) 半宽 %d 块（即 %dx%d 覆盖）"
            % (c[0], c[1], args.size, args.size * 2, args.size * 2))
    add("REGION=%d HALO=%d（窗口原点 = region*REGION - HALO）" % (REGION, HALO))
    add("")

    cfg = report["config"]
    add("--- 配置快照（只读）---")
    if cfg.get("exists"):
        for k, v in sorted(cfg["values"].items()):
            add("  %-32s = %s" % (k, v))
        missing = [k for k in ("worldgenProfilerEnabled",) if k not in cfg["values"]]
        if missing:
            add("  ⚠ 未找到 %s —— profiler 可能没开，性能样本会是空的" % ", ".join(missing))
    else:
        add("  ⚠ 找不到 %s" % cfg["path"])
    add("")

    total_perf = []
    total_model = []
    zero_trace_regions = []

    for ev in parsed:
        add("=== 日志文件: %s（%d 行）===" % (os.path.basename(ev["file"]), ev["lines"]))
        if ev["first_stamp"]:
            add("  时间范围: %s .. %s" % (ev["first_stamp"], ev["last_stamp"]))
        for p in ev["process"][:3]:
            add("  [PROCESS] mode=%s identity=%s" % (p["mode"], p["identity"]))
        if ev["process"]:
            modes = sorted({p["mode"] for p in ev["process"]})
            add("  → 侵蚀模式集合: %s%s" % (",".join(modes),
                                          "" if modes == ["PARTICLE"] else "  ⚠ 非全 PARTICLE"))
        add("  WGP profiler: %s" % ("已启用" if ev["wgp_enabled"] else
                                    ("已关闭（性能样本将只有 PERF-TERRAIN 慢块）"
                                     if ev["wgp_enabled"] is False else "未知")))
        add("")

        add("  --- 窗口内的 region 摘要 ---")
        if not ev["boundary"] and not ev["model"]:
            add("    （窗口内没有任何 region 记录 —— 该区域可能没被生成过）")
        for b in ev["boundary"]:
            sk = b.get("skeleton")
            add("    region(%d,%d) origin(%d,%d) role=%s ports=%d wiredIn=%d rejects=%d"
                " merges=%d unsatisfied=%d"
                % (b["region"][0], b["region"][1], b["origin"][0], b["origin"][1],
                   b["role"], b["ports"], b["wiredIncoming"], b["wireRejects"],
                   b["mergeEvidence"], b["unsatisfied"]))
            if sk:
                add("        skeleton: traces=%d ports=%d incomingStarts=%d"
                    " ends(lake=%d sea=%d join=%d unresolved=%d port=%d)"
                    % (sk["traces"], sk["ports"], sk["incomingStarts"],
                       sk["ends"]["lake"], sk["ends"]["sea"], sk["ends"]["join"],
                       sk["ends"]["unresolved"], sk["ends"]["boundary_exit"]))
                if sk["traces"] == 0:
                    zero_trace_regions.append({"region": b["region"], "log": ev["file"]})
        add("")

        add("  --- HYDRO-SKELETON 记录（全文件，line 顺序）---")
        add("      注意：并行线程会交错打印，region 归属以上面的 HYDRO-BOUNDARY 行为准；")
        add("      这里只按行序列出，便于和 origin 行按时间对齐。")
        for sk in ev["skeleton"][:60]:
            add("    line %-6d traces=%-3d ports=%-3d incomingStarts=%-3d"
                " ends(lake=%d sea=%d join=%d unresolved=%d port=%d)"
                % (sk["line"], sk["traces"], sk["ports"], sk["incomingStarts"],
                   sk["ends"]["lake"], sk["ends"]["sea"], sk["ends"]["join"],
                   sk["ends"]["unresolved"], sk["ends"]["boundary_exit"]))
        if not ev["skeleton"]:
            add("    （无）")
        add("")

        add("  --- HYDRO-MODEL 求解（窗口内）---")
        for m in ev["model"]:
            add("    region(%d,%d) %dms reaches=%d lake=%d channel=%d resolved=%s basins=%d dry=%d grid=%s"
                % (m["region"][0], m["region"][1], m["ms"], m["reaches"], m["lake"],
                   m["channel"], m["resolved"], m["basins"], m["dry"], m["grid"]))
            total_model.append(m["ms"])
        if not ev["model"]:
            add("    （无）")
        add("")

        add("  --- 端口拒收（窗口内）---")
        for r in ev["reject"]:
            add("    line %d: %s cell=%d anchor=(%d,%d)"
                % (r["line"], r["status"], r["cell"], r["anchor"][0], r["anchor"][1]))
        if not ev["reject"]:
            add("    （无）")
        add("")

        add("  --- 合并/未兑现已证（窗口内 anchoring）---")
        shown = 0
        anchors = {(r["anchor"][0], r["anchor"][1]) for r in ev["reject"]}
        if not anchors:
            anchors = {(r["anchor"][0], r["anchor"][1]) for r in ev["receiver"]
                       if r["status"].startswith("BASE_")}
        for e in ev["encode"]:
            shown += 1
            add("    line %d: %-12s anchor=cell%-7d next=%-7d %s steps=%d"
                % (e["line"], e["kind"], e["anchorCell"], e["next"], e["resolution"], e["steps"]))
            if shown >= 40:
                add("    …（超过 40 条，其余见 report.json）")
                break
        if shown == 0:
            add("    （无）")
        add("")

        add("  --- 接收侧裁定（窗口内）---")
        for r in ev["receiver"][:60]:
            add("    line %d: region(%d,%d) anchor=(%d,%d) %s end=%s nodes=%d mouth=(%d,%d)"
                % (r["line"], r["region"][0], r["region"][1], r["anchor"][0], r["anchor"][1],
                   r["status"], r["end"], r["nodes"], r["mouth"][0], r["mouth"][1]))
        if not ev["receiver"]:
            add("    （无）")
        add("")

        if ev["perf"]:
            add("  --- 性能：[PERF-TERRAIN]（仅 >50ms 的块才会打印）---")
            add("    数量=%d" % len(ev["perf"]))
            for key in ("sample", "extract", "hydro", "total"):
                vals = [p[key] for p in ev["perf"]]
                s = summarize(vals)
                add("    %-8s 中位=%sms p90=%sms max=%sms"
                    % (key, s["median"], s["p90"], s["max"]))
            total_perf.extend([p["total"] for p in ev["perf"]])
            add("")
        if ev["wgp"]:
            add("  --- [WGP] 全流程诊断（原文，最多 40 行）---")
            for line in ev["wgp"][:40]:
                add("    " + line)
            if len(ev["wgp"]) > 40:
                add("    …（共 %d 行，全部见 report.json）" % len(ev["wgp"]))
            add("")

        report["logs"].append(ev)

    add("=== 汇总 ===")
    if total_model:
        s = summarize(total_model)
        add("  region 求解耗时: 次数=%d 中位=%sms p90=%sms p99=%sms max=%sms"
            % (s["count"], s["median"], s["p90"], s["p99"], s["max"]))
    if total_perf:
        s = summarize(total_perf)
        add("  [PERF-TERRAIN] total: 次数=%d 中位=%sms p90=%sms max=%sms"
            % (s["count"], s["median"], s["p90"], s["max"]))
    if zero_trace_regions:
        uniq = sorted({tuple(z["region"]) for z in zero_trace_regions})
        add("  ⚠ traces=0 的 region（整区无河，重点看这些）: %s"
            % ", ".join("(%d,%d)" % r for r in uniq))
    else:
        add("  traces=0 的 region: 无")
    add("")

    add("=== 判读须知 ===")
    add("  · traces=0 是连通性过滤的结果：该区没有任何 trace 能到 MOUTH_LAKE/MOUTH_SEA/PORT。")
    add("  · 端口被拒（BASE_JOIN_WITHOUT_DOWNSTREAM 等）不会注水，所以水面干净、看不出破绽；")
    add("    代价是那一段河消失。要确认「消失」是否可见，需要你的截图/描述对位。")
    add("  · [PERF-TERRAIN] 只在 >50ms 时打印，不是全量；全量要看 [WGP]（需开 profiler）。")

    with io.open(os.path.join(out_dir, "report.txt"), "w", encoding="utf-8", newline="") as fh:
        fh.write("\n".join(lines) + "\n")
    with io.open(os.path.join(out_dir, "report.json"), "w", encoding="utf-8", newline="") as fh:
        fh.write(json.dumps(report, ensure_ascii=False, indent=2))

    steps = u"""还需要的两样东西（脚本抓不到）

1. 你实际看到的画面
   - 在窗口内某个位置截一张图（F2 会存到 run/screenshots/），或直接描述：
     「这一带有河 / 没河 / 河断在某处」。
   - 最好能和日志里的 region 对上：站到 region 中心附近再看一眼。
     region 中心块坐标 = rx*512+256, rz*512+256。

2. 日志本身
   - 把 forge-1.20.1-47.4.10-mdk/run/logs/latest.log 给我；
     若有 debug.log 也一起（更全）。整个 run/logs 目录打包更好。

3. 如果 profiler 没开（本脚本会在报告里标出来）
   - 改 run/config/geogenesis-common.toml：
       worldgenProfilerEnabled = true
       worldgenProfilerEveryChunks = 50
   - 该 profiler 零开销、产出逐位不变；重进世界一次即可。
   - 不开也能跑：只是性能样本只剩 [PERF-TERRAIN] 的 >50ms 慢块。
"""
    with io.open(os.path.join(out_dir, "NEXT-STEPS.txt"), "w", encoding="utf-8", newline="") as fh:
        fh.write(steps)

    sys.stdout.write("报告已写入: %s\n" % out_dir)
    sys.stdout.write("  report.txt / report.json / NEXT-STEPS.txt\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))