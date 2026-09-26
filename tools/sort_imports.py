import re,sys,glob
def key(line):
    path=line[len('import '):].strip()
    grp = 1 if path.startswith('java.') else 2 if path.startswith('javax.') else 3 if path.startswith('kotlin.') else 0
    return (grp, path)
def fix(p):
    lines=open(p).read().split('\n')
    idx=[i for i,l in enumerate(lines) if l.startswith('import ')]
    if not idx: return False
    a,b=idx[0],idx[-1]
    block=lines[a:b+1]
    if any(l.strip()=='' for l in block) is False and all(l.startswith('import ') for l in block):
        s=sorted(set(block),key=key)
        if s!=block:
            lines[a:b+1]=s; open(p,'w').write('\n'.join(lines)); return True
    return False
changed=[p for p in sys.argv[1:] if fix(p)]
print('\n'.join(changed))
