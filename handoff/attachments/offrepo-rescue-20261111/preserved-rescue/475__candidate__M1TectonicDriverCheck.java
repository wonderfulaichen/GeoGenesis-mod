package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.TectonicDeformation;
import com.geogenesis.worldgen.terrain.TectonicField;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** M1b bounded driver check. Never changes production, parameters, or the S2/S3 world. */
public final class M1TectonicDriverCheck {
    private static final long[] SEEDS = {9139912035078620160L, 0L, -419L, 12345L};
    private static final long SEED_SALT = 0x9E3779B97F4A7C15L;
    private static final long VELOCITY_SALT = 0xBF58476D1CE4E5B9L;
    private static final long WARP_SALT = 0x2C6EF1A384BD9075L;
    private static final double SPACING = 2000.0, JITTER = 0.7, SIGMA = 1000.0;
    private static final double FORMULA_TOL = 1e-12, DIST_TOL = 1e-9, ROOT_TOL = 4e-12;
    private static final int LEVELS = 21;
    private static final BigInteger MOD = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger MASK24 = BigInteger.valueOf(0xFFFFFFL);
    private final Table assertions = new Table("assertions.csv", "seed,case,check,status,detail");
    private final Table sites = new Table("sites.csv", "seed,cx,cz,seedHash,velocityHash,sx,sz,vx,vz");
    private final Table raw = new Table("raw.csv", "seed,case,level,side,h,qx,qz,actual,ref25,ref81,ref169,num25,den25,baseX,baseZ");
    private final Table limits = new Table("limits.csv", "seed,case,qx,qz,centerActual,minusExteriorBranchLimit,plusExteriorBranchLimit,exteriorBranchGap,centerGap");
    private final Table cutoff = new Table("cutoff.csv", "seed,case,side,radius,nominalX,nominalZ,insideLimit,outsideLimit,jump,actualInside,actualOutside");
    private final Table world = new Table("world.csv", "seed,case,level,side,h,targetX,targetZ,x,z,warpedX,warpedZ,residual,rawAtTarget,stress,refStress,dist,refDist,rawDist,ring0,ring1,ring2,ring3,blurWeight,maxSearchError,minSearchMargin,btype,rate,tangentX,tangentZ,alongCoord,discrete015,gate015sigma130,cw,dw,belt,offset,arcVolcanism");
    private final Table shrink = new Table("shrink.csv", "seed,case,path,channel,level,h,minus,center,plus,c0Gap,c1SlopeGap,gapOver2h,previousGapRatio");
    private final Table smooth = new Table("smooth-pos.csv", "v,eps,actual,independent,negative,discrete015");
    private final List<Table> tables = List.of(assertions, sites, raw, limits, cutoff, world, shrink, smooth);
    private int checks, failures, points, inverseFailures;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: M1TectonicDriverCheck outputDirectory");
        Path out = Path.of(args[0]);
        Files.createDirectory(out); // Existing paths are rejected; no overwrite or recursive deletion.
        M1TectonicDriverCheck check = new M1TectonicDriverCheck();
        try {
            check.run();
        } catch (Throwable t) {
            check.test(false, 0, "setup", "unexpected", t.toString());
            t.printStackTrace();
        } finally {
            check.save(out);
        }
        System.out.println("M1b " + (check.failures == 0 ? "PASS" : "FAIL")
                + " checks=" + check.checks + " failures=" + check.failures + " out=" + out);
        if (check.failures != 0) System.exit(1);
    }

    private void run() throws Exception {
        observeSmoothPos();
        List<Driver> drivers = new ArrayList<>();
        for (long seed : SEEDS) {
            Driver driver = new Driver(seed);
            drivers.add(driver);
            driver.fixtures();
            driver.prepare();
        }
        // Deliberately finish the independent/raw stage before any public sample evidence.
        for (Driver driver : drivers) for (Case c : driver.cases) driver.rawCase(c);
        for (Driver driver : drivers) for (Case c : driver.cases) driver.worldCase(c);
        test(points > 0, 0, "coverage", "non-vacuous", "worldPoints=" + points);
        test(OceanMorphConcepts.SAT_STRESS == 0.15, 0, "constants", "gate-saturation", "must remain 0.15");
        test(OceanMorphConcepts.RIDGE_BAND_SIGMA == 130.0, 0, "constants", "gate-sigma", "must remain 130");
    }

    private void observeSmoothPos() {
        for (double v : new double[]{-1, -.18, -.15, -.09, -.01, -1e-6, 0, 1e-6, .01, .09, .15, .18, 1}) {
            double got = TectonicField.smoothPos(v, .18);
            double t = Math.min(1, Math.max(0, (v + .18) / .36));
            double expected = v * t * t * (3 - 2 * t);
            smooth.add(v, .18, got, expected, got < 0, OceanMorphConcepts.discreteStrength(v));
            test(close(got, expected, FORMULA_TOL), 0, "smoothPos", "formula", "v=" + v);
        }
        assertions.add(0, "smoothPos", "nonnegativity", "OBSERVE",
                "Negative output on (-0.18;0) is recorded, not silently clamped or declared nonnegative");
    }

    private void test(boolean ok, long seed, String c, String name, String detail) {
        checks++;
        if (!ok) failures++;
        assertions.add(seed, c, name, ok ? "PASS" : "FAIL", detail);
    }

    private void save(Path out) throws IOException {
        for (Table table : tables) Files.write(out.resolve(table.name), table.rows,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        String status = failures == 0 ? "PASS" : "FAIL";
        Files.writeString(out.resolve("check.txt"), "M1b " + status + "\nchecks=" + checks
                + "\nfailures=" + failures + "\nworldPoints=" + points + "\ninverseFailures=" + inverseFailures
                + "\nseeds=9139912035078620160,0,-419,12345\n"
                + "Finite preset only: 3x3 sites; axes at -2000/0/2000 with six fixed ordinates; 3x3 corners; eight local bisectors.\n"
                + "h=2^-k wu, k=0..20; no random scan; no production or S2/S3 modification.\n"
                + "Independent unsigned BigInteger hash, sites, velocities, Catmull-Rom warp and stress formula.\n"
                + "Reflection observes private fields/functions; valueNoise is called ONLY inside inverse localization.\n"
                + "Raw ref81/ref169 are expanded-window observations, NOT expected equality to production ref25.\n"
                + "Distance oracle compares original 3x3 search with certified 9x9 search at center and four ring points.\n"
                + "C0 requires tail contraction <=0.75 each halving or numerical zero; C1 checks bisector distance slopes.\n"
                + "Grid one-sided limits and cutoff-disk boundary limits are independently checked; masking cannot excuse raw failures.\n"
                + "Site h>=2^-20 probes stay OUTSIDE d<1e-9: noncontraction is finite-scale evidence, not a false h->0 limit at the disk center.\n"
                + "limits.csv site rows are limits of the exterior singular branch; cutoff.csv tests the actual 1e-9 zero-disk discontinuity.\n"
                + "Bisector normal brackets additionally locate raw distance 420 within fixed 0..1200; missing brackets are explicitly observed.\n"
                + "Floating tolerances are arithmetic floors, not visual/amplitude allowances. All failed observations retained.\n"
                + "Finite PASS is not a global continuity proof. This check does not claim full S2/S3 or Minecraft output coverage.\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private record Case(String id, String kind, double x, double z, double dx, double dz, Site site) { }
    private record Site(int cx, int cz, double x, double z, double vx, double vz) { }
    private record Vote(double value, double numerator, double denominator) { }
    private record Nearest(double first, double second, int ax, int az, int bx, int bz, double outside) { }
    private record Distance(double raw, double[] ring, double weight, double result, double error, double margin) { }
    private record Point(double stress, double dist, double discrete, double gate, double offset, double x, double z) { }
    private record Inverse(double x, double z, double residual) { }

    private final class Driver {
        private final long seed;
        private final TectonicField tf;
        private final TectonicDeformation td;
        private final Method stressMethod, noiseMethod, hashMethod, siteMethod, velocityMethod;
        private final Map<Long, Site> siteCache = new HashMap<>();
        private final List<Case> cases = new ArrayList<>();

        Driver(long seed) throws Exception {
            this.seed = seed;
            tf = new TectonicField(seed);
            td = new TectonicDeformation(seed);
            stressMethod = method("stressField", double.class, double.class);
            noiseMethod = method("valueNoise", double.class, double.class, long.class);
            hashMethod = method("hash", int.class, int.class, long.class);
            siteMethod = method("plateSeed", int.class, int.class, double[].class);
            velocityMethod = method("plateVelocity", int.class, int.class, double[].class);
        }

        private void fixtures() throws Exception {
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                Site s = site(x, z);
                double[] actualSite = new double[2], actualVelocity = new double[2];
                siteMethod.invoke(tf, x, z, actualSite);
                velocityMethod.invoke(tf, x, z, actualVelocity);
                long hs = hash(x, z, SEED_SALT), hv = hash(x, z, VELOCITY_SALT);
                sites.add(seed, x, z, Long.toUnsignedString(hs, 16), Long.toUnsignedString(hv, 16),
                        s.x, s.z, s.vx, s.vz);
                test((long) hashMethod.invoke(tf, x, z, SEED_SALT) == hs
                        && (long) hashMethod.invoke(tf, x, z, VELOCITY_SALT) == hv,
                        seed, "fixture-" + x + "-" + z, "unsigned-hash", "BigInteger modulo 2^64");
                test(close(actualSite[0], s.x, DIST_TOL) && close(actualSite[1], s.z, DIST_TOL)
                        && close(actualVelocity[0], s.vx, FORMULA_TOL)
                        && close(actualVelocity[1], s.vz, FORMULA_TOL),
                        seed, "fixture-" + x + "-" + z, "site-velocity", "independent hash and construction");
            }
        }

        private void prepare() {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                Site s = site(x, z);
                double mag = Math.hypot(s.vx, s.vz);
                cases.add(new Case("site-" + x + "-" + z, "site", s.x, s.z, s.vx / mag, s.vz / mag, s));
                double n = Math.sqrt(.5);
                cases.add(new Case("corner-" + x + "-" + z, "window", x * SPACING, z * SPACING, n, n, null));
            }
            for (int k = -1; k <= 1; k++) for (double t : new double[]{-1700, -1000, -300, 300, 1000, 1700}) {
                cases.add(new Case("window-x-" + k + "-" + t, "window", k * SPACING, t, 1, 0, null));
                cases.add(new Case("window-z-" + k + "-" + t, "window", t, k * SPACING, 0, 1, null));
            }
            prepareBisectors();
        }

        private void prepareBisectors() {
            Site a = site(0, 0);
            int count = 0;
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                Case c = bisector(a, site(x, z));
                if (c != null) {
                    cases.add(c); count++;
                    prepareReach(c);
                } else assertions.add(seed, "bisector-" + x + "-" + z, "fixed-pair", "OBSERVE", "No edge inside fixed +/-1000 tangent interval");
            }
            test(count > 0, seed, "bisectors", "non-vacuous", "activeFixedPairs=" + count);
        }

        private void prepareReach(Case edge) {
            double low = 0, high = Double.NaN;
            for (double t : new double[]{300, 600, 900, 1200}) {
                Nearest n = nearest(edge.x - edge.dx * t, edge.z - edge.dz * t, 4);
                if ((n.second - n.first) * .5 >= 420) { high = t; break; }
                low = t;
            }
            if (!Double.isFinite(high)) {
                assertions.add(seed, edge.id, "reach420", "OBSERVE", "No crossing in fixed 0..1200 normal interval");
                return;
            }
            for (int i = 0; i < 64; i++) {
                double mid = (low + high) * .5;
                Nearest n = nearest(edge.x - edge.dx * mid, edge.z - edge.dz * mid, 4);
                if ((n.second - n.first) * .5 >= 420) high = mid; else low = mid;
            }
            double t = (low + high) * .5;
            cases.add(new Case(edge.id + "-reach420", "reach", edge.x - edge.dx * t,
                    edge.z - edge.dz * t, edge.dx, edge.dz, null));
        }

        private Case bisector(Site a, Site b) {
            double mx = (a.x + b.x) * .5, mz = (a.z + b.z) * .5;
            double len = Math.hypot(b.x - a.x, b.z - a.z);
            double nx = (b.x - a.x) / len, nz = (b.z - a.z) / len;
            double tx = -nz, tz = nx, low = -1000, high = 1000;
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                if ((x == a.cx && z == a.cz) || (x == b.cx && z == b.cz)) continue;
                Site s = site(x, z);
                double dx = s.x - a.x, dz = s.z - a.z;
                double rhs = (dx * dx + dz * dz) * .5 - (mx - a.x) * dx - (mz - a.z) * dz;
                double coeff = tx * dx + tz * dz;
                if (Math.abs(coeff) < 1e-10) { if (rhs < 0) return null; }
                else if (coeff > 0) high = Math.min(high, rhs / coeff);
                else low = Math.max(low, rhs / coeff);
            }
            if (high - low <= 2.0) return null;
            double t = (low + high) * .5, qx = mx + tx * t, qz = mz + tz * t;
            Nearest nearest = nearest(qx, qz, 4);
            test(Math.abs(nearest.second - nearest.first) < 1e-8 && nearest.second < nearest.outside,
                    seed, "bisector-" + b.cx + "-" + b.cz, "certified-edge", "d1=" + nearest.first + ";d2=" + nearest.second);
            return new Case("bisector-" + b.cx + "-" + b.cz, "blur", qx, qz, nx, nz, null);
        }

        private void rawCase(Case c) {
            double[][] values = new double[LEVELS][2];
            double center = rawPoint(c, -1, 0, 0);
            analyticLimits(c, center);
            if (c.site != null) cutoffLimits(c);
            for (int k = 0; k < LEVELS; k++) {
                double h = Math.scalb(1.0, -k);
                values[k][0] = rawPoint(c, k, -1, h);
                values[k][1] = rawPoint(c, k, 1, h);
            }
            series(c, "private-stressField", "stress", center, values, false);
        }

        private double rawPoint(Case c, int level, int side, double h) {
            double x = c.x + side * h * c.dx, z = c.z + side * h * c.dz;
            try {
                Vote reference = vote(x, z, 2);
                double actual = actualStress(x, z);
                raw.add(seed, c.id, level, side, h, x, z, actual, reference.value,
                        vote(x, z, 4).value, vote(x, z, 6).value, reference.numerator,
                        reference.denominator, floorCell(x), floorCell(z));
                test(close(actual, reference.value, FORMULA_TOL), seed, c.id, "raw-formula-" + level + "-" + side,
                        "actual=" + actual + ";ref25=" + reference.value);
                return actual;
            } catch (Throwable t) {
                test(false, seed, c.id, "raw-exception-" + level + "-" + side, t.toString());
                return Double.NaN;
            }
        }

        private void analyticLimits(Case c, double center) {
            try {
                double minus, plus;
                if (c.site != null) {
                    Vote excluded = voteExcluding(c.x, c.z, c.site);
                    double mag = Math.hypot(c.site.vx, c.site.vz);
                    minus = clamp(2 * (excluded.numerator - mag) / (excluded.denominator + 1));
                    plus = clamp(2 * (excluded.numerator + mag) / (excluded.denominator + 1));
                } else if (c.kind.equals("window")) {
                    minus = voteAtBase(c.x, c.z, 2, floorCell(c.x - .1 * c.dx), floorCell(c.z - .1 * c.dz), null).value;
                    plus = voteAtBase(c.x, c.z, 2, floorCell(c.x + .1 * c.dx), floorCell(c.z + .1 * c.dz), null).value;
                } else {
                    minus = vote(c.x, c.z, 2).value;
                    plus = minus;
                }
                double gap = Math.abs(plus - minus);
                double centerGap = Math.max(Math.abs(center - minus), Math.abs(center - plus));
                limits.add(seed, c.id, c.x, c.z, center, minus, plus, gap, centerGap);
                if (c.site != null) assertions.add(seed, c.id, "singular-exterior-branches", "OBSERVE",
                        "gap=" + gap + ";centerGap=" + centerGap + ";actual cutoff disk tested separately");
                else test(gap <= FORMULA_TOL && centerGap <= FORMULA_TOL, seed, c.id, "raw-analytic-C0",
                        "gap=" + gap + ";centerGap=" + centerGap + ";not a visibility threshold");
            } catch (Throwable t) {
                test(false, seed, c.id, "analytic-exception", t.toString());
            }
        }

        private void cutoffLimits(Case c) {
            // The d<1e-9 whole-function return makes a tiny zero DISK, not just a zero point.
            // Exterior limits in limits.csv describe the singular unit-vector branch, not limits inside that disk.
            for (int side : new int[]{-1, 1}) {
                double r = 1e-9, x = c.x + side * r * c.dx, z = c.z + side * r * c.dz;
                try {
                    Vote other = voteExcluding(x, z, c.site);
                    double w = Math.exp(-r * r / (2 * SIGMA * SIGMA));
                    double mag = Math.hypot(c.site.vx, c.site.vz);
                    double outside = clamp(2 * (other.numerator + side * w * mag) / (other.denominator + w));
                    double insideActual = actualStress(c.x + side * r * .5 * c.dx, c.z + side * r * .5 * c.dz);
                    double outsideActual = actualStress(c.x + side * r * 2 * c.dx, c.z + side * r * 2 * c.dz);
                    cutoff.add(seed, c.id, side, r, x, z, 0, outside, Math.abs(outside), insideActual, outsideActual);
                    test(Math.abs(outside) <= FORMULA_TOL, seed, c.id, "cutoff-disk-C0-" + side,
                            "insideLimit=0;outsideBranchLimit=" + outside + ";radius=1e-9");
                    double ref = vote(c.x + side * r * 2 * c.dx, c.z + side * r * 2 * c.dz, 2).value;
                    test(insideActual == 0 && close(outsideActual, ref, FORMULA_TOL), seed, c.id,
                            "cutoff-disk-branch-evidence-" + side, "finite 0.5r/2r samples versus independent formula at identical doubles");
                } catch (Throwable t) {
                    test(false, seed, c.id, "cutoff-exception-" + side, t.toString());
                }
            }
        }

        private void worldCase(Case c) {
            Point center = worldPoint(c, -1, 0, 0);
            Point[][] values = new Point[LEVELS][2];
            for (int k = 0; k < LEVELS; k++) for (int side : new int[]{-1, 1})
                values[k][side < 0 ? 0 : 1] = worldPoint(c, k, side, Math.scalb(1.0, -k));
            boolean preimagesContract = center != null;
            double previous = Double.NaN;
            for (int k = 0; k < LEVELS; k++) {
                Point a = values[k][0], b = values[k][1];
                if (a == null || b == null || center == null) { preimagesContract = false; continue; }
                double gap = Math.max(Math.hypot(a.x - center.x, a.z - center.z),
                        Math.hypot(b.x - center.x, b.z - center.z));
                if (k >= LEVELS - 4) preimagesContract &= gap <= .75 * previous;
                previous = gap;
            }
            test(preimagesContract, seed, c.id, "world-preimage-local-branch", "tailWorldRadius=" + previous);
            String[] channels = {"stress", "distance", "discrete015", "gate015sigma130", "deformation"};
            for (int channel = 0; channel < channels.length; channel++) {
                double[][] scalar = new double[LEVELS][2];
                for (int k = 0; k < LEVELS; k++) for (int side = 0; side < 2; side++)
                    scalar[k][side] = channel(values[k][side], channel);
                series(c, "public-sample-world-preimage", channels[channel], channel(center, channel), scalar, false);
                if ((c.kind.equals("blur") || c.kind.equals("reach")) && channel == 1)
                    series(c, "public-sample-world-preimage", "distance-C1", channel(center, channel), scalar, true);
            }
        }

        private Point worldPoint(Case c, int level, int side, double h) {
            double qx = c.x + side * h * c.dx, qz = c.z + side * h * c.dz;
            try {
                Inverse p = inverse(qx, qz);
                double[] mapped = independentWarp(p.x, p.z);
                double residual = Math.hypot(mapped[0] - qx, mapped[1] - qz);
                boolean located = p.residual <= ROOT_TOL && residual <= ROOT_TOL;
                if (!located) inverseFailures++;
                test(located, seed, c.id, "inverse-" + level + "-" + side,
                        "actualResidual=" + p.residual + ";independentResidual=" + residual);
                TectonicField.Sample s = tf.sample(p.x, p.z);
                Vote expected = vote(mapped[0], mapped[1], 2);
                Distance d = distance(p.x, p.z);
                double discrete = OceanMorphConcepts.discreteStrength(s.stress());
                double gate = OceanMorphConcepts.tectonicGate(s.stress(), s.dist(), 130.0);
                double offset = td.offset(s, p.x, p.z);
                world.add(seed, c.id, level, side, h, qx, qz, p.x, p.z, mapped[0], mapped[1], residual,
                        actualStress(qx, qz), s.stress(), expected.value, s.dist(), d.result, d.raw,
                        d.ring[0], d.ring[1], d.ring[2], d.ring[3], d.weight, d.error, d.margin,
                        s.btype(), s.rate(), s.tangentX(), s.tangentZ(), s.alongCoord(), discrete, gate,
                        TectonicField.smoothPos(s.stress(), .18), TectonicField.smoothPos(-s.stress(), .18),
                        td.beltMaskAt(p.x, p.z), offset, tf.arcVolcanism(s));
                points++;
                test(close(s.stress(), expected.value, FORMULA_TOL), seed, c.id,
                        "sample-stress-" + level + "-" + side, "compare at independently mapped coordinate, not rounded target");
                test(close(s.dist(), d.result, DIST_TOL) && d.error <= DIST_TOL && d.margin > 0,
                        seed, c.id, "sample-distance-" + level + "-" + side,
                        "actual=" + s.dist() + ";ref=" + d.result + ";searchError=" + d.error + ";margin=" + d.margin);
                test(Double.isFinite(offset) && Double.isFinite(gate) && Double.isFinite(discrete), seed, c.id,
                        "propagation-finite-" + level + "-" + side, "real gate and real deformation; no synthetic Sample");
                return located ? new Point(s.stress(), s.dist(), discrete, gate, offset, p.x, p.z) : null;
            } catch (Throwable t) {
                test(false, seed, c.id, "world-exception-" + level + "-" + side, t.toString());
                return null;
            }
        }

        private void series(Case c, String path, String channel, double center, double[][] values, boolean c1) {
            double[] gaps = new double[LEVELS];
            boolean finite = Double.isFinite(center);
            for (int k = 0; k < LEVELS; k++) {
                double h = Math.scalb(1.0, -k), a = values[k][0], b = values[k][1];
                double gap = Math.abs(b - a);
                // At a site, pair equality alone would miss a removable value discontinuity at the center.
                if (c.kind.equals("site")) gap = Math.max(gap, Math.max(Math.abs(a - center), Math.abs(b - center)));
                double slopeGap = Math.abs((b - center) / h - (center - a) / h);
                gaps[k] = c1 ? slopeGap : gap;
                finite &= Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(slopeGap);
                shrink.add(seed, c.id, path, channel, k, h, a, center, b, gap, slopeGap,
                        Math.abs(b - a) / (2 * h), k == 0 ? Double.NaN : gaps[k] / gaps[k - 1]);
            }
            double floor = c1 ? 32 * ROOT_TOL / Math.scalb(1.0, 1 - LEVELS) : 64 * Math.ulp(Math.max(1.0, Math.abs(center)));
            boolean contracted = finite;
            for (int k = LEVELS - 4; k < LEVELS; k++)
                contracted &= gaps[k] <= floor || gaps[k] <= .75 * gaps[k - 1];
            test(contracted, seed, c.id, path + "-" + channel + (c1 ? "-C1" : "-C0"),
                    "tail=" + gaps[LEVELS - 1] + ";previous=" + gaps[LEVELS - 2] + ";roundoffFloor=" + floor
                            + ";finite=" + finite + ";masked zero is not an exemption for raw stress");
        }

        private Vote vote(double x, double z, int radius) {
            return voteAtBase(x, z, radius, floorCell(x), floorCell(z), null);
        }

        private Vote voteExcluding(double x, double z, Site excluded) {
            return voteAtBase(x, z, 2, floorCell(x), floorCell(z), excluded);
        }

        private Vote voteAtBase(double x, double z, int radius, int bx, int bz, Site excluded) {
            double num = 0, den = 0;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                Site s = site(bx + dx, bz + dz);
                if (s == excluded) continue;
                double ux = x - s.x, uz = z - s.z, d = Math.sqrt(ux * ux + uz * uz);
                if (d < 1e-9) return new Vote(0, 0, 0); // Matches the WHOLE-function early return, not a skipped vote.
                double weight = Math.exp(-d * d / (2 * SIGMA * SIGMA));
                num += weight * (s.vx * ux + s.vz * uz) / d;
                den += weight;
            }
            return new Vote(den <= 1e-12 ? 0 : clamp(2 * num / den), num, den);
        }

        private Site site(int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
            return siteCache.computeIfAbsent(key, ignored -> {
                long hs = hash(x, z, SEED_SALT), hv = hash(x, z, VELOCITY_SALT);
                double sx = (x + .5 + (unit(hs, 0) - .5) * JITTER) * SPACING;
                double sz = (z + .5 + (unit(hs, 24) - .5) * JITTER) * SPACING;
                double angle = unit(hv, 0) * Math.PI * 2, magnitude = .3 + unit(hv, 24) * .7;
                return new Site(x, z, sx, sz, Math.cos(angle) * magnitude, Math.sin(angle) * magnitude);
            });
        }

        private long hash(int x, int z, long salt) {
            BigInteger h = BigInteger.valueOf(x).multiply(BigInteger.valueOf(374761393L))
                    .add(BigInteger.valueOf(z).multiply(BigInteger.valueOf(668265263L)))
                    .add(BigInteger.valueOf(salt))
                    .add(BigInteger.valueOf(seed).multiply(BigInteger.valueOf(0x9E3779B9L))).mod(MOD);
            h = h.xor(h.shiftRight(16)).multiply(BigInteger.valueOf(1274126177L)).mod(MOD);
            return h.xor(h.shiftRight(16)).longValue();
        }

        private double noise(double x, double z, long salt) {
            int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
            double[] wx = weights(x - ix), wz = weights(z - iz);
            double sum = 0;
            for (int i = 0; i < 4; i++) {
                double column = 0;
                for (int j = 0; j < 4; j++)
                    column += (2 * unit(hash(ix + i - 1, iz + j - 1, salt), 0) - 1) * wz[j];
                sum += column * wx[i];
            }
            return sum;
        }

        private double[] independentWarp(double x, double z) {
            return new double[]{x + 130 * noise(x * (1.0 / 400), z * (1.0 / 400), WARP_SALT),
                    z + 130 * noise(x * (1.0 / 400) + 17.3, z * (1.0 / 400) + 31.7, WARP_SALT + 1)};
        }

        private Distance distance(double x, double z) {
            double[] center = checkedRawDistance(x, z);
            double[] ring = new double[4];
            double weight = 0, result = center[0], error = center[1], margin = center[2];
            double sum = center[0];
            for (int k = 0; k < 4; k++) {
                double angle = k * (2 * Math.PI / 4);
                double[] n = checkedRawDistance(x + 150 * Math.cos(angle), z + 150 * Math.sin(angle));
                ring[k] = n[0]; sum += n[0];
                error = Math.max(error, n[1]); margin = Math.min(margin, n[2]);
            }
            if (center[0] < 420) {
                weight = 1 - center[0] / 420;
                weight = weight * weight * (3 - 2 * weight);
                result = center[0] + (sum / 5 - center[0]) * weight;
            }
            return new Distance(center[0], ring, weight, result, error, margin);
        }

        private double[] checkedRawDistance(double x, double z) {
            double[] q = independentWarp(x, z);
            Nearest small = nearest(q[0], q[1], 1), big = nearest(q[0], q[1], 4);
            double d = Math.max(0, (small.second - small.first) * .5);
            double err = Math.max(Math.abs(small.first - big.first), Math.abs(small.second - big.second));
            return new double[]{d, err, big.outside - big.second};
        }

        private Nearest nearest(double x, double z, int radius) {
            int bx = floorCell(x), bz = floorCell(z), ax = 0, az = 0, sx = 0, sz = 0;
            double first = Double.MAX_VALUE, second = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                Site s = site(bx + dx, bz + dz);
                double distance = Math.hypot(x - s.x, z - s.z);
                if (distance < first) {
                    second = first; sx = ax; sz = az;
                    first = distance; ax = s.cx; az = s.cz;
                } else if (distance < second) { second = distance; sx = s.cx; sz = s.cz; }
            }
            double fx = x - bx * SPACING, fz = z - bz * SPACING;
            double outside = Math.min(Math.min(fx, SPACING - fx), Math.min(fz, SPACING - fz))
                    + (radius + .15) * SPACING;
            return new Nearest(first, second, ax, az, sx, sz, outside);
        }

        private double actualStress(double x, double z) throws Exception {
            return (double) stressMethod.invoke(tf, x, z);
        }

        /** This is the ONLY call site of reflective valueNoise, used exclusively by the inverse solver. */
        private double[] locatingWarp(double x, double z) throws Exception {
            double nx = (double) noiseMethod.invoke(tf, x * (1.0 / 400), z * (1.0 / 400), WARP_SALT);
            double nz = (double) noiseMethod.invoke(tf, x * (1.0 / 400) + 17.3, z * (1.0 / 400) + 31.7, WARP_SALT + 1);
            return new double[]{x + 130 * nx, z + 130 * nz};
        }

        private Inverse inverse(double qx, double qz) throws Exception {
            double x = qx, z = qz, best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 48; i++) {
                double[] q = locatingWarp(x, z);
                double rx = q[0] - qx, rz = q[1] - qz;
                best = Math.hypot(rx, rz);
                if (best <= ROOT_TOL) return new Inverse(x, z, best);
                double e = .01;
                double[] xp = locatingWarp(x + e, z), xm = locatingWarp(x - e, z);
                double[] zp = locatingWarp(x, z + e), zm = locatingWarp(x, z - e);
                double a = (xp[0] - xm[0]) / (2 * e), b = (zp[0] - zm[0]) / (2 * e);
                double c = (xp[1] - xm[1]) / (2 * e), d = (zp[1] - zm[1]) / (2 * e);
                double det = a * d - b * c;
                if (!Double.isFinite(det) || Math.abs(det) < 1e-12) break;
                double dx = (d * rx - b * rz) / det, dz = (-c * rx + a * rz) / det;
                boolean accepted = false;
                for (double damping = 1; damping >= 1.0 / 1024; damping *= .5) {
                    double xx = x - damping * dx, zz = z - damping * dz;
                    double[] next = locatingWarp(xx, zz);
                    if (Math.hypot(next[0] - qx, next[1] - qz) < best) {
                        x = xx; z = zz; accepted = true; break;
                    }
                }
                if (!accepted) break;
            }
            double[] q = locatingWarp(x, z);
            return new Inverse(x, z, Math.hypot(q[0] - qx, q[1] - qz));
        }
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method method = TectonicField.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static double channel(Point p, int channel) {
        if (p == null) return Double.NaN;
        return switch (channel) {
            case 0 -> p.stress;
            case 1 -> p.dist;
            case 2 -> p.discrete;
            case 3 -> p.gate;
            case 4 -> p.offset;
            default -> throw new IllegalArgumentException("channel=" + channel);
        };
    }

    private static double[] weights(double f) {
        double f2 = f * f, f3 = f2 * f;
        return new double[]{-.5 * f3 + f2 - .5 * f, 1.5 * f3 - 2.5 * f2 + 1,
                -1.5 * f3 + 2 * f2 + .5 * f, .5 * f3 - .5 * f2};
    }

    private static double unit(long h, int shift) {
        BigInteger unsigned = BigInteger.valueOf(h);
        if (h < 0) unsigned = unsigned.add(MOD);
        return unsigned.shiftRight(shift).and(MASK24).doubleValue() / 16777216.0;
    }

    private static int floorCell(double v) { return (int) Math.floor(v / SPACING); }
    private static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }
    private static boolean close(double a, double b, double tolerance) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= tolerance;
    }

    private static final class Table {
        private final String name;
        private final List<String> rows = new ArrayList<>();
        Table(String name, String header) { this.name = name; rows.add(header); }

        void add(Object... columns) {
            StringBuilder row = new StringBuilder();
            for (Object column : columns) {
                if (row.length() > 0) row.append(',');
                String s = column instanceof Double d ? String.format(Locale.ROOT, "%.17g", d) : String.valueOf(column);
                row.append('"').append(s.replace("\"", "\"\"")).append('"');
            }
            rows.add(row.toString());
        }
    }
}
