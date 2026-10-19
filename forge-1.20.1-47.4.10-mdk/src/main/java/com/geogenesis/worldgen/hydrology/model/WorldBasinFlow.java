package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Demand certificates on the canonical world graph; a lower bound is never an exact discharge. */
final class WorldBasinFlow {
    static final int POLICY_VERSION = 6;
    record Water(DemandFlow.Amount amount, double level, WorldBasinRouting.Basin basin, String reason) {
        boolean known() { return amount.known(); }
        double discharge() { return amount.exactValue(); }
        boolean reaches(double demand) { return amount.reaches(demand); }
    }
    private record Unit(Point point, boolean lake) { }
    private record Geometry(DemandFlow.Node<Unit> node, Map<Unit,Set<Point>> entries) { }
    record Stats(long queries, long logicalCells, long expanded, long cacheHits, int peakWorkset, int cachedNodes) { }
    private final WorldDrainage raw;
    private final WorldBasinRouting routing;
    private final LakeWaterBalance.Climate climate;
    private final int step, limit;
    private final LinkedHashMap<Unit,Geometry> nodes = new LinkedHashMap<>(256, .75f, true);
    private final Map<Point,Set<Point>> supported = new HashMap<>();
    private final Map<Point,WorldBasinRouting.Basin> discovered = new HashMap<>();
    private final DemandFlow<Unit> solver;
    private final double threshold;
    private long queries, cells, expanded, cacheHits;
    private int peak;

    WorldBasinFlow(WorldDrainage raw,WorldBasinRouting routing,LakeWaterBalance.Climate climate,int step,int limit) {
        this.raw=raw;this.routing=routing;this.climate=climate;this.step=step;this.limit=limit;
        double minCells=Double.parseDouble(System.getProperty("geogenesis.channelMinCells","150"));
        if(!Double.isFinite(minCells) || minCells<1 || minCells>4096)throw new IllegalArgumentException("world source threshold");
        threshold=Math.ceil(minCells)*step*(double)step*climate.precipitation()*climate.runoffCoeff();
        solver=new DemandFlow<>(u->geometry(u).node(),limit);
    }
    /** Explicit exact query retained for diagnostics; production only requests necessary certificates. */
    Water at(Point p) { return water(p,Double.POSITIVE_INFINITY); }
    Water demand(Point p,double amount) { return water(p,amount); }
    private Water water(Point p,double demand) {
        try {
            Unit u=unit(p);var b=u.lake()?basin(u):null;
            var amount=query(u,demand);double level=Double.NaN;
            if(b!=null && amount.known()) {
                if(amount.exact())level=equilibrium(b,amount.bound()+loss(b));
                else level=b.sill()-1e-9;
                // At zero outflow, inflow cannot be reconstructed from Q=max(0,inflow-loss).
                if(amount.exact() && amount.bound()==0)level=closedLevel(u,b);
            }
            return new Water(amount,level,b,amount.reason());
        } catch(DemandFlow.Unresolved pending) { return unknown(pending.getMessage()); }
        catch(WorldBasinRouting.RoutingBudget pending) { return unknown("flat geometry budget"); }
    }
    private double closedLevel(Unit u,WorldBasinRouting.Basin b) {
        double inflow=geometry(u).node().source();
        for(var input:geometry(u).node().inputs()) {
            var q=query(input,Double.POSITIVE_INFINITY);
            if(!q.exact())throw new DemandFlow.Unresolved(q.reason());
            inflow+=q.bound();
        }
        return equilibrium(b,inflow);
    }
    private Water unknown(String reason) {
        return new Water(new DemandFlow.Amount(DemandFlow.Kind.UNKNOWN,Double.NaN,reason),Double.NaN,null,reason);
    }
    private DemandFlow.Amount query(Unit u,double demand) {
        var result=solver.query(u,demand);queries++;cells+=result.cells();expanded+=result.expanded();
        peak=Math.max(peak,result.peak());return result.amount();
    }
    Stats stats() { return new Stats(queries,cells,expanded,cacheHits,peak,nodes.size()); }
    record Region(WorldDrainage.Result graph, Map<Point,Water> water, int unresolved) {
        Region { water=Map.copyOf(water); }
    }
    Region region(int rx,int rz,int tract) {
        var edges=new ArrayList<WorldDrainage.Edge>();var pending=new ArrayList<Point>();var water=new HashMap<Point,Water>();
        int unresolved=0;
        for(int x=-step;x<=tract;x+=step)for(int z=-step;z<=tract;z+=step) {
            cancelled();Point p=new Point(rx*tract+x,rz*tract+z);
            if(raw.at(p).state()==WorldDrainage.State.BELOW_SEA) {
                water.put(p,new Water(new DemandFlow.Amount(DemandFlow.Kind.EXACT,0,""),Double.NaN,null,"below sea"));continue;
            }
            Water w=demand(p,threshold);water.put(p,w);
            if(!w.known()){unresolved++;pending.add(p);continue;}
            Point next=geometricNext(p);boolean visible=true;
            try {
                if(w.basin()!=null)visible=support(w.basin()).contains(p);
            } catch(DemandFlow.Unresolved failure) {
                water.put(p,unknown(failure.getMessage()));unresolved++;pending.add(p);continue;
            }
            if(next!=null && visible && w.reaches(threshold) && covers(p,next,rx,rz,tract))edges.add(new WorldDrainage.Edge(p,next));
        }
        edges.sort(Comparator.comparingInt((WorldDrainage.Edge e)->e.from().x()).thenComparingInt(e->e.from().z()));
        return new Region(new WorldDrainage.Result(edges,pending,water.size(),raw.terrainSamples()),water,unresolved);
    }
    private boolean covers(Point a,Point b,int rx,int rz,int tract) {
        for(int i=0;i<=step;i++) {
            int x=a.x()+(b.x()-a.x())*i/step,z=a.z()+(b.z()-a.z())*i/step;
            if(Math.floorDiv(x,tract)==rx && Math.floorDiv(z,tract)==rz)return true;
        }
        return false;
    }
    Point geometricNext(Point p) {
        var b=routing.at(p);
        if(b!=null && !b.published())throw new DemandFlow.Unresolved(b.reason());
        return b!=null && b.routeFrom(p)!=null?b.routeFrom(p):routing.baseNext(p);
    }
    private Unit unit(Point p) {
        var b=routing.at(p);
        if(b!=null && !b.published())throw new DemandFlow.Unresolved(b.reason());
        if(b!=null && b.members().contains(p)) {
            var prior=discovered.putIfAbsent(b.identity(),b);
            if(prior!=null && !prior.equals(b))throw new DemandFlow.Unresolved("conflicting basin identity");
            return new Unit(b.identity(),true);
        }
        return new Unit(p,false);
    }
    private WorldBasinRouting.Basin basin(Unit u) {
        var b=discovered.get(u.point());
        if(b==null)throw new DemandFlow.Unresolved("missing canonical basin");
        return b;
    }
    private Geometry geometry(Unit u) {
        cancelled();var hit=nodes.get(u);if(hit!=null){cacheHits++;return hit;}
        var b=u.lake()?basin(u):null;
        var footprint=b==null?List.of(u.point()):b.members();var inside=new HashSet<>(footprint);
        var entries=new LinkedHashMap<Unit,Set<Point>>();
        for(var p:footprint)for(var n:routing.neighbours(p)) {
            if(inside.contains(n))continue;Point next=geometricNext(n);
            if(next==null || !inside.contains(next))continue;
            Unit input=unit(n);if(input.equals(u))continue;
            if(input.lake() && !inside.contains(basin(input).outlet().outer()))
                throw new DemandFlow.Unresolved("basin input not outlet input="+input+" neighbour="+n+" target="+u+" next="+next+" lip="+basin(input).outlet());
            entries.computeIfAbsent(input,k->new LinkedHashSet<>()).add(next);
        }
        var immutable=new LinkedHashMap<Unit,Set<Point>>();entries.forEach((k,v)->immutable.put(k,Set.copyOf(v)));
        var value=new Geometry(new DemandFlow.Node<>(footprint.size()*runoff(),b==null?0:loss(b),
                footprint.size(),List.copyOf(entries.keySet())),Collections.unmodifiableMap(immutable));
        nodes.put(u,value);while(nodes.size()>limit)nodes.remove(nodes.keySet().iterator().next());return value;
    }
    private Set<Point> support(WorldBasinRouting.Basin b) {
        var old=supported.get(b.identity());if(old!=null)return old;
        var inside=new HashSet<>(b.members());var paths=new HashSet<Point>();paths.add(b.outlet().inner());
        for(var e:geometry(new Unit(b.identity(),true)).entries().entrySet()) {
            var q=query(e.getKey(),threshold);if(!q.known())throw new DemandFlow.Unresolved(q.reason());
            if(!q.reaches(threshold))continue;
            for(var start:e.getValue()) {
                Point p=start;int count=0;
                while(inside.contains(p) && paths.add(p)) {
                    cancelled();if(++count>inside.size())throw new IllegalStateException("basin support cycle");p=b.routeFrom(p);
                }
            }
        }
        var value=Set.copyOf(paths);supported.put(b.identity(),value);return value;
    }
    private double runoff() { return step*(double)step*climate.precipitation()*climate.runoffCoeff(); }
    private double loss(WorldBasinRouting.Basin b) { return b.members().size()*step*(double)step*climate.evaporation(); }
    private double equilibrium(WorldBasinRouting.Basin b,double inflow) {
        double[] beds=b.members().stream().mapToDouble(routing::height).sorted().toArray();double high=b.sill()-1e-9;
        if(climate.evaporation()<=0)return high;
        double area=inflow/(climate.evaporation()*step*(double)step);
        if(area<1)return beds[0]-1e-9;
        if(area>=beds.length)return high;
        return Math.min(high,beds[Math.max(0,(int)Math.ceil(area)-1)]+1e-9);
    }
    private static void cancelled() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException("world water budget");
    }
}
