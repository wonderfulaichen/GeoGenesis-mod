from PIL import Image
import glob, os
p = sorted(glob.glob(r'build/hydro-reassessment/canonical-*/canonical.png'))[-1]
im = Image.open(p)
print(p, im.size)
d = os.path.dirname(p)
c1 = im.crop((0, 300, 320, 601)).resize((640, 602), Image.NEAREST)
c1.save(os.path.join(d, 'crop_bottomleft.png'))
c2 = im.crop((0, 0, 300, 250)).resize((600, 500), Image.NEAREST)
c2.save(os.path.join(d, 'crop_topleft.png'))
print('OUT', d)
