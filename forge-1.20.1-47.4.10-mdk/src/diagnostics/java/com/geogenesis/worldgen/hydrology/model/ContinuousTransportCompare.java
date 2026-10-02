package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * D8 / MFD / Continuous-velocity streamline-transport 三路由图对照。
 *
 * <p>连续臂仿照 geotransport 的【计算结构】而非移植 CUDA：输入是同一张真实地形，
 * 速度场由连续双线性高度梯度给出，源点用确定性低差异序列取样，粒子沿连续流线走，
 * 每跨过一个像素格累积源项；采样量递增以检查积分估计是否收敛。</p>
 *
 * <p><b>物理边界必须讲清</b>：此实验只是"给定地形产生的连续速度场上的输运积分"。
 * 它没有水深对速度的反作用、没有蓄水/自由水面反馈、没有局部动量状态，
 * 也没有湖内流动/水量平衡。它只回答：流线积分是否减少了图路由的格点直纹，
 * 不是完整水文解，也不能作为生产接入。</p>
 *
 * <p>可按命令参数：{@code seed cx cz radius step}，默认中心选择半径 320 的内陆窗口。</p>
 */
public final class ContinuousTransportCompare {
    private static final int[][] DIRS8 = {
            {1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}
    };

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -500;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -1000;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 320;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY()); gen.seed(seed);
        int n = 2 * radius / step + 1;
        double[] bed = new double[n*n];
        for (int i=0;i<n;i++) for (int j=0;j<n;j++) {
            bed[i*n+j] = gen.erodedHeightForRouting((cx-radius+i*step)/tp.horizontalScale(),
                    (cz-radius+j*step)/tp.horizontalScale());
        }
        double[] d8 = accumulateD8(bed,n), mfd = accumulateMfd(bed,n,1.1);
        int[] samples = {10000,50000,200000};
        double[][] continuous = new double[samples.length][];
        for (int s=0;s<samples.length;s++) {
            long start=System.nanoTime();
            continuous[s]=integrate(bed,n,samples[s],seed);
            System.out.printf("continuous samples=%d done in %.1fs%n",samples[s],(System.nanoTime()-start)/1e9);
        }
        Path dir=Path.of("build/hydro-reassessment/continuous-"+System.currentTimeMillis()); Files.createDirectories(dir);
        BufferedImage img=new BufferedImage(n*5+32,n,BufferedImage.TYPE_INT_RGB);
        BufferedImage[] fields={shade(bed,n),logField(d8,n),logField(mfd,n),logField(continuous[1],n),logField(continuous[2],n)};
        for(int p=0;p<fields.length;p++) for(int y=0;y<n;y++) for(int x=0;x<n;x++)
            img.setRGB(p*(n+8)+x,y,fields[p].getRGB(x,y));
        ImageIO.write(img,"png",dir.resolve("compare.png").toFile());
        StringBuilder report=new StringBuilder();
        report.append("left-to-right: hillshade, D8, MFD(p=1.1), stream integral N=50000, N=200000\n");
        report.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d grid=%d%n",seed,cx,cz,radius,step,n));
        report.append("Continuous streamline estimator uses prescribed -grad(bed), uniform source, zero decay.\n");
        report.append("NO water-depth feedback, NO lake storage/backwater, NO transient momentum: not a fluid solution.\n");
        report.append("N10k/50k/200k normalized Pearson correlations over land:\n");
        report.append(String.format("  10k vs 50k: %.5f%n",correlation(continuous[0],continuous[1],bed,n)));
        report.append(String.format("  50k vs 200k: %.5f%n",correlation(continuous[1],continuous[2],bed,n)));
        report.append(String.format("parallel neighbor-direction fraction: D8=%.4f MFD=%.4f continuous=%.4f%n",
                directionParallelD8(bed,n),directionParallelMfd(bed,n),directionParallelContinuous(continuous[2],n)));
        Files.writeString(dir.resolve("metrics.txt"),report);
        System.out.print(report); System.out.println("IMAGE="+dir.resolve("compare.png").toAbsolutePath());
    }

    private static double[] integrate(double[] h,int n,int count,long seed) {
        double[] flux=new double[n*n];
        int accepted=0, maxSteps=12*n;
        int shiftX=(int)(seed&0xffff), shiftY=(int)((seed>>>16)&0xffff);
        for(int s=1;s<=count;s++) {
            double x=mod(halton(s,2)+shiftX/65536.0,1)*(n-2)+1;
            double z=mod(halton(s,3)+shiftY/65536.0,1)*(n-2)+1;
            for(int step=0;step<maxSteps;step++) {
                int i=(int)x,j=(int)z,k=i*n+j;
                double[] grad=gradient(h,n,x,z);
                double mag=Math.hypot(grad[0],grad[1]);
                if(mag<1e-10) break;
                double vx=-grad[0]/mag, vz=-grad[1]/mag;
                // Midpoint streamline integration, half-cell step; sample along continuous bilinear gradient.
                double mx=x+0.25*vx,mz=z+0.25*vz;
                double[] gm=gradient(h,n,mx,mz); double ml=Math.hypot(gm[0],gm[1]);
                if(ml<1e-10) break;
                vx=-gm[0]/ml; vz=-gm[1]/ml;
                x+=0.5*vx; z+=0.5*vz;
                if(x<1||z<1||x>=n-2||z>=n-2) break;
                int ni=(int)x,nj=(int)z,nk=ni*n+nj;
                if(nk!=k) { flux[nk]+=1; accepted++; }
            }
        }
        // Each launched particle represents equal source mass; values are visitation counts, log display follows.
        return flux;
    }
    private static double[] gradient(double[] h,int n,double x,double z) {
        int i=(int)x,j=(int)z; double fx=x-i,fz=z-j;
        i=Math.max(0,Math.min(n-2,i)); j=Math.max(0,Math.min(n-2,j));
        double h00=h[i*n+j],h10=h[(i+1)*n+j],h01=h[i*n+j+1],h11=h[(i+1)*n+j+1];
        return new double[]{(1-fz)*(h10-h00)+fz*(h11-h01),(1-fx)*(h01-h00)+fx*(h11-h10)};
    }
    private static double[] accumulateD8(double[] h,int n) {
        double[] a=new double[n*n];java.util.Arrays.fill(a,1);Integer[] o=order(h);
        for(int k:o){int i=k/n,j=k%n,best=-1;double bd=0;for(int[]d:DIRS8){int x=i+d[0],z=j+d[1];if(x<0||z<0||x>=n||z>=n)continue;int q=x*n+z;double drop=(h[k]-h[q])/(d[0]!=0&&d[1]!=0?Math.sqrt(2):1);if(drop>bd){bd=drop;best=q;}}if(best>=0)a[best]+=a[k];}return a;
    }
    private static double[] accumulateMfd(double[] h,int n,double p) {
        double[] a=new double[n*n];java.util.Arrays.fill(a,1);Integer[] o=order(h);
        for(int k:o){int i=k/n,j=k%n;double sum=0;double[]w=new double[8];int[]q=new int[8];for(int t=0;t<8;t++){int[]d=DIRS8[t];int x=i+d[0],z=j+d[1];if(x<0||z<0||x>=n||z>=n){q[t]=-1;continue;}q[t]=x*n+z;double drop=(h[k]-h[q[t]])/(d[0]!=0&&d[1]!=0?Math.sqrt(2):1);if(drop<=0){q[t]=-1;continue;}w[t]=Math.pow(drop,p);sum+=w[t];}if(sum>0)for(int t=0;t<8;t++)if(q[t]>=0)a[q[t]]+=a[k]*w[t]/sum;}return a;
    }
    private static Integer[] order(double[] h){Integer[]o=new Integer[h.length];for(int i=0;i<o.length;i++)o[i]=i;java.util.Arrays.sort(o,(a,b)->Double.compare(h[b],h[a]));return o;}
    private static BufferedImage logField(double[]a,int n){BufferedImage im=new BufferedImage(n,n,BufferedImage.TYPE_INT_RGB);double max=0;for(double v:a)max=Math.max(max,v);double den=Math.log1p(max);for(int k=0;k<a.length;k++){int c=(int)(255*Math.log1p(a[k])/Math.max(1e-12,den));im.setRGB(k/n,k%n,(c<<16)|(c<<8)|c);}return im;}
    private static BufferedImage shade(double[]h,int n){BufferedImage im=new BufferedImage(n,n,BufferedImage.TYPE_INT_RGB);for(int i=0;i<n;i++)for(int j=0;j<n;j++){int i0=Math.max(0,i-1),i1=Math.min(n-1,i+1),j0=Math.max(0,j-1),j1=Math.min(n-1,j+1);double dx=(h[i1*n+j]-h[i0*n+j])/(i1-i0),dz=(h[i*n+j1]-h[i*n+j0])/(j1-j0);int c=(int)(60+175*Math.max(0,(.5*dx+.7-.51*dz)/Math.sqrt(dx*dx+1+dz*dz+1e-9)));im.setRGB(i,j,(c<<16)|(c<<8)|c);}return im;}
    private static double correlation(double[]a,double[]b,double[]h,int n){double ma=0,mb=0;int c=0;for(int i=0;i<a.length;i++)if(h[i]>0){ma+=Math.log1p(a[i]);mb+=Math.log1p(b[i]);c++;}ma/=c;mb/=c;double num=0,va=0,vb=0;for(int i=0;i<a.length;i++)if(h[i]>0){double x=Math.log1p(a[i])-ma,y=Math.log1p(b[i])-mb;num+=x*y;va+=x*x;vb+=y*y;}return num/Math.sqrt(va*vb);}
    private static double directionParallelD8(double[]h,int n){int[]d=new int[h.length];java.util.Arrays.fill(d,-1);for(int k=0;k<h.length;k++){int i=k/n,j=k%n,best=-1,bi=-1;double bd=0;for(int t=0;t<8;t++){int[]v=DIRS8[t];int x=i+v[0],z=j+v[1];if(x<0||z<0||x>=n||z>=n)continue;double drop=h[k]-h[x*n+z];if(drop>bd){bd=drop;best=x*n+z;bi=t;}}d[k]=bi;}return parallel(d,n);}
    private static double directionParallelMfd(double[]h,int n){int[]d=new int[h.length];for(int k=0;k<h.length;k++){int i=k/n,j=k%n;double x=0,z=0;for(int[]v:DIRS8){int a=i+v[0],b=j+v[1];if(a<0||b<0||a>=n||b>=n)continue;double drop=h[k]-h[a*n+b];if(drop>0){x+=drop*v[0];z+=drop*v[1];}}d[k]=(int)(Math.round(Math.atan2(z,x)/(Math.PI/4))+8)%8;}return parallel(d,n);}
    private static double directionParallelContinuous(double[]a,int n){int[]d=new int[a.length];java.util.Arrays.fill(d,-1);for(int i=1;i<n-1;i++)for(int j=1;j<n-1;j++){int k=i*n+j;double gx=(a[(i+1)*n+j]-a[(i-1)*n+j])*.5,gz=(a[i*n+j+1]-a[i*n+j-1])*.5;if(Math.hypot(gx,gz)<1e-12)continue;d[k]=(int)(Math.round(Math.atan2(gz,gx)/(Math.PI/4))+8)%8;}return parallel(d,n);}
    private static double parallel(int[]d,int n){int same=0,total=0;for(int i=0;i<n;i++)for(int j=0;j<n-1;j++){int a=i*n+j,b=a+1;if(d[a]<0||d[b]<0)continue;total++;if(d[a]==d[b])same++;}return same/(double)Math.max(1,total);}
    private static double halton(int index,int base){double f=1,r=0;while(index>0){f/=base;r+=f*(index%base);index/=base;}return r;}
    private static double mod(double x,double m){return x-Math.floor(x/m)*m;}
    private ContinuousTransportCompare(){}
}
