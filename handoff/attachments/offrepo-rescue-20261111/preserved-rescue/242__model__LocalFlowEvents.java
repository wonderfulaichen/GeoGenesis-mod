package com.geogenesis.worldgen.hydrology.model;

import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Diagnostic frozen-terrain transport, NOT ErosionEngine feedback, runoff Q, or water authority.
 * Coordinates and step bounds are integer Minecraft columns. A birth cell has spacing S;
 * seed/configuration/round/world birth cell identify one event. Its hashed start is inside that
 * cell. Every step visits one column and moves at most one cardinal column, strictly downhill.
 * A query needs only events born within L columns of its rectangle (inclusive conservative halo).
 * World owners partition births, never trajectories. Output ownership only crops contributions.
 * No query extent, owner size, cache state or request order participates in an event identity.
 * No claim is made that finite L discovers an unbounded river or reproduces in-place erosion.
 */
final class LocalFlowEvents {
    record Point(int x, int z) implements Comparable<Point> {
        public int compareTo(Point p) { int c = Integer.compare(x, p.x); return c != 0 ? c : Integer.compare(z, p.z); }
    }
    record Rect(int x0, int z0, int x1, int z1) {
        Rect { if (x1 < x0 || z1 < z0)throw new IllegalArgumentException("empty rectangle"); }
        boolean contains(Point p) { return p.x >= x0 && p.x <= x1 && p.z >= z0 && p.z <= z1; }
        Rect expand(int n) { return new Rect(x0-n,z0-n,x1+n,z1+n); }
    }
    record Spec(long seed, long configuration, int spacing, int rounds, int lifetime) {
        Spec { if (spacing < 1 || rounds < 1 || lifetime < 1)throw new IllegalArgumentException("positive event dimensions"); }
    }
    record Id(long seed, long configuration, int cellX, int cellZ, int round) implements Comparable<Id> {
        public int compareTo(Id b) {
            int c=Long.compare(seed,b.seed); if(c!=0)return c;
            c=Long.compare(configuration,b.configuration); if(c!=0)return c;
            c=Integer.compare(cellX,b.cellX); if(c!=0)return c;
            c=Integer.compare(cellZ,b.cellZ); return c!=0?c:Integer.compare(round,b.round);
        }
    }
    record Event(Id id, Point start, int mass) { }
    record Amount(long visits, long mass, long momentumX, long momentumZ) {
        Amount plus(int water, int dx, int dz) { return new Amount(visits+1,mass+water,momentumX+(long)water*dx,momentumZ+(long)water*dz); }
    }
    enum Stop { LIFETIME, LOCAL_MINIMUM, GEOMETRY_PENDING }
    record Trace(List<Point> points, Stop stop) { }
    record Result(Map<Point,Amount> field, int events, long steps, int peakPath, int peakSamples,
                  int uniqueSamples, long sampleCalls, int moves, Map<Stop,Integer> stops) { }
    private static final int[][] SIDES={{1,0},{0,1},{-1,0},{0,-1}};
    private final Spec spec;
    private final WindowOutlets.Terrain terrain;
    LocalFlowEvents(Spec spec, WindowOutlets.Terrain terrain) { this.spec=spec; this.terrain=terrain; }

    List<Event> events(Rect domain) {
        var result=new ArrayList<Event>(); int s=spec.spacing;
        for(int x=Math.floorDiv(domain.x0,s);x<=Math.floorDiv(domain.x1,s);x++)
            for(int z=Math.floorDiv(domain.z0,s);z<=Math.floorDiv(domain.z1,s);z++)
                for(int round=0;round<spec.rounds;round++) {
                    var id=new Id(spec.seed,spec.configuration,x,z,round);
                    long h=mix(spec.seed ^ mix(spec.configuration) ^ mix(((long)x<<32)^(z&0xffffffffL)) ^ mix(round));
                    var start=new Point(x*s+Math.floorMod(h,s),z*s+Math.floorMod(mix(h),s));
                    if(domain.contains(start))result.add(new Event(id,start,1+Math.floorMod(mix(h+1),4)));
                }
        result.sort(Comparator.comparing(Event::id)); return List.copyOf(result);
    }

    Result query(Rect target,int ownerSize,boolean reverse) {
        return accumulate(target,events(target.expand(spec.lifetime)),ownerSize,reverse);
    }
    Result accumulate(Rect target,List<Event> events,int ownerSize,boolean reverse) {
        if(ownerSize<1)throw new IllegalArgumentException("positive owner size");
        var owners=new TreeMap<Point,List<Event>>();var ids=new HashSet<Id>();
        for(var event:events) {
            if(!ids.add(event.id))throw new IllegalArgumentException("duplicate event "+event.id);
            var owner=new Point(Math.floorDiv(event.start.x,ownerSize),Math.floorDiv(event.start.z,ownerSize));
            owners.computeIfAbsent(owner,k->new ArrayList<>()).add(event);
        }
        var keys=new ArrayList<>(owners.keySet());if(reverse)Collections.reverse(keys);
        var field=new TreeMap<Point,Amount>();var samples=new Samples(terrain);long steps=0;int peak=0;
        var stops=new EnumMap<Stop,Integer>(Stop.class);
        for(var key:keys) {
            var group=owners.get(key);if(reverse)Collections.reverse(group);
            for(var event:group) {
                var trace=trace(event,samples);var path=trace.points;stops.merge(trace.stop,1,Integer::sum);
                peak=Math.max(peak,path.size());steps+=path.size();
                for(int i=0;i<path.size();i++) {
                    var p=path.get(i);if(!target.contains(p))continue;
                    var q=i+1<path.size()?path.get(i+1):p;
                    field.put(p,field.getOrDefault(p,new Amount(0,0,0,0)).plus(event.mass,q.x-p.x,q.z-p.z));
                }
            }
        }
        return new Result(Collections.unmodifiableMap(field),events.size(),steps,peak,samples.peak,
                samples.unique.size(),samples.calls,samples.moves,Collections.unmodifiableMap(stops));
    }

    List<Point> path(Event event) { return trajectory(event).points; }
    Trace trajectory(Event event) { return trace(event,new Samples(terrain)); }
    private Trace trace(Event event,Samples samples) {
        var path=new ArrayList<Point>();Point p=event.start;
        for(int step=0;step<spec.lifetime;step++) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException("local flow event");
            samples.prepare(p);path.add(p);double best=samples.height(p);Point next=null;boolean tie=false;
            for(var side:SIDES) {
                var q=new Point(p.x+side[0],p.z+side[1]);double h=samples.height(q);
                if(h<best) {best=h;next=q;tie=false;}
                else if(next!=null && h==best)tie=true;
            }
            // Distinguish a rejected geometry tie from a real local minimum; neither implies dry water.
            if(tie)return new Trace(List.copyOf(path),Stop.GEOMETRY_PENDING);
            if(next==null)return new Trace(List.copyOf(path),Stop.LOCAL_MINIMUM);
            p=next;
        }
        return new Trace(List.copyOf(path),Stop.LIFETIME);
    }

    private static final class Samples {
        final WindowOutlets.Terrain terrain;
        final Map<Point,Double> cache=new HashMap<>();final Set<Point> unique=new HashSet<>();
        Point center;int peak,moves;long calls;
        Samples(WindowOutlets.Terrain terrain) {this.terrain=terrain;}
        void prepare(Point p) {
            if(center==null || Math.max(Math.abs(p.x-center.x),Math.abs(p.z-center.z))>=4) {
                center=p;moves++;cache.keySet().removeIf(q->Math.max(Math.abs(q.x-p.x),Math.abs(q.z-p.z))>8);
            }
        }
        double height(Point p) {
            calls++;Double old=cache.get(p);if(old!=null)return old;
            double h=terrain.height(p.x,p.z);if(!Double.isFinite(h))throw new IllegalArgumentException("nonfinite terrain");
            cache.put(p,h);unique.add(p);peak=Math.max(peak,cache.size());return h;
        }
    }
    private static long mix(long v) {v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
}
