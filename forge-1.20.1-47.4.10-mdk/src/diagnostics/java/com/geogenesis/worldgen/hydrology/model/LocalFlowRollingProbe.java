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
 * World birth events driving the unchanged G42 tracer, not five selected paths or a river provider.
 * L is the number of accepted moves (G42 emits at most L+1 states), not the cardinal model's L visits.
 * Each move has norm <=1. Integer births contributing to floor(x),floor(z) in the target therefore
 * lie within L columns; halo=L+1 additionally guards floating-point boundary rounding. Sampling
 * may extend beyond the path; only the moving sampler limits its cache, never the output rectangle.
 * This is finite-process intensity. Raw G42 pending terminals remain labelled, not dry or lake exits.
 * No lifetime extension, shared confluence rule, runoff Q or production source admission is claimed.
 */
public final class LocalFlowRollingProbe {
    private static final Path OUT=Path.of(System.getProperty("localFlow.output","build/local-flow-reassessment/rolling"));
    private static final StringBuilder LOG=new StringBuilder();
    private static int checks,failures;
    private static LocalFlowJoinProbe.G42 tracer;
    private static TerrainParams params;
    private static Spec spec;
    record Contribution(int states, int mass, String terminal) { }
    record Query(Map<Point,Map<Id,Contribution>> columns,int births,long states,long samples,int moves,
                 int peakPath,int peakRetainedPaths,Map<String,Integer> terminals) { }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);long seed=args.length>0?Long.parseLong(args[0]):ViewSpec.SEED;
        Path config=Path.of("run/config/geogenesis-common.toml");byte[] original=Files.readAllBytes(config);
        Path copy=OUT.resolve("config.toml");Files.write(copy,original);
        try(var cfg=CommentedFileConfig.of(copy,TomlFormat.instance())) {
            cfg.load();GeoGenesisConfig.SPEC.setConfig(cfg);params=GeoGenesisConfig.INSTANCE.buildParams();
            spec=new Spec(seed,GeoGenesisConfig.configFingerprint(),8,1,24);
            tracer=new LocalFlowJoinProbe.G42();synthetic();real();
        }
        check(Arrays.equals(original,Files.readAllBytes(config)),"original TOML unchanged");
        log("SUMMARY checks="+checks+" failures="+failures);
        log("FINITE_ROLLING_EVENT_DISCOVERY="+(failures==0?"ACCEPTED":"REJECTED"));
        log("UNBOUNDED_RIVER_DISCOVERY=NOT_ESTABLISHED; SHARED_RECEIVER=NOT_IMPLEMENTED; RUNOFF_Q=NOT_THIS_MODEL");
        Files.writeString(OUT.resolve("metrics.txt"),LOG);System.out.print(LOG);System.exit(failures==0?0:1);
    }
    private static void log(String text) {LOG.append(text).append('\n');}
    private static void check(boolean ok,String text) {checks++;if(!ok)failures++;log((ok?"PASS ":"FAIL ")+text);}
    private static List<Event> births(Rect rect) {return new LocalFlowEvents(spec,(x,z)->0).events(rect);}

    private static LocalFlowJoinProbe.Run trace(Event event,WindowOutlets.Terrain terrain,
                                                boolean moving,double phase) throws Exception {
        return tracer.trace(new SharedBoundaryDrainage.WorldCell(event.start().x(),event.start().z()),terrain,
                12,4,phase,-phase,moving,-Double.MAX_VALUE,spec.lifetime(),false);
    }
    private static Point column(LocalFlowJoinProbe.St state) {
        return new Point((int)Math.floor(state.x()),(int)Math.floor(state.z()));
    }
    private static Query query(Rect target,WindowOutlets.Terrain terrain,int owner,boolean reverse,double phase) throws Exception {
        return accumulate(target,births(target.expand(spec.lifetime()+1)),terrain,owner,reverse,phase);
    }
    private static Query accumulate(Rect target,List<Event> events,WindowOutlets.Terrain terrain,
                                     int owner,boolean reverse,double phase) throws Exception {
        var groups=new TreeMap<Point,List<Event>>();var ids=new HashSet<Id>();
        for(var e:events) {
            if(!ids.add(e.id()))throw new IllegalArgumentException("duplicate birth");
            var key=new Point(Math.floorDiv(e.start().x(),owner),Math.floorDiv(e.start().z(),owner));
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(e);
        }
        var keys=new ArrayList<>(groups.keySet());if(reverse)Collections.reverse(keys);
        var columns=new TreeMap<Point,Map<Id,Contribution>>();var terminals=new TreeMap<String,Integer>();
        long states=0,samples=0;int moves=0,peakPath=0;
        for(var key:keys) {
            var group=groups.get(key);if(reverse)Collections.reverse(group);
            for(var event:group) {
                var run=trace(event,terrain,true,phase);
                if(run.terminal().equals("WORK_WINDOW_LIMIT"))throw new IllegalStateException("working cache truncated event");
                states+=run.nodes().size();samples+=run.samples();
                peakPath=Math.max(peakPath,run.nodes().size());moves+=run.moves().size();
                terminals.merge(run.terminal(),1,Integer::sum);
                var counts=new TreeMap<Point,Integer>();
                for(var s:run.nodes())if(target.contains(column(s)))counts.merge(column(s),1,Integer::sum);
                counts.forEach((p,count)->columns.computeIfAbsent(p,k->new TreeMap<>()).put(event.id(),
                        new Contribution(count,event.mass(),run.terminal())));
            }
        }
        var immutable=new TreeMap<Point,Map<Id,Contribution>>();
        columns.forEach((p,c)->immutable.put(p,Collections.unmodifiableMap(c)));
        return new Query(Collections.unmodifiableMap(immutable),events.size(),states,samples,moves,peakPath,1,
                Collections.unmodifiableMap(terminals));
    }

    private static void synthetic() throws Exception {
        var target=new Rect(-16,-16,-1,-1);WindowOutlets.Terrain plane=(x,z)->1000+x;
        var local=query(target,plane,16,false,0);var all=births(target.expand(2*spec.lifetime()+8));
        check(local.births()<all.size(),"comparison domains genuinely differ");
        check(local.columns().equals(axisOracle(target,all)),"analytic -x plane oracle matches L moves / L+1 visits including final column");
        check(local.columns().equals(accumulate(target,all,plane,13,true,2).columns()),"larger independent birth domain equals cropped local field");
        check(!local.columns().equals(accumulate(target,births(target),plane,16,false,0).columns()),"negative control: query-only births miss outside contributors");
        variants(target,plane,local);
        var boundary=new Event(new Id(spec.seed(),spec.configuration(),0,0,0),new Point(target.x1()+spec.lifetime(),-8),3);
        var hit=accumulate(target,List.of(boundary),plane,16,false,0);
        check(hit.columns().containsKey(new Point(target.x1(),-8)),"birth L columns away contributes at final accepted move (not L-1)");
        var tooSmall=target.expand(spec.lifetime()-1);
        var clipped=List.of(boundary).stream().filter(e->tooSmall.contains(e.start())).toList();
        check(!hit.columns().equals(accumulate(target,clipped,plane,16,false,0).columns()),"negative control: treating G42 L moves as L visits loses boundary event");
        boolean duplicate=false;try {accumulate(target,List.of(boundary,boundary),plane,16,false,0);}
        catch(IllegalArgumentException expected){duplicate=true;}check(duplicate,"duplicate world birth rejected");
        var fixed=trace(boundary,plane,false,0);var moving=trace(boundary,plane,true,0);
        check(fixed.terminal().equals("WORK_WINDOW_LIMIT") && fixed.nodes().size()<moving.nodes().size(),
                "negative control: fixed work window truncates, moving work window reaches outside birth window");
        var flat=trace(boundary,(x,z)->100,true,0);
        check(flat.nodes().size()==1 && flat.terminal().endsWith("PENDING"),"flat terminal stays pending, never an invented outlet or dry result");
        check(tracer.windowGuardBlocked(plane),"G42 sampler still rejects out-of-working-window samples");
        check(flat.terminal().contains("PENDING") && accumulate(target,List.of(new Event(boundary.id(),new Point(-8,-8),3)),
                (x,z)->100,16,false,0).columns().values().iterator().next().values().iterator().next().terminal().contains("PENDING"),
                "pending prefix retains its reason in per-event contribution, never promoted to complete water");
        boolean cancelled=false;
        try {Thread.currentThread().interrupt();query(target,plane,16,false,0);}
        catch(java.lang.reflect.InvocationTargetException expected){cancelled=expected.getCause() instanceof CancellationException;}
        finally {Thread.interrupted();}
        check(cancelled && local.columns().equals(query(target,plane,16,false,0).columns()),"cancelled G42 reflection preserves cancellation and clean replay");
        stepBounds(boundary,(x,z)->1000-x-z);
        write("synthetic",local);cost("synthetic",local);
    }

    private static Map<Point,Map<Id,Contribution>> axisOracle(Rect target,List<Event> events) {
        var oracle=new TreeMap<Point,Map<Id,Contribution>>();
        for(var event:events)for(int i=0;i<=spec.lifetime();i++) {
            var p=new Point(event.start().x()-i,event.start().z());if(!target.contains(p))continue;
            oracle.computeIfAbsent(p,k->new TreeMap<>()).put(event.id(),new Contribution(1,event.mass(),"STEP_BUDGET_PENDING"));
        }
        return oracle;
    }
    private static void stepBounds(Event birth,WindowOutlets.Terrain terrain) throws Exception {
        var run=trace(birth,terrain,true,0);boolean bounded=run.nodes().size()<=spec.lifetime()+1;
        for(int i=1;i<run.nodes().size();i++) {
            var a=run.nodes().get(i-1);var b=run.nodes().get(i);
            bounded&=Math.hypot(b.x()-a.x(),b.z()-a.z())<=1+1e-9;
            bounded&=Math.max(Math.abs(b.x()-birth.start().x()),Math.abs(b.z()-birth.start().z()))<=spec.lifetime()+1e-9;
        }
        check(bounded,"continuous diagonal norms <=1; at most L+1 states and displacement <=L");
        check(!run.terminal().equals("WORK_WINDOW_LIMIT"),"moving work window is not the process termination condition");
        // Negative coordinates make floor differ from truncation even at the first sub-block diagonal step.
        var second=run.nodes().get(1);
        check(column(second).z()==(int)Math.floor(second.z()) && column(second).z()!=(int)second.z(),
                "negative fractional floor differs from int cast (column conversion control)");
    }

    private static void variants(Rect target,WindowOutlets.Terrain terrain,Query expected) throws Exception {
        for(int owner:new int[]{7,23})check(expected.columns().equals(query(target,terrain,owner,true,2).columns()),
                "reverse owner="+owner+" and window phase preserve full per-event contribution identities");
        var shift=new Rect(target.x0()-8,target.z0()-7,target.x1()+9,target.z1()+11);
        var crop=new TreeMap<Point,Map<Id,Contribution>>();
        query(shift,terrain,11,true,-2).columns().forEach((p,c)->{if(target.contains(p))crop.put(p,c);});
        check(expected.columns().equals(crop),"offset output domain/negative corner overlap matches, trajectories not killed at output boundary");
        var down=new Rect(target.x0(),target.z0(),target.x0()+3,target.z0()+3);
        var first=query(down,terrain,7,true,0);query(target,terrain,16,false,0);
        check(first.columns().equals(query(down,terrain,23,false,2).columns()),"cold downstream-first query needs no upstream publication");
        var pool=Executors.newFixedThreadPool(3);
        try {
            var futures=new ArrayList<Future<Query>>();
            for(int i=0;i<3;i++)futures.add(pool.submit(()->query(target,terrain,13,true,0)));
            boolean same=true;for(var f:futures)same&=expected.columns().equals(f.get().columns());
            check(same,"three concurrent G42 event queries agree without shared mutable particle state");
        } finally {pool.shutdownNow();}
    }

    private static CellGenerator generator() {
        var gen=new CellGenerator(params,params.minY(),params.maxY());gen.seed(spec.seed());return gen;
    }
    private static void real() throws Exception {
        var gen=generator();double hs=params.horizontalScale();WindowOutlets.Terrain terrain=(x,z)->gen.erodedHeight(x/hs,z/hs);
        var target=new Rect(-784,368,-769,383);long fine=HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var local=query(target,terrain,16,false,0);
        check(!local.columns().isEmpty(),"world-born G42 real terrain field is nonempty without rollingSites source list");
        variants(target,terrain,local);
        var larger=accumulate(target,births(target.expand(2*spec.lifetime()+8)),terrain,19,true,2);
        check(local.columns().equals(larger.columns()),"real independent larger birth domain contributes exactly same event identities and state counts");
        var cold=generator();WindowOutlets.Terrain terrain2=(x,z)->cold.erodedHeight(x/hs,z/hs);
        check(local.columns().equals(query(target,terrain2,7,true,-2).columns()),"independent cold terrain rebuild gives identical rolling event field");
        check(HierarchicalHydrology.Diag.FINE_SOLVES.get()==fine,"zero neighbouring fine hydrology solves");
        write("real",local);cost("real",local);profiles(target,terrain);
    }
    private static void profiles(Rect target,WindowOutlets.Terrain terrain) throws Exception {
        var csv=new StringBuilder("birthCellX,birthCellZ,round,node,x,z,height,dirX,dirZ,terminal\n");int trajectories=0;
        for(var birth:births(target)) {
            var run=trace(birth,terrain,true,0);
            for(var s:run.nodes())csv.append(birth.id().cellX()).append(',').append(birth.id().cellZ()).append(',')
                    .append(birth.id().round()).append(',').append(s.step()).append(',').append(s.x()).append(',').append(s.z())
                    .append(',').append(s.h()).append(',').append(s.dx()).append(',').append(s.dz()).append(',').append(run.terminal()).append('\n');
            trajectories++;
        }
        check(trajectories>0,"real full longitudinal profiles retain raw G42 stop reasons");
        Files.writeString(OUT.resolve("real-trajectories.csv"),csv);
    }
    private static void write(String name,Query result) throws Exception {
        var csv=new StringBuilder("x,z,seed,configuration,birthCellX,birthCellZ,round,states,massOncePerEvent,terminal\n");
        result.columns().forEach((p,events)->events.forEach((id,c)->csv.append(p.x()).append(',').append(p.z()).append(',')
                .append(id.seed()).append(',').append(id.configuration()).append(',').append(id.cellX()).append(',').append(id.cellZ())
                .append(',').append(id.round()).append(',').append(c.states()).append(',').append(c.mass()).append(',').append(c.terminal()).append('\n')));
        Files.writeString(OUT.resolve(name+"-events.csv"),csv);
    }
    private static void cost(String name,Query r) {
        log("COST "+name+" births="+r.births()+" states="+r.states()+" rawHeightSamples="+r.samples()
                +" windowMoves="+r.moves()+" peakPathStates="+r.peakPath()+" peakRetainedPaths="+r.peakRetainedPaths()
                +" outputColumns="+r.columns().size()+" terminalCounts="+r.terminals());
        log("COST_SCOPE eventDirectoryAndContributionsMaterialized=true samplerPeakNotCollected=true NOT_WHOLE_PROVIDER_COST=true");
    }
}
