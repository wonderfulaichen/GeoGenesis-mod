package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import com.geogenesis.standalone.NoiseFollowingErosion.Point;
import com.geogenesis.standalone.NoiseFollowingErosion.Settings;
import com.geogenesis.standalone.NoiseFollowingErosion.Trace;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Read-only, finite-resolution stage-A evidence. Never creates or repairs graph edges. */
public final class DescentTerminalAudit {
    private DescentTerminalAudit() {}
    public static final Settings SETTINGS = new Settings(8, 0, .25, .015625, 12, .0625);
    public record Profile(int rises, double maxRise, double argWu, double totalRise,
                          boolean accepted, int samples) {}
    public record Evidence(Trace trace, int fineRises, boolean valid) {}
    public record Pool(int size, double pitch, double shift, double x0, double z0,
                       double[] heights, double[] spill, int minimum, int walkSteps,
                       boolean boundaryMinimum, double depth, String classification) {
        public double x(int at) { return x0 + at % size * pitch; }
        public double z(int at) { return z0 + at / size * pitch; }
    }
    private record Flood(int at, double level) {}

    public static double h(HeightField field, double x, double z) {
        double h = field.height(x, z);
        require(Double.isFinite(h), "nonfinite complete H0");
        return h;
    }
    public static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Same endpoint/spacing arithmetic as the original graph, with additional rise metrics. */
    public static Profile profile(HeightField field, Point a, Point b, double pitch,
                                  double offset, BufferedWriter csv, String label) throws IOException {
        double length = Math.hypot(b.x()-a.x(), b.z()-a.z());
        require(length > 0 && pitch > 0, "positive profile length/pitch");
        int intervals = (int) Math.ceil(length / pitch), rises = 0, count = 1;
        double ds = length / intervals, first = h(field,a.x(),a.z()), previous = first;
        double max = 0, arg = Double.NaN, total = 0;
        if (csv != null) csv.write(label+",0.0,"+a.x()+","+a.z()+","+first+",0.0\n");
        for (double s = offset == 0 ? ds : offset*ds;; s += ds) {
            double at = Math.min(s, length), t = at / length;
            double x = at == length ? b.x() : a.x()+t*(b.x()-a.x());
            double z = at == length ? b.z() : a.z()+t*(b.z()-a.z());
            double value = h(field,x,z), rise = value-previous;
            if (rise > 0) { rises++; total += rise; }
            if (rise > max) { max = rise; arg = at; }
            if (csv != null) csv.write(label+","+at+","+x+","+z+","+value+","+rise+"\n");
            previous = value; count++;
            if (at == length) break;
        }
        return new Profile(rises,max,arg,total,previous < first && rises == 0,count);
    }

    public static Evidence audit(HeightField field, String root, double x, double z) throws IOException {
        Trace trace = NoiseFollowingErosion.trace(field,root,x,z,SETTINGS);
        int rises = 0;
        for (int i = 1; i < trace.points().size(); i++) {
            rises += profile(field,trace.points().get(i-1),trace.points().get(i),.0073,0,null,root).rises();
        }
        Point end = trace.points().get(trace.points().size()-1);
        boolean valid = trace.length() > .25 && rises == 0 && h(field,end.x(),end.z()) < h(field,x,z);
        return new Evidence(trace,rises,valid);
    }

    /** Eight-neighbour steepest descent from the centered root, then boundary minimax flooding. */
    public static Pool pool(HeightField field, double x, double z, double pitch, double shift) {
        int n = (int) Math.round(4 / pitch)+1, center = n/2*n+n/2;
        double x0 = x-2+shift, z0 = z-2+shift;
        double[] heights = new double[n*n];
        for (int i = 0; i < heights.length; i++) heights[i] = h(field,x0+i%n*pitch,z0+i/n*pitch);
        int at = center, steps = 0;
        while (true) {
            int best = at; double steepest = 0;
            for (int next : neighbors(at,n)) {
                double slope = (heights[at]-heights[next]) / Math.hypot(next%n-at%n,next/n-at/n);
                if (slope > steepest) { steepest = slope; best = next; }
            }
            if (best == at) break;
            at = best; steps++;
        }
        double[] spill = flood(heights,n);
        boolean boundary = boundary(at,n);
        double depth = spill[at]-heights[at];
        String classification = !boundary && depth > 0
                ? steps > 0 ? "BASIN_CANDIDATE_WITH_DESCENT" : "LOCAL_CLOSED_DEPRESSION_FINITE"
                : "UNKNOWN";
        return new Pool(n,pitch,shift,x0,z0,heights,spill,at,steps,boundary,depth,classification);
    }

    private static double[] flood(double[] heights, int n) {
        double[] spill = new double[heights.length]; Arrays.fill(spill,Double.POSITIVE_INFINITY);
        PriorityQueue<Flood> queue = new PriorityQueue<>(Comparator.comparingDouble(Flood::level)
                .thenComparingInt(Flood::at));
        for (int i = 0; i < heights.length; i++) if (boundary(i,n)) {
            spill[i] = heights[i]; queue.add(new Flood(i,heights[i]));
        }
        while (!queue.isEmpty()) {
            Flood current = queue.remove();
            if (current.level() != spill[current.at()]) continue;
            for (int next : neighbors(current.at(),n)) {
                double level = Math.max(current.level(),heights[next]);
                if (level < spill[next]) { spill[next] = level; queue.add(new Flood(next,level)); }
            }
        }
        return spill;
    }
    private static boolean boundary(int at, int n) {
        return at%n == 0 || at%n == n-1 || at/n == 0 || at/n == n-1;
    }
    private static List<Integer> neighbors(int at, int n) {
        List<Integer> result = new ArrayList<>(8);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int x = at%n+dx, z = at/n+dz;
            if ((dx != 0 || dz != 0) && x >= 0 && z >= 0 && x < n && z < n) result.add(z*n+x);
        }
        return result;
    }
    public static void writePool(BufferedWriter csv, int root, Pool p) throws IOException {
        for (int i = 0; i < p.heights().length; i++) csv.write(root+","+p.pitch()+","+p.shift()+","+i
                +","+p.x(i)+","+p.z(i)+","+p.heights()[i]+","+p.spill()[i]+","+(p.spill()[i]-p.heights()[i])+"\n");
    }
    public static void writeWitness(BufferedWriter csv, HeightField field, Evidence e) throws IOException {
        require(e.valid(),"only valid witness geometry exported");
        Trace t = e.trace();
        for (int i = 0; i < t.points().size(); i++) {
            Point p = t.points().get(i);
            csv.write(t.id()+","+i+","+p.x()+","+p.z()+","+h(field,p.x(),p.z())+","+t.arc().get(i)+"\n");
        }
    }

    public static void selfTest() throws IOException {
        require(audit((x,z)->-x,"slope",0,0).valid(),"slope witness");
        require(audit((x,z)->-x+Math.pow(z-.02*x*x,2),"curving",0,1).valid(),"curving valley witness");
        require(!audit((x,z)->x*x+z*z,"bowl",0,0).valid(),"bowl cannot absorb");
        Pool bowl = pool((x,z)->x*x+z*z,0,0,.125,0);
        require(bowl.depth() > 0 && bowl.classification().equals("LOCAL_CLOSED_DEPRESSION_FINITE"),"closed bowl");
        Pool down = pool((x,z)->x*x+z*z,1,0,.125,0);
        require(down.walkSteps() > 0 && down.classification().equals("BASIN_CANDIDATE_WITH_DESCENT"),"basin not root");
        require(pool((x,z)->7,0,0,.125,0).classification().equals("UNKNOWN"),"flat not positive pool");
        Point a = new Point(0,0), b = new Point(4,0);
        HeightField ridge = (x,z)->-x+20*Math.exp(-Math.pow((x-2)/.3,2));
        require(ridge.height(4,0)<ridge.height(0,0),"ridge lower target");
        require(!profile(ridge,a,b,.0073,0,null,"").accepted(),"forbidden ridge absorption");
        HeightField narrow = (x,z)->-x+Math.exp(-Math.pow((x-.125)/.003,2));
        require(profile(narrow,a,b,.25,0,null,"").accepted(),"narrow coarse passes");
        require(!profile(narrow,a,b,.0078125,0,null,"").accepted(),"narrow fine failure retained");
        require(!audit((x,z)->7,"flat",0,0).valid(),"no descent forbidden absorption");
        System.out.println("SYNTHETIC PASS: slope/curving valley/bowl/ridge/flat/narrow alias; finite evidence only");
    }
    public static void main(String[] args) {
        try { require(args.length == 0,"selftest takes no arguments"); selfTest(); }
        catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
}
