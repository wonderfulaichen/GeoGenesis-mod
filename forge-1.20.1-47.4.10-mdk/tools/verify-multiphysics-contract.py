"""Compile isolated contract copies; each semantic mutation must compile then fail its assertion."""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "com.geogenesis.worldgen.hydrology.model."
SOURCE = ROOT / "src/diagnostics/java/com/geogenesis/worldgen/hydrology/model"
NAMES = ["TraceBedField", "TracePathContract", "TraceSpillWitness", "TracePhysicsContract",
         "TraceSharedStateContract", "TraceSharedStateChecks", "TracePathOracleChecks", "MultiphysicsContractProbe"]
MUTATIONS = [
    ("gradient-units", "TraceBedField", "slopes[3], tz) / step", "slopes[3], tz)", "plane dx wu"),
    ("skip-dda", "TracePathContract", "if (next == 1) {", "if (true) {", "corner contacts explicit"),
    ("false-join", "TracePathContract", "if (t < 0 || t > 1 || u < 0 || u > 1) continue;",
     "if (t < 0 || t > 1) continue;", "no false join"),
    ("late-overwrite", "TraceSharedStateContract", "if (before != null && !before.equals(reach))",
     "if (false)", "late conflict not overwrite"),
    ("energy-injection", "TracePhysicsContract", "head - loss - height", "head + loss - height",
     "discrete energy budget"),
]


def run(command, log):
    p = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log.write_bytes(p.stdout)
    return p.returncode, p.stdout.decode("utf-8", errors="replace")


def variant(out, name, mutation=None):
    folder = out / name
    sources, classes = folder / "src", folder / "classes"
    sources.mkdir(parents=True, exist_ok=True)
    classes.mkdir(exist_ok=True)
    for symbol in NAMES:
        text = (SOURCE / (symbol + ".java")).read_text(encoding="utf-8-sig")
        if mutation and symbol == mutation[1]:
            if text.count(mutation[2]) != 1:
                raise AssertionError("mutation target not unique: " + name)
            text = text.replace(mutation[2], mutation[3], 1)
        (sources / (symbol + ".java")).write_text(text, encoding="utf-8")
    command = ["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(classes)]
    code, output = run(command + [str(p) for p in sorted(sources.glob("*.java"))], folder / "compile.log")
    if code:
        raise AssertionError("compile failure is NOT a detected mutation: " + name + "\n" + output)
    code, output = run(["java", "-cp", str(classes), PACKAGE + "MultiphysicsContractProbe"], folder / "run.log")
    if mutation:
        if code == 0 or "AssertionError" not in output or mutation[4] not in output:
            raise AssertionError("mutation not caught by named assertion: " + name + "\n" + output)
    elif code != 0 or "PASS multiphysics-contract" not in output:
        raise AssertionError("baseline failed: " + output)
    return name + ": compile=0 run=" + str(code) + " expected=" + (mutation[4] if mutation else "PASS")


def main():
    out = ROOT / "build/multiphysics-contract/mutations"
    out.mkdir(parents=True, exist_ok=True)
    report = [variant(out, "baseline")]
    for mutation in MUTATIONS:
        report.append(variant(out, mutation[0], mutation))
    a = (out / "baseline/run.log").read_bytes()
    code, _ = run(["java", "-cp", str(out / "baseline/classes"), PACKAGE + "MultiphysicsContractProbe"], out / "cold.log")
    if code or a != (out / "cold.log").read_bytes():
        raise AssertionError("cold JVM evidence differs")
    report.append("cold JVM output: byte-identical")
    (out / "summary.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))


if __name__ == "__main__":
    main()
