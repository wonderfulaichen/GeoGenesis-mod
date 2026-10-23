package com.geogenesis.worldgen.hydrology.model;

import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.geogenesis.worldgen.hydrology.model.ReverseOrdinaryProbe.*;

/** Isolated finite-fixture task. No provider integration or production budget policy. */
public final class ResumableDiscoveryProbe {
    record Identity(long seed,String config,String strategy,int step,int origin,int extent,Point target) { }
    record Limits(long expansions,long transitions,long sampleCalls,int samples,int states,int frontier) { }
    enum Status { PAUSED,CANCELLED,LIMIT,COMPLETE }
    record Snapshot(Identity identity,Limits limits,List<State> queue,Set<State> seen,Set<Point> sources,
                    Set<Point> samples,List<State> pending,int cursor,long expanded,long transitions,long calls,
                    Status status,String reason) {
        Snapshot {
            queue=List.copyOf(queue); seen=Set.copyOf(seen); sources=Set.copyOf(sources); samples=Set.copyOf(samples);
            pending=List.copyOf(pending);
        }
    }
    static int checks;
    static void require(boolean ok,String message) { if(!ok)throw new AssertionError(message); checks++; }
    static String strategy() {
        return "ordinary-fixture-v1/"+DeterministicSkeleton.GRADIENT_SPAN+"/"
                +DeterministicSkeleton.W_DROP+"/"+DeterministicSkeleton.W_DOWN+"/"+DeterministicSkeleton.W_KEEP;
    }
    static Identity id(int origin,int extent,int target) {
        return new Identity(9139912035078620160L,"analytic-valley-fixed-admission-v1",strategy(),1,origin,extent,new Point(target,0));
    }
    static Limits limits(long expanded) { return new Limits(expanded,200000,5000000,10000,20000,20000); }

    static final class Task {
        final Identity identity;
        final Limits limits;
        final ReverseOrdinaryProbe terrain;
        final ArrayDeque<State> queue;
        final Set<State> seen;
        final Set<Point> sources,samples;
        final List<State> pending=new ArrayList<>();
        int cursor;
        long expanded,transitions,calls;
        Status status;
        String reason;

        Task(Identity identity,Limits limits) {
            this.identity=identity; this.limits=limits;
            terrain=new ReverseOrdinaryProbe(identity.origin,identity.extent);
            queue=new ArrayDeque<>(); seen=new HashSet<>(); sources=new HashSet<>(); samples=new HashSet<>();
            for(int h=0;h<DIR.length;h++) { State s=new State(identity.target,h); queue.add(s); seen.add(s); }
            status=Status.PAUSED; reason="";
            if(seen.size()>limits.states || queue.size()>limits.frontier) { status=Status.LIMIT; reason="initial-memory"; }
        }

        Task(Identity identity,Limits limits,Snapshot saved) {
            if(!identity.equals(saved.identity) || !limits.equals(saved.limits))
                throw new IllegalArgumentException("checkpoint identity or limits mismatch");
            this.identity=identity; this.limits=limits;
            terrain=new ReverseOrdinaryProbe(identity.origin,identity.extent);
            queue=new ArrayDeque<>(saved.queue); seen=new HashSet<>(saved.seen);
            sources=new HashSet<>(saved.sources); samples=new HashSet<>(saved.samples);
            pending.addAll(saved.pending); cursor=saved.cursor;
            expanded=saved.expanded; transitions=saved.transitions; calls=saved.calls;
            status=saved.status; reason=saved.reason;
        }

        Snapshot snapshot() {
            return new Snapshot(identity,limits,new ArrayList<>(queue),seen,sources,samples,pending,cursor,
                    expanded,transitions,calls,status,reason);
        }

        Set<Point> publishedSources() {
            if(status!=Status.COMPLETE)throw new IllegalStateException("discovery incomplete");
            return Set.copyOf(sources);
        }

        Status advance(int batch,BooleanSupplier cancelled) {
            if(batch<1 || batch>4096)throw new IllegalArgumentException("batch outside 1..4096");
            if(status==Status.COMPLETE || status==Status.LIMIT)return status;
            status=Status.PAUSED; reason="";
            for(int i=0;i<batch && !queue.isEmpty();i++) {
                if(cancelled.getAsBoolean()) { status=Status.CANCELLED; return status; }
                if(expanded>=limits.expansions) { status=Status.LIMIT; reason="expansions"; return status; }
                if(!expand(cancelled))return status;
            }
            if(queue.isEmpty())status=Status.COMPLETE;
            return status;
        }

        boolean expand(BooleanSupplier cancelled) {
            State current=queue.peekFirst();
            boolean found=current.heading()==0 && terrain.admitted.contains(current.point());
            if(current.heading()!=0) {
                int[] d=DIR[current.heading()]; Point p=new Point(current.point().x()-d[0],current.point().z()-d[1]);
                if(terrain.inside(p)) while(cursor<DIR.length) {
                    if(cancelled.getAsBoolean()) { status=Status.CANCELLED; return false; }
                    State candidate=new State(p,cursor);
                    if(!transition(current,candidate))return false;
                    cursor++;
                }
            }
            queue.removeFirst(); queue.addAll(pending); seen.addAll(pending);
            if(found)sources.add(current.point());
            pending.clear(); cursor=0; expanded++;
            return true;
        }

        boolean transition(State current,State candidate) {
            int span=Math.max(1,DeterministicSkeleton.GRADIENT_SPAN),width=2*span+1;
            Set<Point> sampled=new HashSet<>();
            for(int x=-span;x<=span;x++)for(int z=-span;z<=span;z++)
                sampled.add(new Point(candidate.point().x()+x,candidate.point().z()+z));
            Set<Point> union=new HashSet<>(samples); union.addAll(sampled);
            String exceeded=transitions+1>limits.transitions?"transitions":calls+(long)width*width>limits.sampleCalls?"sample-calls"
                    :union.size()>limits.samples?"unique-samples":"";
            if(!exceeded.isEmpty()) { status=Status.LIMIT; reason=exceeded; return false; }
            // Charge before evaluation, including warm-cache calls; preserve the cursor across cancellation.
            transitions++; calls+=(long)width*width; samples.addAll(sampled);
            boolean add=current.equals(terrain.next(candidate)) && !seen.contains(candidate);
            exceeded=add && seen.size()+pending.size()+1>limits.states?"states"
                    :add && queue.size()-1+pending.size()+1>limits.frontier?"frontier":"";
            if(!exceeded.isEmpty()) { status=Status.LIMIT; reason=exceeded; return false; }
            if(add)pending.add(candidate);
            return true;
        }
    }

    static Snapshot finish(Task task,int batch,boolean evict) {
        while(task.advance(batch,()->false)==Status.PAUSED)if(evict)task.terrain.transitions.clear();
        return task.snapshot();
    }
    static void unpublished(Task task) {
        boolean rejected=false;
        try { task.publishedSources(); } catch(IllegalStateException expected) { rejected=true; }
        require(rejected,"incomplete task must not publish sources");
    }

    static String equivalence(int origin) {
        Identity key=id(origin,36,origin+28); Limits limit=limits(4096);
        Snapshot reference=finish(new Task(key,limit),4096,false);
        require(reference.status==Status.COMPLETE,"finite task completes");
        require(reference.sources.equals(new ReverseOrdinaryProbe(origin).forwardOracle(key.target)),"sources equal forward oracle");
        for(int batch:new int[]{1,2,7,4096}) {
            Task task=new Task(key,limit); task.advance(7,()->false); Snapshot frozen=task.snapshot();
            Task restored=new Task(key,limit,frozen);
            require(restored.snapshot().equals(frozen),"checkpoint restores queue and logical counters exactly");
            Snapshot result=finish(restored,batch,true);
            require(result.equals(reference),"batch/resume/cache eviction must preserve all logical evidence");
            require(frozen.expanded==7,"checkpoint immutable after later progress");
            require(restored.publishedSources().equals(reference.sources),"only completed result published");
        }
        Task cancelled=new Task(key,limit); cancelled.advance(32,()->false);
        Snapshot before=cancelled.snapshot(); int[] polls={0};
        cancelled.advance(4096,()->++polls[0]==3);
        require(cancelled.status==Status.CANCELLED,"cancel inside predecessor loop");
        Snapshot stopped=cancelled.snapshot();
        require(stopped.expanded==before.expanded && stopped.transitions==before.transitions+1
                && stopped.calls==before.calls+25 && stopped.cursor==1 && stopped.queue.equals(before.queue)
                && stopped.seen.equals(before.seen),"cancel preserves evaluated work and predecessor cursor without publishing partial expansion");
        int[] repeated={0}; cancelled.advance(4096,()->++repeated[0]==2);
        require(cancelled.snapshot().equals(stopped),"repeated cancellation cannot replay or reset charged work");
        unpublished(cancelled);
        require(finish(new Task(key,limit,stopped),2,true).equals(reference),"cancel cold restore equals uninterrupted result");
        boolean mismatch=false;
        try { new Task(id(origin,36,origin+20),limit,before); } catch(IllegalArgumentException expected) { mismatch=true; }
        require(mismatch,"checkpoint rejects different target identity");
        mismatch=false;
        try { new Task(key,limits(4097),before); } catch(IllegalArgumentException expected) { mismatch=true; }
        require(mismatch,"checkpoint cannot increase total budget on resume");
        for(int stop=2;stop<=10;stop++) {
            Task retry=new Task(key,limit,before); int[] count={0}; final int at=stop;
            retry.advance(7,()->++count[0]==at);
            require(retry.status==Status.CANCELLED,"cancel at each predecessor cursor");
            Snapshot saved=retry.snapshot();
            require(new Task(key,limit,saved).snapshot().equals(saved),"mid-state checkpoint preserves pending results");
            require(finish(new Task(key,limit,saved),7,true).equals(reference),"all predecessor cancellation positions retry identically");
        }
        for(Identity wrong:List.of(new Identity(key.seed+1,key.config,key.strategy,key.step,key.origin,key.extent,key.target),
                new Identity(key.seed,key.config+"x",key.strategy,key.step,key.origin,key.extent,key.target),
                new Identity(key.seed,key.config,key.strategy+"x",key.step,key.origin,key.extent,key.target),
                new Identity(key.seed,key.config,key.strategy,key.step+1,key.origin,key.extent,key.target))) {
            boolean rejected=false;
            try { new Task(wrong,limit,before); } catch(IllegalArgumentException expected) { rejected=true; }
            require(rejected,"checkpoint rejects seed/config/strategy/step mismatch");
        }
        return "origin="+origin+" expanded="+reference.expanded+" transitions="+reference.transitions
                +" calls="+reference.calls+" uniqueSamples="+reference.samples.size()+" states="+reference.seen.size()+"\n";
    }

    static String exhaustion() {
        Identity key=id(-128,128,-16); Limits limit=limits(4096);
        Task task=new Task(key,limit); finish(task,7,true);
        require(task.status==Status.LIMIT && task.expanded==4096,"cumulative expansion limit survives batches");
        Snapshot terminal=task.snapshot(); unpublished(task);
        require(finish(new Task(key,limit,terminal),4096,false).equals(terminal),"exhaustion cannot reset on restore");
        for(Limits tiny:List.of(new Limits(4096,1,5000000,10000,20000,20000),
                new Limits(4096,200000,1,10000,20000,20000),new Limits(4096,200000,5000000,1,20000,20000),
                new Limits(4096,200000,5000000,10000,9,20000),new Limits(4096,200000,5000000,10000,20000,9))) {
            Task bounded=new Task(id(0,36,28),tiny); finish(bounded,2,false);
            require(bounded.status==Status.LIMIT,"independent resource limit enforced"); unpublished(bounded);
            require(bounded.expanded<=tiny.expansions && bounded.transitions<=tiny.transitions
                    && bounded.calls<=tiny.sampleCalls && bounded.samples.size()<=tiny.samples
                    && bounded.seen.size()<=tiny.states && bounded.queue.size()<=tiny.frontier,"committed resources never overshoot");
        }
        return "long expanded="+task.expanded+" status="+task.status+" reason="+task.reason+"\n";
    }

    public static void main(String[] args) throws Exception {
        String summary=equivalence(0)+equivalence(-40)+exhaustion();
        summary+="RESUMABLE COMPONENT PASS checks="+checks+"\n"
                +"Scope: in-memory immutable checkpoints; finite ordinary-flow fixture only; logical sampling charged even on cache hit.\n"
                +"NOT CERTIFIED: production admission/guards/spill/provider cancellation, durable checkpoint format, cache bytes or infinite-world completeness.\n";
        Files.writeString(Path.of(args[0]),summary); System.out.print(summary);
    }
}
