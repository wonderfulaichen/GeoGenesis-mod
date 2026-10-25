package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Synthetic terrain; production D8/build and production incoming assembly, no Forge world. */
public final class MultiRegionDiscoveryProbe {
    static final int REGION=512, HALO=96, STEP=2, N=353;
    static final long SEED=9139912035078620160L;
    static final Path DEBUG=Path.of("D:/Office software/Development Project/GeoGenesis-mod/debug-agent.log");
    record Region(int x,int z) { }
    record Frame(int ox,int oz) {
        int cell(int x,int z) { return Math.floorDiv(x-ox,STEP)*N+Math.floorDiv(z-oz,STEP); }
        int x(int c) { return ox+c/N*STEP; }
        int z(int c) { return oz+c%N*STEP; }
    }
    final int first;
    final boolean productionParams=Boolean.getBoolean("probe.productionParams");
    final boolean physicalFlow=Boolean.getBoolean("probe.physicalFlow");
    final ModelHydrologyProvider provider;
    final Map<Region,DeterministicSkeleton.Result> primary=new HashMap<>(), finals=new HashMap<>();
    final Method incoming;
    final Constructor<?> keyCtor, passCtor;
    final Field cacheField, strategyField;
    final StringBuilder csv=new StringBuilder("arm,ownerX,ownerZ,pass1Traces,pass1Ports,incoming,finalTraces,finalPorts,pending,seaReached\n");

    MultiRegionDiscoveryProbe(int first) throws Exception {
        this.first=first;
        provider=new ModelHydrologyProvider(new CellGenerator(TerrainParams.defaults(),-64,320),SEED);
        provider.clear();
        Class<?> cls=ModelHydrologyProvider.class;
        incoming=cls.getDeclaredMethod("collectIncomingWires",int.class,int.class,int.class,int.class,int.class);
        incoming.setAccessible(true);
        cacheField=cls.getDeclaredField("P1_CACHE"); cacheField.setAccessible(true);
        strategyField=cls.getDeclaredField("strategy"); strategyField.setAccessible(true);
        Class<?> key=Class.forName(cls.getName()+"$CacheKey");
        keyCtor=key.getDeclaredConstructors()[0]; keyCtor.setAccessible(true);
        Class<?> pass=Class.forName(cls.getName()+"$Pass1");
        passCtor=Arrays.stream(pass.getDeclaredConstructors()).filter(c->c.getParameterCount()==5).findFirst().orElseThrow();
        passCtor.setAccessible(true);
    }

    Frame frame(Region r) { return new Frame(r.x*REGION-HALO,r.z*REGION-HALO); }
    double height(int x,int z) {
        return (Boolean.getBoolean("probe.fullPipeline") ? 63.0+(first+3)*REGION+256 : 10000.0)
                -x+10.0*Math.abs(z-256);
    }

    final Map<Region,HydrologyPipeline.Result> pipes=new HashMap<>();
    DeterministicSkeleton.Result buildPipeline(Region r,List<WireIn> wires,DeterministicSkeleton.Result base) {
        Frame f=frame(r);
        var p=pipes.computeIfAbsent(r, key -> {
            var tp=TerrainParams.defaults();
            var gen=new CellGenerator(tp,-64,320); gen.seed(SEED);
            var req=new HydrologyPipeline.Request(r.x*REGION+REGION/2,r.z*REGION+REGION/2,
                    REGION/2+HALO,STEP,new LakeWaterBalance.Climate(0.25,0,1));
            return HydrologyPipeline.run(gen,tp,req,new HydrologyPipeline.Layer(
                    (x,z)->height((int)x,(int)z),null,false,"synthetic-valley-production-pipeline"));
        });
        require(p.nx()==N && p.nz()==N,"pipeline frame dimensions");
        var params=ModelHydrologyProvider.productionParams(p.lakeResult().runoffPerCell());
        var outside=new DeterministicSkeleton.OutsideContinuation(this::height,f.ox,f.oz,STEP,2048,63);
        return DeterministicSkeleton.build(p.level(),p.parent(),p.resolved(),p.lake(),p.sea(),
                p.discharge(),p.lakeResult().basinOutlet(),N,N,params,
                wires.stream().map(WireIn::anchorCell).toList(),wires,p.bed(),null,outside,base,
                base==null?DeterministicSkeleton.PassRole.PRIMARY:DeterministicSkeleton.PassRole.SECOND_PASS);
    }

    DeterministicSkeleton.Result build(Region r,List<WireIn> wires,DeterministicSkeleton.Result base) {
        if (Boolean.getBoolean("probe.fullPipeline")) return buildPipeline(r,wires,base);
        Frame f=frame(r); int cells=N*N;
        double[] bed=new double[cells], flow=new double[cells]; int[] parent=new int[cells];
        boolean[] resolved=new boolean[cells], lake=new boolean[cells], sea=new boolean[cells];
        Arrays.fill(resolved,true); Arrays.fill(parent,-1);
        for(int i=0;i<N;i++) for(int j=0;j<N;j++) {
            int c=i*N+j, x=f.x(c), z=f.z(c);
            bed[c]=height(x,z); sea[c]=x>=(first+3)*REGION+256;
            if(i+1<N) parent[c]=c+N;
        }
        var params=productionParams ? ModelHydrologyProvider.productionParams(1.0)
                : new DeterministicSkeleton.Params(0,1.0,0,0,24,0,2,0,12);
        if (physicalFlow) {
            if (Boolean.getBoolean("probe.steepestParent")) {
                for (int i=0;i<N;i++) for (int j=0;j<N;j++) {
                    int k=i*N+j, best=-1; double slope=0;
                    for (int di=-1;di<=1;di++) for (int dj=-1;dj<=1;dj++) {
                        if (di==0 && dj==0 || i+di<0 || i+di>=N || j+dj<0 || j+dj>=N) continue;
                        int p=(i+di)*N+j+dj;
                        double drop=(bed[k]-bed[p])/Math.hypot(di,dj);
                        if (drop>slope) { slope=drop; best=p; }
                    }
                    parent[k]=best;
                }
            }
            flow=RiverNetwork.build(N,N,bed,parent,1.0,Map.of(),resolved,
                    RiverNetwork.Params.defaults()).discharge();
        } else if(base==null && r.equals(new Region(first,0))) {
            // Original interface counterexample: intentionally non-conserved supplied flow.
            flow[f.cell(first*REGION+16,256)]=params.minSourceAccum()+1;
        }
        var outside=new DeterministicSkeleton.OutsideContinuation(this::height,f.ox,f.oz,STEP,2048,-100000);
        return DeterministicSkeleton.build(bed,parent,resolved,lake,sea,flow,null,N,N,params,
                wires.stream().map(WireIn::anchorCell).toList(),wires,bed,
                productionParams ? null : Boolean.FALSE,outside,base,
                base==null?DeterministicSkeleton.PassRole.PRIMARY:DeterministicSkeleton.PassRole.SECOND_PASS);
    }

    DeterministicSkeleton.Result primary(Region r) {
        return primary.computeIfAbsent(r,key->build(key,List.of(),null));
    }

    @SuppressWarnings("unchecked")
    List<WireIn> actualIncoming(Region r) throws Exception {
        Map<Object,Object> cache=(Map<Object,Object>)cacheField.get(null);
        synchronized(cache) {
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                Region n=new Region(r.x+dx,r.z+dz); Frame f=frame(n); var sk=primary(n);
                Object key=keyCtor.newInstance(SEED,n.x,n.z,strategyField.getLong(provider));
                cache.put(key,passCtor.newInstance(null,sk.exitSeeds,f.ox,f.oz,sk));
            }
        }
        Frame f=frame(r);
        return (List<WireIn>)incoming.invoke(provider,r.x,r.z,N,f.ox,f.oz);
    }

    DeterministicSkeleton.Result query(Region r,String arm) throws Exception {
        if(finals.containsKey(r)) return finals.get(r);
        var base=primary(r); var wires=actualIncoming(r); var result=build(r,wires,base);
        finals.put(r,result); record(arm,r,base,wires.size(),result);
        return result;
    }

    void record(String arm,Region r,DeterministicSkeleton.Result base,int count,DeterministicSkeleton.Result result) {
        csv.append(arm).append(',').append(r.x).append(',').append(r.z).append(',')
            .append(base.traces.size()).append(',').append(base.ports.size()).append(',').append(count).append(',')
            .append(result.traces.size()).append(',').append(result.ports.size()).append(',')
            .append(result.pendingExits.size()).append(',').append(reachesSea(result)).append('\n');
    }

    static boolean reachesSea(DeterministicSkeleton.Result r) {
        return r.traces.stream().anyMatch(t->t.end==DeterministicSkeleton.End.MOUTH_SEA);
    }
    static void require(boolean test,String reason) { if(!test) throw new AssertionError(reason); }

    String digest() {
        StringBuilder s=new StringBuilder();
        finals.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<Region,DeterministicSkeleton.Result> e)->e.getKey().x)
                .thenComparingInt(e->e.getKey().z)).forEach(e->{
            Frame f=frame(e.getKey()); s.append(e.getKey());
            for(var t:e.getValue().traces) {
                s.append(t.end).append(':'); for(int c:t.cell) s.append(f.x(c)).append('/').append(f.z(c)).append(';');
            }
        });
        return s.toString();
    }

    void proveContinuousRule() {
        // Positive control: same build/terrain/wire validation; forward all emitted ports explicitly.
        // This is NOT a provider repair: it deliberately bypasses pass1-only discovery for diagnosis.
        var prior=primary(new Region(first,0));
        for(int x=first+1;x<=first+3;x++) {
            Region r=new Region(x,0); Frame f=frame(r); List<WireIn> wires=new ArrayList<>();
            for(var raw:prior.ports) {
                var p=raw.stamp(x-1,0,REGION);
                require(p.receiverRegionX()==x && p.receiverRegionZ()==0,"control owner mismatch");
                wires.add(new WireIn(f.cell(p.anchorX(),p.anchorZ()),f.cell(p.nextX(),p.nextZ()),
                        p.dirX(),p.dirZ(),p.anchorX(),p.anchorZ(),p.nextX(),p.nextZ()));
            }
            require(!wires.isEmpty(),"control lost relay before D");
            prior=build(r,wires,primary(r)); record("explicit-forward-control",r,primary(r),wires.size(),prior);
        }
        require(reachesSea(prior),"condition D8 itself must reach sea across four windows");
    }

    static void runCase(Path out,int first) throws Exception {
        var solo=new MultiRegionDiscoveryProbe(first);
        var isolated=solo.query(new Region(first+3,0),"downstream-only");
        require(solo.finals.size()==1 && !solo.primary.containsKey(new Region(first,0)),
                "downstream-only query must not discover A through a warm cache");
        require(isolated.traces.isEmpty(),"baseline downstream-only unexpectedly has a river");
        var middle=new MultiRegionDiscoveryProbe(first);
        var mid=middle.query(new Region(first+2,0),"middle-only");
        require(middle.finals.size()==1 && !middle.primary.containsKey(new Region(first,0)),
                "middle-only query must not solve A");
        require(mid.traces.isEmpty(),"baseline middle-only unexpectedly has a river");
        var forward=new MultiRegionDiscoveryProbe(first);
        for(int x=first;x<first+4;x++) forward.query(new Region(x,0),"forward");
        require(forward.primary(new Region(first,0)).ports.size()==1,"A must emit exactly one port");
        require(forward.finals.get(new Region(first+1,0)).pendingExits.size()==1,"B must expose unconsumed second-hop port");
        require(forward.finals.get(new Region(first+2,0)).traces.isEmpty(),"unexpected independent source in C");
        require(forward.finals.get(new Region(first+3,0)).traces.isEmpty(),"unexpected independent source in D");
        forward.proveContinuousRule();
        var reverse=new MultiRegionDiscoveryProbe(first);
        for(int x=first+3;x>=first;x--) reverse.query(new Region(x,0),"downstream-first");
        require(forward.digest().equals(reverse.digest()),"query order changed geometry");
        boolean desired=reachesSea(reverse.finals.get(new Region(first+3,0)));
        require(!desired,"baseline unexpectedly repaired: revise reproduction");
        Files.writeString(out.resolve("regions-"+first+".csv"),forward.csv.toString()
                +reverse.csv.substring(reverse.csv.indexOf("\n")+1)
                +solo.csv.substring(solo.csv.indexOf("\n")+1)
                +middle.csv.substring(middle.csv.indexOf("\n")+1));
        Files.writeString(out.resolve("geometry-"+first+".txt"),forward.digest());
        String line="{\"hypothesisId\":\"D8-DISCOVERY\",\"location\":\"actualIncoming\",\"firstRegion\":"+first
                +",\"B_pending\":1,\"D_has_chain\":"+desired+",\"explicitForwardSea\":true,\"orderIdentical\":true}\n";
        Files.writeString(DEBUG,line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        System.out.print(line);
    }

    int followPhysicalChain() {
        record Node(Region owner,int cell) { }
        Map<Node,Set<Node>> next=new HashMap<>(); Set<Node> seas=new HashSet<>();
        for(var entry:finals.entrySet()) {
            Region r=entry.getKey(); var sk=entry.getValue(); Frame f=frame(r);
            for(var t:sk.traces) {
                for(int i=1;i<t.cell.length;i++) next.computeIfAbsent(new Node(r,t.cell[i-1]),k->new HashSet<>())
                        .add(new Node(r,t.cell[i]));
                if(t.end==DeterministicSkeleton.End.MOUTH_SEA) seas.add(new Node(r,t.mouthCell()));
            }
            for(var raw:sk.ports) {
                var p=raw.stamp(r.x,r.z,REGION); Region receiver=new Region(p.receiverRegionX(),p.receiverRegionZ());
                if(!finals.containsKey(receiver)) continue;
                Frame rf=frame(receiver); Node a=new Node(r,f.cell(p.anchorX(),p.anchorZ()));
                Node b=new Node(receiver,rf.cell(p.anchorX(),p.anchorZ()));
                Node promised=new Node(receiver,rf.cell(p.nextX(),p.nextZ()));
                require(next.getOrDefault(b,Set.of()).contains(promised)
                        || finals.get(receiver).traces.stream().anyMatch(t-> {
                            for(int j=1;j<t.cell.length;j++) if(t.cell[j-1]==b.cell && t.cell[j]==promised.cell) return true;
                            return false;
                        }),"physical receiver must contain exact promised next");
                if (!Boolean.getBoolean("probe.dropPhysicalLinks"))
                    next.computeIfAbsent(a,k->new HashSet<>()).add(b);
            }
        }
        Region a=new Region(first,0); Frame f=frame(a);
        var root=primary(a).traces.stream().filter(t->Arrays.stream(t.cell).anyMatch(c->f.z(c)==256))
                .findFirst().orElseThrow(()->new AssertionError("no physical valley source in A"));
        ArrayDeque<Node> todo=new ArrayDeque<>(); Set<Node> seen=new HashSet<>(); todo.add(new Node(a,root.sourceCell()));
        while(!todo.isEmpty()) {
            Node node=todo.removeFirst(); if(!seen.add(node)) continue;
            if(seas.contains(node)) return seen.size();
            todo.addAll(next.getOrDefault(node,Set.of()));
        }
        throw new AssertionError("physical A valley chain has no linked path to sea across four owners");
    }

    static void runPhysicalCase(Path out,int first) throws Exception {
        var solo=new MultiRegionDiscoveryProbe(first);
        var isolated=solo.query(new Region(first+3,0),"physical-downstream-only");
        require(solo.finals.size()==1 && !solo.primary.containsKey(new Region(first,0)),
                "physical downstream query cannot warm remote A");
        require(!isolated.traces.isEmpty() && reachesSea(isolated),
                "physical downstream-only must independently contain a sea-reaching chain");
        var forward=new MultiRegionDiscoveryProbe(first);
        for(int x=first;x<first+4;x++) forward.query(new Region(x,0),"physical-forward");
        int linked=forward.followPhysicalChain();
        var reverse=new MultiRegionDiscoveryProbe(first);
        for(int x=first+3;x>=first;x--) reverse.query(new Region(x,0),"physical-reverse");
        require(forward.digest().equals(reverse.digest()),"physical flow query order changed geometry");
        Files.writeString(out.resolve("regions-"+first+".csv"),forward.csv.toString()
                +reverse.csv.substring(reverse.csv.indexOf("\n")+1)
                +solo.csv.substring(solo.csv.indexOf("\n")+1));
        Files.writeString(out.resolve("geometry-"+first+".txt"),forward.digest());
        String line="{\"hypothesisId\":\"D8-PHYSICAL-FLOW\",\"location\":\"actualIncoming\",\"firstRegion\":"+first
                +",\"D_has_chain\":"+!isolated.traces.isEmpty()+",\"D_reaches_sea\":"+reachesSea(isolated)
                +",\"orderIdentical\":true,\"linkedCoarseNodes\":"+linked
                +",\"fullPipeline\":"+Boolean.getBoolean("probe.fullPipeline")
                +",\"steepestParent\":"+Boolean.getBoolean("probe.steepestParent")+"}\n";
        Files.writeString(DEBUG,line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        System.out.print(line);
    }

    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]); Files.createDirectories(out);
        if (Boolean.getBoolean("probe.physicalFlow")) {
            runPhysicalCase(out,0); runPhysicalCase(out,-4);
            Files.writeString(out.resolve("summary.txt"),"PHYSICAL FLOW DIAGNOSTIC COMPLETE. Full pipeline="
                    +Boolean.getBoolean("probe.fullPipeline")+"; synthetic valley, existing D8 accumulator/build/assembler. Linked coarse A-to-sea path and cold query order checked; not global continuity or wet-column acceptance.\n");
            return;
        }
        require(DeterministicSkeleton.PORT_EGRESS,"boundaryPorts must be true");
        System.out.println("PARAMETERS production="+Boolean.getBoolean("probe.productionParams")
                +" values="+ModelHydrologyProvider.productionParams(1.0)
                +" defaultNetworkFilter="+DeterministicSkeleton.NETWORK_FILTER);
        runCase(out,0); runCase(out,-4);
        Files.writeString(out.resolve("summary.txt"),"REPRODUCED: condition D8 reaches sea with explicit relay; production pass1-only discovery loses second-hop.\nForward/downstream-first equal, both missing C/D. Negative coordinates reproduced.\nAcceptance FAIL: four-region independent discovery is not satisfied.\n");
        System.out.println("REPRODUCTION CHECKS PASS; REQUIRED DISCOVERY CONTRACT FAIL");
        System.exit(1);
    }
}
