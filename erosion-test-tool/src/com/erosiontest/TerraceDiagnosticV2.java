package com.erosiontest;

import java.io.File;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 精确复刻 Mod 的 generateErosionTile + pyramidErosion（世界坐标密度法）
 * 检测梯田：相邻像素高度差=0占比 + 归一化后映射到Minecraft块高的效果
 */
public class TerraceDiagnosticV2 {
    static final int TILE = 112, CHUNK = 16, HALF = 3, OUT_R = 1, OUT_DIM = 3;
    
    public static void main(String[] args) throws Exception {
        long seed = 12345L;
        Noise noise = new Noise((int)seed);
        
        // 扫描找高陆地坐标
        System.out.println("=== 扫描高陆地区域 ===");
        for (int sz=0; sz<20000; sz+=3000) {
            for(int sx=0; sx<20000; sx+=3000){
                float c=noise.continentRaw(sx,sz);
                if(c>0.3f) System.out.printf("  高陆(%d,%d) continent=%.3f%n",sx,sz,c);
            }
        }
        
        // 用扫描到的高陆地区做梯田测试
        int[][] centers = {{-48,-48}, {400,-48}, {9000,15000}};
        String[] labels = {"海洋(-48,-48)", "海岸(400,-48)", "高陆(9000,15000)"};
        
        for(int ci=0; ci<centers.length; ci++){
            int cx=centers[ci][0], cz=centers[ci][1];
            runTest(noise, cx, cz, labels[ci], seed);
        }
    }
    
    static void runTest(Noise noise, int worldStartX, int worldStartZ, String label, long seed) throws Exception {
        System.out.printf("\n=== %s ===%n", label);
        
        // 1. 生成 112×112 噪声（用computeHeight做海陆映射，与模组TerrainCache.computeHeight一致）
        float[][] raw = new float[TILE][TILE];
        float mn=1,mx=0;
        for (int z=0;z<TILE;z++)for(int x=0;x<TILE;x++){
            raw[z][x]=ErosionPipeline.computeHeight(noise, worldStartX+x, worldStartZ+z);
            mn=Math.min(mn,raw[z][x]); mx=Math.max(mx,raw[z][x]);
        }
        float r=mx-mn;
        System.out.printf("computeHeight范围: %.4f~%.4f (range=%.4f)%n",mn,mx,r);
        
        // 2. 精确复刻 generateErosionTile 的侵蚀管线
        int[] res={14,28,56,112};
        int[] bp={3,4,4};
        float[][] h=null;
        
        for(int li=0;li<res.length;li++){
            int cr=res[li];
            if(li==0)h=downsample(raw,TILE,cr);
            else h=ErosionPipeline.bicubicUpsampleGrid(h,res[li-1],cr);
            
            if(li<res.length-1){
                int pad=Math.max(bp[li]*2,4);
                float[][] p=new float[cr+pad*2][cr+pad*2];
                // Edge clamp (exactly like mod's padMirror)
                for(int z=0;z<cr+pad*2;z++)for(int x=0;x<cr+pad*2;x++)
                    p[z][x]=h[clamp(z-pad,0,cr-1)][clamp(x-pad,0,cr-1)];
                
                // pyramidErosion with world-coordinate density seeding
                int ws=TILE/cr;
                float str=li==0?1.5f:li==1?1.2f:1.0f;
                float es=li==0?0.3f:li==1?0.2f:0.15f;
                float ds=li==0?0.04f:li==1?0.03f:0.02f;
                int drops=li==0?7500:li==1?9000:6000;
                
                pyramidErosionExact(p,cr+pad*2,drops,str,bp[li],
                    0.5f,0.001f,2.5f,es,ds,
                    worldStartX,worldStartZ,pad,cr,ws,seed+li*10000L);
                
                for(int z=0;z<cr;z++)System.arraycopy(p[z+pad],pad,h[z],0,cr);
            }
        }
        
        // 3. 梯田量化（归一化高度内的相邻差）
        float hsMin=1,hsMax=0;
        for(int z=0;z<TILE;z++)for(int x=0;x<TILE;x++){
            if(h[z][x]<hsMin)hsMin=h[z][x]; if(h[z][x]>hsMax)hsMax=h[z][x];
        }
        float hsR=Math.max(hsMax-hsMin,0.001f);
        System.out.printf("侵蚀后范围: %.4f~%.4f (range=%.4f)%n",hsMin,hsMax,hsR);
        
        // 映射到Minecraft 384格世界高度后的阶梯检测
        double blockRange=384.0;
        double blockMin=hsMin*blockRange, blockMax=hsMax*blockRange;
        System.out.printf("MC块高范围: ~%.0f~%.0f (%.0f格)%n",blockMin,blockMax,hsR*blockRange);
        
        int[] flatBins=new int[5];  // diff=0, 0.5block, 1block, 2block, >2block
        double[] adjSlopes=new double[Math.max(20000,TILE*TILE*2)];
        int totalAdj=0, adjIdx=0;
        double maxSlope=0;
        
        for(int z=0;z<TILE;z++)for(int x=0;x<TILE;x++){
            if(x<TILE-1){double d=Math.abs(h[z][x+1]-h[z][x])*blockRange; maxSlope=Math.max(maxSlope,d);
                adjSlopes[adjIdx++]=d; totalAdj++;
                if(d<0.5)flatBins[0]++; else if(d<1.0)flatBins[1]++; else if(d<2.0)flatBins[2]++; else if(d<4.0)flatBins[3]++; else flatBins[4]++;}
            if(z<TILE-1){double d=Math.abs(h[z+1][x]-h[z][x])*blockRange; maxSlope=Math.max(maxSlope,d);
                adjSlopes[adjIdx++]=d; totalAdj++;
                if(d<0.5)flatBins[0]++; else if(d<1.0)flatBins[1]++; else if(d<2.0)flatBins[2]++; else if(d<4.0)flatBins[3]++; else flatBins[4]++;}
        }
        
        // 排序求中位数
        java.util.Arrays.sort(adjSlopes,0,adjIdx);
        double median=adjSlopes[adjIdx/2];
        double p90=adjSlopes[(int)(adjIdx*0.9)];
        double p99=adjSlopes[(int)(adjIdx*0.99)];
        
        System.out.printf("\n--- MC块高阶梯度量 ---%n");
        System.out.printf("总相邻对: %d  最大高差: %.2f格%n",totalAdj,maxSlope);
        System.out.printf("中位数: %.2f格  P90: %.2f格  P99: %.2f格%n",median,p90,p99);
        System.out.printf("差<0.5格(相邻同高): %5.1f%%%n",flatBins[0]*100.0/totalAdj);
        System.out.printf("差<1.0格: %5.1f%%%n",flatBins[1]*100.0/totalAdj);
        System.out.printf("差<2.0格: %5.1f%%%n",flatBins[2]*100.0/totalAdj);
        System.out.printf("差<4.0格: %5.1f%%%n",flatBins[3]*100.0/totalAdj);
        System.out.printf("差>=4格: %5.1f%%%n",flatBins[4]*100.0/totalAdj);
        
        double flatPct=flatBins[0]*100.0/totalAdj;
        String verdict=flatPct>30?"✗ 严重梯田":flatPct>15?"△ 中度梯田":flatPct>5?"~ 轻度梯田":"✓ 正常";
        System.out.printf("诊断: %s%n",verdict);
        
        // 4. 可视化
        saveVisual(h,hsMin,hsR,flatBins,totalAdj,adjSlopes,adjIdx);
    }
    
    /** 精确复刻 Mod的 pyramidErosion（世界坐标密度法，30步） */
    static void pyramidErosionExact(float[][] map,int bs,int drops,float str,int radius,
        float fo,float in,float gv,float es,float ds,int ox,int oz,int pad,int baseSize,int ws,long seed){
        float cap=10f,mc=0.005f,ev=0.35f;
        float[] f=new float[bs*bs];
        for(int z=0;z<bs;z++)for(int x=0;x<bs;x++)f[z*bs+x]=map[z][x];
        
        int r2=radius*radius,maxB=(2*radius+1)*(2*radius+1);
        int[] bo=new int[maxB]; float[] bw=new float[maxB]; int bn=0;
        for(int dy=-radius;dy<=radius;dy++)for(int dx=-radius;dx<=radius;dx++){
            float d2=dx*dx+dy*dy; if(d2<r2){bo[bn]=dy*bs+dx; bw[bn]=1f-(float)Math.sqrt(d2)/radius; bn++;}
        }
        {float s=0;for(int i=0;i<bn;i++)s+=bw[i];for(int i=0;i<bn;i++)bw[i]/=s;}
        
        int is=pad,ie=pad+baseSize,mg=1;
        float pc=(float)((baseSize-mg*2)*(baseSize-mg*2));
        float den=drops/pc; long th=(long)(den*(1L<<20));
        int gen=0;
        
        for(int py=is+mg;py<ie-mg;py++)for(int px=is+mg;px<ie-mg;px++){
            int wx=ox+(px-pad)*ws,wz=oz+(py-pad)*ws;
            long hh=hashCoarse(wx*31+1,wz*73+1); if((hh&((1L<<20)-1))>=th)continue;
            int idx=py*bs+px; if(f[idx]<=0.02f)continue;
            gen++;
            
            float dx=0,dz=0,sed=0,spd=1,wtr=1,pxf=px+0.5f,pzf=py+0.5f;
            for(int st=0;st<30;st++){
                int ix=(int)pxf,iz=(int)pzf;
                if(ix<1||ix>=bs-2||iz<1||iz>=bs-2)break;
                idx=iz*bs+ix; float fx=pxf-ix,fz=pzf-iz;
                float nw=f[idx],ne=f[idx+1],sw=f[idx+bs],se=f[idx+bs+1];
                float h0=nw*(1-fx)*(1-fz)+ne*fx*(1-fz)+sw*(1-fx)*fz+se*fx*fz;
                if(h0<=0.02f)break;
                float gx=(ne-nw)*(1-fz)+(se-sw)*fz,gz=(sw-nw)*(1-fx)+(se-ne)*fx;
                float gl=(float)Math.sqrt(gx*gx+gz*gz); if(gl<1e-12f)break;
                dx=dx*in-gx*(1-in); dz=dz*in-gz*(1-in);
                float dl=(float)Math.sqrt(dx*dx+dz*dz); if(dl<1e-12f)break; dx/=dl;dz/=dl;
                float npx=pxf+dx,npz=pzf+dz;
                if(npx<1||npx>=bs-2||npz<1||npz>=bs-2)break;
                int nix=(int)npx,niz=(int)npz; float fnx=npx-nix,fnz=npz-niz;
                int nidx=niz*bs+nix;
                float h1=f[nidx]*(1-fnx)*(1-fnz)+f[nidx+1]*fnx*(1-fnz)+f[nidx+bs]*(1-fnx)*fnz+f[nidx+bs+1]*fnx*fnz;
                float dh=(h1-h0)*Math.min(1,h0/fo);
                float c=Math.max(-dh*spd*wtr*cap*str,mc);
                if(sed>c||dh>0){float dep=dh>0?Math.min(dh,sed):(sed-c)*ds;sed-=dep;
                    f[idx]+=dep*(1-fx)*(1-fz); f[idx+1]+=dep*fx*(1-fz);
                    f[idx+bs]+=dep*(1-fx)*fz; f[idx+bs+1]+=dep*fx*fz;}
                else{float ea=Math.min((c-sed)*es,-dh);
                    for(int b=0;b<bn;b++){int bi=idx+bo[b]; if(bi>=0&&bi<bs*bs){
                        float dt=Math.min(f[bi],ea*bw[b]);f[bi]-=dt;sed+=dt;}}}
                pxf=npx;pzf=npz; spd=(float)Math.sqrt(spd*spd+dh*gv); if(spd<=0)break; wtr*=(1-ev);
            }
        }
        for(int z=0;z<bs;z++)for(int x=0;x<bs;x++){float v=Math.max(0,Math.min(1,f[z*bs+x]));map[z][x]=Float.isNaN(v)||Float.isInfinite(v)?0.5f:v;}
    }
    
    static long hashCoarse(int a,int b){long h=a*0x9e3779b9L+b*0x9e3779b9L*31;h=(h^(h>>>16))*0x85ebca6bL;h=h^(h>>>13);h=h*0xc2b2ae35L;h=h^(h>>>16);return h;}
    static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    static float[][] downsample(float[][] s,int ss,int ds){
        float[][] d=new float[ds][ds]; int scale=ss/ds;
        for(int z=0;z<ds;z++)for(int x=0;x<ds;x++){float su=0;int n=0;
            for(int dz=0;dz<scale;dz++)for(int dx=0;dx<scale;dx++){su+=s[z*scale+dz][x*scale+dx];n++;}
            d[z][x]=su/n;}
        return d;
    }
    
    static void saveVisual(float[][] h,float min,float range,int[] bins,int total,double[] slopes,int slen) throws Exception {
        int sz=TILE*4, ih=100;
        BufferedImage img=new BufferedImage(sz,sz+ih,BufferedImage.TYPE_INT_RGB);
        fillBg(img,0x111111);
        
        for(int z=0;z<TILE;z++)for(int x=0;x<TILE;x++){
            int c=ErosionPipeline.toColor(h[z][x],min,range);
            if(x<TILE-1){double d=Math.abs(h[z][x+1]-h[z][x])*384; if(d<0.5)c=0xFF0000; else if(d<1.0)c=0xFF6600;}
            if(z<TILE-1){double d=Math.abs(h[z+1][x]-h[z][x])*384; if(d<0.5)c=0xFF0000; else if(d<1.0)c=0xFF6600;}
            for(int dy=0;dy<4;dy++)for(int dx=0;dx<4;dx++)img.setRGB(x*4+dx,z*4+dy,c);
        }
        
        drawLabel(img,String.format("Flat<0.5blk:%.1f%% <1blk:%.1f%% Max:%.1fblk",
            bins[0]*100.0/total,bins[1]*100.0/total,slopes[slen-1]),5,sz+4,0xFFAA33);
        drawLabel(img,"Red=adjacent same-height Orange=<1block diff Green=normal",5,sz+16,0x888888);
        
        // 简单剖面
        int profZ=56, graphY=sz+30, graphH=50;
        for(int x=1;x<TILE;x++){int gx1=1+x*sz/TILE,gx2=1+(x+1)*sz/TILE;
            int y1=graphY+graphH-(int)((h[profZ][x]-min)/range*graphH);
            int y2=graphY+graphH-(int)((h[profZ][x-1]-min)/range*graphH);
            drawLine(img,gx1,y2,gx2,y1,0x00FF00);}
        
        new File("output").mkdirs();
        ImageIO.write(img,"png",new File("output/terrace_v2.png"));
        System.out.println("\n可视化: output/terrace_v2.png");
    }
    static void fillBg(BufferedImage img,int c){for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++)img.setRGB(x,y,c);}
    static void drawLabel(BufferedImage img,String t,int x,int y,int c){for(int i=0;i<t.length();i++)for(int dy=0;dy<9;dy++)for(int dx=0;dx<7;dx++){int px=x+i*8+dx,py=y+dy;if(px<img.getWidth()&&py<img.getHeight())img.setRGB(px,py,c);}}
    static void drawLine(BufferedImage img,int x0,int y0,int x1,int y1,int c){int dx=Math.abs(x1-x0),sx=x0<x1?1:-1,dy=-Math.abs(y1-y0),sy=y0<y1?1:-1,err=dx+dy,x=x0,y=y0;while(true){if(x>=0&&x<img.getWidth()&&y>=0&&y<img.getHeight())img.setRGB(x,y,c);if(x==x1&&y==y1)break;int e2=err*2;if(e2>=dy){err+=dy;x+=sx;}if(e2<=dx){err+=dx;y+=sy;}}}
}
