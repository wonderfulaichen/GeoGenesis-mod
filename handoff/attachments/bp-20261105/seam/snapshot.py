import pathlib,hashlib,json,sys,subprocess
out=pathlib.Path(__file__).resolve().parent
root=out.parents[2]
base=out.parent/'bp-20261105-054953-3413'
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
if sys.argv[1]=='before':
    paths=set()
    for line in subprocess.check_output(['git','ls-files','-co','--exclude-standard','-z'],cwd=str(root)).decode().split('\0'):
        p=root/line
        if line and p.is_file(): paths.add(p)
    for folder in [root/'forge-1.20.1-47.4.10-mdk/src/main',base,root/'forge-1.20.1-47.4.10-mdk/tools']:
        paths.update(p for p in folder.rglob('*') if p.is_file())
    data={str(p.relative_to(root)):digest(p) for p in sorted(paths) if out not in p.parents}
    (out/'hashes-before.json').write_text(json.dumps(data,indent=2),encoding='utf8')
    (out/'git-status-before.txt').write_bytes(subprocess.check_output(['git','status','--short'],cwd=str(root)))
    print('hashed',len(data))
else:
    data=json.loads((out/'hashes-before.json').read_text(encoding='utf8'))
    changes={k:('MISSING' if not (root/k).is_file() else digest(root/k)) for k,v in data.items() if not (root/k).is_file() or digest(root/k)!=v}
    (out/'hash-verification.json').write_text(json.dumps({'checked':len(data),'changes':changes},indent=2))
    (out/'git-status-after.txt').write_bytes(subprocess.check_output(['git','status','--short'],cwd=str(root)))
    print('verification',len(data),'changed',len(changes))
    sys.exit(bool(changes))
