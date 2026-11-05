import pathlib,subprocess,json,time
O=pathlib.Path(__file__).resolve().parent;B=O.parent/'bp-20261105-054953-3413'
s=(O/'KernelDiagnostic.java').read_text();prefix=s[:s.index('   int[][] owners=')].replace('public final class KernelDiagnostic','public final class ChainDiagnostic')
body=r'''
   Map<TileGeometry.Owner,ParticleTile> tiles=new HashMap<>();
   Map<TileGeometry.Owner,float[]> observed=new HashMap<>();
   int[][] owners={{-1920,-2976},{-2112,-3024},{-2160,-2928}};
   for(int t=0;t<3;t++){
    var owner=new TileGeometry.Owner(owners[t][0],owners[t][1]);
    float[] post=new float[146*146];
    try(var r=new DataInputStream(new BufferedInputStream(Files.newInputStream(o.resolve("tile"+t+"/original-flat.bin"))))){for(int i=0;i<post.length;i++)post[i]=r.readFloat();}
    float[] pre=ParticleTile.buildInput(ctx,owner,()->false),delta=new float[128*128];
    for(int z=0;z<128;z++)for(int x=0;x<128;x++){int i=(z+9)*146+x+9;delta[z*128+x]=ParticleTile.limitedDelta(post[i],pre[i]);}
    observed.put(owner,delta);
    float[][] g=ErosionEngine.buildHardnessGrid(ctx::resistance,11,owner.inputX(),owner.inputZ());
    float[] hard=new float[146*146];for(int z=0;z<146;z++)for(int x=0;x<146;x++)hard[z*146+x]=ErosionEngine.sampleHardness(g,11,owner.inputX(),owner.inputZ(),owner.inputX()+x,owner.inputZ()+z);
    binary(o.resolve("tile"+t+"/sampled-hardness.bin"),hard);
   }
   int n=0,bad=0,selected=0;
   try(var w=Files.newBufferedWriter(o.resolve("chain.csv"),StandardOpenOption.CREATE_NEW)){
    w.write("tile,x,z,originalEP,reconstructedEP,selectedWeight,delta00,delta10,delta01,delta11,fx,fz\n");
    for(String line:Files.readAllLines(o.resolve("exact-local.csv")).subList(1,7204)){
     String[] v=line.split(",");double x=Double.parseDouble(v[1]),z=Double.parseDouble(v[2]),ep=Double.parseDouble(v[5]);var f=TileGeometry.footprint(x,z);
     for(var owner:f.required())if(!tiles.containsKey(owner))tiles.put(owner,ParticleTile.build(ctx,owner,()->false));
     double d00=at(f.main(),x,z,tiles,observed),d10=f.fx()>0?at(f.sideX(),x,z,tiles,observed):d00;
     double d01=f.fz()>0?at(f.sideZ(),x,z,tiles,observed):d00;
     double d11=f.fx()>0&&f.fz()>0?at(f.corner(),x,z,tiles,observed):f.fx()>0?d10:d01;
     double delta=d00;
     if(f.fx()>0||f.fz()>0)delta=d00*(1-f.fx())*(1-f.fz())+d10*f.fx()*(1-f.fz())+d01*(1-f.fx())*f.fz()+d11*f.fx()*f.fz();
     double rec=ctx.assemble(ctx.baseE(x,z),delta),weight=0;
     for(var owner:f.required())if(observed.containsKey(owner))weight+=(owner.x()==f.main().x()?1-f.fx():f.fx())*(owner.z()==f.main().z()?1-f.fz():f.fz());
     if(weight>0)selected++;if(Double.doubleToRawLongBits(ep)!=Double.doubleToRawLongBits(rec))bad++;n++;
     w.write(v[0]+","+x+","+z+","+ep+","+rec+","+weight+","+d00+","+d10+","+d01+","+d11+","+f.fx()+","+f.fz()+"\n");
    }
   }
   Files.writeString(o.resolve("chain-result.txt"),"points="+n+"; mismatches="+bad+"; observed-tile-contributing-points="+selected+"; required-tiles="+tiles.size()+"\n");
   if(bad!=0)throw new AssertionError("chain mismatch");
  }
 }
 static double at(TileGeometry.Owner owner,double x,double z,Map<TileGeometry.Owner,ParticleTile> tiles,Map<TileGeometry.Owner,float[]> observed){
  float[] delta=observed.get(owner);if(delta==null)return tiles.get(owner).at(x,z);
  double lx=x-owner.originX(),lz=z-owner.originZ();int ix=(int)Math.floor(lx),iz=(int)Math.floor(lz);
  float fx=(float)(lx-ix),fz=(float)(lz-iz);int i=iz*128+ix;
  float top=delta[i]+(delta[i+1]-delta[i])*fx;float bot=delta[i+128]+(delta[i+129]-delta[i+128])*fx;
  return top+(bot-top)*fz;
 }
}
'''
(O/'ChainDiagnostic.java').write_text(prefix+body)
cp=str(O/'classes')+';'+str(B/'main-classes')+';'+(B/'compile-classpath.txt').read_text().strip()
for name,cmd in [('chain-compile',['javac','--release','17','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(O/'classes'),str(O/'ChainDiagnostic.java')]),('chain',['java','-Xmx2g','-Dfile.encoding=UTF-8','-cp',cp,'com.geogenesis.worldgen.process.ChainDiagnostic',str(O),str(B)])]:
 (O/(name+'-command.json')).write_text(json.dumps(cmd,indent=2));start=time.time()
 with (O/(name+'.log')).open('xb') as f:p=subprocess.run(cmd,cwd=str(O),stdout=f,stderr=subprocess.STDOUT)
 (O/(name+'-exit.json')).write_text(json.dumps({'exit':p.returncode,'seconds':time.time()-start}));print(name,p.returncode,flush=True)
 if p.returncode:raise SystemExit(p.returncode)
