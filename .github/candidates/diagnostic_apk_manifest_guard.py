import re

def parse_tree(text):
    roots=[]; stack=[]
    for line in text.splitlines():
        m=re.match(r'(\s*)E: ([^ ]+)',line)
        if m:
            indent=len(m[1]);node={'tag':m[2],'attrs':{},'children':[]}
            while stack and stack[-1][0]>=indent:stack.pop()
            (stack[-1][1]['children'] if stack else roots).append(node)
            stack.append((indent,node));continue
        m=re.match(r'\s*A: (?:android:)?([^ (]+)(?:\([^)]*\))?=(.*)',line)
        if m and stack:
            raw=m[2];string=re.search(r'\(Raw: "(.*)"\)$',raw)
            value=string[1] if string else (raw[1:-1] if raw.startswith('"') and raw.endswith('"') else raw)
            stack[-1][1]['attrs'][m[1]]=value
    assert len(roots)==1 and roots[0]['tag']=='manifest'
    return roots[0]

def full_name(package,name):return package+name if name.startswith('.') else name

def fingerprint(tree,package):
    components=[];definitions=[]
    for n in tree['children']:
        if n['tag']=='permission':
            definitions.append({'name':n['attrs']['name'],'level':n['attrs'].get('protectionLevel')})
        if n['tag']=='application':
            for c in n['children']:
                if c['tag'] not in ['activity','activity-alias','service','receiver','provider']:continue
                filters=[]
                for f in c['children']:
                    if f['tag']=='intent-filter':
                        filters.append(sorted((x['tag'],x['attrs'].get('name')) for x in f['children'] if x['tag'] in ['action','category']))
                components.append({'type':c['tag'],'name':full_name(package,c['attrs']['name']),
                    'exported':c['attrs'].get('exported'),'permission':c['attrs'].get('permission'),
                    'filters':sorted(filters)})
    return {'components':sorted(components,key=lambda x:(x['type'],x['name'])),
            'permission_definitions':sorted(definitions,key=lambda x:x['name'])}

def verify_snapshot(text,package,baseline,diagnostic):
    actual=fingerprint(parse_tree(text),package)
    expected={'components':list(baseline['components']),'permission_definitions':baseline['permission_definitions']}
    if diagnostic:
        expected['components'].append({'type':'activity','name':package+'.SppDiagnosticActivity',
            'exported':'(type 0x12)0xffffffff' if package.endswith('.glasses') else '(type 0x12)0x0',
            'permission':None,'filters':[[['action','android.intent.action.MAIN'],['category','android.intent.category.LAUNCHER']]] if package.endswith('.glasses') else []})
        expected['components'].sort(key=lambda x:(x['type'],x['name']))
    # JSON normalizes tuples from parser to lists in pinned fixtures.
    import json
    assert json.loads(json.dumps(actual))==expected,'Merged exported components/filters/permissions differ from the pinned baseline plus the reviewed diagnostic activity'
    return actual
