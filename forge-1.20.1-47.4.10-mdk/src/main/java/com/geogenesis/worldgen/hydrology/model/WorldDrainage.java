package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** World-coordinate drainage. Windows only select edges; they never define successors or admission. */
final class WorldDrainage {
    record Point(int x, int z) { }
    enum State { DOWNHILL, BASIN_PENDING, BELOW_SEA }
    record Step(Point point, Point next, double bed, State state) { }
    record Edge(Point from, Point to) { }
    record Admission(boolean channel, int uniqueCells, boolean complete) { }
    record Result(List<Edge> edges, List<Point> pendingBasins, int sampledNodes, long terrainSamples) {
        Result { edges=List.copyOf(edges);pendingBasins=List.copyOf(pendingBasins); }
    }
    private static final Comparator<Point> ORDER=Comparator.comparingInt(Point::x).thenComparingInt(Point::z);
    private final int step, threshold;
    private final double sea;
    private final WindowOutlets.Terrain terrain;
    private final Map<Point,Double> heights=new HashMap<>();
    private final Map<Point,Step> successors=new HashMap<>();
    private final Map<Point,Admission> admissions=new HashMap<>();
    private long samples;
    long terrainSamples() { return samples; }

    WorldDrainage(int step,int threshold,double sea,WindowOutlets.Terrain terrain) {
        if(step<1 || threshold<1 || threshold>4096 || !Double.isFinite(sea))
            throw new IllegalArgumentException("bounded world drainage policy");
        this.step=step;this.threshold=threshold;this.sea=sea;this.terrain=terrain;
    }

    private double height(Point p) {
        cancelled();var old=heights.get(p);if(old!=null)return old;
        if(heights.size()>=524288)throw new IllegalStateException("world terrain budget exhausted, not dry");
        double h=terrain.height(p.x(),p.z());samples++;
        if(!Double.isFinite(h))throw new IllegalArgumentException("nonfinite world terrain at "+p);
        heights.put(p,h);return h;
    }

    Step at(Point p) {
        cancelled();if(Math.floorMod(p.x(),step)!=0 || Math.floorMod(p.z(),step)!=0)
            throw new IllegalArgumentException("world lattice point required");
        var old=successors.get(p);if(old!=null)return old;
        double h=height(p),slope=0;Point next=null;
        if(h>=sea)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            if(dx==0 && dz==0)continue;
            var q=offset(p,dx,dz);double drop=(h-height(q))/Math.hypot(dx,dz);
            if(drop>slope){slope=drop;next=q;}
        }
        var result=new Step(p,next,h,h<sea?State.BELOW_SEA:next==null?State.BASIN_PENDING:State.DOWNHILL);
        successors.put(p,result);return result;
    }

    /** Exact threshold predicate: stop at N distinct upstream cells, never sum overlapping cached basins. */
    Admission admission(Point p) {
        cancelled();var old=admissions.get(p);if(old!=null)return old;
        if(at(p).state()==State.BELOW_SEA)return new Admission(false,0,true);
        var seen=new HashSet<Point>();var queue=new ArrayDeque<Point>();seen.add(p);queue.add(p);
        while(!queue.isEmpty() && seen.size()<threshold) {
            cancelled();var here=queue.remove();
            for(int dx=-1;dx<=1 && seen.size()<threshold;dx++)for(int dz=-1;dz<=1 && seen.size()<threshold;dz++) {
                if(dx==0 && dz==0)continue;
                var candidate=offset(here,dx,dz);
                if(!seen.contains(candidate) && here.equals(at(candidate).next())) {seen.add(candidate);queue.add(candidate);}
            }
        }
        var result=new Admission(seen.size()>=threshold,seen.size(),seen.size()<threshold);
        admissions.put(p,result);return result;
    }

    Result region(int rx,int rz,int tract) {
        if(tract<step || tract%step!=0)throw new IllegalArgumentException("aligned region");
        int ox=Math.multiplyExact(rx,tract),oz=Math.multiplyExact(rz,tract);
        var edges=new ArrayList<Edge>();var pending=new ArrayList<Point>();
        // One stencil beyond the crop includes incoming edges and their exact common endpoints.
        for(int x=-step;x<=tract;x+=step)for(int z=-step;z<=tract;z+=step) {
            cancelled();var p=new Point(Math.addExact(ox,x),Math.addExact(oz,z));var n=at(p);
            if(n.next()==null) {
                if(inside(p,rx,rz,tract) && n.state()==State.BASIN_PENDING && admission(p).channel())pending.add(p);
                continue;
            }
            if(coversCore(p,n.next(),rx,rz,tract) && admission(p).channel())edges.add(new Edge(p,n.next()));
        }
        edges.sort(Comparator.comparing(Edge::from,ORDER).thenComparing(Edge::to,ORDER));pending.sort(ORDER);
        return new Result(edges,pending,successors.size(),samples);
    }

    List<Point> trace(Point origin,int maxNodes) {
        if(maxNodes<1 || maxNodes>131072)throw new IllegalArgumentException("bounded trace");
        var path=new ArrayList<Point>();var p=origin;
        for(int i=0;i<maxNodes;i++) {
            cancelled();path.add(p);var n=at(p);if(n.next()==null)return List.copyOf(path);p=n.next();
        }
        throw new IllegalStateException("world trace budget exhausted, not a terminal");
    }
    private boolean coversCore(Point a,Point b,int rx,int rz,int tract) {
        int count=Math.max(Math.abs(b.x()-a.x()),Math.abs(b.z()-a.z()));
        for(int i=0;i<=count;i++) {
            var p=new Point(a.x()+(b.x()-a.x())*i/count,a.z()+(b.z()-a.z())*i/count);
            if(inside(p,rx,rz,tract))return true;
        }
        return false;
    }
    private Point offset(Point p,int dx,int dz) {
        return new Point(Math.addExact(p.x(),Math.multiplyExact(dx,step)),Math.addExact(p.z(),Math.multiplyExact(dz,step)));
    }
    private static boolean inside(Point p,int rx,int rz,int tract) {
        return Math.floorDiv(p.x(),tract)==rx && Math.floorDiv(p.z(),tract)==rz;
    }
    private static void cancelled() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException("world drainage");
    }
}
