import csv,collections,statistics,math,json,hashlib,pathlib
rows=list(csv.DictReader(open('perf2.csv'))); assert len(rows)==1800
result={}; hashes={}
for z in [0,1831,-1832,29295]:
 result[str(z)]={}; hashes[str(z)]={}
 for arm in 'ABC':
  data=[r for r in rows if int(r['zone'])==z and r['arm']==arm]
  assert len(data)==150 and all(sum(int(r['batch'])==i for r in data)==6 for i in range(25))
  hs={r['hash'] for r in data}; sums={r['sum'] for r in data}; assert len(hs)==len(sums)==1
  hashes[str(z)][arm]={'hash':next(iter(hs)),'sum':next(iter(sums))}
  vals=sorted(int(r['elapsed_ns'])/1e6 for r in data if int(r['batch'])>=10); assert len(vals)==90
  result[str(z)][arm]={'p50_ms':round(statistics.median(vals),3),'p95_ms':round(vals[math.ceil(.95*len(vals))-1],3),'n':len(vals)}
 assert hashes[str(z)]['B']==hashes[str(z)]['C'],(z,hashes[str(z)])
 result[str(z)]['C_vs_B_P50_pct']=round(100*(result[str(z)]['C']['p50_ms']/result[str(z)]['B']['p50_ms']-1),2)
print(json.dumps({'results':result,'checksums':hashes},indent=2))
pathlib.Path('perf2-summary.json').write_text(json.dumps({'protocol':'6 permutations, fresh JVM per arm/zone/round; 10 warmup batches then 15 measured batches, 10000 permuted unique points per batch; p95 nearest rank over 90 batches per arm/zone; checksum FNV over raw double bits and sum','results':result,'checksums':hashes},indent=2)+'\n')
