# Проверка миграций Room: v1 → … → vN должна дать ту же структуру, что и схема vN.
import json, sqlite3, re, sys
base=sys.argv[1]; src=open(sys.argv[2]).read()
def create(db, ver):
    d=json.load(open(f'{base}/{ver}.json'))['database']
    for e in d['entities']:
        t=e['tableName']; db.execute(e['createSql'].replace('${TABLE_NAME}',t))
        for i in e.get('indices',[]): db.execute(i['createSql'].replace('${TABLE_NAME}',t))
def mig(a,b):
    i=src.index(f'Migration({a}, {b})'); j=src.index('        }\n    }', i) if False else src.index('\n        }\n', i)
    return [q.replace('\\"','"') for q in re.findall(r'db\.execSQL\("(.*?)"\)\n', src[i:j]+'\n')]
def info(db):
    out={}
    for (t,) in db.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name!='room_master_table'"):
        cols=sorted(db.execute(f'pragma table_info(`{t}`)').fetchall(), key=lambda r:r[1])
        idx=sorted((r[1], r[2]) for r in db.execute(f'pragma index_list(`{t}`)') if not r[1].startswith('sqlite_autoindex'))
        out[t]=([(c[1],c[2],c[3],c[4],c[5]) for c in cols], idx)
    return out
top=int(sys.argv[3])
for start in range(2, top):
    a=sqlite3.connect(':memory:'); create(a,start)
    for v in range(start, top):
        for q in mig(v, v+1): a.execute(q)
    b=sqlite3.connect(':memory:'); create(b, top)
    ia, ib = info(a), info(b)
    bad=[t for t in set(ia)|set(ib) if ia.get(t)!=ib.get(t)]
    print(f'v{start}→v{top}:', 'OK' if not bad else f'MISMATCH {bad}')
    for t in bad: print(' got', ia.get(t), '\n exp', ib.get(t))
