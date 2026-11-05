package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import com.geogenesis.standalone.SharedDepthField.Cell;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Synthetic contracts only: no real export, morphology acceptance, or production dependencies. */
public final class SharedDepthCheck {
    private static int assertions;
    private static final Settings SETTINGS = Settings.defaults();
    private SharedDepthCheck() {}

    public static void main(String[] args) {
        try {
            int count = verify();
            System.out.println("SHARED DEPTH CONTRACT PASS assertions=" + count
                    + "; effectAccepted=false; finite synthetic evidence only");
        } catch (RuntimeException | AssertionError failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static synchronized int verify() {
        assertions = 0;
        coefficientChecks();
        exactChecks();
        basisChecks();
        smoothChecks();
        geometryChecks();
        return assertions;
    }

    private static void coefficientChecks() {
        HeightField h = (x,z) -> -0.0;
        Cut old = new Cut(h, List.of(), SETTINGS);
        for (double bad : new double[] {-1e-20, Math.nextUp(1.0), Double.NaN,
                Double.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { new SharedDepthField(h, old, Map.of(new Cell(0,0), bad)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "reject, not clip coefficient " + bad);
        }
        Map<Cell,Double> mutable = new HashMap<>();
        mutable.put(new Cell(0,0), 0.0);
        SharedDepthField f = new SharedDepthField(h, old, mutable);
        double before = f.weight(0,0);
        mutable.clear();
        require(bits(before, f.weight(0,0)), "defensive copy");
        boolean immutable = false;
        try { f.coefficients().clear(); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        require(immutable, "immutable coefficients");
        var bbox = f.modifiedBounds();
        require(bbox.minX() == -8 && bbox.maxX() == 8
                && bbox.minZ() == -8 && bbox.maxZ() == 8, "basis support bbox");
        require(f.weight(8,0) == 1 && f.weight(-8,0) == 1, "bbox boundary default one");
        require(f.weight(100,-100) == 1, "outside bbox exact one");
    }

    private static void exactChecks() {
        Trace line = path("line", new Point(-20,-4), new Point(40,-4));
        HeightField h = (x,z) -> x == 0 ? -0.0 : -.001*x;
        Cut old = new Cut(h, List.of(line), SETTINGS);
        SharedDepthField identity = new SharedDepthField(h, old, Map.of());
        // A finite zero control must cover the ENTIRE tube plus two coefficient rings.
        Map<Cell,Double> zeros = new HashMap<>(), ones = new HashMap<>();
        for (long cz = -5; cz <= 3; cz++) for (long cx = -9; cx <= 14; cx++) {
            zeros.put(new Cell(cx,cz), 0.0);
            ones.put(new Cell(cx,cz), 1.0);
        }
        SharedDepthField zero = new SharedDepthField(h, old, zeros);
        SharedDepthField allOne = new SharedDepthField(h, old, ones);
        for (int iz = -52; iz <= 20; iz++) for (int ix = -112; ix <= 192; ix++) {
            double x = ix*.25, z = iz*.25;
            require(bits(identity.height(x,z), old.height(x,z)), "default one exact old");
            require(bits(allOne.height(x,z), old.height(x,z)), "saved one exact old");
            require(bits(zero.height(x,z), h.height(x,z)), "padded zero exact H0");
            if (old.displacement(x,z) == 0) {
                require(bits(identity.height(x,z), h.height(x,z)), "zero D exact incl signed zero");
            }
        }
        require(zero.weight(1000,1000) == 1, "finite zero control is not global zero");
        require(allOne.modifiedBounds() == null, "identity bbox absent");
    }

    private static SharedDepthField patterned(Cut old, HeightField h) {
        Map<Cell,Double> coefficients = new HashMap<>();
        for (long z = -4; z <= 9; z++) for (long x = -4; x <= 12; x++) {
            coefficients.put(new Cell(x,z), Math.floorMod(x*17+z*31, 101)/100.0);
        }
        return new SharedDepthField(h, old, coefficients);
    }

    private static void basisChecks() {
        HeightField h = (x,z) -> 1;
        SharedDepthField f = patterned(new Cut(h, List.of(), SETTINGS), h);
        for (int i = -800; i <= 800; i++) {
            double x = i*.037, z = i*-.0197;
            double sum = 0, dot = 0;
            var terms = SharedDepthField.basis(x,z);
            require(terms.size() == 16, "sixteen tensor terms");
            for (var b : terms) {
                require(b.value() >= 0, "nonnegative basis");
                sum += b.value();
                dot += b.value()*f.coefficients().getOrDefault(b.cell(),1.0);
            }
            require(Math.abs(sum-1) <= 8*Math.ulp(1.0), "partition unity, no normalization");
            double actual = f.weight(x,z);
            require(actual >= 0 && actual <= 1, "convex range without clipping");
            require(Math.abs(actual-dot) <= 8*Math.ulp(1.0), "basis/de Boor agreement");
        }
    }

    private static void smoothChecks() {
        HeightField h = (x,z) -> 1;
        SharedDepthField f = patterned(new Cut(h,List.of(),SETTINGS),h);
        double e = .001;
        for (int axis = 0; axis < 2; axis++) for (int knot = -8; knot <= 14; knot++) {
            double p = knot*4.0, other = -3.137;
            double[] v = new double[5];
            for (int j = -2; j <= 2; j++) {
                v[j+2] = axis == 0 ? f.weight(p+j*e,other) : f.weight(other,p+j*e);
            }
            require(Math.abs(v[3]-v[1]) < .002, "value continuity at knot/negative/window");
            double left = (v[2]-v[1])/e, right = (v[3]-v[2])/e;
            require(Math.abs(left-right) < .001, "first derivative at knots");
            double ll = (v[2]-2*v[1]+v[0])/(e*e);
            double rr = (v[4]-2*v[3]+v[2])/(e*e);
            require(Math.abs(ll-rr) < .001, "second derivative at knots");
        }
        SharedDepthField single = new SharedDepthField(h,new Cut(h,List.of(),SETTINGS),
                Map.of(new Cell(0,0),0.0));
        require(single.weight(4,0) < 1, "saved coefficient influences outside saved cell window");
        require(Math.abs(single.weight(8-e,0)-1) < 1e-10, "outer support smooth approach");
        require(single.weight(8+e,0) == 1, "outer support exact default");
    }

    private static void geometryChecks() {
        HeightField h = (x,z) -> -.001*x + .0001*z;
        Trace u = path("U",new Point(0,0),new Point(30,0),new Point(30,4),new Point(0,4));
        Trace cross = path("cross",new Point(10,-20),new Point(10,24));
        Cut old = new Cut(h,List.of(u,cross),SETTINGS);
        SharedDepthField f = patterned(old,h);
        Cut reversed = new Cut(h,List.of(cross,u),SETTINGS);
        SharedDepthField cold = new SharedDepthField(h,reversed,f.coefficients());
        double e = 1e-7;
        require(Math.abs(f.displacement(20,2-e)-f.displacement(20,2+e)) < 1e-8,
                "U equidistant nonadjacent passages continuous");
        require(Math.abs(f.displacement(10-e,0)-f.displacement(10+e,0)) < 1e-8,
                "cross shared weight continuous");
        Cut lineCut = new Cut(h,List.of(path("boundary",new Point(0,0),new Point(40,0))),SETTINGS);
        SharedDepthField boundary = patterned(lineCut,h);
        for (double z : new double[] {-8-e,-8,-8+e,8-e,8,8+e}) {
            double d = boundary.displacement(20,z);
            require(d >= 0 && d < 1e-12, "synthetic compact support boundary");
            if (Math.abs(z) >= 8) require(bits(boundary.height(20,z),h.height(20,z)), "outside exact");
        }
        double[] forward = new double[512];
        for (int i = 0; i < forward.length; i++) forward[i] = f.height(i*.123-8,i*.071-12);
        for (int i = forward.length-1; i >= 0; i--) {
            require(bits(forward[i],cold.height(i*.123-8,i*.071-12)), "cold reverse/order replay");
        }
        boolean parallel = IntStream.range(0,512).parallel().allMatch(i ->
                bits(forward[i],cold.height(i*.123-8,i*.071-12)));
        require(parallel,"parallel immutable queries");
    }

    private static Trace path(String id, Point... points) {
        List<Double> arc = new ArrayList<>();
        arc.add(0.0);
        for (int i = 1; i < points.length; i++) {
            arc.add(arc.get(i-1)+Math.hypot(points[i].x()-points[i-1].x(),points[i].z()-points[i-1].z()));
        }
        return new Trace(id,List.of(points),arc,"SYNTHETIC",arc.get(arc.size()-1));
    }

    private static boolean bits(double a,double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private static void require(boolean ok,String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
}
