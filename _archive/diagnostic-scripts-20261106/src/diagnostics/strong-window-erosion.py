"""Approved OFFLINE strong visual experiment, not S2 discovery or production.
Run from MDK: python -B src/diagnostics/strong-window-erosion.py build/<NEW>
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, Normalize

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'build/terminal-depth-repair-20261104'
JAVA=ROOT/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/StrongWindowErosionProbe.java'
PALETTE=ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
LIGHT=np.array([-.5,-.3,1.]);LIGHT/=np.linalg.norm(LIGHT)
LABELS=['不加后处理侵蚀 H0','当前弱侵蚀（修复版）','整窗强侵蚀（离线视觉实验）']
FOOTER=('同种子/同窗/同色带/同光照，无逐图归一化或额外高度夸张。HS=2，Yproxy=354.66H；'
        'e=.02+1.5H，固定色带[-.35,1.2]；光照[-.5,-.3,1]，阴影=.65+.35×positiveDot。\n'
        '强臂：固定世界起点间距24wu、半宽8wu、深度上限.08H≈28.37格。未做安全QP；坑、断头、规则感均保留。仅有界窗口实验，非S2/生产/实机。')


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(p,v):
    p.write_text(json.dumps(v,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')


def snapshot():
    paths=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
    paths += [p for p in BASE.rglob('*') if p.is_file()]
    paths += [p for p in (ROOT/'src/diagnostics/java').rglob('*.java') if p!=JAVA]
    return {p.relative_to(ROOT).as_posix():sha(p) for p in paths}


def command(out,name,args):
    print('COMMAND',name,flush=True)
    with (out/(name+'.log')).open('wb') as f:
        p=subprocess.Popen(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        for b in iter(lambda:p.stdout.read1(65536),b''):
            f.write(b);f.flush();sys.stdout.buffer.write(b);sys.stdout.buffer.flush()
        code=p.wait()
    if code!=0:raise RuntimeError(name+' exit '+str(code))
    return dict(name=name,argv=args,exitCode=code,logSha256=sha(out/(name+'.log')))


def shade(h,step):
    gz,gx=np.gradient(h*354.66,step*2)
    dot=(-gx*LIGHT[0]-gz*LIGHT[1]+LIGHT[2])/np.sqrt(1+gx*gx+gz*gz)
    return .65+.35*np.maximum(dot,0)


def finish(fig,out,name,title):
    fig.suptitle(title,fontsize=18);fig.text(.02,.012,FOOTER,fontsize=10)
    fig.tight_layout(rect=(0,.07,1,.94));fig.savefig(out/name,dpi=150);plt.close(fig)


def render(out):
    meta=json.loads((out/'data/sampling.json').read_text());n=meta['size'];step=meta['stepWu'];x,z=meta['originWu'];span=(n-1)*step
    h=[np.fromfile(out/('data/'+name+'.f64be'),dtype='>f8').astype(float).reshape(n,n)
       for name in ['carrier-height','weak-height','strong-height']]
    depth=np.fromfile(out/'data/strong-depth.f64be',dtype='>f8').astype(float).reshape(n,n)
    assert all(np.isfinite(v).all() for v in h) and np.all(h[2]<=h[0])
    assert np.all(depth>=0) and np.all(depth<=.08)
    assert sha(out/'data/carrier-height.f64be')==sha(BASE/'evaluated/carrier-height.f64be')
    assert sha(out/'data/weak-height.f64be')==sha(BASE/'evaluated/new-height.f64be')
    colors=np.loadtxt(PALETTE,delimiter=',',skiprows=1);cmap=ListedColormap(colors[:,1:]/255)
    shadows=[shade(v,step) for v in h]
    rgb=[cmap(Normalize(-.35,1.2,clip=True)(.02+1.5*v))[...,:3]*s[...,None] for v,s in zip(h,shadows)]
    extent=[x-step/2,x+span+step/2,z+span+step/2,z-step/2]
    plt.rcParams['font.sans-serif']=['Microsoft YaHei','SimHei','DejaVu Sans'];plt.rcParams['axes.unicode_minus']=False
    for name,images,gray in [('full-color-3arms.png',rgb,False),('full-hillshade-3arms.png',shadows,True)]:
        fig,axes=plt.subplots(1,3,figsize=(27,10.5))
        for ax,img,title in zip(axes,images,LABELS):
            ax.imshow(img,extent=extent,origin='upper',interpolation='nearest',**({'cmap':'gray','vmin':.65,'vmax':1} if gray else {}))
            ax.set(title=title,xlabel='世界x（wu）',ylabel='世界z（wu）')
        finish(fig,out,name,'完整同窗：无侵蚀 / 当前弱侵蚀 / 高覆盖强侵蚀')
    crops=[]
    for page in range(2):
        fig,axes=plt.subplots(2,3,figsize=(24,15))
        for row in range(2):
            # Fixed quadrants, not selected after seeing the strong image.
            cx=x+span*(.25 if row==0 else .75);cz=z+span*(.25 if page==0 else .75)
            bounds=[cx-220,cx+220,cz-220,cz+220];crops.append(bounds)
            for ax,img,title in zip(axes[row],rgb,LABELS):
                ax.imshow(img,extent=extent,origin='upper',interpolation='nearest')
                ax.set(title=title,xlim=bounds[:2],ylim=(bounds[3],bounds[2]),xlabel='世界x（wu）',ylabel='世界z（wu）')
        finish(fig,out,'fixed-closeup-%d.png'%(page+1),'固定四分区近景｜不按结果挑图')
    statistics=dict(totalPoints=n*n,changedPoints=int(np.count_nonzero(h[2]!=h[0])),
        changedPercent=float(np.mean(h[2]!=h[0])*100),maxDepthYproxy=float(depth.max()*354.66),
        activeMedianDepthYproxy=float(np.median(depth[depth>0])*354.66) if (depth>0).any() else 0,
        meanDepthYproxy=float(depth.mean()*354.66),fixedCropsWu=crops,
        morphologicalAcceptance=False,unsafeVisualExperiment=True)
    save(out/'statistics.json',statistics)
    return statistics


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('out');args=parser.parse_args()
    out=(ROOT/args.out).resolve();assert out.parent==ROOT/'build' and not out.exists()
    locked=snapshot();out.mkdir();save(out/'locked-inputs.json',locked);classes=out/'classes';classes.mkdir()
    cp=ROOT/'build/remaining-drop-field-sparse-equilibrated-20261104/classes'
    commands=[command(out,'compile',['javac','--release','17','-encoding','UTF-8','-proc:none',
        '-Xlint:all','-Werror','-cp',str(cp),'-d',str(classes),str(JAVA)])]
    commands.append(command(out,'generate',['java','-Xmx3g','-cp',str(classes)+os.pathsep+str(cp),
        'com.geogenesis.worldgen.terrain.morph.StrongWindowErosionProbe',str(out/'data')]))
    stats=render(out);assert locked==snapshot(),'Old inputs changed'
    save(out/'manifest.json',dict(scope='approved bounded strong visual experiment; no S2 or production',
        originalInputsUnchanged=True,lockedInputs=locked,commands=commands,statistics=stats,
        sourceHashes={JAVA.relative_to(ROOT).as_posix():sha(JAVA),Path(__file__).resolve().relative_to(ROOT).as_posix():sha(Path(__file__).resolve())},
        outputHashes={p.relative_to(out).as_posix():sha(p) for p in out.rglob('*') if p.is_file() and p.name!='manifest.json'}))
    print('STRONG VISUAL EXPERIMENT READY',out,stats,flush=True)


if __name__=='__main__':main()
