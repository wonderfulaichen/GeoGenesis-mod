package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.*;
import java.util.concurrent.CancellationException;

/** World-coordinate geometry only. Neither a cache hit nor a region boundary authorizes water. */
final class WorldBasinRouting {
    static final int CHAIN_LIMIT = 128;
    enum State { PUBLISHED, UNKNOWN }
    record Lip(Point inner, Point outer, double crest) { }
    record Basin(State state, WorldBasin.State geometry, Point identity, double floor, double sill,
                 List<Point> members, Lip outlet, Map<Point, Point> next, List<Point> spillChain,
                 Point nextSink, boolean toSea, String reason) {
        Basin { members=List.copyOf(members);next=Map.copyOf(next);spillChain=List.copyOf(spillChain); }
        boolean published() { return state==State.PUBLISHED; }
        Point routeFrom(Point p) { return next.get(p); }
    }
    private static final Comparator<Point> ORDER=Comparator.comparingInt(Point::x).thenComparingInt(Point::z);
    private final WorldDrainage raw;
    private final WindowOutlets.Terrain terrain;
    private final int step,limit;
    private final Map<Point,Point> terminals=new HashMap<>();
    private final Map<Point,WorldBasin.Geometry> geometries=new HashMap<>();
    private final Map<Point,Basin> basins=new HashMap<>();
    record Outlet(WorldBasin.Geometry geometry, WorldBasin.Face face, Point saddle, double slope, String reason) {
        boolean known() { return face != null && reason.isEmpty(); }
    }
    private final Map<Point,Outlet> outlets=new HashMap<>();
    private final Map<Point,Double> heights=new HashMap<>();
    private final Map<Point,Point> flatRoutes=new HashMap<>();
    private final Set<Point> flatChecked=new HashSet<>();
    private final Map<Point,Point> directRoutes=new HashMap<>();
    private final Set<Point> directChecked=new HashSet<>();

    WorldBasinRouting(WorldDrainage raw,WindowOutlets.Terrain terrain,int step,int limit) {
        if(step<1 || limit<9 || limit>131072)throw new IllegalArgumentException("bounded basin routing");
        this.raw=raw;this.terrain=terrain;this.step=step;this.limit=limit;
    }
    Basin at(Point point) {
        cancelled();Point sink=terminal(point);
        if(raw.at(sink).state()==WorldDrainage.State.BELOW_SEA)return null;
        Basin old=basins.get(sink);if(old!=null)return old;
        var visited=new HashSet<Point>();var initial=outlet(sink);var cursor=initial;
        Point firstTarget=null;
        for(int i=0;i<CHAIN_LIMIT;i++) {
            if(!cursor.known())return remember(sink,unknown(cursor.reason()));
            Point target=receiver(cursor);
            if(target==null)return remember(sink,unknown("spill path never leaves raw catchment"));
            if(firstTarget==null)firstTarget=target;
            if(raw.at(target).state()==WorldDrainage.State.BELOW_SEA)
                return remember(sink,publish(initial,firstTarget,firstTarget.equals(target)));
            if(!visited.add(target))return remember(sink,unknown("basin cycle"));
            var downstream=outlet(target);
            if(!downstream.known())return remember(sink,unknown("downstream "+downstream.reason()));
            if(downstream.geometry().sill()>initial.geometry().sill()) {
                if(!new HashSet<>(downstream.geometry().members()).containsAll(initial.geometry().members()))
                    return remember(sink,unknown("backwater incomplete merge"));
                initial=downstream;firstTarget=null;
            }
            cursor=downstream;
        }
        return remember(sink,unknown("basin depth budget"));
    }
    Outlet outlet(Point sink) {
        var old=outlets.get(sink);if(old!=null)return old;
        var solver=new WorldBasin(raw,terrain,step,limit);
        var g=solver.resolveHydraulic(sink);geometries.put(sink,g);
        var decision=chooseOutlet(g);
        if(decision.known() && !decision.face().equals(pathFace(g))) {
            var rebuilt=solver.through(g,decision.face());
            decision=WorldBasin.settled(rebuilt.state())
                    ?new Outlet(rebuilt,decision.face(),decision.saddle(),decision.slope(),"")
                    :new Outlet(rebuilt,null,decision.saddle(),decision.slope(),"selected spill path "+rebuilt.state());
        }
        outlets.put(sink,decision);return decision;
    }
    Outlet chooseOutlet(WorldBasin.Geometry g) {
        if(!WorldBasin.settled(g.state()) || g.exits().isEmpty())
            return new Outlet(g,null,null,Double.NaN,g.state().name());
        var original=pathFace(g);
        if(original==null)return new Outlet(g,null,null,Double.NaN,"missing spill lip");
        if(g.exits().size()==1 || g.exits().stream().allMatch(f->f.outer().equals(original.outer())))
            return new Outlet(g,original,null,Double.NaN,"");
        var shared=new HashSet<>(crestColumns(g.exits().get(0)));
        for(var face:g.exits())shared.retainAll(crestColumns(face));
        if(shared.size()!=1)return new Outlet(g,null,null,Double.NaN,"MULTIPLE_EXITS shared saddle count="+shared.size());
        Point saddle=shared.iterator().next();double best=0;WorldBasin.Face chosen=null;boolean tied=false;
        for(var face:g.exits()) {
            double slope=outwardSlope(face,saddle);
            if(!(slope>0))return new Outlet(g,null,saddle,Double.NaN,"MULTIPLE_EXITS no outward descent");
            if(slope>best){best=slope;chosen=face;tied=false;}
            else if(slope==best)tied=true;
        }
        if(tied)return new Outlet(g,null,saddle,best,"MULTIPLE_EXITS tied outward descent");
        return new Outlet(g,chosen,saddle,best,"");
    }
    private WorldBasin.Face pathFace(WorldBasin.Geometry g) {
        for(int i=1;i<g.path().size();i++) {
            var face=new WorldBasin.Face(g.path().get(i-1),g.path().get(i),g.sill());
            if(g.exits().contains(face))return face;
        }
        return null;
    }
    private double outwardSlope(WorldBasin.Face face,Point saddle) {
        int at=-1;
        for(int i=0;i<=step;i++)if(column(face.inner(),face.outer(),i).equals(saddle)){at=i;break;}
        if(at<0 || at==step)return Double.NaN;
        for(int i=at+1;i<=step;i++)if(height(column(face.inner(),face.outer(),i))>face.crest())return Double.NaN;
        Point next=column(face.inner(),face.outer(),at+1);
        return (face.crest()-height(next))/Math.hypot(next.x()-saddle.x(),next.z()-saddle.z());
    }
    private Point column(Point a,Point b,int i) {
        return new Point(a.x()+(b.x()-a.x())*i/step,a.z()+(b.z()-a.z())*i/step);
    }
    private Set<Point> crestColumns(WorldBasin.Face face) {
        var points=new HashSet<Point>();
        for(int i=0;i<=step;i++) {
            var p=new Point(face.inner().x()+(face.outer().x()-face.inner().x())*i/step,
                    face.inner().z()+(face.outer().z()-face.inner().z())*i/step);
            if(height(p)==face.crest())points.add(p);
        }
        return points;
    }
    private Basin remember(Point sink,Basin b) { basins.put(sink,b);return b; }
    private Basin unknown(String why) {
        return new Basin(State.UNKNOWN,WorldBasin.State.GEOMETRY_PENDING,null,Double.NaN,Double.NaN,
                List.of(),null,Map.of(),List.of(),null,false,why);
    }
    private Point receiver(Outlet outlet) {
        var g=outlet.geometry();var members=new HashSet<>(g.members());
        int first=g.path().indexOf(outlet.face().outer());
        for(int i=first;i>=0 && i<g.path().size();i++) {
            Point target=terminal(g.path().get(i));
            if(!members.contains(target))return target;
        }
        return null;
    }
    Map<Point,WorldBasin.Geometry> geometriesForProbe() { return Map.copyOf(geometries); }
    Point terminal(Point point) {
        cancelled();Point old=terminals.get(point);if(old!=null)return old;
        var path=new ArrayList<Point>();Point p=point;
        for(int i=0;i<4096;i++) {
            cancelled();path.add(p);Point next=baseNext(p);
            if(next==null){for(var v:path)terminals.put(v,p);return p;}
            p=next;
        }
        throw new IllegalStateException("world flat trace budget exhausted, not a terminal");
    }
    Point baseNext(Point p) {
        var s=raw.at(p);
        if(s.state()==WorldDrainage.State.BELOW_SEA)return null;
        Point direct=directNext(p);if(direct!=null)return direct;
        if(!flatChecked.contains(p))resolveFlat(p);
        return flatRoutes.get(p);
    }
    private Point directNext(Point p) {
        if(directChecked.contains(p))return directRoutes.get(p);
        double h=height(p),best=0;Point next=null;
        for(var n:neighbours(p)) {
            double drop=(h-height(n))/Math.hypot(n.x()-p.x(),n.z()-p.z());
            if(drop>best && crest(p,n)<=h){best=drop;next=n;}
        }
        directChecked.add(p);if(next!=null)directRoutes.put(p,next);return next;
    }
    private void resolveFlat(Point start) {
        double level=height(start);var seen=new HashSet<Point>();var queue=new ArrayDeque<Point>();
        var drains=new TreeSet<Point>(Comparator.comparingDouble((Point p)->height(directNext(p))).thenComparing(ORDER));
        seen.add(start);queue.add(start);
        while(!queue.isEmpty()) {
            cancelled();Point p=queue.remove();
            for(var n:neighbours(p)) {
                if(height(n)!=level || crest(p,n)>level)continue;
                if(directNext(n)!=null){drains.add(n);continue;}
                if(seen.add(n)) {
                    if(seen.size()>limit)throw new RoutingBudget();
                    queue.add(n);
                }
            }
        }
        if(drains.isEmpty()) {
            // A closed flat has one sink, independent of the first queried point.
            Point root=seen.stream().min(ORDER).orElseThrow();queue.add(root);
        } else {
            Point drain=drains.first();queue.add(drain);
        }
        var routes=new HashMap<Point,Point>();var reached=new HashSet<Point>();reached.add(queue.peek());
        while(!queue.isEmpty()) {
            cancelled();Point p=queue.remove();
            for(var n:neighbours(p))if(seen.contains(n) && crest(p,n)<=level && reached.add(n)) {
                routes.put(n,p);queue.add(n);
            }
        }
        flatRoutes.putAll(routes);flatChecked.addAll(seen);
    }
    static final class RoutingBudget extends RuntimeException { }
    private Basin publish(Outlet outlet,Point target,boolean sea) {
        var g=outlet.geometry();var face=outlet.face();
        var members=new HashSet<>(g.members());
        if(!face.equals(pathFace(g)))return unknown("selected spill lip missing from witness");
        var next=new LinkedHashMap<>(BasinStorageRouting.build(g.members(),face.inner(),face.outer(),
                g.sill(),this::height,this::neighbours,this::crest));
        int first=g.path().indexOf(face.outer());if(first<0)return unknown("missing spill lip");
        var corridor=new ArrayList<Point>();
        for(int i=first;i<g.path().size();i++) {
            var p=g.path().get(i);corridor.add(p);
            boolean into=members.contains(terminal(p));
            if(!into)return new Basin(State.PUBLISHED,g.state(),g.identity(),g.floor(),g.sill(),g.members(),
                    new Lip(face.inner(),face.outer(),face.crest()),next,corridor,target,sea,"");
            if(i+1<g.path().size())next.put(p,g.path().get(i+1));
        }
        return unknown("spill path never leaves raw catchment");
    }
    List<Point> neighbours(Point p) {
        var out=new ArrayList<Point>(8);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0 || dz!=0)
            out.add(new Point(Math.addExact(p.x(),dx*step),Math.addExact(p.z(),dz*step)));
        out.sort(ORDER);return out;
    }
    double height(Point p) {
        Double old=heights.get(p);if(old!=null)return old;
        if(heights.size()>=524288)throw new IllegalStateException("basin geometry cache budget");
        double h=terrain.height(p.x(),p.z());if(!Double.isFinite(h))throw new IllegalArgumentException("finite terrain");
        heights.put(p,h);return h;
    }
    private double crest(Point a,Point b) {
        double h=Math.max(height(a),height(b));
        for(int i=1;i<step;i++)h=Math.max(h,height(new Point(a.x()+(b.x()-a.x())*i/step,a.z()+(b.z()-a.z())*i/step)));
        return h;
    }
    private static void cancelled() { if(Thread.currentThread().isInterrupted())throw new CancellationException("basin routing"); }
}
