from pathlib import Path
import sys,subprocess,zipfile,re,hashlib,json,os,xml.etree.ElementTree as ET
module,apk,out=sys.argv[1:]
apk=Path(apk);out=Path(out);out.mkdir(exist_ok=True,parents=True)
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
def run(tool,*args):
 p=subprocess.run([str(tools/tool),*map(str,args)],check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 return p.stdout
sig=run('apksigner','verify','--verbose','--print-certs',apk)
(out/(module+'-signature.txt')).write_text(sig)
certs=re.findall(r'^Signer #\d+ certificate SHA-256 digest: (.+)$',sig,re.M)
assert certs==['55b22185392b8c1f084c3dec58bb0f342ece7102289556ae23cadee6ccec2097'],certs
bad=run('aapt','dump','badging',apk);(out/(module+'-badging.txt')).write_text(bad)
package='com.anezium.rokidbus.'+('phone' if module=='phone-hub' else 'glasses')
code,name=('10426','1.4.26') if module=='phone-hub' else ('10430','1.4.30')
assert all(s in bad.splitlines()[0] for s in ["name='"+package+"'","versionCode='"+code+"'","versionName='"+name+"'"])
assert 'application-debuggable' not in bad
run('zipalign','-c','4',apk)
permissions=run('aapt','dump','permissions',apk);(out/(module+'-permissions.txt')).write_text(permissions)
ns='{http://schemas.android.com/apk/res/android}'
source=ET.parse(Path(module)/'src/main/AndroidManifest.xml').getroot()
expected={e.attrib[ns+'name'] for e in source if e.tag.startswith('uses-permission')}
actual=set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'",permissions))
assert actual==expected,(actual-expected,expected-actual)
with zipfile.ZipFile(apk) as z:
 assert z.testzip() is None
 assert not any(Path(n).suffix.lower() in ['.jks','.keystore','.p12','.pfx','.key'] for n in z.namelist())
 dex=b''.join(z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n))
 for token in [b'SPP_DIAG_01_20261101',b'SppDiagnosticActivity',b'/core/spp-diagnostic/']:
  assert token in dex,token
 assert b'Lcom/buildground/ownerregression/' not in dex
 if module=='phone-hub':
  pin=os.environ['GLASSES_SHA'];assert pin.encode() in dex
  for s in [b'SPP diagnostics (read only)',b'Read Glasses SPP status',b'SppDiagnosticPending',b'SppClientSessionOwner']:
   assert s in dex,s
 else:
  for s in [b'SppDiagnosticJournal',b'SppSessionOwner',b'Last listener stage (not a health probe)']:
   assert s in dex,s
 label=json.loads(z.read('assets/buildground-spp-diagnostic.json'))
 assert label['build_label']=='SPP_DIAG_01_20261101' and label['call']=='UNSUPPORTED'
meta={'file':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'bytes':apk.stat().st_size,'package':package,'versionName':name,'versionCode':int(code),'cert':certs[0],'permissions':sorted(actual),'label':label,'device_validation':'NOT_RUN'}
(out/(module+'-verification.json')).write_text(json.dumps(meta,indent=2)+'\n')
print(module,'artifact verified',meta['sha256'])
