package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.geogenesis.worldgen.hydrology.model.LocalFlowEvents.*;

/**
 * Diagnostic one-way coupling: finite frozen G42 event preferences -> a shared cardinal block receiver.
 * A column is a declared control volume, NOT proof that two continuous trajectories physically merged.
 * Each event supplies its first incoming direction once per column; repeated sub-block states are not rain.
 * Events never read this receiver, so the L+1 halo remains valid (no hidden recursive feedback radius).
 * The receiver is a NEW geometry candidate, not unchanged G42, a natural river claim, Q, or water authority.
 */
public final class LocalFlowReceiverProbe {
    private static final Path OUT=Path.of(System.getProperty("localFlow.output","build/local-flow-reassessment/receiver"));
    private static final StringBuilder LOG=new StringBuilder();
    private static int checks,failures;
    private static final int[][] SIDES={{1,0},{0,1},{-1,0},{0,-1}};
    record Incident(int mass,double dx,double dz,String terminal) { }
    record Momentum(long mass,double x,double z) { }
    record Receiver(Point column,Point next,Momentum preference,String reason) { }
    record Field(Map<Point,Map<Id,Incident>> columns,int births,long states,long samples) { }
    private final Spec spec;
    private final WindowOutlets.Terrain terrain;
    private final LocalFlowJoinProbe.G42 tracer;
    LocalFlowReceiverProbe(Spec spec,WindowOutlets.Terrain terrain) throws Exception {
        this.spec=spec;this.terrain=terrain;this.tracer=new LocalFlowJoinProbe.G42();
    }
    private static void log(String s){LOG.append(s).append('\n');}
    private static void check(boolean ok,String s){checks++;if(!ok)failures++;log((ok?"PASS ":"FAIL ")+s);}
    private static void cancelled(){if(Thread.currentThread().isInterrupted())throw new CancellationException("shared receiver");}
    private List<Event> births(Rect domain){return new LocalFlowEvents(spec,terrain).events(domain);}
    Field field(Rect target,int owner,boolean reverse,double phase) throws Exception {
        return accumulate(target,births(target.expand(spec.lifetime()+1)),owner,reverse,phase);
    }
    Field accumulate(Rect target,List<Event> events,int owner,boolean reverse,double phase) throws Exception {
        var ordered=new ArrayList<>(events);var seen=new HashSet<Id>();
        for(var e:ordered)if(!seen.add(e.id()))throw new IllegalArgumentException("duplicate birth");
        ordered.sort(Comparator.comparingInt((Event e)->Math.floorDiv(e.start().x(),owner))
                .thenComparingInt(e->Math.floorDiv(e.start().z(),owner)).thenComparing(Event::id));
        if(reverse)Collections.reverse(ordered);
        var columns=new TreeMap<Point,Map<Id,Incident>>();long states=0,samples=0;
        for(var e:ordered) {
            cancelled();var run=tracer.trace(new SharedBoundaryDrainage.WorldCell(e.start().x(),e.start().z()),
                    terrain,12,4,phase,-phase,true,-Double.MAX_VALUE,spec.lifetime(),false);
            if(run.terminal().equals("WORK_WINDOW_LIMIT"))throw new IllegalStateException("working cache truncated event");
            states+=run.nodes().size();samples+=run.samples();var visited=new HashSet<Point>();
            for(var s:run.nodes()) {
                var p=new Point((int)Math.floor(s.x()),(int)Math.floor(s.z()));
                if(target.contains(p) && visited.add(p))columns.computeIfAbsent(p,k->new TreeMap<>()).put(e.id(),
                        new Incident(e.mass(),s.dx(),s.dz(),run.terminal()));
            }
        }
        var frozen=new TreeMap<Point,Map<Id,Incident>>();
        columns.forEach((p,c)->frozen.put(p,Collections.unmodifiableMap(c)));
        return new Field(Collections.unmodifiableMap(frozen),events.size(),states,samples);
    }
    static Momentum reduce(Map<Id,Incident> incoming) {
        long mass=0;double x=0,z=0;
        // Stable world identity order, not owner/traversal order (floating addition is not associative).
        for(var e:new TreeMap<>(incoming).values()) {
            mass+=e.mass();x+=e.mass()*e.dx();z+=e.mass()*e.dz();
        }
        return new Momentum(mass,x,z);
    }
    Receiver at(Point p) throws Exception {
        return receive(p,field(new Rect(p.x(),p.z(),p.x(),p.z()),16,false,0).columns().getOrDefault(p,Map.of()));
    }
    Receiver receive(Point p,Map<Id,Incident> incoming) {
        cancelled();var m=reduce(incoming);double norm=Math.hypot(m.x(),m.z()),h=height(p);
        var drops=new TreeMap<Point,Double>();double maxDrop=0;
        for(var d:SIDES) {
            var q=new Point(p.x()+d[0],p.z()+d[1]);double drop=h-height(q);
            if(drop>0 && descendingEdge(p,q)){drops.put(q,drop);maxDrop=Math.max(maxDrop,drop);}
        }
        if(drops.isEmpty())return new Receiver(p,null,m,"BLOCK_LOCAL_MINIMUM_OR_FLAT_PENDING");
        double best=-Double.MAX_VALUE;Point next=null;boolean tied=false;
        for(var e:drops.entrySet()) {
            Point q=e.getKey();double alignment=norm>0?(m.x()*(q.x()-p.x())+m.z()*(q.z()-p.z()))/norm:0;
            double score=0.8*e.getValue()/maxDrop+0.2*alignment;
            if(score>best){best=score;next=q;tied=false;}else if(score==best)tied=true;
        }
        return new Receiver(p,tied?null:next,m,tied?"EQUAL_RECEIVER_SCORE_PENDING":"STRICT_CARDINAL_DESCENT");
    }
    private double height(Point p) {
        double h=terrain.height(p.x(),p.z());if(!Double.isFinite(h))throw new IllegalArgumentException("finite terrain");return h;
    }
    private boolean descendingEdge(Point p,Point q) {
        double previous=height(p);
        for(int i=1;i<=8;i++) {
            double h=terrain.height(p.x()+(q.x()-p.x())*i/8.0,p.z()+(q.z()-p.z())*i/8.0);
            if(!Double.isFinite(h))throw new IllegalArgumentException("finite edge terrain");
            if(h>previous+1e-10)return false;previous=h;
        }
        return previous<height(p)-1e-12;
    }
    private List<Receiver> path(Point start,int budget) throws Exception {
        var path=new ArrayList<Receiver>();Point p=start;
        for(int i=0;i<budget;i++){var r=at(p);path.add(r);if(r.next()==null)break;p=r.next();}
        return List.copyOf(path);
    }
    private Map<Point,Receiver> receivers(Field f) {
        var result=new TreeMap<Point,Receiver>();f.columns().forEach((p,c)->result.put(p,receive(p,c)));return result;
    }
    private static LocalFlowReceiverProbe real(Spec spec,TerrainParams params) throws Exception {
        var gen=new CellGenerator(params,params.minY(),params.maxY());gen.seed(spec.seed());double hs=params.horizontalScale();
        return new LocalFlowReceiverProbe(spec,(x,z)->gen.erodedHeight(x/hs,z/hs));
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);long seed=args.length>0?Long.parseLong(args[0]):ViewSpec.SEED;
        var config=Path.of("run/config/geogenesis-common.toml");byte[] original=Files.readAllBytes(config);
        var copy=OUT.resolve("config.toml");Files.write(copy,original);
        try(var cfg=CommentedFileConfig.of(copy,TomlFormat.instance())) {
            cfg.load();GeoGenesisConfig.SPEC.setConfig(cfg);var params=GeoGenesisConfig.INSTANCE.buildParams();
            var spec=new Spec(seed,GeoGenesisConfig.configFingerprint(),8,1,24);
            algebra(spec);synthetic(spec);realChecks(spec,params);blockHandoff(spec,params);
        }
        check(Arrays.equals(original,Files.readAllBytes(config)),"original TOML unchanged");
        log("SUMMARY checks="+checks+" failures="+failures);
        log("SHARED_BLOCK_RECEIVER="+(failures==0?"ACCEPTED_LOCAL_CANDIDATE":"REJECTED"));
        log("G42_PATHS_CHANGED=NO; SHARED_GRAPH_IS_NEW_CARDINAL_CANDIDATE=true; NATURAL_SHAPE=UNVERIFIED");
        log("BASIN_HANDOFF=SEPARATE_PROBE; WATER_AUTHORITY=NONE; RUNOFF_Q=NOT_THIS_MODEL; UNBOUNDED_RIVER=NOT_ESTABLISHED");
        Files.writeString(OUT.resolve("metrics.txt"),LOG);System.out.print(LOG);System.exit(failures==0?0:1);
    }
    private static void algebra(Spec spec) throws Exception {
        var p=new Point(0,0);var a=new Id(spec.seed(),spec.configuration(),0,0,0);
        var b=new Id(spec.seed(),spec.configuration(),1,0,0);var c=new Id(spec.seed(),spec.configuration(),2,0,0);
        var left=new LinkedHashMap<Id,Incident>();left.put(a,new Incident(2,1,0,"PENDING"));
        left.put(b,new Incident(3,-1,0,"PENDING"));left.put(c,new Incident(4,0,1,"PENDING"));
        var reverse=new LinkedHashMap<Id,Incident>();reverse.put(c,left.get(c));reverse.put(b,left.get(b));reverse.put(a,left.get(a));
        check(reduce(left).equals(new Momentum(9,-1,4)),"three incoming event masses and direction sums have independent arithmetic oracle");
        check(reduce(left).equals(reduce(reverse)),"identity-ordered reduction ignores insertion order");
        var test=new LocalFlowReceiverProbe(spec,(x,z)->100-x-z);
        var chosen=test.receive(p,left);
        check(chosen.next().equals(new Point(0,1)),"shared preference breaks equal downhill slopes toward combined positive z");
        check(chosen.equals(test.receive(p,reverse)),"reverse three-inflow order preserves complete receiver state");
        check(!test.receive(p,Map.of(a,left.get(a))).next().equals(test.receive(p,Map.of(b,left.get(b))).next()),
                "negative control: first-arrival-only preference changes receiver");
        var tie=test.receive(p,Map.of());
        check(tie.next()==null && tie.reason().equals("EQUAL_RECEIVER_SCORE_PENDING"),"equal geometry scores remain pending, not coordinate-order outlet");
        var bowl=new LocalFlowReceiverProbe(spec,(x,z)->100+x*x+z*z);
        check(bowl.receive(p,left).next()==null,"momentum cannot force an uphill outlet from a true block minimum");
        // Stable order is essential even when integer masses happen to sum exactly.
        var floating=new LinkedHashMap<Id,Incident>();floating.put(a,new Incident(1,1,0,"PENDING"));
        floating.put(b,new Incident(1,-1,0,"PENDING"));floating.put(c,new Incident(1,1e-17,0,"PENDING"));
        var reversed=new LinkedHashMap<Id,Incident>();reversed.put(c,floating.get(c));reversed.put(b,floating.get(b));reversed.put(a,floating.get(a));
        check(reduce(floating).equals(reduce(reversed)),"non-associative floating directions reduced in world identity order");
    }
    private static void synthetic(Spec spec) throws Exception {
        WindowOutlets.Terrain plane=(x,z)->1000+x;
        var axis=new LocalFlowReceiverProbe(spec,plane);var box=new Rect(-8,-8,-1,-1);var f=axis.field(box,16,false,0);
        boolean west=true;for(var r:axis.receivers(f).values())west&=r.next().equals(new Point(r.column().x()-1,r.column().z()));
        check(!f.columns().isEmpty() && west,"independent -x plane oracle: every occupied column receives west");
        var translated=new LocalFlowReceiverProbe(spec,(x,z)->1000+x+64);
        var event=new Event(new Id(spec.seed(),spec.configuration(),0,0,0),new Point(-2,-4),3);
        var shifted=new Event(event.id(),new Point(-66,-52),3);
        var a=axis.accumulate(box,List.of(event),16,false,0);
        var b=translated.accumulate(new Rect(-72,-56,-65,-49),List.of(shifted),7,true,2);
        var shiftedBack=new TreeMap<Point,Map<Id,Incident>>();b.columns().forEach((p,v)->shiftedBack.put(new Point(p.x()+64,p.z()+48),v));
        check(a.columns().equals(shiftedBack) && !a.columns().isEmpty(),"negative translation preserves declared event preference and cardinal receiver physics");
        variants("synthetic",axis,box,f);write("synthetic",axis,f);confluence(spec);
    }
    private static void confluence(Spec spec) throws Exception {
        var test=new LocalFlowReceiverProbe(spec,(x,z)->1000-z+2*x*x);
        var a=test.path(new Point(-2,0),12);var b=test.path(new Point(2,0),12);var c=test.path(new Point(0,-2),12);
        var origin=new Point(0,0);int ia=-1,ib=-1,ic=-1;
        for(int i=0;i<a.size();i++)if(a.get(i).column().equals(origin))ia=i;
        for(int i=0;i<b.size();i++)if(b.get(i).column().equals(origin))ib=i;
        for(int i=0;i<c.size();i++)if(c.get(i).column().equals(origin))ic=i;
        boolean meet=ia>=0 && ib>=0 && ic>=0;check(meet,"three independent arms actually enter same world receiver");
        if(meet) {
            int n=Math.min(a.size()-ia,Math.min(b.size()-ib,c.size()-ic));
            check(n>=5 && a.subList(ia,ia+n).equals(b.subList(ib,ib+n)) && a.subList(ia,ia+n).equals(c.subList(ic,ic+n)),
                    "three independent arms share complete receiver suffix, no source momentum survives handoff");
        }
        var barrier=new LocalFlowReceiverProbe(spec,(x,z)->100-x+2*Math.sin(Math.PI*x)*Math.sin(Math.PI*x)+10*z*z);
        check(barrier.height(new Point(1,0))<barrier.height(origin) && barrier.receive(origin,Map.of()).next()==null,
                "negative control: lower endpoint beyond sub-block crest cannot authorize edge");
    }
    private static void variants(String name,LocalFlowReceiverProbe test,Rect box,Field f) throws Exception {
        check(f.columns().equals(test.field(box,7,true,2).columns()),name+" owner/reverse/window phase preserves incoming identities and directions");
        var larger=test.accumulate(box,test.births(box.expand(2*test.spec.lifetime()+8)),23,true,-2);
        check(larger.births()>f.births() && f.columns().equals(larger.columns()),name+" genuinely larger birth domain preserves complete local receiver input");
        var outer=test.field(box.expand(4),11,true,0);var crop=new TreeMap<Point,Map<Id,Incident>>();
        outer.columns().forEach((p,c)->{if(box.contains(p))crop.put(p,c);});
        check(f.columns().equals(crop),name+" shifted output overlap ignores output boundary");
        var first=f.columns().keySet().iterator().next();
        check(test.receive(first,f.columns().get(first)).equals(test.at(first)),name+" cold singleton downstream query equals full event-field receiver");
        var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<Future<Field>>();for(int i=0;i<3;i++)jobs.add(pool.submit(()->test.field(box,13,true,0)));
            boolean same=true;for(var job:jobs)same&=f.columns().equals(job.get().columns());
            check(same,name+" three concurrent independent queries agree");
        } finally {pool.shutdownNow();}
        boolean duplicate=false;var event=test.births(box.expand(25)).get(0);
        try{test.accumulate(box,List.of(event,event),16,false,0);}catch(IllegalArgumentException e){duplicate=true;}
        check(duplicate,name+" duplicate event cannot inject extra mass");
        boolean interrupted=false;
        try{Thread.currentThread().interrupt();test.field(box,16,false,0);}catch(CancellationException e){interrupted=true;}
        finally{Thread.interrupted();}
        check(interrupted && f.columns().equals(test.field(box,16,false,0).columns()),name+" cancellation leaves clean replay");
    }
    private static void realChecks(Spec spec,TerrainParams params) throws Exception {
        var test=real(spec,params);var box=new Rect(-784,368,-769,383);
        long fine=HierarchicalHydrology.Diag.FINE_SOLVES.get();var f=test.field(box,16,false,0);
        check(!f.columns().isEmpty(),"real world-born receiver inputs are nonempty");variants("real",test,box,f);
        var cold=real(spec,params);
        check(f.columns().equals(cold.field(box,7,true,-2).columns()),"independent cold real terrain rebuild matches all incoming identities/directions");
        long multi=f.columns().values().stream().filter(c->c.size()>1).count();
        log("OBSERVATION real multiEventColumns="+multi+" totalColumns="+f.columns().size());
        check(multi>0,"real world event field contains actual multi-event control volumes (not physical merge claim)");
        boolean downhill=true;int pending=0;
        for(var r:test.receivers(f).values()) {
            if(r.next()==null){pending++;continue;}
            downhill&=Math.abs(r.next().x()-r.column().x())+Math.abs(r.next().z()-r.column().z())==1;
            downhill&=test.height(r.next())<test.height(r.column());
        }
        check(downhill,"all published receiver edges are contiguous cardinal and strictly downhill with eight along-edge samples (not continuous proof)");
        log("OBSERVATION real pendingColumns="+pending+" (not dry/no-water)");
        // The graph state is shared; compare independently rebuilt suffixes at a receiver reached from an upstream query.
        var source=f.columns().keySet().iterator().next();var path=test.path(source,12);
        if(path.size()>2) {
            var pivot=path.get(2).column();var suffix=cold.path(pivot,path.size()-2);
            check(path.subList(2,path.size()).equals(suffix),"real upstream path suffix equals independent cold downstream-first reconstruction");
        } else check(false,"real path too short to test shared downstream suffix");
        check(HierarchicalHydrology.Diag.FINE_SOLVES.get()==fine,"zero neighbouring fine hydrology solves");
        write("real",test,f);writePath(path);
        log("COST births="+f.births()+" rawG42States="+f.states()+" rawHeightCallbacks="+f.samples()+" outputColumns="+f.columns().size());
        log("COST_SCOPE per-field only; eventDirectoryAndOutputMaterialized=true; samplerPeakNotCollected=true; NOT_PROVIDER_COST=true");
    }
    private static void blockHandoff(Spec spec,TerrainParams params) throws Exception {
        var test=real(spec,params);var sink=new Point(-428,-94);var start=new Point(-428,-92);
        var path=test.path(start,16);var last=path.get(path.size()-1);
        var csv=new StringBuilder("x,z,nextX,nextZ,mass,momentumX,momentumZ,reason\n");
        for(var r:path)append(csv,r);Files.writeString(OUT.resolve("real-basin-handoff.csv"),csv);
        var slab=new LocalFlowBlockHandoffProbe.Slab((x,z)->test.height(new Point(x,z)),-460,-126,-396,-62,false);
        var block=new LocalFlowBlockHandoffProbe.BlockCell(last.column().x(),last.column().z());
        boolean minimum=LocalFlowBlockHandoffProbe.strictAllNeighboursHigher(slab,block);
        log("OBSERVATION shared receiver handoff="+last.column()+" reason="+last.reason()+" independentlyFourMinimum="+minimum);
        if(spec.seed()==ViewSpec.SEED)check(last.column().equals(sink) && minimum,
                "real shared receiver reaches certified sample block minimum, not merely continuous pending");
        else check(last.next()==null || path.size()==16,"alternate seed retains explicit pending or path budget (sample coordinates not universal)");
        if(last.next()!=null || !minimum)return;
        var c=slab.certify(block.x(),block.z(),test.height(last.column()));
        check(c.closed(),"shared receiver terminal hands off nonempty independently closed block certificate");
        boolean same=true;var identities=new StringBuilder("entryX,entryZ,terminalX,terminalZ,ownerX,ownerZ,escape,members\n");
        for(var member:c.members()) {
            var p=new Point(member.x(),member.z());var arm=test.path(p,16);var end=arm.get(arm.size()-1);
            var resolved=slab.certify(end.column().x(),end.column().z(),test.height(end.column()));
            same&=end.next()==null && resolved.closed() && resolved.owner().equals(c.owner())
                    && resolved.escape()==c.escape() && resolved.members().equals(c.members());
            identities.append(p.x()).append(',').append(p.z()).append(',').append(end.column().x()).append(',')
                    .append(end.column().z()).append(',').append(resolved.owner().x()).append(',').append(resolved.owner().z())
                    .append(',').append(resolved.escape()).append(',').append(resolved.members().size()).append('\n');
        }
        Files.writeString(OUT.resolve("real-basin-identities.csv"),identities);
        check(!c.members().isEmpty() && same,"all real basin entries follow shared receivers to same floor/level/member identity");
        log("OBSERVATION shared block handoff owner="+c.owner()+" escape="+c.escape()+" members="+c.members().size()+" WATER_AUTHORITY=NONE");
    }
    private static void write(String name,LocalFlowReceiverProbe test,Field f) throws Exception {
        var csv=new StringBuilder("x,z,birthCellX,birthCellZ,round,mass,dirX,dirZ,rawTerminal\n");
        f.columns().forEach((p,events)->events.forEach((id,c)->csv.append(p.x()).append(',').append(p.z()).append(',')
                .append(id.cellX()).append(',').append(id.cellZ()).append(',').append(id.round()).append(',').append(c.mass())
                .append(',').append(c.dx()).append(',').append(c.dz()).append(',').append(c.terminal()).append('\n')));
        Files.writeString(OUT.resolve(name+"-incoming.csv"),csv);
        var routes=new StringBuilder("x,z,nextX,nextZ,mass,momentumX,momentumZ,reason\n");
        for(var r:test.receivers(f).values())append(routes,r);
        Files.writeString(OUT.resolve(name+"-receivers.csv"),routes);
    }
    private static void append(StringBuilder csv,Receiver r) {
        csv.append(r.column().x()).append(',').append(r.column().z()).append(',');
        if(r.next()!=null)csv.append(r.next().x()).append(',').append(r.next().z());else csv.append(',');
        csv.append(',').append(r.preference().mass()).append(',').append(r.preference().x()).append(',')
                .append(r.preference().z()).append(',').append(r.reason()).append('\n');
    }
    private static void writePath(List<Receiver> path) throws Exception {
        var csv=new StringBuilder("x,z,nextX,nextZ,mass,momentumX,momentumZ,reason\n");
        for(var r:path)append(csv,r);Files.writeString(OUT.resolve("real-shared-path.csv"),csv);
    }
}
