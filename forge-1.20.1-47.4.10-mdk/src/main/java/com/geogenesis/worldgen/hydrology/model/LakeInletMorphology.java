package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Bounded, pure-data submerged morphology, never a published river skeleton.
 * An excavation-weighted wet-domain route finds a natural receiving section.
 * A smooth residual-cut envelope approaches that section; widths come from the inlet.
 * Basin-wide hard collars retain the original source value/gradient stencil and
 * natural receiving sections, even where different inlet corridors overlap.
 * Discrete bed samples are authoritative. Smooth collar weights have zero first
 * derivatives at their support boundaries; underlying P and segment max union
 * are not asserted globally C1 between every pair of samples.
 * This is geometric terrain design, not a calibrated hydraulic/sediment model.
 * Unknown/truncated basins and infeasible inlets explicitly produce no corridor.
 */
public final class LakeInletMorphology {
    private LakeInletMorphology() { }
    @FunctionalInterface public interface Height { double at(double x, double z); }
    public record Inlet(int trace, double x, double z, double half, double bed, double slope) { }
    public record Point(double x, double z, double p, double bed, double radius, double arc) { }
    public record Corridor(int trace, String status, List<Point> points, double cost) { }
    public record Domain(int x, int z, int nx, int nz, boolean[] wet, double[] p) {
        int index(int wx, int wz) {
            int i = wx - x, j = wz - z;
            return i < 0 || j < 0 || i >= nx || j >= nz ? -1 : i * nz + j;
        }
        boolean contains(double wx, double wz) {
            int i = (int) Math.floor(wx), j = (int) Math.floor(wz);
            for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
                int k = index(i + a, j + b);
                if (k < 0 || !wet[k]) return false;
            }
            return true;
        }
        double sample(double wx, double wz) {
            int i = (int) Math.floor(wx), j = (int) Math.floor(wz);
            int k = index(i, j);
            if (k < 0 || index(i + 1, j + 1) < 0) return Double.NaN;
            double u = wx - i, v = wz - j;
            return (p[k] * (1-v) + p[k+1] * v) * (1-u)
                    + (p[k+nz] * (1-v) + p[k+nz+1] * v) * u;
        }
    }
    public record Plan(Domain domain, List<Corridor> corridors, long buildNanos,
                       double[] baselineCut, double[] constrainedCut) {
        /** Stored basin solve; all source and receiver constraints apply to the common union. */
        public double cut(int x, int z) {
            int k=domain.index(x,z);
            return k<0 || !domain.wet[k] ? 0 : constrainedCut[k];
        }
        /** C2 tensor interpolation of the authoritative residual samples. At integer
         * coordinates it is identical to production; all first derivatives agree (zero)
         * across grid edges. This is a diagnostic continuous representative, not a claim
         * that Minecraft cubes or the natural terrain interpolation are differentiable.
         */
        public double interpolatedCut(double x,double z) {
            int i=(int)Math.floor(x),j=(int)Math.floor(z);
            double u=smooth(x-i),v=smooth(z-j);
            return (cut(i,j)*(1-v)+cut(i,j+1)*v)*(1-u)
                    +(cut(i+1,j)*(1-v)+cut(i+1,j+1)*v)*u;
        }
        private double unconstrainedCut(int x, int z) {
            int k = domain.index(x, z);
            if (k < 0 || !domain.wet[k]) return 0;
            double result = 0;
            for (Corridor c : corridors) for (int i = 1; i < c.points.size(); i++) {
                Point a = c.points.get(i-1), b = c.points.get(i);
                double dx = b.x-a.x, dz = b.z-a.z, len2 = dx*dx+dz*dz;
                if (len2 < 1e-12) continue;
                double t = Math.max(0, Math.min(1, ((x-a.x)*dx+(z-a.z)*dz)/len2));
                double distance = Math.hypot(x-a.x-t*dx, z-a.z-t*dz);
                double radius = a.radius + t*(b.radius-a.radius);
                if (distance >= radius) continue;
                double residual = Math.max(0, a.p-a.bed)*(1-t) + Math.max(0, b.p-b.bed)*t;
                Point end=c.points.get(c.points.size()-1);
                double receivingFade=smooth(Math.hypot(x-end.x,z-end.z)/end.radius);
                double cut = residual * (1-smooth(distance/radius))*receivingFade;
                result = Math.max(result, cut);
            }
            return result;
        }
    }
    private record Visit(int k, double cost) { }
    private static final int[] DX = {1,0,-1,0,1,-1,-1,1};
    private static final int[] DZ = {0,1,0,-1,1,1,-1,-1};

    public static Plan solve(Domain domain, List<Inlet> inlets) {
        double[] baseline=new double[domain.p.length];
        for(Inlet in:inlets) for(int k=0;k<baseline.length;k++) {
            double r=Math.hypot(domain.x+k/domain.nz-in.x,domain.z+k%domain.nz-in.z);
            if(r<=in.half) baseline[k]=Math.max(baseline[k],Math.max(0,domain.p[k]-in.bed));
        }
        return solve(domain,inlets,baseline);
    }

    public static Plan solve(Domain domain, List<Inlet> inlets, double[] baselineCut) {
        long start = System.nanoTime();
        List<Corridor> corridors = new ArrayList<>();
        for (Inlet inlet : inlets) corridors.add(route(domain, inlet));
        double[] cut=new double[domain.p.length];
        Plan plan=new Plan(domain,List.copyOf(corridors),0,baselineCut,cut);
        for(int k=0;k<cut.length;k++) if(domain.wet[k]) {
            int x=domain.x+k/domain.nz,z=domain.z+k%domain.nz;
            cut[k]=constrain(plan,x,z,plan.unconstrainedCut(x,z));
        }
        return new Plan(domain,plan.corridors,System.nanoTime()-start,baselineCut,cut);
    }

    /** One common constraint mask, not per-corridor endpoint fades competing by max.
     * Receiver sections use their original inlet width. Source collars are the actual
     * preexisting incision footprint and its grid stencil, so no guessed connection length.
     */
    private static double constrain(Plan p, int x, int z, double cut) {
        Domain d=p.domain;
        double sourceWeight=0, sourceCut=0, receiverWeight=1;
        for(Corridor c:p.corridors) {
            if(c.points.isEmpty()) continue;
            Point a=c.points.get(0), b=c.points.get(c.points.size()-1);
            Point next=c.points.get(Math.min(1,c.points.size()-1));
            double dx=next.x-a.x,dz=next.z-a.z,len=Math.hypot(dx,dz);
            if(len==0) continue;
            double along=((x-a.x)*dx+(z-a.z)*dz)/len;
            double across=Math.abs(((x-a.x)*dz-(z-a.z)*dx)/len);
            double width=a.radius/2;
            // Grid support is a sampling stencil, not a fixed-distance bed transition.
            double source=smooth(1-Math.max(0,Math.abs(along)-1)/width)
                    *smooth(1-Math.max(0,across-Math.max(1,width))/width);
            int k=d.index(x,z);
            if(source>sourceWeight) {sourceWeight=source;sourceCut=p.baselineCut[k];}
            double r=Math.hypot(x-b.x,z-b.z);
            receiverWeight=Math.min(receiverWeight,smooth(Math.max(0,r-width)/width));
        }
        // Source section and adjacent longitudinal stencil retain baseline value and slope.
        // Receiver collar wins in case of conflict; solver diagnostics must reject overlaps.
        return ((1-sourceWeight)*cut+sourceWeight*sourceCut)*receiverWeight;
    }

    private static Corridor route(Domain d, Inlet in) {
        int start = d.index((int)Math.round(in.x), (int)Math.round(in.z));
        if (start < 0 || !d.wet[start] || !(in.half > 0) || !Double.isFinite(in.bed))
            return new Corridor(in.trace, "UNKNOWN_SOURCE", List.of(), 0);
        double[] cost = new double[d.p.length];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        int[] prev = new int[d.p.length];
        Arrays.fill(prev, -1);
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingDouble(Visit::cost)
                .thenComparingInt(Visit::k));
        cost[start] = 0;
        queue.add(new Visit(start, 0));
        int goal = -1;
        while (!queue.isEmpty()) {
            Visit v = queue.remove();
            if (v.cost != cost[v.k]) continue;
            if (v.k != start && receiving(d, in, v.k, prev[v.k])) { goal = v.k; break; }
            relax(d, in, v, cost, prev, queue);
        }
        if (goal < 0) return new Corridor(in.trace, "NO_NATURAL_SECTION", List.of(), 0);
        List<Integer> reverse = new ArrayList<>();
        for (int k = goal; k >= 0; k = prev[k]) reverse.add(k);
        List<double[]> path = new ArrayList<>();
        for (int i = reverse.size()-1; i >= 0; i--) {
            int k = reverse.get(i);
            path.add(new double[] {d.x+k/d.nz, d.z+k%d.nz});
        }
        if (path.size() < 3) return new Corridor(in.trace, "NATURAL_AT_SOURCE", List.of(), cost[goal]);
        soften(d, path);
        return profile(d, in, path, cost[goal]);
    }

    private static void relax(Domain d, Inlet in, Visit v, double[] cost, int[] prev,
                              PriorityQueue<Visit> queue) {
        int x = d.x+v.k/d.nz, z = d.z+v.k%d.nz;
        for (int i = 0; i < DX.length; i++) {
            int k = d.index(x+DX[i], z+DZ[i]);
            if (k < 0 || !d.wet[k]) continue;
            if (DX[i] != 0 && DZ[i] != 0) {
                int sideX=d.index(x+DX[i],z), sideZ=d.index(x,z+DZ[i]);
                if (sideX<0 || sideZ<0 || !d.wet[sideX] || !d.wet[sideZ]) continue;
            }
            double cut = Math.max(0, d.p[k]-in.bed);
            double turn = 0;
            if (prev[v.k] >= 0) {
                int px = v.k/d.nz-prev[v.k]/d.nz, pz = v.k%d.nz-prev[v.k]%d.nz;
                turn = 1-(px*DX[i]+pz*DZ[i])/(Math.hypot(px,pz)*Math.hypot(DX[i],DZ[i]));
            }
            // Dimensionless weights; horizontal scale is the actual inlet half width.
            double next = v.cost + Math.hypot(DX[i],DZ[i])*(1+cut*cut+in.half*turn);
            if (next < cost[k]) { cost[k]=next; prev[k]=v.k; queue.add(new Visit(k,next)); }
        }
    }

    private static boolean receiving(Domain d, Inlet in, int k, int parent) {
        if (parent < 0 || d.p[k] > in.bed) return false;
        double x = d.x+k/d.nz, z = d.z+k%d.nz;
        double dx = k/d.nz-parent/d.nz, dz = k%d.nz-parent%d.nz, len = Math.hypot(dx,dz);
        double depth = Math.max(0, d.sample(in.x,in.z)-in.bed);
        for (int i = -4; i <= 4; i++) {
            double u = i/4.0, wx = x-dz/len*in.half*u, wz = z+dx/len*in.half*u;
            if (!d.contains(wx,wz) || d.sample(wx,wz) > in.bed+depth*smooth(Math.abs(u))) return false;
        }
        return true;
    }

    private static void soften(Domain d, List<double[]> p) {
        for (int iteration = 0; iteration < 12; iteration++) {
            List<double[]> next = new ArrayList<>();
            next.add(p.get(0));
            for (int i = 1; i < p.size()-1; i++) {
                double[] a = p.get(i-1), b = p.get(i), c = p.get(i+1);
                double x = (a[0]+2*b[0]+c[0])/4, z = (a[1]+2*b[1]+c[1])/4;
                next.add(d.contains(x,z) ? new double[] {x,z} : b);
            }
            next.add(p.get(p.size()-1));
            for (int i = 0; i < p.size(); i++) p.set(i,next.get(i));
        }
    }

    private static Corridor profile(Domain d, Inlet in, List<double[]> path, double cost) {
        double[] arc = new double[path.size()];
        for (int i = 1; i < path.size(); i++) arc[i]=arc[i-1]+Math.hypot(
                path.get(i)[0]-path.get(i-1)[0], path.get(i)[1]-path.get(i-1)[1]);
        double length = arc[arc.length-1];
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            double[] q = path.get(i);
            double t = arc[i]/length, t2 = t*t, t3 = t2*t;
            double natural=d.sample(q[0],q[1]);
            // Residual against P has a double zero at the receiving section: value and slope
            // become natural, rather than clipping an overshooting cubic into an early wall.
            double initial=Math.max(0,d.sample(path.get(0)[0],path.get(0)[1])-in.bed);
            double bed=natural-initial*(1-smooth(t));
            points.add(new Point(q[0],q[1],natural,bed,2*in.half,arc[i]));
        }
        return new Corridor(in.trace,"SOLVED_GEOMETRIC",List.copyOf(points),cost);
    }

    static double smooth(double x) { x=Math.max(0,Math.min(1,x)); return x*x*x*(10+x*(-15+6*x)); }
}
