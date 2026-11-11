package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.TectonicField;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent finite diagnostics for the §11 R0–R2 candidate, not a production test. */
public final class DiagnosticTectonicDriverCheck {
    private static final long[] SEEDS = {9139912035078620160L, 0, -419, 12345};
    private static final long SS = 0x9E3779B97F4A7C15L, VS = 0xBF58476D1CE4E5B9L;
    private static final long WS = 0x2C6EF1A384BD9075L;
    private static final double L = 2000, R = 150, H = R / (2 * Math.sqrt(2));
    private static final BigInteger MOD = BigInteger.ONE.shiftLeft(64), MASK = BigInteger.valueOf(0xffffff);
    private static final Map<Long, Map<Long, Site>> REFERENCE_SITES = new LinkedHashMap<>();
    private static final Map<Long, Map<Long, Double>> REFERENCE_COEFFICIENTS = new LinkedHashMap<>();
    private final List<String> assertions = new ArrayList<>(List.of("seed,case,check,status,detail"));
    private final List<String> data = new ArrayList<>(List.of("seed,case,path,axis,h,minus,center,plus,derivative,localError"));
    private final StringWriter exceptions = new StringWriter();
    private int failures, checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("operators") || args[0].equals("replay")))
            throw new IllegalArgumentException("usage: operators|replay outDir frozenDriverDir");
        Path out = Path.of(args[1]);
        Files.createDirectory(out);
        DiagnosticTectonicDriverCheck c = new DiagnosticTectonicDriverCheck();
        try {
            c.operators();
            if (args[0].equals("replay") && c.failures == 0) c.replay(Path.of(args[2]));
            else if (args[0].equals("replay")) c.record(false, 0, "replay", "operators prerequisite", "replay skipped");
        } catch (Throwable t) { c.error(0, "run", t); }
        finally { c.save(out); }
        System.out.println((c.failures == 0 ? "PASS" : "FAIL") + " checks=" + c.checks + " failures=" + c.failures);
        if (c.failures != 0) System.exit(1);
    }

    private void save(Path out) throws Exception {
        Files.write(out.resolve("assertions.csv"), assertions, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.write(out.resolve("samples.csv"), data, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("exceptions.txt"), exceptions.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("check.txt"), (failures == 0 ? "PASS" : "FAIL") + "\nchecks=" + checks
                + "\nfailures=" + failures + "\nFinite samples are not a global proof; no propagation or terrain.\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String csv(Object... fields) {
        StringBuilder s = new StringBuilder();
        for (Object field : fields) {
            if (s.length() != 0) s.append(',');
            s.append('"').append(String.valueOf(field).replace("\"", "\"\"")).append('"');
        }
        return s.toString();
    }
    private void record(boolean ok, long seed, String id, String check, String detail) {
        checks++; if (!ok) failures++;
        assertions.add(csv(seed, id, check, ok ? "PASS" : "FAIL", detail));
    }
    private void error(long seed, String id, Throwable t) {
        record(false, seed, id, "exception", t.toString());
        t.printStackTrace(new PrintWriter(exceptions));
    }
    private static boolean near(double a, double b, double tol) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= tol;
    }
    private void eq(long seed, String id, String label, double got, double expected, double tol) {
        record(near(got, expected, tol), seed, id, label, "got=" + got + ";expected=" + expected + ";tol=" + tol);
    }

    private record Site(double x, double z, double vx, double vz) { }
    private record F(double v, double x, double z) { }
    private record ND(double n, double d) { }
    private record Near(double first, double second, double outside) { }
    private record Case(long seed, String id, double qx, double qz, double x, double z) { }

    private static long hash(long seed, int x, int z, long salt) {
        BigInteger h = BigInteger.valueOf(x).multiply(BigInteger.valueOf(374761393L))
                .add(BigInteger.valueOf(z).multiply(BigInteger.valueOf(668265263L)))
                .add(BigInteger.valueOf(salt)).add(BigInteger.valueOf(seed).multiply(BigInteger.valueOf(0x9E3779B9L))).mod(MOD);
        h = h.xor(h.shiftRight(16)).multiply(BigInteger.valueOf(1274126177)).mod(MOD);
        return h.xor(h.shiftRight(16)).longValue();
    }
    private static double unit(long value, int shift) {
        BigInteger b = BigInteger.valueOf(value); if (value < 0) b = b.add(MOD);
        return b.shiftRight(shift).and(MASK).doubleValue() / 16777216.0;
    }
    private static Site site(long seed, int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        Map<Long, Site> sites = REFERENCE_SITES.computeIfAbsent(seed, ignored -> new LinkedHashMap<>());
        return sites.computeIfAbsent(key, ignored -> buildSite(seed, x, z));
    }
    private static Site buildSite(long seed, int x, int z) {
        long a = hash(seed, x, z, SS), b = hash(seed, x, z, VS);
        double angle = unit(b, 0) * Math.PI * 2, speed = .3 + .7 * unit(b, 24);
        return new Site((x + .5 + (unit(a, 0) - .5) * .7) * L,
                (z + .5 + (unit(a, 24) - .5) * .7) * L, Math.cos(angle) * speed, Math.sin(angle) * speed);
    }
    private static double[] cat(double t) {
        double q = t * t, c = q * t;
        return new double[]{-.5*c+q-.5*t, 1.5*c-2.5*q+1, -1.5*c+2*q+.5*t, .5*c-.5*q};
    }
    private static double noise(long seed, double x, double z, long salt) {
        int ix = (int)Math.floor(x), iz = (int)Math.floor(z);
        double[] a = cat(x-ix), b = cat(z-iz); double sum = 0;
        for (int i=0;i<4;i++) {
            double column = 0;
            for (int j=0;j<4;j++) column += (2*unit(hash(seed, ix+i-1, iz+j-1, salt),0)-1)*b[j];
            sum += column*a[i];
        }
        return sum;
    }
    private static double[] warp(long seed, double x, double z) {
        return new double[]{x + 130*noise(seed,x/400,z/400,WS),
                z + 130*noise(seed,x/400+17.3,z/400+31.7,WS+1)};
    }
    private static double cubicDerivative(double[] p, double t) {
        return .5 * ((p[2] - p[0]) + 2 * (2*p[0]-5*p[1]+4*p[2]-p[3]) * t
                + 3 * (-p[0]+3*p[1]-3*p[2]+p[3]) * t*t);
    }
    private static F noiseDerivative(long seed, double x, double z, long salt) {
        int ix=(int)Math.floor(x), iz=(int)Math.floor(z);
        double tx=x-ix,tz=z-iz; double[] ax=cat(tx),az=cat(tz),cols=new double[4],rows=new double[4];
        double vx=0,vz=0;
        for(int i=0;i<4;i++) {
            double[] zs=new double[4];
            for(int j=0;j<4;j++) zs[j]=2*unit(hash(seed,ix+i-1,iz+j-1,salt),0)-1;
            for(int j=0;j<4;j++) cols[i]+=zs[j]*az[j];
            vz+=ax[i]*cubicDerivative(zs,tz);
        }
        for(int j=0;j<4;j++) {
            double[] xs=new double[4];
            for(int i=0;i<4;i++) xs[i]=2*unit(hash(seed,ix+i-1,iz+j-1,salt),0)-1;
            rows[j]=cubicDerivative(xs,tx); vx+=az[j]*rows[j];
        }
        return new F(noise(seed,x,z,salt),vx,vz);
    }
    private static F projection(double x, double z, double vx, double vz) {
        double d2 = x*x+z*z, dot = vx*x+vz*z, f, derivative;
        if (d2 < R*R) { f = (3*R*R-d2)/(2*R*R*R); derivative = -1/(R*R*R); }
        else { f = 1/Math.sqrt(d2); derivative = -f/d2; }
        return new F(dot*f, vx*f+dot*x*derivative, vz*f+dot*z*derivative);
    }
    private static ND window(long seed, double x, double z, int bx, int bz) {
        double n=0,d=0;
        for (int i=-2;i<=2;i++) for (int j=-2;j<=2;j++) {
            Site s=site(seed,bx+i,bz+j); double rx=x-s.x, rz=z-s.z;
            double w=Math.exp(-(rx*rx+rz*rz)/2000000.0);
            n += w*projection(rx,rz,s.vx,s.vz).v; d += w;
        }
        return new ND(n,d);
    }
    private static ND mixed(long seed, double x, double z) {
        int bx=(int)Math.floor(x/L), bz=(int)Math.floor(z/L);
        double u=x/L-bx,v=z/L-bz, a=u*u*(3-2*u), b=v*v*(3-2*v), n=0,d=0;
        for (int i=0;i<2;i++) for(int j=0;j<2;j++) {
            double w=(i==0?1-a:a)*(j==0?1-b:b); ND q=window(seed,x,z,bx+i,bz+j);
            n+=w*q.n; d+=w*q.d;
        }
        return new ND(n,d);
    }
    private static double raw(long seed, double x, double z) { ND a=mixed(seed,x,z); return 2*a.n/a.d; }
    // Truncated-power cubic spline, algebraically different from the piecewise candidate.
    private static F b3(double t) {
        double v=0,d=0,dd=0; int[] c={1,-4,6,-4,1};
        for(int i=0;i<5;i++) { double a=Math.max(0,t+2-i); v+=c[i]*a*a*a/6; d+=c[i]*a*a/2; dd+=c[i]*a; }
        return new F(v,d,dd);
    }
    private static Near nearest(long seed, double x, double z, int radius) {
        int bx=(int)Math.floor(x/L), bz=(int)Math.floor(z/L); double a=Double.POSITIVE_INFINITY,b=a;
        for(int i=-radius;i<=radius;i++) for(int j=-radius;j<=radius;j++) {
            Site s=site(seed,bx+i,bz+j); double d=Math.hypot(x-s.x,z-s.z);
            if(d<a) { b=a;a=d; } else if(d<b) b=d;
        }
        double fx=x-bx*L,fz=z-bz*L;
        return new Near(a,b,Math.min(Math.min(fx,L-fx),Math.min(fz,L-fz))+(radius+.15)*L);
    }
    private static double rawDistance(long seed,double x,double z) {
        double[] q=warp(seed,x,z); Near n=nearest(seed,q[0],q[1],4);
        if (!(n.outside > n.second)) throw new AssertionError("reference nearest enumeration incomplete");
        return (n.second-n.first)/2;
    }
    private static double coefficient(long seed, int ix, int iz) {
        long key = ((long) ix << 32) ^ (iz & 0xffffffffL);
        var coefficients = REFERENCE_COEFFICIENTS.computeIfAbsent(seed, ignored -> new LinkedHashMap<>());
        return coefficients.computeIfAbsent(key, ignored -> rawDistance(seed, ix * H, iz * H));
    }
    private static F distance(long seed,double x,double z) {
        int ix=(int)Math.floor(x/H),iz=(int)Math.floor(z/H); double value=0,dx=0,dz=0;
        for(int i=-1;i<=2;i++) for(int j=-1;j<=2;j++) {
            F a=b3(x/H-ix-i),b=b3(z/H-iz-j); double c=coefficient(seed,ix+i,iz+j);
            value+=a.v*b.v*c; dx+=a.x*b.v*c/H; dz+=a.v*b.x*c/H;
        }
        return new F(value,dx,dz);
    }
    private static double diff(java.util.function.DoubleUnaryOperator f, double x, double h) {
        return (f.applyAsDouble(x-2*h)-8*f.applyAsDouble(x-h)+8*f.applyAsDouble(x+h)-f.applyAsDouble(x+2*h))/(12*h);
    }

    private void operators() {
        record(DiagnosticTectonicDriver.L==L && DiagnosticTectonicDriver.R==R && DiagnosticTectonicDriver.SIGMA==1000
                && DiagnosticTectonicDriver.GAIN==2 && DiagnosticTectonicDriver.H==H,0,"constants","contract","fixed parameters");
        splineChecks(); projectionChecks();
        for(long seed:SEEDS) {
            try { fixtureChecks(seed); } catch(Throwable t) { error(seed,"fixtures",t); }
            try { fieldChecks(seed); interfaceChecks(seed); } catch(Throwable t) { error(seed,"fields",t); }
        }
    }
    private void interfaceChecks(long seed) {
        var driver = new DiagnosticTectonicDriver(seed);
        double epsilon = 1e-5;
        for (int k = -1; k <= 1; k++) for (int axis = 0; axis < 2; axis++) {
            double x = axis == 0 ? k * L : 317, z = axis == 1 ? k * L : -281;
            var left = driver.stressAt(x - (axis == 0 ? epsilon : 0), z - (axis == 1 ? epsilon : 0));
            var right = driver.stressAt(x + (axis == 0 ? epsilon : 0), z + (axis == 1 ? epsilon : 0));
            var center = driver.stressAt(x, z);
            double derivative = axis == 0 ? center.dx() : center.dz();
            eq(seed,"POU-"+k+":"+axis,"value-interface",right.raw()-left.raw(),2*epsilon*derivative,1e-12);
            eq(seed,"POU-"+k+":"+axis,"gradient-x-interface",left.dx(),right.dx(),1e-9);
            eq(seed,"POU-"+k+":"+axis,"gradient-z-interface",left.dz(),right.dz(),1e-9);
            double gx = axis == 0 ? k * H : 17, gz = axis == 1 ? k * H : -31;
            checkDistance(seed, driver, gx, gz, "B3-grid-"+k+":"+axis);
            var dl=driver.distance(gx-(axis==0?epsilon:0),gz-(axis==1?epsilon:0));
            var dr=driver.distance(gx+(axis==0?epsilon:0),gz+(axis==1?epsilon:0));
            eq(seed,"B3-grid","gradient-x",dl.dx(),dr.dx(),1e-6);
            eq(seed,"B3-grid","gradient-z",dl.dz(),dr.dz(),1e-6);
        }
        for (int ix=-1;ix<=1;ix++) for(int iz=-1;iz<=1;iz++) {
            Site s=site(seed,ix,iz);
            for(double angle:new double[]{0,.71,2.2,4.5}) {
                double ux=Math.cos(angle),uz=Math.sin(angle);
                for(double r:new double[]{0,.5e-9,2e-9,149.999,150,150.001}) {
                    double x=s.x+r*ux,z=s.z+r*uz; var got=driver.stressAt(x,z); ND expected=mixed(seed,x,z);
                    eq(seed,"seed-interface","independent-U",got.raw(),2*expected.n/expected.d,2e-14);
                }
            }
        }
    }
    private void splineChecks() {
        for(double t:new double[]{-2.1,-2,-1.999,-1,-.5,0,.5,1,1.999,2,2.1}) {
            F ref=b3(t); var a=DiagnosticTectonicDriver.spline(t);
            eq(0,"b3:"+t,"value",a.value(),ref.v,2e-14);
            eq(0,"b3:"+t,"first",a.dx(),ref.x,2e-14);
            eq(0,"b3:"+t,"second",a.dz(),ref.z,2e-14);
            record(a.value()>=0,0,"b3:"+t,"nonnegative","value="+a.value());
        }
        for(double t:new double[]{-2,-1.23,-1,0,.37,1,1.99,2}) {
            double s=0,d=0,dd=0;
            int base=(int)Math.floor(t);
            for(int i=-1;i<=2;i++) { var a=DiagnosticTectonicDriver.spline(t-base-i); s+=a.value();d+=a.dx();dd+=a.dz(); }
            eq(0,"b3-sum:"+t,"partition",s,1,2e-15); eq(0,"b3-sum:"+t,"first",d,0,2e-15);
            eq(0,"b3-sum:"+t,"second",dd,0,2e-15);
        }
        record(2*Math.sqrt(2)*H==R,0,"b3","support-radius","16 fixed coefficients; max radius="+(2*Math.sqrt(2)*H));
        for(double t:new double[]{-2,-1,0,1,2}) {
            var left=DiagnosticTectonicDriver.spline(t-1e-7);
            var right=DiagnosticTectonicDriver.spline(t+1e-7);
            eq(0,"b3-interface:"+t,"C0",left.value(),right.value(),2e-7);
            eq(0,"b3-interface:"+t,"C1",left.dx(),right.dx(),5e-7);
            eq(0,"b3-interface:"+t,"C2",left.dz(),right.dz(),7e-7);
        }
    }
    private void projectionChecks() {
        for(double r:new double[]{0,1e-10,1,149.999,150,150.001,300}) for(double angle:new double[]{0,.71,2.2}) {
            double x=r*Math.cos(angle),z=r*Math.sin(angle); F b=projection(x,z,.6,-.4);
            var a=DiagnosticTectonicDriver.projection(x,z,.6,-.4); String id="projection:"+r+":"+angle;
            eq(0,id,"value",a.value(),b.v,1e-14); eq(0,id,"dx",a.dx(),b.x,1e-14); eq(0,id,"dz",a.dz(),b.z,1e-14);
            record(Math.abs(a.value())<=Math.hypot(.6,.4)+1e-14,0,id,"bound","value="+a.value());
        }
    }
    private void fixtureChecks(long seed) throws Exception {
        var d=new DiagnosticTectonicDriver(seed); var old=new TectonicField(seed);
        Method hm=method("hash",int.class,int.class,long.class), sm=method("plateSeed",int.class,int.class,double[].class);
        Method vm=method("plateVelocity",int.class,int.class,double[].class), nm=method("valueNoise",double.class,double.class,long.class);
        for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) {
            Site s=site(seed,x,z); var a=d.site(x,z); double[] p=new double[2],v=new double[2];
            sm.invoke(old,x,z,p); vm.invoke(old,x,z,v);
            for(long salt:new long[]{SS,VS,WS}) record(d.hash(x,z,salt)==hash(seed,x,z,salt)
                    && (long)hm.invoke(old,x,z,salt)==hash(seed,x,z,salt),seed,x+":"+z,"hash","BigInteger and old");
            eq(seed,x+":"+z,"site-x",a.sx(),s.x,0); eq(seed,x+":"+z,"site-z",a.sz(),s.z,0);
            eq(seed,x+":"+z,"velocity-x",a.vx(),s.vx,0); eq(seed,x+":"+z,"velocity-z",a.vz(),s.vz,0);
            eq(seed,x+":"+z,"old-site-x",p[0],s.x,0); eq(seed,x+":"+z,"old-site-z",p[1],s.z,0);
            eq(seed,x+":"+z,"old-vx",v[0],s.vx,0); eq(seed,x+":"+z,"old-vz",v[1],s.vz,0);
        }
        for(double[] p:new double[][]{{-2000,-1700},{0,0},{499.38,934.48},{3456,-2345}}) {
            var a=d.warp(p[0],p[1]); double[] b=warp(seed,p[0],p[1]);
            double ox=p[0]+130*(double)nm.invoke(old,p[0]*(1.0/400),p[1]*(1.0/400),WS);
            double oz=p[1]+130*(double)nm.invoke(old,p[0]*(1.0/400)+17.3,p[1]*(1.0/400)+31.7,WS+1);
            eq(seed,"warp","x-independent",a.x(),b[0],1e-11); eq(seed,"warp","z-independent",a.z(),b[1],1e-11);
            eq(seed,"warp","x-original",a.x(),ox,0); eq(seed,"warp","z-original",a.z(),oz,0);
            F nx=noiseDerivative(seed,p[0]/400,p[1]/400,WS);
            F nz=noiseDerivative(seed,p[0]/400+17.3,p[1]/400+31.7,WS+1);
            eq(seed,"warp","xx-analytic",a.xx(),1+130*nx.x/400,2e-14);
            eq(seed,"warp","xz-analytic",a.xz(),130*nx.z/400,2e-14);
            eq(seed,"warp","zx-analytic",a.zx(),130*nz.x/400,2e-14);
            eq(seed,"warp","zz-analytic",a.zz(),1+130*nz.z/400,2e-14);
        }
    }
    private static Method method(String name,Class<?>... types) throws Exception {
        Method m=TectonicField.class.getDeclaredMethod(name,types); m.setAccessible(true); return m;
    }
    private void fieldChecks(long seed) {
        var d=new DiagnosticTectonicDriver(seed); Site s=site(seed,0,0);
        for(double[] p:new double[][]{{-2000,-1700},{-2000,0},{0,0},{2000,2000},{s.x,s.z},{-1234,789},{2300,-407}}) {
            String id=p[0]+":"+p[1]; var a=d.stressAt(p[0],p[1]); ND b=mixed(seed,p[0],p[1]);
            eq(seed,id,"N",a.numerator(),b.n,2e-14); eq(seed,id,"D",a.denominator(),b.d,2e-14);
            eq(seed,id,"raw",a.raw(),2*b.n/b.d,2e-14);
            eq(seed,id,"clamp",a.stress(),Math.max(-1,Math.min(1,a.raw())),0);
            record(b.d>=Math.exp(-2.89),seed,id,"denominator lower bound","D="+b.d);
            eq(seed,id,"q dx",a.dx(),diff(t->raw(seed,t,p[1]),p[0],.01),2e-9);
            eq(seed,id,"q dz",a.dz(),diff(t->raw(seed,p[0],t),p[1],.01),2e-9);
            checkDistance(seed,d,p[0],p[1],id);
        }
    }
    private void checkDistance(long seed,DiagnosticTectonicDriver d,double x,double z,String id) {
        double[] q=warp(seed,x,z); Near small=nearest(seed,q[0],q[1],2), big=nearest(seed,q[0],q[1],4);
        record(big.outside>big.second && small.outside>small.second,seed,id,"distance-completeness",
                "5x5 margin="+(small.outside-small.second)+";9x9 margin="+(big.outside-big.second));
        eq(seed,id,"raw-5x5",d.rawDistance(x,z),(big.second-big.first)/2,1e-9);
        F expected=distance(seed,x,z); var got=d.distance(x,z);
        eq(seed,id,"b3-16-value",got.value(),expected.v,1e-9);
        eq(seed,id,"b3-16-dx",got.dx(),expected.x,1e-10); eq(seed,id,"b3-16-dz",got.dz(),expected.z,1e-10);
        record(got.value()>=0,seed,id,"distance-nonnegative","value="+got.value());
        eq(seed,id,"distance-fd-x",got.dx(),diff(t->distance(seed,t,z).v,x,.01),1e-7);
        eq(seed,id,"distance-fd-z",got.dz(),diff(t->distance(seed,x,t).v,z,.01),1e-7);
    }

    private static List<String[]> readCsv(Path file) throws Exception {
        List<String[]> result=new ArrayList<>();
        try(var lines=Files.lines(file,StandardCharsets.UTF_8)) {
            lines.skip(1).forEach(line->result.add(line.replace("\"","").split(",",-1)));
        }
        return result;
    }
    private void replay(Path frozen) throws Exception {
        Map<String,Case> cases=new LinkedHashMap<>();
        for(String[] row:readCsv(frozen.resolve("world.csv"))) if(row[2].equals("-1")) {
            long seed=Long.parseLong(row[0]); String key=row[0]+"/"+row[1];
            Case c=new Case(seed,row[1],Double.parseDouble(row[5]),Double.parseDouble(row[6]),
                    Double.parseDouble(row[7]),Double.parseDouble(row[8]));
            if(cases.put(key,c)!=null) record(false,seed,c.id,"duplicate center",key);
        }
        int limits=0;
        for(String[] row:readCsv(frozen.resolve("limits.csv"))) {
            limits++; Case c=cases.get(row[0]+"/"+row[1]);
            record(c!=null,Long.parseLong(row[0]),row[1],"frozen case join","limits to world center");
            if(c!=null) {
                eq(c.seed,c.id,"target qx",c.qx,Double.parseDouble(row[2]),1e-9);
                eq(c.seed,c.id,"target qz",c.qz,Double.parseDouble(row[3]),1e-9);
            }
        }
        record(limits>0 && limits==cases.size(),0,"replay","complete case coverage","limits="+limits+" centers="+cases.size());
        for(Case c:cases.values()) try { replayCase(c); } catch(Throwable t) { error(c.seed,c.id,t); }
        negativeControls();
        try { oldCusp(frozen); } catch(Throwable t) { error(12345,"old-cusp",t); }
        for(long seed:SEEDS) try { vertex(seed); } catch(Throwable t) { error(seed,"vertex",t); }
    }
    private void vertex(long seed) {
        Site a=site(seed,0,0);
        for(int i=-1;i<=1;i++) for(int j=-1;j<=1;j++) for(int k=-1;k<=1;k++) for(int l=-1;l<=1;l++) {
            if ((i==0 && j==0) || (k==0 && l==0) || (i==k && j==l)) continue;
            Site b=site(seed,i,j),c=site(seed,k,l);
            double bx=b.x-a.x,bz=b.z-a.z,cx=c.x-a.x,cz=c.z-a.z,det=bx*cz-bz*cx;
            if(Math.abs(det)<1e-6) continue;
            double u=(bx*bx+bz*bz)/2,v=(cx*cx+cz*cz)/2;
            double qx=a.x+(u*cz-bz*v)/det,qz=a.z+(bx*v-u*cx)/det;
            if(Math.abs(qx)>6000 || Math.abs(qz)>6000) continue;
            Near n=nearest(seed,qx,qz,4); double da=Math.hypot(qx-a.x,qz-a.z);
            if(Math.abs(da-n.first)>1e-7 || Math.abs(da-n.second)>1e-7 || n.outside<=da) continue;
            double x=qx,z=qz;
            for(int t=0;t<20;t++) {
                double[] w=warp(seed,x,z); double rx=w[0]-qx,rz=w[1]-qz;
                if(Math.hypot(rx,rz)<1e-12) break;
                final double fx=x,fz=z;
                double aa=diff(y->warp(seed,y,fz)[0],x,.01),bb=diff(y->warp(seed,fx,y)[0],z,.01);
                double cc=diff(y->warp(seed,y,fz)[1],x,.01),dd=diff(y->warp(seed,fx,y)[1],z,.01);
                double dv=aa*dd-bb*cc; if(Math.abs(dv)<1e-9) break;
                x-=(dd*rx-bb*rz)/dv; z-=(-cc*rx+aa*rz)/dv;
            }
            double[] w=warp(seed,x,z);
            record(Math.hypot(w[0]-qx,w[1]-qz)<1e-8,seed,"vertex","inverse localization","residual="+Math.hypot(w[0]-qx,w[1]-qz));
            replayCase(new Case(seed,"vertex",qx,qz,x,z)); return;
        }
        record(false,seed,"vertex","three-way coverage","no certified fixed-neighborhood circumcenter");
    }
    private void replayCase(Case c) {
        var d=new DiagnosticTectonicDriver(c.seed); var w=d.warp(c.x,c.z);
        eq(c.seed,c.id,"saved world forward x",w.x(),c.qx,4e-12);
        eq(c.seed,c.id,"saved world forward z",w.z(),c.qz,4e-12);
        var snap=d.sample(c.x,c.z); var vote=d.stressAt(c.qx,c.qz); F expected=distance(c.seed,c.x,c.z);
        eq(c.seed,c.id,"world distance oracle",snap.distance(),expected.v,1e-9);
        eq(c.seed,c.id,"world distance dx",snap.distanceX(),expected.x,1e-10);
        eq(c.seed,c.id,"world distance dz",snap.distanceZ(),expected.z,1e-10);
        eq(c.seed,c.id,"world U chain x",snap.stressX(),vote.dx()*w.xx()+vote.dz()*w.zx(),1e-12);
        eq(c.seed,c.id,"world U chain z",snap.stressZ(),vote.dx()*w.xz()+vote.dz()*w.zz(),1e-12);
        for(int axis=0;axis<2;axis++) {
            final int a=axis;
            java.util.function.DoubleUnaryOperator qfun=t->raw(c.seed,c.qx+(a==0?t:0),c.qz+(a==1?t:0));
            java.util.function.DoubleUnaryOperator wfun=t->{ double[] q=warp(c.seed,c.x+(a==0?t:0),c.z+(a==1?t:0)); return raw(c.seed,q[0],q[1]); };
            eq(c.seed,c.id,"q-high-order-"+axis,a==0?vote.dx():vote.dz(),diff(qfun,0,.01),2e-9);
            eq(c.seed,c.id,"world-high-order-"+axis,a==0?snap.stressX():snap.stressZ(),diff(wfun,0,.01),2e-8);
            series(c,d,axis,vote,snap);
        }
    }
    private void series(Case c,DiagnosticTectonicDriver d,int axis,DiagnosticTectonicDriver.Vote v,DiagnosticTectonicDriver.Snapshot s) {
        List<Double> steps=new ArrayList<>(List.of(1.0,.1,.01,.001));
        for(int k=0;k<=20;k++) steps.add(Math.scalb(1.0,-k));
        double qder=axis==0?v.dx():v.dz(), wder=axis==0?s.stressX():s.stressZ();
        for(double h:steps) {
            double qxm=c.qx-(axis==0?h:0),qzm=c.qz-(axis==1?h:0);
            double qxp=c.qx+(axis==0?h:0),qzp=c.qz+(axis==1?h:0);
            double qm=d.stressAt(qxm,qzm).raw(),qp=d.stressAt(qxp,qzp).raw();
            data.add(csv(c.seed,c.id,"q-U",axis,h,qm,v.raw(),qp,qder,Math.max(Math.abs(qm-v.raw()+h*qder),Math.abs(qp-v.raw()-h*qder))));
            double xm=c.x-(axis==0?h:0),zm=c.z-(axis==1?h:0),xp=c.x+(axis==0?h:0),zp=c.z+(axis==1?h:0);
            var am=d.sample(xm,zm); var ap=d.sample(xp,zp);
            data.add(csv(c.seed,c.id,"world-U",axis,h,am.stressRaw(),s.stressRaw(),ap.stressRaw(),wder,
                    Math.max(Math.abs(am.stressRaw()-s.stressRaw()+h*wder),Math.abs(ap.stressRaw()-s.stressRaw()-h*wder))));
            double dd=axis==0?s.distanceX():s.distanceZ();
            double de=Math.max(Math.abs(am.distance()-s.distance()+h*dd),Math.abs(ap.distance()-s.distance()-h*dd));
            data.add(csv(c.seed,c.id,"world-D",axis,h,am.distance(),s.distance(),ap.distance(),dd,de));
            eq(c.seed,c.id,"q-minus-oracle-"+axis+":"+h,qm,raw(c.seed,qxm,qzm),2e-14);
            eq(c.seed,c.id,"q-plus-oracle-"+axis+":"+h,qp,raw(c.seed,qxp,qzp),2e-14);
            F dm=distance(c.seed,xm,zm),dp=distance(c.seed,xp,zp);
            eq(c.seed,c.id,"D-minus-oracle-"+axis+":"+h,am.distance(),dm.v,1e-9);
            eq(c.seed,c.id,"D-plus-oracle-"+axis+":"+h,ap.distance(),dp.v,1e-9);
            eq(c.seed,c.id,"D-minus-gradient-"+axis+":"+h,axis==0?am.distanceX():am.distanceZ(),axis==0?dm.x:dm.z,1e-10);
            eq(c.seed,c.id,"D-plus-gradient-"+axis+":"+h,axis==0?ap.distanceX():ap.distanceZ(),axis==0?dp.x:dp.z,1e-10);
            if (h==.001) {
                double qe=Math.max(Math.abs(qm-v.raw()+h*qder),Math.abs(qp-v.raw()-h*qder));
                double we=Math.max(Math.abs(am.stressRaw()-s.stressRaw()+h*wder),Math.abs(ap.stressRaw()-s.stressRaw()-h*wder));
                record(qe<1e-8 && we<1e-8 && de<1e-5,c.seed,c.id,"Taylor-axis-"+axis,
                        "q="+qe+";world="+we+";distance="+de+";h="+h);
            }
        }
    }
    private void oldCusp(Path frozen) throws Exception {
        double center=Double.NaN,minus=Double.NaN,plus=Double.NaN,mx=0,mz=0,px=0,pz=0;
        for(String[] r:readCsv(frozen.resolve("world.csv"))) {
            if(!r[0].equals("12345") || !r[1].equals("bisector-0-1")) continue;
            if(r[2].equals("-1")) center=Double.parseDouble(r[16]);
            if(r[2].equals("10") && r[3].equals("-1")) { minus=Double.parseDouble(r[16]); mx=Double.parseDouble(r[7]); mz=Double.parseDouble(r[8]); }
            if(r[2].equals("10") && r[3].equals("1")) { plus=Double.parseDouble(r[16]); px=Double.parseDouble(r[7]); pz=Double.parseDouble(r[8]); }
        }
        double h=Math.scalb(1,-10),gap=Math.abs((plus-center)/h-(center-minus)/h);
        record(Double.isFinite(gap) && gap>.3,12345,"old-cusp","frozen slope jump","gap="+gap);
        TectonicField old=new TectonicField(12345);
        eq(12345,"old-cusp","real old minus",old.sample(mx,mz).dist(),minus,1e-9);
        eq(12345,"old-cusp","real old plus",old.sample(px,pz).dist(),plus,1e-9);
    }
    private void negativeControls() throws Exception {
        Method old=method("stressField",double.class,double.class);
        TectonicField tf=new TectonicField(-419); double h=Math.scalb(1,-20);
        double a=(double)old.invoke(tf,-2000-h,-1700.0),b=(double)old.invoke(tf,-2000+h,-1700.0);
        record(Math.abs(a-b)>6e-5,-419,"window-negative","old floor window jump","gap="+Math.abs(a-b));
        long seed=SEEDS[0]; Site s=site(seed,0,0); tf=new TectonicField(seed);
        double speed=Math.hypot(s.vx,s.vz),dx=s.vx/speed,dz=s.vz/speed;
        a=(double)old.invoke(tf,s.x-h*dx,s.z-h*dz); b=(double)old.invoke(tf,s.x+h*dx,s.z+h*dz);
        record(Math.abs(a-b)>.5,seed,"site-negative","old exterior directional branches","gap="+Math.abs(a-b));
        record((double)old.invoke(tf,s.x,s.z)==0,seed,"site-negative","old zero disk","center only; disk boundary separate");
        for (int side : new int[]{-1, 1}) {
            double inside = (double) old.invoke(tf, s.x + side * .5e-9 * dx, s.z + side * .5e-9 * dz);
            double outside = (double) old.invoke(tf, s.x + side * 2e-9 * dx, s.z + side * 2e-9 * dz);
            record(inside == 0 && Math.abs(outside) > .1, seed, "zero-disk-boundary-" + side,
                    "old discontinuity detected", "inside=" + inside + ";outside=" + outside);
            var candidate = new DiagnosticTectonicDriver(seed);
            var ci = candidate.stressAt(s.x + side * .5e-9 * dx, s.z + side * .5e-9 * dz);
            var co = candidate.stressAt(s.x + side * 2e-9 * dx, s.z + side * 2e-9 * dz);
            record(Math.abs(ci.raw() - co.raw()) < 1e-10, seed, "zero-disk-boundary-" + side,
                    "candidate no artificial cutoff", "inside=" + ci.raw() + ";outside=" + co.raw());
        }
    }
}
