import statistics as st,glob,collections
def load(l):
    per=collections.defaultdict(list); nodes={}
    for f in sorted(glob.glob(f'{l}/run[1-5].txt')):
        run=collections.defaultdict(list)
        for line in open(f):
            _,s,ms,sem,lay=line.split()
            if s.endswith('.capture') or s in('live','seeded'): 
                if s=='seeded': nodes[s]=(sem,lay)
                continue
            run[s].append(float(ms)); nodes[s]=(sem,lay)
        for s,v in run.items(): per[s].append(st.median(v))  # per-run median of warm reps
    return per,nodes
b,bn=load('base'); d,dn=load('dev')
print(f"{'step':22}{'base med':>9}{'(min-max)':>16}{'dev med':>9}{'(min-max)':>16}{'delta':>8}  nodes sem/layout base -> dev")
for s in b:
    bm,dm=st.median(b[s]),st.median(d[s]); 
    print(f"{s:22}{bm:9.1f}{f'({min(b[s]):.0f}-{max(b[s]):.0f})':>16}{dm:9.1f}{f'({min(d[s]):.0f}-{max(d[s]):.0f})':>16}{(dm-bm)/bm*100:+7.1f}%  {'/'.join(bn[s])} -> {'/'.join(dn[s])}")
print('seeded nodes', bn['seeded'], dn['seeded'])
