package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.SharedBoundaryDrainage.*;
import com.geogenesis.worldgen.hydrology.model.ProductionDrainageAudit.Region;
import java.util.*;
import java.util.concurrent.*;

/** Diagnostic only: checks source support, not physical outflow or the cost of obtaining owner inputs. */
public final class WorldSkeletonProbe {
    private static final WorldPolicy POLICY=new WorldPolicy(20261016,7,1,16,4);
    private static int failures;

    public static void main(String[] args) throws Exception {
        var tree=tree(0,0);checkTree(tree,0,0);checkTree(tree,-1,0);
        checkTree(tree(0,-16),0,-1);checkTree(tree(-16,-16),-1,-1);
        checkTree(corner(),-1,-1);checkTree(corner(),0,0);
        var a=query(tree,0,0);var b=query(tree,1,0);
        var crossing=new WorldEdge(at(12,0),at(16,0));
        check(a.support().get(crossing).equals(b.support().get(crossing)),"same crossing identity on both sides");
        check(b.routes().containsKey(at(-8,0)) && !tree.sources.contains(at(16,0)),"receiver does not re-admit source");
        check(!a.support().containsKey(new WorldEdge(at(0,8),at(4,8))),"source-free chain stays unsupported");
        check(a.routes().get(at(-8,0)).state()==WorldState.OUTSIDE_INPUT,"core crop is explicit pending, not ocean");
        check(b.routes().get(at(-8,0)).state()==WorldState.BASIN_PENDING,"basin keeps identity and no fabricated outflow");
        terminalCases();negativeCases();orderAndConcurrency(tree);
        System.out.println(failures==0?"ALL PASS":"FAILURES="+failures);
        System.exit(failures==0?0:1);
    }

    private static void check(boolean ok,String message) {
        System.out.println((ok?"[PASS] ":"[FAIL] ")+message);if(!ok)failures++;
    }
    private static WorldCell at(int x,int z){return new WorldCell(x,z);}
    private static Region owner(WorldCell p){return new Region(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));}
    private static boolean inside(WorldCell p,int rx,int rz) {
        return owner(p).equals(new Region(rx,rz));
    }
    private static WorldSkeleton.Result query(Fixture f,int rx,int rz) {
        return new WorldSkeleton(POLICY,f::product).query(rx,rz);
    }

    private static final class Fixture {
        final Map<WorldCell,WorldNode> nodes=new LinkedHashMap<>();
        final Set<WorldCell> sources=new LinkedHashSet<>();
        void chain(int ox,int oz,int[][] points) {
            for(int i=0;i<points.length;i++) {
                var a=at(ox+points[i][0],oz+points[i][1]);
                var b=i+1==points.length?null:at(ox+points[i+1][0],oz+points[i+1][1]);
                double level=200-i;
                nodes.put(a,new WorldNode(a,b,level,level,b==null?WorldState.BASIN_PENDING:WorldState.NEXT));
            }
        }
        WorldProduct product(Region region) {
            var data=new LinkedHashMap<WorldCell,WorldNode>();
            for(int x=0;x<16;x+=4)for(int z=0;z<16;z+=4) {
                var p=at(region.x()*16+x,region.z()*16+z);
                data.put(p,nodes.getOrDefault(p,new WorldNode(p,null,300,300,WorldState.INPUT_PENDING)));
            }
            var origins=new ArrayList<WorldOrigin>();
            for(var source:sources)if(owner(source).equals(region))origins.add(new WorldOrigin(source,20,10));
            return new WorldProduct(new WorldPiece(POLICY,region,data),new OriginPolicy(24,10,true),origins);
        }
    }

    private static Fixture tree(int ox,int oz) {
        var f=new Fixture();
        f.chain(ox,oz,new int[][]{{-8,0},{-4,0},{0,0},{4,0},{8,0},{12,0},{16,0},{20,0},{24,0}});
        var source=at(ox+4,oz-4);var join=at(ox+4,oz);
        f.nodes.put(source,new WorldNode(source,join,205,205,WorldState.NEXT));
        f.sources.add(at(ox-8,oz));f.sources.add(source);
        f.chain(ox,oz,new int[][]{{0,8},{4,8},{8,8}});return f;
    }
    private static Fixture corner() {
        var f=new Fixture();f.chain(0,0,new int[][]{{-8,-8},{-4,-4},{0,0},{4,4},{8,8}});
        f.sources.add(at(-8,-8));return f;
    }

    /** Full forward traversal from every declared source; no predecessor closure from the implementation. */
    private static Map<WorldEdge,Set<WorldCell>> oracle(Fixture f,int rx,int rz) {
        var result=new HashMap<WorldEdge,Set<WorldCell>>();
        for(var source:f.sources) {
            var p=source;var seen=new HashSet<WorldCell>();
            while(p!=null && seen.add(p)) {
                var n=f.nodes.get(p);if(n==null || n.next()==null)break;
                var edge=new WorldEdge(p,n.next());
                if(inside(p,rx,rz) || inside(n.next(),rx,rz))result.computeIfAbsent(edge,k->new HashSet<>()).add(source);
                p=n.next();
            }
        }
        return result;
    }
    private static void checkTree(Fixture f,int rx,int rz) {
        check(query(f,rx,rz).support().equals(oracle(f,rx,rz)),"full forward oracle core="+rx+","+rz);
    }
    private static void terminalCases() {
        for(var state:List.of(WorldState.BASIN_PENDING,WorldState.INPUT_PENDING,WorldState.OCEAN_CANDIDATE)) {
            var f=tree(0,0);var end=at(24,0);f.nodes.put(end,new WorldNode(end,null,190,190,state));
            var result=query(f,1,0);
            check(result.routes().get(at(-8,0)).state()==state,"explicit terminal "+state);
        }
        var f=new Fixture();var a=at(0,0);var b=at(4,0);f.sources.add(a);
        f.nodes.put(a,new WorldNode(a,b,100,100,WorldState.NEXT));
        f.nodes.put(b,new WorldNode(b,a,100,100,WorldState.NEXT));
        var cycle=query(f,0,0);
        check(cycle.support().isEmpty() && cycle.routes().get(a).state()==WorldState.CYCLE,"local cycle is not water");
        f.nodes.put(b,new WorldNode(b,null,101,101,WorldState.BASIN_PENDING));
        var rise=query(f,0,0);
        check(rise.support().isEmpty() && rise.routes().get(a).state()==WorldState.NONDESCENDING,"uphill is not water");
    }
    private static void negativeCases() {
        boolean missing=false;try {
            new WorldSkeleton(POLICY,r->new WorldProduct(new WorldPiece(POLICY,r,Map.of()),
                    new OriginPolicy(24,10,true),List.of())).query(0,0);
        }catch(IllegalStateException ex){missing=true;}
        check(missing,"missing input throws instead of returning dry");
        var f=tree(0,0);var skeleton=new WorldSkeleton(POLICY,f::product);boolean cancelled=false;
        try{Thread.currentThread().interrupt();skeleton.query(0,0);}
        catch(CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        check(cancelled && skeleton.query(0,0).equals(query(f,0,0)),"cancel then retry produces same result");
        boolean frozen=false;try{query(f,0,0).support().clear();}catch(UnsupportedOperationException ex){frozen=true;}
        check(frozen,"support map frozen");
    }
    private static void orderAndConcurrency(Fixture f) throws Exception {
        var expected=query(f,0,0);var reverse=new Fixture();
        var entries=new ArrayList<>(f.nodes.entrySet());Collections.reverse(entries);
        entries.forEach(e->reverse.nodes.put(e.getKey(),e.getValue()));
        var ids=new ArrayList<>(f.sources);Collections.reverse(ids);reverse.sources.addAll(ids);
        check(expected.equals(query(reverse,0,0)),"reverse insertion order same result");
        var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<Future<WorldSkeleton.Result>>();
            for(int i=0;i<3;i++)jobs.add(pool.submit(()->query(f,0,0)));
            boolean same=true;for(var job:jobs)same &= expected.equals(job.get(30,TimeUnit.SECONDS));
            check(same,"independent concurrent queries same result");
        }finally{pool.shutdownNow();}
    }
}
