package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static com.geogenesis.worldgen.hydrology.model.LocalFlowEvents.*;

/** Differential tests of a finite event model, deliberately not a production river generator. */
public final class LocalFlowOwnershipProbe {
    private static final Path OUT=Path.of(System.getProperty("localFlow.output","build/local-flow-reassessment/ownership"));
    private static final StringBuilder LOG=new StringBuilder();
    private static int checks,failures;
    private static void check(boolean ok,String label) {
        checks++;if(!ok)failures++;String line=(ok?"PASS ":"FAIL ")+label;LOG.append(line).append('\n');System.out.println(line);
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);long seed=args.length==0?ViewSpec.SEED:Long.parseLong(args[0]);
        Path original=Path.of("run/config/geogenesis-common.toml"),copy=OUT.resolve("config.toml");
        byte[] input=Files.readAllBytes(original);Files.write(copy,input);
        try(var config=CommentedFileConfig.of(copy,TomlFormat.instance())) {
            config.load();GeoGenesisConfig.SPEC.setConfig(config);
            var params=GeoGenesisConfig.INSTANCE.buildParams();long fingerprint=GeoGenesisConfig.configFingerprint();
            var spec=new Spec(seed,fingerprint,4,2,16);
            synthetic(spec);real(spec,params);rawDischarge(seed,params);
        }
        check(Arrays.equals(input,Files.readAllBytes(original)),"original TOML unchanged");
        LOG.append("SUMMARY checks=").append(checks).append(" failures=").append(failures)
                .append("\nEVENT_CONTRACT=FINITE_FROZEN_TRANSPORT_ONLY\nPRODUCTION_PROMOTION=NOT_AUTHORIZED_BY_THIS_PROBE\n");
        Files.writeString(OUT.resolve("metrics.txt"),LOG);System.out.println("SUMMARY checks="+checks+" failures="+failures);
        System.exit(failures==0?0:1);
    }

    private static void synthetic(Spec spec) throws Exception {
        WindowOutlets.Terrain plane=(x,z)->1000-.17*x-.83*z;
        var model=new LocalFlowEvents(spec,plane);var target=new Rect(-16,-16,15,15);
        var local=model.query(target,16,false);var events=model.events(target.expand(64));
        var full=oracle(model,events,target);
        check(local.field().equals(full),"true smaller dependency domain equals independently accumulated larger domain");
        check(local.field().equals(planeOracle(events,target,spec.lifetime())),"analytic plane oracle, independent of path integration and owner fold");
        check(model.events(target.expand(16)).size()<events.size(),"compared event domains genuinely differ");
        variants(model,target,local);
        var noHalo=model.accumulate(target,model.events(target),16,false);
        check(!local.field().equals(noHalo.field()),"negative control: dropping external halo events must change field");
        var list=new ArrayList<>(model.events(target.expand(16)));list.add(list.get(0));boolean duplicate=false;
        try {model.accumulate(target,list,16,false);}catch(IllegalArgumentException expected){duplicate=true;}
        check(duplicate,"negative control: duplicate event rejected instead of double injection");
        var wrong=new LocalFlowEvents(new Spec(spec.seed()+1,spec.configuration(),4,2,16),plane).query(target,16,false);
        check(!local.field().equals(wrong.field()),"negative control: changing local seed detected");
        var config=new LocalFlowEvents(new Spec(spec.seed(),spec.configuration()+1,4,2,16),plane).query(target,16,false);
        check(!local.field().equals(config.field()),"configuration participates in event identity");
        var wider=new LocalFlowEvents(new Spec(spec.seed(),spec.configuration(),4,2,32),plane).query(target,16,false);
        check(!local.field().equals(wider.field()),"lifetime defines a different model, not complete upstream Q");
        boolean cancelled=false;try {Thread.currentThread().interrupt();model.query(target,16,false);}
        catch(CancellationException expected){cancelled=true;}finally {Thread.interrupted();}
        check(cancelled && model.query(target,16,false).field().equals(local.field()),"cancellation and clean retry");
        writeField(OUT.resolve("synthetic-field.csv"),local);cost("synthetic",local);
    }

    private static Map<Point,Amount> oracle(LocalFlowEvents model,List<Event> events,Rect target) {
        var totals=new TreeMap<Point,Amount>();
        // Explicit event-by-event fold, no owner grouping, output cropping only after path construction.
        for(var event:events) {
            var path=model.path(event);
            for(int i=path.size()-1;i>=0;i--) {
                var p=path.get(i);if(!target.contains(p))continue;var q=i+1<path.size()?path.get(i+1):p;
                var old=totals.getOrDefault(p,new Amount(0,0,0,0));
                totals.put(p,new Amount(old.visits()+1,old.mass()+event.mass(),
                        old.momentumX()+(long)event.mass()*(q.x()-p.x()),old.momentumZ()+(long)event.mass()*(q.z()-p.z())));
            }
        }
        return totals;
    }

    private static Map<Point,Amount> planeOracle(List<Event> events,Rect target,int lifetime) {
        var totals=new TreeMap<Point,Amount>();
        // For h=1000-.17*x-.83*z the unique cardinal recipient is always z+1.
        for(var event:events)for(int age=0;age<lifetime;age++) {
            var p=new Point(event.start().x(),event.start().z()+age);if(!target.contains(p))continue;
            var a=totals.getOrDefault(p,new Amount(0,0,0,0));
            totals.put(p,new Amount(a.visits()+1,a.mass()+event.mass(),0,
                    a.momentumZ()+(age+1<lifetime?event.mass():0)));
        }
        return totals;
    }
    private static void variants(LocalFlowEvents model,Rect target,Result expected) throws Exception {
        for(int owner:new int[]{7,16,23})for(boolean reverse:new boolean[]{false,true})
            check(model.query(target,owner,reverse).field().equals(expected.field()),"owner repartition/reverse owner="+owner+" reverse="+reverse);
        var shifted=new Rect(target.x0()-8,target.z0()-7,target.x1()+9,target.z1()+11);
        var larger=model.query(shifted,13,true).field();boolean same=true;
        for(int x=target.x0();x<=target.x1();x++)for(int z=target.z0();z<=target.z1();z++) {
            var p=new Point(x,z);same&=Objects.equals(expected.field().get(p),larger.get(p));
        }
        check(same,"offset output owner/corner overlap agrees, no event killed at output boundary");
        var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<Future<Result>>();for(int i=0;i<3;i++)jobs.add(pool.submit(()->model.query(target,11,true)));
            boolean equal=true;for(var job:jobs)equal&=expected.field().equals(job.get().field());
            check(equal,"three independent event accumulators agree under concurrent queries");
        } finally {pool.shutdownNow();}
    }

    private static void real(Spec spec,TerrainParams params) throws Exception {
        var gen=new CellGenerator(params,params.minY(),params.maxY());gen.seed(spec.seed());double hs=params.horizontalScale();
        WindowOutlets.Terrain terrain=(x,z)->gen.erodedHeight(x/hs,z/hs);
        var model=new LocalFlowEvents(spec,terrain);var target=new Rect(-240,496,-225,511);
        long fine=HierarchicalHydrology.Diag.FINE_SOLVES.get();var local=model.query(target,16,false);
        check(!local.field().isEmpty(),"real production erodedHeight produces finite local event field");
        check(local.field().equals(oracle(model,model.events(target.expand(32)),target)),"real smaller/larger dependency domains agree");
        variants(model,target,local);
        var cold=new CellGenerator(params,params.minY(),params.maxY());cold.seed(spec.seed());
        var rebuilt=new LocalFlowEvents(spec,(x,z)->cold.erodedHeight(x/hs,z/hs)).query(target,23,true);
        check(local.field().equals(rebuilt.field()),"real independent cold terrain engine/reverse event reconstruction bit-exact");
        check(HierarchicalHydrology.Diag.FINE_SOLVES.get()==fine,"no neighbouring fine hydrology solves");
        writeField(OUT.resolve("real-field.csv"),local);cost("real",local);
        drawEventField(target,local,terrain);
        LOG.append("REAL terrainRetainedTiles=").append(gen.erosionTileCacheSize()).append(" coldTiles=")
                .append(cold.erosionTileCacheSize()).append(" coordinateUnits=MC_BLOCK hs=").append(hs).append('\n');
    }

    private static void cost(String name,Result r) {
        LOG.append("COST ").append(name).append(" events=").append(r.events()).append(" steps=").append(r.steps())
                .append(" peakPath=").append(r.peakPath()).append(" peakWorkingSamples=").append(r.peakSamples())
                .append(" uniqueTerrainSamples=").append(r.uniqueSamples()).append(" sampleCalls=").append(r.sampleCalls())
                .append(" windowMoves=").append(r.moves()).append(" retainedOutputColumns=").append(r.field().size())
                .append(" stops=").append(r.stops()).append(" auditUniqueSetUnboundedByWindow=true eventDirectoryMaterialized=true\n");
    }
    private static void writeField(Path path,Result result) throws Exception {
        var csv=new StringBuilder("x,z,visits,mass,momentumX,momentumZ\n");
        result.field().forEach((p,a)->csv.append(p.x()).append(',').append(p.z()).append(',').append(a.visits()).append(',')
                .append(a.mass()).append(',').append(a.momentumX()).append(',').append(a.momentumZ()).append('\n'));
        Files.writeString(path,csv);
    }

    private static Object tile(CellGenerator gen,int x,int z) throws Exception {
        Method method=CellGenerator.class.getMethod("getErosionTileResultForProbe",int.class,int.class);
        return method.invoke(gen,x,z);
    }
    private static float[][] array(Object tile) throws Exception {
        Field f=tile.getClass().getDeclaredField("discharge");f.setAccessible(true);return (float[][])f.get(tile);
    }
    private static int origin(Object tile,String name) throws Exception {
        Field f=tile.getClass().getDeclaredField(name);f.setAccessible(true);return f.getInt(tile);
    }
    private static float read(Object tile,int x,int z) throws Exception {
        return array(tile)[z-origin(tile,"originZ")][x-origin(tile,"originX")];
    }
    private static int constant(String name) throws Exception {
        Field f=CellGenerator.class.getDeclaredField(name);f.setAccessible(true);return f.getInt(null);
    }
    private static void rawDischarge(long seed,TerrainParams params) throws Exception {
        var gen=new CellGenerator(params,params.minY(),params.maxY());gen.seed(seed);
        int center=constant("ERODE_TILE_CENTER"),cx=-5*center,cz=5*center;
        Object left=tile(gen,cx-center,cz),right=tile(gen,cx,cz);
        var csv=new StringBuilder("wuX,wuZ,phase,leftDis,rightDis,absoluteDifference\n");
        int changed=0,crossing=0,n=0;double max=0,total=0;
        for(int x=cx-8;x<=cx+8;x++)for(int z=cz+8;z<=cz+40;z++) {
            double a=read(left,x,z),b=read(right,x,z),d=Math.abs(a-b);n++;total+=d;max=Math.max(max,d);
            if(d!=0)changed++;if((a>=.01)!=(b>=.01))crossing++;
            csv.append(x).append(',').append(z).append(',').append(x-cx).append(',').append(a).append(',').append(b).append(',').append(d).append('\n');
        }
        check(n>0 && Double.isFinite(max),"raw production dis compared at identical coordinates in two actual overlapping tile arrays");
        var cold=new CellGenerator(params,params.minY(),params.maxY());cold.seed(seed);
        var cr=array(tile(cold,cx,cz));var cl=array(tile(cold,cx-center,cz));
        check(Arrays.deepEquals(array(left),cl) && Arrays.deepEquals(array(right),cr),"each raw tile independently replays bit-exact in reverse generation order");
        Files.writeString(OUT.resolve("raw-discharge-overlap.csv"),csv);
        LOG.append("RAW_DISCHARGE compared=").append(n).append(" different=").append(changed)
                .append(" maxAbs=").append(max).append(" meanAbs=").append(total/n).append(" selectionThresholdDiagnosticOnly=.01 disagreements=")
                .append(crossing).append(" wuNotBlocks=true noProductionSourcePolicyChanged=true\n");
        LOG.append("RAW_DISCHARGE tileReplaySHA256=").append(digest(array(left))).append('/').append(digest(array(right))).append('\n');
    }
    private static void drawEventField(Rect target,Result result,WindowOutlets.Terrain terrain) throws Exception {
        int scale=16,width=(target.x1()-target.x0()+1)*scale,height=(target.z1()-target.z0()+1)*scale;
        var image=new java.awt.image.BufferedImage(width*2,height+40,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(java.awt.Color.WHITE);
        g.drawString("DIAGNOSTIC FROZEN TERRAIN | FINITE EVENT MASS (NOT RIVER/WATER)",4,17);
        long max=result.field().values().stream().mapToLong(Amount::mass).max().orElse(1);
        for(int x=target.x0();x<=target.x1();x++)for(int z=target.z0();z<=target.z1();z++) {
            int band=((int)Math.floor(terrain.height(x,z)/8))&7,v=30+225*band/7;
            g.setColor(new java.awt.Color(v,v,v));g.fillRect((x-target.x0())*scale,40+(z-target.z0())*scale,scale,scale);
            var a=result.field().get(new Point(x,z));int mass=a==null?0:(int)(255*a.mass()/Math.max(1,max));
            g.setColor(new java.awt.Color(mass,mass/2,0));g.fillRect(width+(x-target.x0())*scale,40+(z-target.z0())*scale,scale,scale);
        }
        g.dispose();javax.imageio.ImageIO.write(image,"png",OUT.resolve("finite-event-diagnostic.png").toFile());
    }
    private static String digest(float[][] values) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");for(var row:values)for(float v:row) {
            int b=Float.floatToRawIntBits(v);for(int i=0;i<4;i++)hash.update((byte)(b>>>(8*i)));
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
