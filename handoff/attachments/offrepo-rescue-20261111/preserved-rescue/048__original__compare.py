import argparse, hashlib, json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

BASE_SHA = '7be0418369d097ce107eeef58b8d02c6776782f1d7fbc6ad6c5e0e5728d7a238'
STOPS = [-64, 0, 50, 62.999, 63, 95, 150, 220, 320]
COLORS = np.array([[12,37,72],[24,64,102],[42,103,135],[90,175,182],[73,123,69],[119,150,85],[148,128,93],[153,150,140],[245,245,245]])

def load(path, rows, cols):
    raw = Path(path).read_bytes()
    if len(raw) != rows * cols * 8: raise ValueError('wrong byte count: ' + path)
    a = np.frombuffer(raw, dtype='>f8').reshape(rows, cols)
    if not np.isfinite(a).all(): raise ValueError('non-finite height: ' + path)
    return a, hashlib.sha256(raw).hexdigest()

def shade(h):
    gz, gx = np.gradient(h, 5.0)
    light = np.array([-0.5,-0.35,1.0]); light /= np.linalg.norm(light)
    lit = np.clip((-gx*light[0]-gz*light[1]+light[2])/np.sqrt(gx*gx+gz*gz+1),0,1)
    rgb = np.stack([np.interp(h, STOPS, COLORS[:,k]) for k in range(3)], axis=-1)
    return np.uint8(np.clip(rgb*(0.42+0.72*lit)[...,None],0,255))

def main():
    p = argparse.ArgumentParser(description='Full 1401 baseline shader, then common crop; no acceptance judgment.')
    p.add_argument('--baseline', required=True); p.add_argument('--candidate', required=True)
    p.add_argument('--delivered', required=True); p.add_argument('--out', required=True)
    p.add_argument('--window', nargs=4, type=int, default=[0,0,1401,1401], metavar=('X','Z','WIDTH','HEIGHT'))
    a = p.parse_args(); base, bs = load(a.baseline,1401,1401); cand, cs = load(a.candidate,1401,1401)
    if bs != BASE_SHA: raise ValueError('baseline SHA mismatch: '+bs)
    x,z,w,h = a.window
    if x<0 or z<0 or w<1 or h<1 or x+w>1401 or z+h>1401: raise ValueError('invalid window')
    ar, br = shade(base), shade(cand)
    ref = np.asarray(Image.open(a.delivered).convert('RGB'))
    if ref.shape != ar.shape or not np.array_equal(ref,ar): raise ValueError('baseline shade differs from delivered PNG')
    sl = np.s_[z:z+h,x:x+w]; av, bv = ar[sl], br[sl]; diff = cand[sl]-base[sl]
    rgbdiff = np.abs(bv.astype(np.int16)-av.astype(np.int16)).astype(np.uint8)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=False)
    Image.fromarray(av).save(out/'baseline.png'); Image.fromarray(bv).save(out/'candidate.png')
    panel = Image.new('RGB',(2*w,h+24),'white'); panel.paste(Image.fromarray(av),(0,24)); panel.paste(Image.fromarray(bv),(w,24))
    draw = ImageDraw.Draw(panel); draw.text((4,4),'BASELINE',fill='black'); draw.text((w+4,4),'CANDIDATE',fill='black'); panel.save(out/'ab.png')
    Image.fromarray(rgbdiff).save(out/'rgb-absolute-diff.png')
    np.asarray(diff,dtype='>f8').tofile(out/'height-signed-diff.f64be')
    scale = float(np.max(np.abs(diff))); norm = diff/scale if scale else diff
    visual = np.full((h,w,3),255,dtype=np.uint8); visual[...,1] = np.uint8(255*(1-np.abs(norm))); visual[...,0] = np.uint8(255*(1-np.maximum(-norm,0))); visual[...,2] = np.uint8(255*(1-np.maximum(norm,0)))
    Image.fromarray(visual).save(out/'height-signed-diff.png')
    metrics = {'baseline_sha256':bs,'candidate_sha256':cs,'window_x_z_width_height':a.window,'window_origin_world_xz':[-3642.5+5*x,-3365.5+5*z], 'step':5,'height_diff_candidate_minus_baseline':{'min':float(diff.min()),'max':float(diff.max()),'mean':float(diff.mean()),'mae':float(np.abs(diff).mean()),'rmse':float(np.sqrt(np.mean(diff**2))),'abs_p50_p95_p99':np.percentile(np.abs(diff),[50,95,99]).tolist(),'changed_pixels':int(np.count_nonzero(diff)),'visual_full_scale':scale},'rgb_changed_pixels':int(np.any(rgbdiff!=0,axis=2).sum()),'rgb_max_channel_diff':int(rgbdiff.max()),'baseline_delivered_exact':True,'note':'Comparison only; not candidate acceptance. Equal dimensions do not prove same world window.'}
    (out/'metrics.json').write_text(json.dumps(metrics,indent=2),encoding='utf-8'); print(json.dumps(metrics,indent=2))
if __name__ == '__main__': main()
