package com.geogenesis.worldgen.hydrology.model;

import java.nio.file.*;
import java.util.*;
import com.geogenesis.worldgen.terrain.*;

/** Reads final traces and actual columns. EXIT is never a water terminal. */
public final class BoundaryRepairAudit {
    record P(int x, int z) { }
    record R(int x, int z) { }
    record Walk(String end, int steps, int hops) { }
    final ModelHydrologyProvider provider;
    final Map<R, Map<P,P>> successors = new HashMap<>();
    final Map<R, Map<P,DeterministicSkeleton.End>> terminals = new HashMap<>();
    final Map<R, List<BoundaryPort>> ports = new HashMap<>();
    final Map<R, double[][]> shapes = new HashMap<>();
    final Map<R, int[][]> paths = new HashMap<>();
    final Map<R,Integer> pending = new HashMap<>();
    final GeoGenesisTerrain terrain;
    BoundaryRepairAudit(ModelHydrologyProvider p, GeoGenesisTerrain terrain) { provider=p; this.terrain=terrain; }

    void load(R r) {
        if (successors.containsKey(r)) return;
        if (successors.size() >= 40) throw new IllegalStateException("audit region bound");
        var sk=provider.finalSkeletonForProbe(r.x,r.z);
        var next=new LinkedHashMap<P,P>(); var ends=new LinkedHashMap<P,DeterministicSkeleton.End>();
        int[][] ps=provider.reachNodesWorldForProbe(r.x*512+256,r.z*512+256);
        for(int t=0;t<ps.length;t++) {
            int[] a=ps[t];
            for(int i=2;i<a.length;i+=2) {
                P from=new P(a[i-2],a[i-1]), to=new P(a[i],a[i+1]);
                P old=next.putIfAbsent(from,to);
                if(old!=null&&!old.equals(to)) throw new AssertionError("multiple successor "+r+from);
            }
            if(a.length>0) {
                var e=sk.traces.get(t).end; P last=new P(a[a.length-2],a[a.length-1]);
                var old=ends.get(last);
                if(old==null||e==DeterministicSkeleton.End.MOUTH_LAKE||e==DeterministicSkeleton.End.MOUTH_SEA)
                    ends.put(last,e);
            }
        }
        successors.put(r,next); terminals.put(r,ends); paths.put(r,ps);
        shapes.put(r,provider.shapedNodesWorldForProbe(r.x*512+256,r.z*512+256));
        ports.put(r,provider.finalPortsForProbe(r.x,r.z));
        pending.put(r,sk.pendingExits.size()+sk.unsatisfiedIncoming.size()+sk.wireRejects.size()
            +(sk.roleConflict?1:0)+provider.wireAssemblyRejectsForProbe(r.x,r.z).size());
    }

    Walk follow(R r,P at) {
        Set<String> seen=new HashSet<>(); int hops=0;
        for(int step=0;step<8192;step++) {
            load(r); if(!seen.add(r+":"+at)) return new Walk("CYCLE",step,hops);
            P next=successors.get(r).get(at);
            if(next!=null) { at=next; continue; }
            var e=terminals.get(r).get(at);
            if(e==DeterministicSkeleton.End.MOUTH_LAKE||e==DeterministicSkeleton.End.MOUTH_SEA)
                return new Walk(waterBody(at.x,at.z)?e.name():"DRY_"+e.name(),step,hops);
            if(e==null&&waterBody(at.x,at.z)) return new Walk("ABSORBED_WATER",step,hops);
            if(e!=DeterministicSkeleton.End.BOUNDARY_EXIT) return new Walk(String.valueOf(e),step,hops);
            BoundaryPort hit=null;
            for(var p:ports.get(r)) if(p.anchorX()==at.x&&p.anchorZ()==at.z) {hit=p;break;}
            if(hit==null) return new Walk("MISSING_PORT",step,hops);
            if(++hops>8) return new Walk("HOP_LIMIT",step,hops);
            r=new R(hit.receiverRegionX(),hit.receiverRegionZ());
        }
        return new Walk("STEP_LIMIT",8192,hops);
    }

    Cell column(int x,int z) {
        return terrain.getChunkCells(Math.floorDiv(x,16),Math.floorDiv(z,16))[
                Math.floorMod(x,16)*16+Math.floorMod(z,16)];
    }

    boolean wet(int x,int z) {
        return ProductionViewProbe.waterKind(column(x,z),terrain.riversEnabled(),terrain.seaLevel())!=0;
    }

    boolean waterBody(int x,int z) {
        Cell c=column(x,z);
        return wet(x,z)&&(c.lakeMask||c.height<terrain.seaLevel());
    }

    boolean shapeHas(R r,P p) {
        for(double[] a:shapes.get(r)) for(int i=0;i<a.length;i+=2)
            if(a[i]==p.x&&a[i+1]==p.z) return true;
        return false;
    }

    void run(Path out,List<R> owners) throws Exception {
        StringBuilder csv=new StringBuilder("sx,sz,ax,az,nx,nz,rx,rz,receiverHasEdge,shapeAnchor,wetAnchor,wetNext,terminal,steps,hops\n");
        int failures=0,total=0;
        for(R r:owners) {
            load(r);
            for(var port:ports.get(r)) {
                total++; R recv=new R(port.receiverRegionX(),port.receiverRegionZ()); load(recv);
                P anchor=new P(port.anchorX(),port.anchorZ()), next=new P(port.nextX(),port.nextZ());
                P actual=successors.get(recv).get(anchor);
                boolean absorbed=actual==null&&waterBody(anchor.x,anchor.z)&&waterBody(next.x,next.z);
                boolean edge=next.equals(actual)||absorbed;
                boolean shape=shapeHas(r,anchor)&&(shapeHas(recv,anchor)||absorbed);
                boolean wa=wet(anchor.x,anchor.z), wn=wet(next.x,next.z);
                Walk w=follow(recv,anchor);
                boolean water=w.end.equals("MOUTH_LAKE")||w.end.equals("MOUTH_SEA")||w.end.equals("ABSORBED_WATER");
                if(!edge||!shape||!wa||!wn||!water) failures++;
                csv.append(r.x).append(',').append(r.z).append(',').append(anchor.x).append(',').append(anchor.z)
                    .append(',').append(next.x).append(',').append(next.z).append(',').append(recv.x).append(',').append(recv.z)
                    .append(',').append(edge).append(',').append(shape).append(',').append(wa).append(',').append(wn)
                    .append(',').append(w.end).append(',').append(w.steps).append(',').append(w.hops).append('\n');
            }
        }
        Files.writeString(out.resolve("all-ports.csv"),csv);
        String summary="ports="+total+" failing="+failures+" pendingByRegion="+pending+"\n";
        Files.writeString(out.resolve("summary.txt"),summary);System.out.print(summary);
        if(failures>0||pending.values().stream().anyMatch(v->v>0)) System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        Path copy=out.resolve("config.toml");Files.copy(Path.of("run/config/geogenesis-common.toml"),copy);
        var cfg=com.electronwill.nightconfig.core.file.CommentedFileConfig.of(copy,
            com.electronwill.nightconfig.toml.TomlFormat.instance());cfg.load();
        com.geogenesis.config.GeoGenesisConfig.SPEC.setConfig(cfg);
        var gen=new CellGenerator(com.geogenesis.config.GeoGenesisConfig.INSTANCE.buildParams(),-64,320);
        long seed=args.length>2?Long.parseLong(args[2]):9139912035078620160L;gen.seed(seed);
        var terrain=new GeoGenesisTerrain(gen); terrain.seed(seed);
        var engineField=GeoGenesisTerrain.class.getDeclaredField("hydrologyExperiment"); engineField.setAccessible(true);
        var engine=engineField.get(terrain);
        var providerField=engine.getClass().getDeclaredField("modelProvider"); providerField.setAccessible(true);
        var audit=new BoundaryRepairAudit((ModelHydrologyProvider)providerField.get(engine),terrain);
        List<R> owners=new ArrayList<>();
        for(String s:args[1].split(";")) {String[] v=s.split(":");owners.add(new R(Integer.parseInt(v[0]),Integer.parseInt(v[1])));}
        audit.run(out,owners);
    }
}
