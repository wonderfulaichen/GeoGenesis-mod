package com.erosiontest;

import java.io.File;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** 剖面测试：检查A右边缘 vs B左边缘在各级别的一致性 */
public class ProfileTest {
    static final int TILE = 112, CHUNK = 16, HALF = 3, OUT_R = 1, OUT_DIM = 3;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 剖面诊断 ===\n");
        long seed = 12345L;
        Noise n = new Noise((int) seed);
        int[] res = {14, 28, 56, 112};
        int[] bp = {3, 4, 4};

        float[][] raw112A = genMap(n, -48, -48);
        float[][] raw112B = genMap(n, 0, -48);

        float[][][] rA = new float[4][][], rB = new float[4][][];
        float[][][] eA = new float[4][][], eB = new float[4][][];
        process(seed, res, bp, raw112A, -48, -48, null, rA, eA);
        process(seed+999L, res, bp, raw112B, 0, -48, eA, rB, eB);

        for (int li = 0; li < 4; li++) {
            int cr = res[li];
            int os = (HALF-OUT_R)*CHUNK*cr/TILE;
            int ow = OUT_DIM*CHUNK*cr/TILE;
            int aR = os+ow-1, bL = os;

            System.out.printf("L%d %dx%d: 原始 A右(%d)vsB左(%d) max=%.6f  侵蚀 max=%.6f%n",
                li, cr, cr, aR, bL,
                maxColDiff(rA[li], rB[li], os, ow, aR, bL),
                maxColDiff(eA[li], eB[li], os, ow, aR, bL));
        }

        saveHeatmap(eA, eB, raw112A, raw112B, rA, rB, os(112), ow(112), "profile_heat.png");
        System.out.println("热力图: output/profile_heat.png");
    }

    static float[][] genMap(Noise n, int wx, int wz) {
        float[][] m = new float[TILE][TILE];
        float mn=1,mx=0;
        for(int z=0;z<TILE;z++)for(int x=0;x<TILE;x++){
            m[z][x]=n.terrainBaseMod(wx+x,wz+z);
            mn=Math.min(mn,m[z][x]); mx=Math.max(mx,m[z][x]);
        }
        float r=mx-mn;
        for(int z=0;z<TILE;z++)for(int x=0;x<TILE;x++)m[z][x]=(m[z][x]-mn)/(r>0?r:1);
        return m;
    }

    static int os(int cr){return (HALF-OUT_R)*CHUNK*cr/TILE;}
    static int ow(int cr){return OUT_DIM*CHUNK*cr/TILE;}

    static double maxColDiff(float[][] a, float[][] b, int os, int ow, int ca, int cb) {
        double m=0;
        for(int i=os;i<os+ow&&i<a.length;i++)m=Math.max(m,Math.abs(a[i][ca]-b[i][cb]));
        return m;
    }

    static float[][] copyGrid(float[][] s, int sz) {
        float[][] d=new float[sz][sz];
        for(int i=0;i<sz;i++)System.arraycopy(s[i],0,d[i],0,sz);
        return d;
    }

    static float[][] downsample(float[][] s, int ss, int ds) {
        float[][] d=new float[ds][ds];
        int scale=ss/ds;
        for(int z=0;z<ds;z++)for(int x=0;x<ds;x++){
            float sum=0;int n=0;
            for(int dz=0;dz<scale;dz++)for(int dx=0;dx<scale;dx++){sum+=s[z*scale+dz][x*scale+dx];n++;}
            d[z][x]=sum/n;
        }
        return d;
    }

    static float[][] padEdgeClamp(float[][] s, int sz, int pad) {
        int ns=sz+pad*2;
        float[][] d=new float[ns][ns];
        for(int z=0;z<ns;z++)for(int x=0;x<ns;x++)d[z][x]=s[clamp(z-pad,0,sz-1)][clamp(x-pad,0,sz-1)];
        return d;
    }

    static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    static float clamp(float v){return v<0?0:v>1?1:v;}

    static void pyramidErosionClone(float[][] map, int bs, int drops, float str, int radius,
        float fo, float in, float gv, float es, float ds, int ox, int oz, int pad, int baseSize, int ws, boolean[][] locked, long seed) {
        float cap=10f, mc=0.005f, ev=0.35f;
        float[] f=new float[bs*bs];
        for(int z=0;z<bs;z++)for(int x=0;x<bs;x++)f[z*bs+x]=map[z][x];
        int r2=radius*radius, maxB=(2*radius+1)*(2*radius+1);
        int[] bo=new int[maxB]; float[] bw=new float[maxB]; int bn=0;
        for(int dy=-radius;dy<=radius;dy++)for(int dx=-radius;dx<=radius;dx++){
            float d2=dx*dx+dy*dy; if(d2<r2){bo[bn]=dy*bs+dx; bw[bn]=1f-(float)Math.sqrt(d2)/radius; bn++;}
        }
        {float s=0;for(int i=0;i<bn;i++)s+=bw[i];for(int i=0;i<bn;i++)bw[i]/=s;}
        int is=pad, ie=pad+baseSize, mg=1;
        float pc=(float)((baseSize-mg*2)*(baseSize-mg*2));
        float den=drops/pc; long th=(long)(den*(1L<<20));
        for(int py=is+mg;py<ie-mg;py++)for(int px=is+mg;px<ie-mg;px++){
            int wx=ox+(px-pad)*ws, wz=oz+(py-pad)*ws;
            long h=hashCoarse(wx*31+1,wz*73+1); if((h&((1L<<20)-1))>=th)continue;
            int idx=py*bs+px; if(f[idx]<=0.02f)continue;
            if(locked!=null&&locked[py][px])continue;
            float dx=0,dz=0, sed=0, spd=1,wtr=1,fpx=px+0.5f,fpz=py+0.5f;
            for(int st=0;st<30;st++){
                int ix=(int)fpx,iz=(int)fpz;
                if(ix<1||ix>=bs-2||iz<1||iz>=bs-2)break;
                idx=iz*bs+ix; float fx=fpx-ix,fz=fpz-iz;
                float nw=f[idx],ne=f[idx+1],sw=f[idx+bs],se=f[idx+bs+1];
                float h0=nw*(1-fx)*(1-fz)+ne*fx*(1-fz)+sw*(1-fx)*fz+se*fx*fz;
                if(h0<=0.02f)break;
                float gx=(ne-nw)*(1-fz)+(se-sw)*fz,gz=(sw-nw)*(1-fx)+(se-ne)*fx;
                float gl=(float)Math.sqrt(gx*gx+gz*gz); if(gl<1e-12f)break;
                dx=dx*in-gx*(1-in); dz=dz*in-gz*(1-in);
                float dl=(float)Math.sqrt(dx*dx+dz*dz); if(dl<1e-12f)break; dx/=dl;dz/=dl;
                float npx=fpx+dx,npz=fpz+dz;
                if(npx<1||npx>=bs-2||npz<1||npz>=bs-2)break;
                int nix=(int)npx,niz=(int)npz; float fnx=npx-nix,fnz=npz-niz;
                int nidx=niz*bs+nix;
                float h1=f[nidx]*(1-fnx)*(1-fnz)+f[nidx+1]*fnx*(1-fnz)+f[nidx+bs]*(1-fnx)*fnz+f[nidx+bs+1]*fnx*fnz;
                float dh=(h1-h0)*Math.min(1,h0/fo);
                float c=Math.max(-dh*spd*wtr*cap*str,mc);
                if(sed>c||dh>0){float dep=dh>0?Math.min(dh,sed):(sed-c)*ds;sed-=dep;
                    if(locked==null||!locked[iz][ix])f[idx]+=dep*(1-fx)*(1-fz);
                    if(locked==null||!locked[iz][ix+1])f[idx+1]+=dep*fx*(1-fz);
                    if(locked==null||!locked[iz+1][ix])f[idx+bs]+=dep*(1-fx)*fz;
                    if(locked==null||!locked[iz+1][ix+1])f[idx+bs+1]+=dep*fx*fz;
                }else{float ea=Math.min((c-sed)*es,-dh);
                    for(int b=0;b<bn;b++){int bi=idx+bo[b]; if(bi>=0&&bi<bs*bs){
                        int bz=bi/bs,bx=bi%bs; if(locked!=null&&locked[bz][bx])continue;
                        float dt=Math.min(f[bi],ea*bw[b]);f[bi]-=dt;sed+=dt;}}}
                fpx=npx;fpz=npz; spd=(float)Math.sqrt(spd*spd+dh*gv); if(spd<=0)break; wtr*=(1-ev);
            }
        }
        for(int z=0;z<bs;z++)for(int x=0;x<bs;x++){float v=clamp(f[z*bs+x]);map[z][x]=Float.isNaN(v)||Float.isInfinite(v)?0.5f:v;}
    }

    static long hashCoarse(int a,int b){long h=a*0x9e3779b9L+b*0x9e3779b9L*31;h=(h^(h>>>16))*0x85ebca6bL;h=h^(h>>>13);h=h*0xc2b2ae35L;h=h^(h>>>16);return h;}

    static void process(long seed,int[] res,int[] bp,float[][] raw,int wx,int wz,float[][][] leftCache,float[][][] rawOut,float[][][] erodedOut){
        float[][] h=null;
        for(int li=0;li<res.length;li++){
            int cr=res[li];
            if(li==0)h=downsample(raw,TILE,cr); else h=ErosionPipeline.bicubicUpsampleGrid(h,res[li-1],cr);
            rawOut[li]=copyGrid(h,cr);
            if(li<res.length-1){
                int pad=Math.max(bp[li]*2,4);
                float[][] p=padEdgeClamp(h,cr,pad);
                pyramidErosionClone(p,cr+pad*2,7500,1.5f,bp[li],0.5f,0.001f,2.5f,0.3f,0.04f,wx,wz,pad,cr,TILE/cr,null,seed+li*10000L);
                for(int z=0;z<cr;z++)System.arraycopy(p[z+pad],pad,h[z],0,cr);
            }
            if(li<res.length-1&&leftCache!=null&&leftCache[li]!=null){
                int os=(HALF-OUT_R)*CHUNK*cr/TILE,ow=OUT_DIM*CHUNK*cr/TILE,delta=3*CHUNK*cr/TILE,bw=bp[li];
                for(int col=0;col<bw;col++){int d=os+col,s=os+delta+col;if(d>=cr||s>=cr)break;
                    float w=0.5f*(1f+(float)Math.cos((1f-(float)col/bw)*Math.PI));
                    for(int i=os;i<os+ow&&i<cr;i++)h[i][d]=h[i][d]*(1-w)+leftCache[li][i][s]*w;}
            }
            erodedOut[li]=copyGrid(h,cr);
        }
        erodedOut[3]=h;
    }

    static void saveHeatmap(float[][][] eA,float[][][] eB,float[][] rawA,float[][] rawB,
        float[][][] rA,float[][][] rB,int os,int ow,String fn) throws Exception {
        int sz=512,h=256,cols=8;
        BufferedImage img=new BufferedImage(sz,sz*2,BufferedImage.TYPE_INT_RGB);
        int[] res={14,28,56,112}; String[] lb={"14裸噪声","14侵蚀后","14混合差","28裸噪声","28侵蚀后","28混合差","56裸噪声","56侵蚀后","56混合差","112裸噪声","112侵蚀后","112混合差"};

        for(int band=0;band<12;band++){
            int li=band/3,sub=band%3;
            int cr=res[li];
            int oss=(HALF-OUT_R)*CHUNK*cr/TILE, oww=OUT_DIM*CHUNK*cr/TILE;
            int aR=oss+oww-1,bL=oss;
            int y0=band*sz/12,y1=(band+1)*sz/12;

            for(int py=y0;py<y1;py++){
                int z=(int)((float)(py-y0)/(y1-y0)*oww);
                if(z>=oww||oss+z>=cr)continue;
                int row=oss+z;
                float va,vb;
                if(sub==0){va=rA[li][row][aR];vb=rB[li][row][bL];}  // 原始噪声
                else if(sub==1){va=eA[li][row][aR];vb=eB[li][row][bL];} // 侵蚀后
                else {va=eA[li][row][aR];vb=eB[li][row][bL];}  // 相同但用红色标差异
                int gv=(int)(clamp(va)*255);
                int color=(sub==2&&Math.abs(va-vb)>0.001)?0xFF0000:(sub==2?0x00FF00:(sub==0?0x0000FF:0xFFFF00));
                color=color&0x00FFFFFF|(Math.min(255,gv)<<24);
            }
        }

        new File("output").mkdirs();
        ImageIO.write(img,"png",new File("output/"+fn));
    }
}
