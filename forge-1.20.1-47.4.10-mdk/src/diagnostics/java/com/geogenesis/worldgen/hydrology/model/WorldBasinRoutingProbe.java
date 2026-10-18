package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Analytic world-basin integration tests. Any failed contract exits nonzero. */
public final class WorldBasinRoutingProbe {
    private static int failures;
    private static void check(boolean ok,String text) { System.out.println((ok?"PASS ":"FAIL ")+text);if(!ok)failures++; }
    private static WindowOutlets.Terrain fixture() {
        return (x,z)->z==0 && x==0?0:z==0 && x==1?2:z==0 && x==2?-1:z==0 && x==3?1:z==0 && x==4?-5:
                x>=-3 && x<=6 && Math.abs(z)<=3?10:-5;
    }
    public static void main(String[] args) throws Exception {
        var t=fixture();var raw=new WorldDrainage(1,2,-2,t);var route=new WorldBasinRouting(raw,t,1,2048);
        var a=route.at(new Point(0,0));var b=route.at(new Point(2,0));
        check(a!=null && a.published() && a.sill()==2,"upper pan geometry");
        check(b!=null && b.published() && b.sill()==1,"lower pan geometry");
        check(a!=null && a.routeFrom(new Point(0,0)).equals(new Point(1,0)),"lake points to lip");
        var flow=new WorldBasinFlow(raw,route,new LakeWaterBalance.Climate(.004,.002,.3),1,4096);
        var wa=flow.at(new Point(0,0));var wb=flow.at(new Point(2,0));
        System.out.println("upper="+wa+" lower="+wb);
        check(wa.known() && wa.discharge()>0 && wa.level()<2 && wa.level()>1.9,"upper positive budget reaches sill");
        check(wb.known() && wb.discharge()>0,"lower positive budget");
        var reverse=new WorldBasinFlow(new WorldDrainage(1,2,-2,t),new WorldBasinRouting(new WorldDrainage(1,2,-2,t),t,1,2048),
                new LakeWaterBalance.Climate(.004,.002,.3),1,4096);
        var rb=reverse.at(new Point(2,0));var ra=reverse.at(new Point(0,0));
        check(wb.equals(rb) && wa.equals(ra),"water lookup order independent");
        var dry=new WorldBasinFlow(raw,route,new LakeWaterBalance.Climate(.004,10,.3),1,4096);
        var wd=dry.at(new Point(0,0));
        check(wd.known() && wd.discharge()==0 && wd.level()<2,"closed basin keeps equilibrium level but no outlet water");
        check(route.at(new Point(4,0))==null,"sea is not a fabricated lake");
        longPlateau();budgetOrder();mergedBasins();
        sameSaddleOneOutlet();distinctReceiversStayUnknown();multiLevelBackwater();
        closedLakeZeroOutflow();freshInstances();interruptRetry();
        negativeOffsets();stepFourCrest();
        crossSaddlePublishes();separateSaddlesSameReceiverUnknown();sharedSaddleContracts();saddleDecisionNegatives();
        basinSupportClosure();deepBasinChain();hiddenLipMembership();sharedSaddleBalance();firstReceivingPan();
        System.out.println("FAILURES="+failures);System.exit(failures==0?0:1);
    }
    private static void hiddenLipMembership() {
        WindowOutlets.Terrain t=(x,z)->{
            if(z==0) {
                if(x<=-4)return x-20;
                if(x<0)return 5;
                if(x==0)return 1;
                if(x<4)return .5;
                if(x==4)return 0;
            }
            return 40;
        };
        var raw=new WorldDrainage(4,2,-10,t);var route=new WorldBasinRouting(raw,t,4,4096);
        check(new Point(-4,0).equals(raw.at(new Point(0,0)).next()),"hidden-lip fixture raw endpoints still choose downhill outside pan");
        var a=route.at(new Point(4,0));var lip=route.at(new Point(0,0));
        check(a!=null && a.published() && a.members().size()==2 && a.sill()==5,"hidden-lip pan has two members behind integer crest five");
        check(a!=null && a.equals(lip),"lip and bottom share canonical basin before any cached member authority");
        var coldRaw=new WorldDrainage(4,2,-10,t);var reverse=new WorldBasinRouting(coldRaw,t,4,4096);
        var first=reverse.at(new Point(0,0));
        check(first!=null && first.equals(reverse.at(new Point(4,0))) && first.equals(a),"cold lip-first membership equals bottom-first");
        var flow=new WorldBasinFlow(raw,route,wet(),4,4096);
        check(flow.at(new Point(0,0)).equals(flow.at(new Point(4,0))),"lip never receives a second ordinary-cell rain credit");
    }
    private static WorldBasinRouting descendingPans(int count) {
        WindowOutlets.Terrain t=(x,z)->{
            if(z==0 && x>=0 && x<2*count) {
                int ix=(int)x;
                return (ix&1)==0?-(ix/2.0):count-ix/2;
            }
            if(Math.abs(z)<=1 && x>=-1 && x<2*count)return 1000;
            return -1000;
        };
        var raw=new WorldDrainage(1,2,-500,t);
        return new WorldBasinRouting(raw,t,1,2048);
    }
    private static void deepBasinChain() {
        var route=descendingPans(40);var first=route.at(new Point(0,0));
        check(first!=null && first.published() && first.members().size()==1
                        && new Point(2,0).equals(first.nextSink()) && !first.toSea(),
                "40 descending pans publish beyond the historical 32-layer limit without merging or skipping the first receiver");
        var reversed=descendingPans(40);reversed.at(new Point(78,0));
        check(first!=null && first.equals(reversed.at(new Point(0,0))),
                "deep chain is identical after querying its last pan first");
        var exhausted=descendingPans(129).at(new Point(0,0));
        check(exhausted!=null && !exhausted.published() && exhausted.members().isEmpty()
                        && exhausted.reason().equals("basin depth budget"),
                "129 pans exhaust the bounded chain and stay UNKNOWN, never partial water or dry");
    }
    private static void firstReceivingPan() {
        WindowOutlets.Terrain t=(x,z)-> {
            if(z==0 && x>=0 && x<=7)return switch((int)x) {case 0->0;case 1->5;case 2->3;case 3->4;case 4->-1;case 5->2;default->-200;};
            return x>=-1 && x<=8 && Math.abs(z)<=1?100:-200;
        };
        var raw=new WorldDrainage(1,2,-100,t);var route=new WorldBasinRouting(raw,t,1,2048);
        var first=route.at(new Point(0,0));var witness=route.outlet(new Point(0,0)).geometry().path();
        check(witness.get(witness.size()-1).equals(new Point(4,0))
                        && route.terminal(new Point(2,0)).equals(new Point(2,0)),
                "lower witness lies beyond a real first receiving pan at x2");
        check(first!=null && first.published() && new Point(2,0).equals(first.nextSink()) && !first.toSea(),
                "downstream certification visits first receiving pan instead of jumping to lower witness x4");
    }
    private static void budgetOrder() {
        WindowOutlets.Terrain t=(x,z)->x<0?-5:1000-x;
        var raw=new WorldDrainage(1,2,-2,t);var route=new WorldBasinRouting(raw,t,1,2048);
        var flow=new WorldBasinFlow(raw,route,new LakeWaterBalance.Climate(.004,.002,.3),1,12);
        var p=new Point(25,0);var first=flow.at(p);
        for(int x=0;x<25;x++)flow.at(new Point(x,0));
        check(!first.known() && first.equals(flow.at(p)),"budget exhaustion stays unknown after upstream prewarming");
        var flatRaw=new WorldDrainage(1,2,-2,(x,z)->10);
        var flat=new WorldBasinFlow(flatRaw,new WorldBasinRouting(flatRaw,(x,z)->10,1,9),
                new LakeWaterBalance.Climate(.004,.002,.3),1,12);
        check(!flat.at(new Point(0,0)).known(),"unbounded flat is unknown, never a closed dry certificate");
    }
    private static void mergedBasins() {
        WindowOutlets.Terrain t=(x,z)->z==0 && x==0?0:z==0 && x==1?2:z==0 && x==2?-2:
                z==0 && x==3?5:z==0 && x==4?-5:x>=-3 && x<=6 && Math.abs(z)<=3?10:-5;
        var raw=new WorldDrainage(1,2,-3,t);var route=new WorldBasinRouting(raw,t,1,2048);
        var a=route.at(new Point(0,0));var b=route.at(new Point(2,0));
        check(a!=null && a.published() && a.equals(b) && a.sill()==5 && a.members().size()==3,
                "backwater combines both pans and intervening saddle into one atomic footprint");
        var flow=new WorldBasinFlow(raw,route,new LakeWaterBalance.Climate(.004,.002,.3),1,4096);
        check(flow.at(new Point(0,0)).equals(flow.at(new Point(2,0))),"merged footprint has one budget and one plane");
    }
    private static void longPlateau() {
        WindowOutlets.Terrain t=(x,z)->z==0 && x==0?0:z==0 && x>=1 && x<=3?2:z==0 && x>=4?-5:10;
        var raw=new WorldDrainage(1,2,-2,t);var routing=new WorldBasinRouting(raw,t,1,2048);
        var b=routing.at(new Point(0,0));
        check(b!=null && b.published(),"multi-node sill corridor is recognized");
        var flow=new WorldBasinFlow(raw,routing,new LakeWaterBalance.Climate(.004,.002,.3),1,4096);
        check(new Point(2,0).equals(flow.geometricNext(new Point(1,0))),"first spill plateau node leaves lake");
        check(new Point(3,0).equals(flow.geometricNext(new Point(2,0))),"middle plateau node independently continues");
        var coldRaw=new WorldDrainage(1,2,-2,t);var cold=new WorldBasinRouting(coldRaw,t,1,2048);
        check(new Point(3,0).equals(cold.baseNext(new Point(2,0))),"cold receiver discovers its successor without upstream query");
        check(cold.at(new Point(0,0)).equals(b),"receiver-first query preserves basin geometry");
    }

    // ── new evidence: one saddle many faces, real double receivers, chained backwater ──

    /** A(floor -2,sill 2) -> B(floor -4,sill 4) -> C(floor -10,sill 6) -> below-sea at x=6. Walled, no open flat. */
    private static WindowOutlets.Terrain chain() {
        return (x,z)->{
            if(z==0 && x>=0 && x<=5)return x==0?-2:x==1?2:x==2?-4:x==3?4:x==4?-10:6;
            if(Math.abs(z)<=1 && x>=-1 && x<=5)return 100;
            return -200;
        };
    }
    private static LakeWaterBalance.Climate wet() { return new LakeWaterBalance.Climate(.004,.0005,.3); }
    private static LakeWaterBalance.Climate arid() { return new LakeWaterBalance.Climate(.004,10,.3); }
    private static final List<Point> PROBES=List.of(new Point(0,0),new Point(2,0),new Point(4,0),new Point(5,0));
    private static Point moved(Point p,int dx,int dz) { return new Point(p.x()+dx,p.z()+dz); }
    private static WindowOutlets.Terrain shifted(WindowOutlets.Terrain base,int dx,int dz) {
        return (x,z)->base.height(x-dx,z-dz);
    }
    private static boolean close(double a,double b) {
        if(Double.isNaN(a) || Double.isNaN(b))return Double.isNaN(a) && Double.isNaN(b);
        return Math.abs(a-b)<=1e-12*Math.max(1,Math.max(Math.abs(a),Math.abs(b)));
    }
    private static boolean leadsTo(WorldBasinRouting.Basin b,Point from,Point target) {
        var p=from;
        for(int i=0;i<16;i++) {
            Point next=b.routeFrom(p);
            if(next==null)return false;
            if(next.equals(target))return true;
            p=next;
        }
        return false;
    }
    /**
     * Two rim faces of one depression that meet at one saddle cell are one outlet. Endpoint-only receivers
     * would report competing receivers here and refuse the pan.
     */
    private static void sameSaddleOneOutlet() {
        WindowOutlets.Terrain t=(x,z)->x==0 && (z==0 || z==1)?0
                :x==1 && z==1?2
                :x==2 && z==2?-5
                :Math.abs(x)<=3 && Math.abs(z)<=3?10:-200;
        var raw=new WorldDrainage(1,2,-2,t);var sink=new Point(0,0);
        var g=new WorldBasin(raw,t,1,2048).resolve(sink);
        long outers=g.exits().stream().map(WorldBasin.Face::outer).distinct().count();
        check(g.state()==WorldBasin.State.MULTIPLE_EXITS && g.exits().size()==2 && outers==1,
                "fixture really has two faces meeting one saddle (state="+g.state()+" faces="+g.exits().size()+" outers="+outers+")");
        var b=new WorldBasinRouting(raw,t,1,2048).at(sink);
        check(b!=null && b.published() && b.sill()==2,
                "faces sharing one outer are a single outlet, not competing receivers");
        check(b!=null && b.members().size()==2 && b.members().stream().allMatch(m->leadsTo(b,m,new Point(1,1))),
                "whole two-cell pan reaches the shared saddle, not just the first face");
    }
    /** Two saddles at one crest with different receivers stay UNKNOWN, with no fabricated footprint. */
    private static void distinctReceiversStayUnknown() {
        WindowOutlets.Terrain t=(x,z)->x==0 && z==0?0
                :(x==1 || x==-1) && z==0?2
                :(x==2 || x==-2) && z==0?-200
                :Math.abs(x)<=3 && Math.abs(z)<=3?10:-200;
        var raw=new WorldDrainage(1,2,-2,t);var sink=new Point(0,0);
        var g=new WorldBasin(raw,t,1,2048).resolve(sink);
        long outers=g.exits().stream().map(WorldBasin.Face::outer).distinct().count();
        check(g.state()==WorldBasin.State.MULTIPLE_EXITS && g.exits().size()==2 && outers==2,
                "fixture really has two distinct receivers at one crest (outers="+outers+")");
        var route=new WorldBasinRouting(raw,t,1,2048);var b=route.at(sink);
        check(b!=null && !b.published() && b.members().isEmpty() && b.reason().contains("MULTIPLE_EXITS"),
                "different receivers are never collapsed into one outlet (reason="+(b==null?null:b.reason())+")");
        var flow=new WorldBasinFlow(raw,route,wet(),1,4096);
        check(!flow.at(sink).known(),"competing receivers authorize no water, not even a low level");
    }
    /** A(floor -2,sill 2) -> B(floor -4,sill 4) -> C(floor -10,sill 6): two promotions, no cycle. */
    private static void multiLevelBackwater() {
        var t=chain();var raw=new WorldDrainage(1,2,-100,t);
        var a=new WorldBasin(raw,t,1,2048).resolve(new Point(0,0));
        var b=new WorldBasin(raw,t,1,2048).resolve(new Point(2,0));
        check(a.state()==WorldBasin.State.UNIQUE_EXIT && a.sill()==2 && a.members().size()==1
                && b.sill()==4 && b.members().size()==3,"pan chain really starts at distinct sills 2 and 4");
        var route=new WorldBasinRouting(raw,t,1,2048);var merged=route.at(new Point(0,0));
        check(merged!=null && merged.published() && merged.sill()==6 && merged.members().size()==5,
                "chained backwater promotes A into B into C instead of publishing the small pan");
        check(merged!=null && merged.published() && merged.reason().isEmpty(),
                "two promotions end published, never a basin-cycle verdict");
        check(merged!=null && merged.equals(route.at(new Point(4,0))),
                "small pan and bottom pan agree on one atomic footprint");
        var coldRaw=new WorldDrainage(1,2,-100,t);var reverse=new WorldBasinRouting(coldRaw,t,1,2048);
        check(reverse.at(new Point(4,0)).equals(reverse.at(new Point(0,0))),
                "reversed query order yields the same multi-level merge");
        var flow=new WorldBasinFlow(raw,route,wet(),1,4096);
        check(flow.at(new Point(0,0)).equals(flow.at(new Point(4,0))),
                "merged multi-level footprint has one budget and one plane");
    }
    /**
     * A closed lake exports exactly zero, so its mouth may only carry its own rain — never the lake's inflow. The
     * mouth normally routes the lake surplus downstream; the teeth here are that a zero surplus adds nothing.
     */
    private static void closedLakeZeroOutflow() {
        var t=chain();var raw=new WorldDrainage(1,2,-100,t);var route=new WorldBasinRouting(raw,t,1,2048);
        var dry=new WorldBasinFlow(raw,route,arid(),1,4096);var openFlow=new WorldBasinFlow(raw,route,wet(),1,4096);
        var shut=dry.at(new Point(0,0));var open=openFlow.at(new Point(0,0));
        double own=1*1*.004*.3;
        check(shut.known() && shut.discharge()==0 && shut.level()<6,
                "arid lake exports exactly zero and stays below its sill");
        check(open.known() && open.discharge()>0 && shut.level()<open.level(),
                "a wetter climate opens the same basin, so the zero above is not a dead probe");
        var lip=dry.at(new Point(5,0));var lipOpen=openFlow.at(new Point(5,0));
        check(lip.known() && close(lip.discharge(),own),
                "closed lake credits its mouth nothing: the mouth keeps only its own rain ("+lip.discharge()+")");
        check(close(lipOpen.discharge(),own+open.discharge()),
                "an overflowing lake does hand its surplus down, so the zero above is a surplus of zero");
        check(close(lipOpen.discharge()-open.discharge(),lip.discharge()),
                "mouth minus lake is the same own-rain constant under both climates, no second rain model");
    }
    private static List<WorldBasinFlow.Water> probeAll(WindowOutlets.Terrain t,int limit) {
        var raw=new WorldDrainage(1,2,-100,t);
        var flow=new WorldBasinFlow(raw,new WorldBasinRouting(raw,t,1,2048),wet(),1,limit);
        var out=new ArrayList<WorldBasinFlow.Water>();
        for(var p:PROBES)out.add(flow.at(p));
        return out;
    }
    /** Same instances, opposite visitation order: an independent basin may never depend on query order. */
    private static List<WorldBasinFlow.Water> probeReversed(WindowOutlets.Terrain t) {
        var raw=new WorldDrainage(1,2,-100,t);
        var flow=new WorldBasinFlow(raw,new WorldBasinRouting(raw,t,1,2048),wet(),1,4096);
        var order=new ArrayList<>(PROBES);Collections.reverse(order);
        var out=new ArrayList<WorldBasinFlow.Water>();
        for(var p:order)out.add(flow.at(p));
        Collections.reverse(out);
        return out;
    }
    /** Three fresh instances computed concurrently must equal the single-thread reference, budgets included. */
    private static void freshInstances() throws Exception {
        var t=chain();var reference=probeAll(t,4096);
        check(probeReversed(t).equals(reference),"visiting independent basins in reverse order changes nothing");
        var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<Future<List<WorldBasinFlow.Water>>>();
            for(int i=0;i<3;i++)jobs.add(pool.submit(()->probeAll(t,4096)));
            for(int i=0;i<jobs.size();i++)check(jobs.get(i).get(60,TimeUnit.SECONDS).equals(reference),
                    "fresh concurrent instance "+i+" reproduces the single-thread reference");
            var starved=new ArrayList<Future<List<WorldBasinFlow.Water>>>();
            for(int i=0;i<3;i++)starved.add(pool.submit(()->probeAll(t,2)));
            var s0=starved.get(0).get(60,TimeUnit.SECONDS);var s1=starved.get(1).get(60,TimeUnit.SECONDS);
            check(s0.equals(s1) && s0.equals(starved.get(2).get(60,TimeUnit.SECONDS)),
                    "budget-starved instances agree with each other, no order-dependent verdict");
            check(s0.stream().noneMatch(WorldBasinFlow.Water::known),
                    "a starved budget is unknown everywhere, never a silent dry certificate");
            check(s0.size()==PROBES.size() && !s0.get(0).known(),"the pan itself is the starved query, not a skipped probe");
        } finally { pool.shutdownNow(); }
    }
    /** An interrupt must surface as cancellation, and a retry must not inherit a poisoned verdict. */
    private static void interruptRetry() throws Exception {
        var t=chain();var reference=probeAll(t,4096).get(0);
        var raw=new WorldDrainage(1,2,-100,t);var route=new WorldBasinRouting(raw,t,1,2048);
        var flow=new WorldBasinFlow(raw,route,wet(),1,4096);
        var swallowed=new AtomicReference<Throwable>();var retried=new AtomicReference<WorldBasinFlow.Water>();
        var worker=new Thread(()->{
            Thread.currentThread().interrupt();
            try { flow.at(new Point(0,0));swallowed.set(new AssertionError("interrupt swallowed into a verdict")); }
            catch(CancellationException expected) { Thread.interrupted(); }
            catch(Throwable other) { swallowed.set(other); }
            retried.set(flow.at(new Point(0,0)));
        },"probe-interrupt");
        worker.setDaemon(true);worker.start();worker.join(30000);
        check(!worker.isAlive(),"an interrupted query terminates instead of spinning");
        check(swallowed.get()==null,"a pre-interrupted query throws CancellationException, not a water verdict");
        check(reference.equals(retried.get()),"retrying the same instance after clearing the interrupt matches the reference");
        check(reference.equals(probeAll(t,4096).get(0)),"a fresh instance after the interrupt also matches");
    }
    private static boolean sameBasin(WorldBasinRouting.Basin ref,Point off,WorldBasinRouting.Basin got) {
        if(ref==null || got==null)return ref==got;
        if(ref.published()!=got.published() || !close(ref.sill(),got.sill()))return false;
        if(ref.identity()==null || got.identity()==null)return ref.identity()==got.identity();
        if(!moved(ref.identity(),off.x(),off.z()).equals(got.identity()))return false;
        var members=new HashSet<Point>();
        for(var m:ref.members())members.add(moved(m,off.x(),off.z()));
        if(!members.equals(new HashSet<>(got.members())))return false;
        for(var hop:ref.next().entrySet())
            if(!moved(hop.getValue(),off.x(),off.z()).equals(got.routeFrom(moved(hop.getKey(),off.x(),off.z()))))return false;
        var corridor=new ArrayList<Point>();
        for(var p:ref.spillChain())corridor.add(moved(p,off.x(),off.z()));
        return corridor.equals(got.spillChain());
    }
    /** The same chain translated into negative / far coordinates must answer identically, step 1 included. */
    private static void negativeOffsets() {
        var base=chain();var refRaw=new WorldDrainage(1,2,-100,base);var refRoute=new WorldBasinRouting(refRaw,base,1,2048);
        var refBasin=refRoute.at(new Point(0,0));var refFlow=new WorldBasinFlow(refRaw,refRoute,wet(),1,4096);
        var refLip=refFlow.at(new Point(5,0));var refLake=refFlow.at(new Point(0,0));
        for(var off:List.of(new Point(-7,-13),new Point(-3,5),new Point(11,-17),new Point(-1000,-999))) {
            var t=shifted(base,off.x(),off.z());var raw=new WorldDrainage(1,2,-100,t);
            var route=new WorldBasinRouting(raw,t,1,2048);var flow=new WorldBasinFlow(raw,route,wet(),1,4096);
            check(sameBasin(refBasin,off,route.at(off)),
                    "translated basin keeps identity, members, route and corridor at "+off);
            var lip=flow.at(moved(new Point(5,0),off.x(),off.z()));
            check(lip.known()==refLip.known() && close(lip.discharge(),refLip.discharge())
                            && close(lip.level(),refLip.level()),
                    "translated spill mouth keeps discharge and level at "+off);
            var lake=flow.at(off);
            check(lake.known() && close(lake.discharge(),refLake.discharge()) && close(lake.level(),refLake.level()),
                    "translated lake keeps its budget at "+off);
        }
    }
    /**
     * Step-4 corridor: one-cell pan at x=0, a sill at x=4, then a below-sea trough. The wall next to the corridor
     * drains outward into that trough, so no flat ever needs a routing budget; the spike sits strictly inside one
     * hop, where only integer-column sampling can see it.
     */
    private static WindowOutlets.Terrain stepFour(int spikeX,double spikeH) {
        return (x,z)->{
            if(z==0 && x>=0 && x<=11) {
                if(x==0)return 0;
                if(x==spikeX)return spikeH;
                if(x<4)return 1;
                if(x==4)return 2;
                return -50;
            }
            // The band must be at least one step wide, or a step-4 neighbour jumps from the pan into the trough.
            if(Math.abs(z)<=4 && x>=-4 && x<=16)return 100;
            return -200;
        };
    }
    /** Step-4 sea level sits above the trough, so the trough is a genuine below-sea terminal. */
    private static final double STEP4_SEA=-10;
    /** A step-4 crest must sample the integer columns inside a hop; endpoint-only crests would report sill 2. */
    private static void stepFourCrest() {
        var first=stepFour(2,7);var raw=new WorldDrainage(4,2,STEP4_SEA,first);
        var route=new WorldBasinRouting(raw,first,4,2048);var b=route.at(new Point(0,0));
        check(b!=null && b.published() && b.sill()==7 && b.members().size()==1,
                "crest sees column x=2 inside the first step-4 hop (endpoint-only would say 2): sill="
                        +(b==null?null:b.sill()));
        var second=stepFour(6,9);var raw2=new WorldDrainage(4,2,STEP4_SEA,second);
        var b2=new WorldBasinRouting(raw2,second,4,2048).at(new Point(0,0));
        check(b2!=null && b2.published() && b2.sill()==9,
                "crest sees column x=6 between hop endpoints x=4 and x=8 (endpoint-only would say 2): sill="
                        +(b2==null?null:b2.sill()));
        // Flood it: area >> 1 cell, so the plane sits at the sill and the sampled crest is visible in the water.
        var flood=new LakeWaterBalance.Climate(10,1e-6,1);
        var water=new WorldBasinFlow(raw,route,flood,4,4096).at(new Point(0,0));
        check(water.known() && water.discharge()>0 && close(water.level(),7-1e-9),
                "step-4 water plane sits on the sampled sill 7, not the endpoint sill 2: level="
                        +(water.known()?water.level():"unknown"));
    }

    /**
     * Step-4 pan holding {(0,0),(4,0)} with two diagonal rim hops: (0,0)->(4,-4) and (4,0)->(0,-4). Both hops pass
     * through the same integer column (2,-2), so {@code c11}/{@code c22}/{@code c31} choose which column each hop
     * crests on. Both outers drain to one below-sea node (4,-8) — (4,-4) hops there cheaply, (0,-4) only through a
     * taller (cost 6) hop, so the minimax path always enters the trough via {@code (4,-4)} = the first exit outer.
     * Every other rim column is 6, so the shared saddle is the only way out at cost 5.
     */
    private static WindowOutlets.Terrain crossPan(int c11,int c22,int c31) {
        var h=new HashMap<Point,Double>();
        h.put(new Point(0,0),0.0);h.put(new Point(4,0),0.0);
        for(int x=1;x<=3;x++)h.put(new Point(x,0),0.0);
        h.put(new Point(1,-1),(double)c11);h.put(new Point(2,-2),(double)c22);
        h.put(new Point(3,-3),0.0);h.put(new Point(4,-4),0.0);
        h.put(new Point(3,-1),(double)c31);h.put(new Point(1,-3),0.0);h.put(new Point(0,-4),0.0);
        // The straight hops must stay at 6, or the outer cells would flood in as extra members.
        for(int z=-1;z>=-3;z--) { h.put(new Point(0,z),6.0);h.put(new Point(4,z),6.0); }
        // One shared below-sea trough, reached from (4,-4) at crest 0 and from (0,-4) only at crest 6.
        for(int z=-5;z>=-7;z--)h.put(new Point(4,z),0.0);
        h.put(new Point(4,-8),-200.0);
        // A real below-bed path between receivers, not the old endpoint-only jump over a six-high barrier.
        for(int x=0;x<=4;x++)h.put(new Point(x,-4),0.0);
        // Every remaining hop off a member is a 6-wall, so no other cell floods in and no other face appears.
        int[][] walls={{-1,0},{-2,0},{-3,0},{-1,-1},{-2,-2},{-3,-3},{-1,1},{-2,2},{-3,3},
                {0,1},{0,2},{0,3},{1,1},{2,2},{3,3},
                {5,0},{6,0},{7,0},{5,4},{6,4},{7,4},{5,-4},{6,-4},{7,-4},
                {4,1},{4,2},{4,3},{3,1},{1,3}};
        for(var w:walls)h.put(new Point(w[0],w[1]),6.0);
        return (x,z)->h.getOrDefault(new Point((int)x,(int)z),100.0);
    }
    /**
     * Integer columns along one rim hop that actually reach the crest height. Re-derived from the fixture here on
     * purpose: it must not reuse the production {@code crestColumns}, or a bug there would agree with itself.
     */
    private static Set<Point> crestColumns(WindowOutlets.Terrain t,WorldBasin.Face f,int step) {
        var out=new HashSet<Point>();
        for(int i=0;i<=step;i++) {
            var p=new Point(f.inner().x()+(f.outer().x()-f.inner().x())*i/step,
                    f.inner().z()+(f.outer().z()-f.inner().z())*i/step);
            if(t.height(p.x(),p.z())==f.crest())out.add(p);
        }
        return out;
    }
    private static Set<Point> sharedColumns(WindowOutlets.Terrain t,List<WorldBasin.Face> faces,int step) {
        var shared=crestColumns(t,faces.get(0),step);
        for(var f:faces)shared.retainAll(crestColumns(t,f,step));
        return shared;
    }
    private static Point drain(WorldBasinRouting route,Point from) {
        var p=from;
        for(int i=0;i<64;i++) { Point next=route.baseNext(p);if(next==null)return p;p=next; }
        throw new IllegalStateException("drain budget exhausted, not a terminal");
    }
    /**
     * A unique outward slope selects one face at the shared crossing, independently of receiver equality.
     */
    private static void crossSaddlePublishes() {
        var base=crossPan(0,5,0);
        WindowOutlets.Terrain t=(x,z)->x==1 && z==-3?1:base.height(x,z);var raw=new WorldDrainage(4,2,STEP4_SEA,t);var sink=new Point(0,0);
        var g=new WorldBasin(raw,t,4,2048).resolve(sink);var faces=g.exits();
        check(g.state()==WorldBasin.State.MULTIPLE_EXITS && faces.size()==2
                        && !faces.get(0).outer().equals(faces.get(1).outer())
                        && g.members().size()==2 && g.sill()==5,
                "fixture has two diagonal faces, different outers ("
                        +faces.stream().map(WorldBasin.Face::outer).toList()+"), sill="+g.sill());
        var shared=sharedColumns(t,faces,4);
        check(shared.equals(Set.of(new Point(2,-2))),
                "both diagonal hops crest on the same integer column (2,-2): shared="+shared);
        var route=new WorldBasinRouting(raw,t,4,2048);
        check(drain(route,faces.get(0).outer()).equals(drain(route,faces.get(1).outer())),
                "the two faces also share one raw receiver, so only the crossing column can authorize them");
        var basin=route.at(sink);
        check(basin!=null && basin.published() && basin.sill()==5 && basin.members().size()==2,
                "crossing saddle columns are one outlet and publish (sill="
                        +(basin==null?null:basin.sill())+")");
        check(basin!=null && basin.published()
                        && basin.members().stream().allMatch(m->leadsTo(basin,m,faces.get(0).outer())),
                "both pan cells leave through the one shared lip, no second competing lip");
    }
    /**
     * Two separated saddles that still reconverge on one raw receiver must not be forced into a single outlet:
     * a shared receiver alone is not proof of one saddle, so this stays UNKNOWN with no footprint.
     */
    private static WindowOutlets.Terrain splitCross(double left,double right) {
        var base=crossPan(0,5,0);
        return (x,z)-> {
            if(x==1 && z==-3)return left;
            if(x==3 && z==-3)return right;
            if((x==0 || x==4) && z==-4)return -20;
            return base.height(x,z);
        };
    }
    private static void sharedSaddleContracts() throws Exception {
        var sink=new Point(0,0);var t=splitCross(1,-1);
        var raw=new WorldDrainage(4,2,STEP4_SEA,t);var route=new WorldBasinRouting(raw,t,4,2048);
        var g=new WorldBasin(raw,t,4,2048).resolveHydraulic(sink);
        var chosen=route.outlet(sink);var b=route.at(sink);
        var expected=new WorldBasin.Face(new Point(0,0),new Point(4,-4),5);
        check(g.exits().size()==2 && !route.terminal(g.exits().get(0).outer()).equals(route.terminal(g.exits().get(1).outer())),
                "shared crossing fixture has distinct terminals, not receiver equality");
        check(chosen.known() && expected.equals(chosen.face()) && new Point(2,-2).equals(chosen.saddle())
                        && chosen.slope()==6/Math.sqrt(2),"unique first-integer outward slope selects the steeper face");
        var reversed=new ArrayList<>(g.exits());Collections.reverse(reversed);
        var rg=new WorldBasin.Geometry(g.state(),g.identity(),g.sill(),g.floor(),g.members(),reversed,g.path(),g.spill(),g.samples());
        check(expected.equals(route.chooseOutlet(rg).face()),"reversing complete cut enumeration keeps the selected face");
        check(!g.path().contains(expected.outer()),"minimax witness used the other exit, so path rebuilding is exercised");
        check(b!=null && b.published() && b.outlet().outer().equals(expected.outer())
                        && expected.outer().equals(b.nextSink()) && b.toSea(),"published lip and first receiver both use selected face");
        check(b!=null && b.published() && b.members().stream().allMatch(m->leadsTo(b,m,expected.outer())),
                "every basin member routes to selected lip rather than old minimax lip");
        check(chosen.geometry().path().contains(expected.outer())
                        && chosen.geometry().path().get(chosen.geometry().path().size()-1).equals(expected.outer()),
                "rebuilt complete witness ends at the selected strictly lower receiver");
        var tied=splitCross(0,0);var tr=new WorldDrainage(4,2,STEP4_SEA,tied);
        var tie=new WorldBasinRouting(tr,tied,4,2048).at(sink);
        check(tie!=null && !tie.published() && tie.reason().contains("tied outward"),"exactly tied outward slopes stay UNKNOWN");
        var flipped=splitCross(-1,1);var fr=new WorldDrainage(4,2,STEP4_SEA,flipped);
        check(new Point(0,-4).equals(new WorldBasinRouting(fr,flipped,4,2048).outlet(sink).face().outer()),
                "changing first exterior heights reverses physical choice, not coordinates");
        for(var off:List.of(new Point(-12,-16),new Point(8,-20))) {
            var shifted=shifted(t,off.x(),off.z());var sr=new WorldDrainage(4,2,STEP4_SEA,shifted);
            var shiftedRoute=new WorldBasinRouting(sr,shifted,4,2048);
            shiftedRoute.at(moved(expected.outer(),off.x(),off.z()));
            check(sameBasin(b,off,shiftedRoute.at(off)),"negative translation and receiver-first lookup preserve selected witness "+off);
        }
        var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<Future<WorldBasinRouting.Basin>>();
            for(int i=0;i<3;i++)jobs.add(pool.submit(()-> {
                var r=new WorldDrainage(4,2,STEP4_SEA,t);return new WorldBasinRouting(r,t,4,2048).at(sink);
            }));
            for(var job:jobs)check(b.equals(job.get(60,TimeUnit.SECONDS)),"fresh concurrent shared-saddle decision equals cold reference");
        } finally { pool.shutdownNow(); }
    }
    private static void sharedSaddleBalance() {
        var base=splitCross(1,-1);
        WindowOutlets.Terrain t=(x,z)->Math.abs(x)>12 || Math.abs(z)>12?-200:base.height(x,z);
        var raw=new WorldDrainage(4,2,STEP4_SEA,t);var route=new WorldBasinRouting(raw,t,4,2048);
        var sink=new Point(0,0);var selected=new Point(4,-4);var other=new Point(0,-4);
        var wet=new WorldBasinFlow(raw,route,wet(),4,4096);var dry=new WorldBasinFlow(raw,route,arid(),4,4096);
        var lake=wet.at(sink);var closed=dry.at(sink);var mouth=wet.at(selected);var dryMouth=dry.at(selected);
        check(lake.known() && closed.known() && mouth.known() && dryMouth.known(),
                "bounded shared-crossing catchment has complete water certificates");
        check(lake.equals(wet.at(new Point(4,0))) && closed.equals(dry.at(new Point(4,0))),
                "selected crossing keeps all members in one indivisible water Unit");
        check(lake.discharge()>0 && closed.discharge()==0 && close(mouth.discharge()-dryMouth.discharge(),lake.discharge()),
                "selected receiver gets the basin net surplus exactly once, not member rain twice");
        check(wet.at(other).known() && dry.at(other).known() && wet.at(other).equals(dry.at(other)),
                "unselected receiver never receives a share of basin runoff");
        withSourceCells(1,()-> {
            var support=new WorldBasinFlow(raw,route,wet(),4,4096);
            var r=support.region(0,-1,16);var b=route.at(sink);
            check(r.unresolved()==0 && inflowsReachLip(r,support,b),"selected crossing support and predecessor closure are complete");
            check(r.graph().edges().stream().anyMatch(e->b.members().contains(e.from()))
                            && r.graph().edges().stream().filter(e->b.members().contains(e.from()))
                            .allMatch(e->leadsTo(b,e.from(),selected)),"published member spine has only the selected outlet");
        });
    }
    private static WorldBasin.Geometry decisionGeometry(List<WorldBasin.Face> faces) {
        var f=faces.get(0);
        return new WorldBasin.Geometry(WorldBasin.State.MULTIPLE_EXITS,f.inner(),5,0,
                List.of(f.inner()),faces,List.of(f.inner(),f.outer()),null,0);
    }
    private static void saddleDecisionNegatives() {
        var peaks=Set.of(new Point(2,0),new Point(6,0),new Point(10,0),new Point(14,0));
        WindowOutlets.Terrain t=(x,z)->peaks.contains(new Point((int)x,(int)z))?5:0;
        var faces=List.of(new WorldBasin.Face(new Point(0,0),new Point(8,0),5),
                new WorldBasin.Face(new Point(4,0),new Point(12,0),5),
                new WorldBasin.Face(new Point(8,0),new Point(16,0),5));
        var a=crestColumns(t,faces.get(0),8);var b=crestColumns(t,faces.get(1),8);
        var c=crestColumns(t,faces.get(2),8);
        check(a.contains(new Point(6,0)) && b.contains(new Point(6,0))
                        && b.contains(new Point(10,0)) && c.contains(new Point(10,0))
                        && sharedColumns(t,faces,8).isEmpty(),"partial-overlap chain really has no all-face shared maximum");
        var raw=new WorldDrainage(8,2,-10,t);var route=new WorldBasinRouting(raw,t,8,2048);
        var refused=route.chooseOutlet(decisionGeometry(faces));
        check(!refused.known() && refused.reason().equals("MULTIPLE_EXITS shared saddle count=0"),
                "all-face intersection is required, not first face or partial overlap");
        WindowOutlets.Terrain platform=(x,z)->z==0 && x==0?5:0;
        var overlapping=List.of(faces.get(0),new WorldBasin.Face(new Point(8,0),new Point(0,0),5));
        check(sharedColumns(t,overlapping,8).size()==2,"multi-column platform fixture has two common maxima");
        var multi=route.chooseOutlet(decisionGeometry(overlapping));
        check(!multi.known() && multi.reason().equals("MULTIPLE_EXITS shared saddle count=2"),
                "multiple shared maximum columns stay UNKNOWN without coordinate choice");
        var noDropFaces=List.of(new WorldBasin.Face(new Point(0,0),new Point(4,0),5),
                new WorldBasin.Face(new Point(0,0),new Point(0,4),5));
        var pr=new WorldDrainage(4,2,-10,platform);
        var noDrop=new WorldBasinRouting(pr,platform,4,2048).chooseOutlet(decisionGeometry(noDropFaces));
        check(noDrop.known()==false && noDrop.reason().contains("tied outward"),
                "equal axis outward slopes at shared endpoint cannot be ordered");
        WindowOutlets.Terrain flatFirst=(x,z)->z==0 && (x==0 || x==1)?5:0;
        var flatRaw=new WorldDrainage(4,2,-10,flatFirst);
        var flatDecision=new WorldBasinRouting(flatRaw,flatFirst,4,2048).chooseOutlet(decisionGeometry(noDropFaces));
        check(!flatDecision.known() && flatDecision.reason().contains("no outward descent"),
                "first exterior neighbour must strictly descend, even if farther columns do descend");
        WindowOutlets.Terrain rising=(x,z)->x==0 && z==0?5:x==1 && z==0?6:0;
        var rr=new WorldDrainage(4,2,-10,rising);
        var blocked=new WorldBasinRouting(rr,rising,4,2048).chooseOutlet(decisionGeometry(noDropFaces));
        check(!blocked.known() && blocked.reason().contains("no outward descent"),
                "intermediate exterior crest above sill is rejected even with low endpoints");
    }
    private static void separateSaddlesSameReceiverUnknown() {
        var t=crossPan(5,0,5);var raw=new WorldDrainage(4,2,STEP4_SEA,t);var sink=new Point(0,0);
        var g=new WorldBasin(raw,t,4,2048).resolve(sink);var faces=g.exits();
        check(g.state()==WorldBasin.State.MULTIPLE_EXITS && faces.size()==2
                        && !faces.get(0).outer().equals(faces.get(1).outer()) && g.sill()==5,
                "fixture keeps two rim faces at one crest (faces="+faces.size()+" sill="+g.sill()+")");
        var first=crestColumns(t,faces.get(0),4);var second=crestColumns(t,faces.get(1),4);
        var shared=sharedColumns(t,faces,4);
        check(first.equals(Set.of(new Point(1,-1))) && second.equals(Set.of(new Point(3,-1))) && shared.isEmpty(),
                "the two saddles sit on separated columns: "+first+" vs "+second+" shared="+shared);
        var route=new WorldBasinRouting(raw,t,4,2048);
        check(drain(route,faces.get(0).outer()).equals(drain(route,faces.get(1).outer())),
                "they do reconverge on one raw receiver, so only saddle separation can refuse this pan");
        var basin=route.at(sink);
        check(basin!=null && !basin.published() && basin.reason().contains("MULTIPLE_EXITS"),
                "separated saddles stay UNKNOWN even with one receiver (reason="
                        +(basin==null?null:basin.reason())+")");
        check(basin!=null && basin.members().isEmpty(),
                "no footprint is fabricated for separated saddles sharing a downstream receiver");
    }

    // ── new evidence: region() must publish a supported spine, not every member ──

    /**
     * An eight-cell published pan {(0..3)x(0..1)} with one lip at (3,0)->(4,0). With {@code feeder} a five-cell
     * terrace climbs away from (0,2), so exactly one basin-external node (0,2) carries more than the source
     * threshold and pours into a member. Without it the pan is fed by its own rain only.
     */
    private static WindowOutlets.Terrain supportPan(boolean feeder) {
        var h=new HashMap<Point,Double>();
        for(int x=0;x<=3;x++)for(int z=0;z<=1;z++)h.put(new Point(x,z),0.0);
        for(int x=-1;x<=4;x++)for(int z=-1;z<=2;z++) {
            var p=new Point(x,z);if(!h.containsKey(p))h.put(p,100.0);
        }
        h.put(new Point(4,0),1.0);
        if(feeder) {
            h.put(new Point(0,2),3.0);
            for(int k=3;k<=8;k++)h.put(new Point(0,k),(double)(k+1));
            for(int k=2;k<=9;k++) { h.put(new Point(-1,k),200.0);h.put(new Point(1,k),200.0); }
            h.put(new Point(0,9),300.0);
        }
        return (x,z)->h.getOrDefault(new Point((int)x,(int)z),-50.0);
    }
    /** Run against a temporary channelMinCells, restoring the previous value whatever happens. */
    private static void withSourceCells(int cells,Runnable body) {
        String old=System.getProperty("geogenesis.channelMinCells");
        try {
            System.setProperty("geogenesis.channelMinCells",Integer.toString(cells));
            body.run();
        } finally {
            if(old==null)System.clearProperty("geogenesis.channelMinCells");
            else System.setProperty("geogenesis.channelMinCells",old);
        }
    }
    private static final Comparator<Point> XY=Comparator.comparingInt(Point::x).thenComparingInt(Point::z);
    /** Basin-external nodes whose own water clears the source threshold and whose next step enters the pan. */
    private static TreeSet<Point> supportedEntries(WorldBasinFlow.Region region,WorldBasinFlow flow,
                                                   Set<Point> members,double threshold) {
        var out=new TreeSet<Point>(XY);
        for(var e:region.water().entrySet()) {
            if(members.contains(e.getKey()) || !e.getValue().known())continue;
            if(!e.getValue().reaches(threshold))continue;
            var next=flow.geometricNext(e.getKey());
            if(next!=null && members.contains(next))out.add(e.getKey());
        }
        return out;
    }
    /** Every member on a basin route from an entry to the lip, walked through the published Basin.next alone. */
    private static TreeSet<Point> closureToLip(WorldBasinRouting.Basin b,WorldBasinFlow flow,Set<Point> entries) {
        var out=new TreeSet<Point>(XY);
        for(var entry:entries) {
            var p=flow.geometricNext(entry);
            for(int i=0;i<64 && p!=null;i++) {
                if(!b.members().contains(p))break;
                out.add(p);p=b.routeFrom(p);
            }
        }
        return out;
    }
    private static TreeSet<Point> edgeFrom(WorldBasinFlow.Region region) {
        var out=new TreeSet<Point>(XY);
        for(var e:region.graph().edges())out.add(e.from());
        return out;
    }
    private static double sourceThreshold(int cells) { return Math.ceil(cells)*1*1*.004*.3; }
    /**
     * Every published edge that starts on a member must still reach the lip along {@code Basin.next}; a spine that
     * stops dead inside the pan would silently drop that inflow.
     */
    private static boolean inflowsReachLip(WorldBasinFlow.Region region,WorldBasinFlow flow,WorldBasinRouting.Basin b) {
        var members=new HashSet<>(b.members());
        Point lip=b.outlet().outer();
        for(var e:region.graph().edges()) {
            if(!members.contains(e.from()))continue;
            var p=e.from();
            for(int i=0;i<64 && p!=null && members.contains(p);i++)p=b.routeFrom(p);
            if(!lip.equals(p))return false;
        }
        return true;
    }

    /**
     * A supported pan publishes only the entry-to-lip spine plus the spill, never all eight members: publishing
     * every member's own water is the "fishbone" that puts an artificial channel in the middle of a lake.
     * With no qualifying external input the spine is empty, so only the lip may publish — still not all members.
     */
    private static void basinSupportClosure() {
        withSourceCells(5,()->supportedPanPublishesSpine());
        withSourceCells(4,()->unsupportedPanPublishesLipOnly());
    }
    private static void supportedPanPublishesSpine() {
        var t=supportPan(true);var raw=new WorldDrainage(1,2,-10,t);var sink=new Point(0,0);
        var route=new WorldBasinRouting(raw,t,1,2048);var b=route.at(sink);
        check(b!=null && b.published() && b.members().size()==8 && b.outlet()!=null,
                "fixture publishes one eight-cell pan (members="+(b==null?null:b.members().size())+")");
        var flow=new WorldBasinFlow(raw,route,new LakeWaterBalance.Climate(.004,.0005,.3),1,4096);
        var region=flow.region(0,0,16);var members=new HashSet<>(b.members());
        var entries=supportedEntries(region,flow,members,sourceThreshold(5));
        var single=new TreeSet<Point>(XY);single.add(new Point(0,2));
        check(entries.equals(single),
                "exactly one basin-external node clears the threshold and enters the pan: "+entries);
        var closure=closureToLip(b,flow,entries);
        var lip=b.outlet().outer();
        check(closure.containsAll(Set.of(new Point(0,1),new Point(1,0),new Point(2,0),new Point(3,0)))
                        && closure.size()==4,
                "the entry's support spine walks (0,1)->(1,0)->(2,0)->(3,0): "+closure);
        var from=edgeFrom(region);
        check(from.contains(lip),"the spill itself stays published: "+lip+" in "+from);
        check(from.stream().filter(members::contains).toList().stream().allMatch(closure::contains),
                "no member publishes outside the support spine, so no fishbone: "
                        +from.stream().filter(members::contains).toList());
        check(from.containsAll(closure),"every support spine node is actually published: "+closure);
        check(members.stream().filter(m->!closure.contains(m)).count()==4,
                "at least one non-supported member is dropped (four of eight here)");
        check(inflowsReachLip(region,flow,b),"no pan inflow is truncated: every supported one still ends at the lip");
    }
    private static void unsupportedPanPublishesLipOnly() {
        var t=supportPan(false);var raw=new WorldDrainage(1,2,-10,t);var sink=new Point(0,0);
        var route=new WorldBasinRouting(raw,t,1,2048);var b=route.at(sink);
        check(b!=null && b.published() && b.members().size()==8,
                "the same eight-cell pan publishes without any external feeder");
        var flow=new WorldBasinFlow(raw,route,new LakeWaterBalance.Climate(.004,.0005,.3),1,4096);
        var region=flow.region(0,0,16);var members=new HashSet<>(b.members());
        var entries=supportedEntries(region,flow,members,sourceThreshold(4));
        check(entries.isEmpty(),"no basin-external node reaches the threshold, so there is no support: "+entries);
        var closure=closureToLip(b,flow,entries);
        check(closure.isEmpty(),"an unsupported pan has an empty support spine: "+closure);
        var from=edgeFrom(region);
        check(from.contains(b.outlet().outer()),"the lip still publishes as the internal overflow");
        check(from.stream().filter(members::contains).toList().equals(List.of(b.outlet().inner())),
                "only the spill member publishes diffuse surplus, never a whole fishbone: "
                        +from.stream().filter(members::contains).toList());
        check(inflowsReachLip(region,flow,b),"with no support, nothing is truncated because nothing is invented");
    }
}
