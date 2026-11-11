package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Diagnostic source support on frozen owner inputs; not a production source-discovery or outflow authority. */
final class WorldSkeleton {
    record Result(Map<SharedBoundaryDrainage.WorldEdge, Set<SharedBoundaryDrainage.WorldCell>> support,
                  Map<SharedBoundaryDrainage.WorldCell, SharedBoundaryDrainage.WorldRoute> routes,
                  int inspectedNodes, int sourceOwners) {
        Result {
            var edges=new HashMap<SharedBoundaryDrainage.WorldEdge,Set<SharedBoundaryDrainage.WorldCell>>();
            support.forEach((e,s)->edges.put(e,Set.copyOf(s)));support=Map.copyOf(edges);routes=Map.copyOf(routes);
        }
    }
    private static final Comparator<SharedBoundaryDrainage.WorldCell> ORDER=Comparator
            .comparingInt(SharedBoundaryDrainage.WorldCell::x).thenComparingInt(SharedBoundaryDrainage.WorldCell::z);
    private final SharedBoundaryDrainage.WorldPolicy policy;
    private final Function<ProductionDrainageAudit.Region,SharedBoundaryDrainage.WorldProduct> input;
    private final Map<ProductionDrainageAudit.Region,SharedBoundaryDrainage.WorldProduct> owners=new HashMap<>();
    private final Map<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode> inspected=new HashMap<>();

    WorldSkeleton(SharedBoundaryDrainage.WorldPolicy policy,
                  Function<ProductionDrainageAudit.Region,SharedBoundaryDrainage.WorldProduct> input) {
        this.policy=policy;this.input=input;
    }

    private SharedBoundaryDrainage.WorldNode node(SharedBoundaryDrainage.WorldCell at) {
        cancelled();var old=inspected.get(at);if(old!=null)return old;
        if(inspected.size()>=262144)throw new IllegalStateException("world skeleton inspection budget exhausted; not a dry result");
        var owner=new ProductionDrainageAudit.Region(Math.floorDiv(at.x(),policy.tract()),Math.floorDiv(at.z(),policy.tract()));
        var product=owners.computeIfAbsent(owner,input);
        if(!product.piece().policy().equals(policy) || !product.piece().owner().equals(owner))
            throw new IllegalArgumentException("wrong source-owner product");
        var value=product.piece().nodes().get(at);
        if(value==null)throw new IllegalStateException("missing owned successor at "+at);
        inspected.put(at,value);return value;
    }

    Result query(int rx,int rz) {
        int step=policy.step(),tract=policy.tract();var selected=new HashSet<SharedBoundaryDrainage.WorldCell>();
        var queue=new ArrayDeque<SharedBoundaryDrainage.WorldCell>();
        int ox=Math.multiplyExact(rx,tract),oz=Math.multiplyExact(rz,tract);
        for(int x=0;x<tract;x+=step)for(int z=0;z<tract;z+=step) {
            var at=new SharedBoundaryDrainage.WorldCell(Math.addExact(ox,x),Math.addExact(oz,z));selected.add(at);queue.add(at);
        }
        // Complete predecessor closure, including the adjacent endpoints of edges entering this core.
        while(!queue.isEmpty()) {
            cancelled();var at=queue.remove();node(at);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                if(dx==0 && dz==0)continue;
                var prev=new SharedBoundaryDrainage.WorldCell(Math.addExact(at.x(),dx*step),Math.addExact(at.z(),dz*step));
                var candidate=node(prev);
                if(candidate.state()==SharedBoundaryDrainage.WorldState.NEXT && at.equals(candidate.next()) && selected.add(prev)) {
                    if(selected.size()>131072)throw new IllegalStateException("world skeleton upstream budget exhausted; not a dry result");
                    queue.add(prev);
                }
            }
        }
        var origins=new java.util.TreeSet<SharedBoundaryDrainage.WorldCell>(ORDER);
        for(var product:owners.values())for(var origin:product.origins())if(selected.contains(origin.identity()))origins.add(origin.identity());
        if(origins.size()>4096)throw new IllegalStateException("world skeleton origin budget exhausted");
        return project(origins,selected,ox,oz,tract);
    }

    private Result project(Set<SharedBoundaryDrainage.WorldCell> origins,Set<SharedBoundaryDrainage.WorldCell> selected,
                           int ox,int oz,int tract) {
        var support=new HashMap<SharedBoundaryDrainage.WorldEdge,Set<SharedBoundaryDrainage.WorldCell>>();
        var routes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldRoute>();int pairs=0;
        for(var origin:origins) {
            var path=new ArrayList<SharedBoundaryDrainage.WorldCell>();var seen=new HashSet<SharedBoundaryDrainage.WorldCell>();
            var at=origin;var state=SharedBoundaryDrainage.WorldState.OUTSIDE_INPUT;
            while(selected.contains(at)) {
                cancelled();if(!seen.add(at)){state=SharedBoundaryDrainage.WorldState.CYCLE;break;}
                if(path.size()==4096)throw new IllegalStateException("world skeleton route budget exhausted; not a dry result");
                var n=node(at);path.add(at);state=n.state();
                if(state!=SharedBoundaryDrainage.WorldState.NEXT)break;
                var next=n.next();long dx=(long)next.x()-at.x(),dz=(long)next.z()-at.z();
                if(Math.abs(dx)>policy.step() || Math.abs(dz)>policy.step() || dx==0 && dz==0)
                    throw new IllegalStateException("nonlocal owned successor");
                var receiver=node(next);
                if(receiver.level()>n.level()+1e-9){state=SharedBoundaryDrainage.WorldState.NONDESCENDING;break;}
                at=next;
            }
            if(state==SharedBoundaryDrainage.WorldState.NEXT)state=SharedBoundaryDrainage.WorldState.OUTSIDE_INPUT;
            routes.put(origin,new SharedBoundaryDrainage.WorldRoute(path,state));
            if(state==SharedBoundaryDrainage.WorldState.CYCLE || state==SharedBoundaryDrainage.WorldState.NONDESCENDING)continue;
            for(var from:path) {
                var n=node(from);if(n.state()!=SharedBoundaryDrainage.WorldState.NEXT)continue;
                var to=n.next();if(!inside(from,ox,oz,tract) && !inside(to,ox,oz,tract))continue;
                var edge=new SharedBoundaryDrainage.WorldEdge(from,to);
                if(support.computeIfAbsent(edge,k->new HashSet<>()).add(origin) && ++pairs>1048576)
                    throw new IllegalStateException("world skeleton source-edge budget exhausted");
            }
        }
        return new Result(support,routes,inspected.size(),owners.size());
    }

    private static boolean inside(SharedBoundaryDrainage.WorldCell at,int ox,int oz,int tract) {
        return at.x()>=ox && at.z()>=oz && (long)at.x()<(long)ox+tract && (long)at.z()<(long)oz+tract;
    }
    private static void cancelled() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException("world skeleton source support");
    }
    private WorldSkeleton() { throw new AssertionError(); }
}
