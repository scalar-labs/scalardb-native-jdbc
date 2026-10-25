"""A BenchBase run's pg_stat_statements (its server.txt): execution and planning time by kind.

Usage: python3 -I pgss-sum.py RUN_DIR/server.txt...
Kinds: the driver's write forms (fused, slim/keyed, merged batch, insert), reads, the reads of
in-flight rows' before images, and the vendor's plain statements.
"""
import sys,re
def load(p):
    rows=[]
    for line in open(p):
        f=line.rstrip('\n').split('\t')
        if len(f)<6 or not f[0].isdigit(): continue
        rows.append((int(f[0]),float(f[1]),float(f[2]),f[5]))
    return rows
def kind(q):
    if q.startswith('WITH scalardb_native_s'): return 'write beyond key (fused, stamp first)'
    if q.startswith('WITH scalardb_native_inserted') or q.startswith('INSERT'): return 'insert'
    if q.startswith('WITH scalardb_native_e'): return 'merged batch'
    if q.startswith('WITH scalardb_native_keys'): return 'slim / keyed write'
    if "before_" in q[:300] or "_tx_metadata\" m" in q[:400]: return 'read: before images of in-flight rows'
    if q.startswith('SELECT') or q.startswith('WITH'): return 'read'
    if q.startswith('UPDATE') or q.startswith('DELETE'): return 'plain update/delete'
    return q.split()[0].upper() if q else '?'
for p in sys.argv[1:]:
    rows=load(p)
    commits=None
    for line in open(p):
        m=re.search(r'commits\D+(\d+)',line)
        if m and not line[0].isdigit(): commits=int(m.group(1))
    tot={}
    for c,e,pl,q in rows:
        k=kind(q); a=tot.setdefault(k,[0,0.0,0.0]); a[0]+=c; a[1]+=e; a[2]+=pl
    print(p.split('/')[-3]+'/'+p.split('/')[-2], 'commits', commits, 'exec ms total %.0f plan %.0f'%(sum(e for _,e,_,_ in rows),sum(pl for _,_,pl,_ in rows)))
    for k,(c,e,pl) in sorted(tot.items(),key=lambda x:-x[1][1]):
        print('  %-45s calls %8d exec %8.0f ms plan %6.0f ms'%(k,c,e,pl))
