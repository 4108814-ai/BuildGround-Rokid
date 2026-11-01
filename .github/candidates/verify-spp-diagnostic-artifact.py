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
# This signature permission already exists in both verified baseline APKs (AndroidX).
# Keep the final merged-APK allowlist exact rather than permitting arbitrary additions.
dynamic=package+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
assert actual==expected|{dynamic},(actual-(expected|{dynamic}),(expected|{dynamic})-actual)
tree=run('aapt','dump','xmltree',apk,'AndroidManifest.xml')
blocks=re.findall(r'^    E: permission .*?(?=^    E:|\Z)',tree,re.M|re.S)
owned=[block for block in blocks if '"'+dynamic+'"' in block]
assert len(owned)==1 and re.search(r'android:protectionLevel[^\n]*=\(type 0x11\)0x2(?:\s|$)',owned[0]), 'Existing AndroidX permission must stay signature-only'
(out/(module+'-manifest-tree.txt')).write_text(tree)
from diagnostic_apk_manifest_guard import verify_snapshot
baselines=json.loads(Path(__file__).with_name('diagnostic-baseline-manifests.json').read_text())
# Allow only the existing merged components plus the one reviewed diagnostic activity.
verify_snapshot(tree,package,baselines[module]['fingerprint'],True)
with zipfile.ZipFile(apk) as z:
 assert z.testzip() is None
 assert not any(Path(n).suffix.lower() in ['.jks','.keystore','.p12','.pfx','.key'] for n in z.namelist())
 dex=b''.join(z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n))
 for token in [b'SPP_DIAG_01_20261101',b'SppDiagnosticActivity',b'/core/spp-diagnostic/']:
  assert token in dex,token
 assert b'Lcom/buildground/ownerregression/' not in dex
 if module=='phone-hub':
  pin=os.environ['GLASSES_SHA'];assert pin.encode() in dex
  expected_text=[' · ', ' ›', 'AUTO ›', 'Asking the glasses to check their helper…', 'MANUAL ›', 'Nothing to repair — the helper is already in place.', 'READY ›', 'Repair ›', 'SET UP ›', 'Turn on Wi-Fi first — the update travels to the glasses over Wi-Fi.', 'Working…', '›']
  corrupt_text=[' Р\xa0вЂ™Р’В· ', ' вЂє', 'AUTO вЂє', 'Asking the glasses to check their helperвЂ¦', 'MANUAL вЂє', 'Nothing to repair вЂ” the helper is already in place.', 'READY вЂє', 'Repair вЂє', 'SET UP вЂє', 'Turn on Wi-Fi first Р\xa0Р†Р\xa0вЂљРІР‚Сњ the update travels to the glasses over Wi-Fi.', 'WorkingвЂ¦', 'вЂє']
  assert all(t.encode('utf-8') in dex for t in expected_text), 'Restored UI text absent'
  assert all(t.encode('utf-8') not in dex for t in corrupt_text), 'Corrupt UI text detected' 
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
