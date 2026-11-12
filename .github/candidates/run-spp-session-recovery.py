"""Offline helper regression runner. Paths must refer to existing trusted tools only."""
from pathlib import Path
import argparse, subprocess, json

p = argparse.ArgumentParser()
p.add_argument('--java', required=True)
p.add_argument('--compiler-classpath', required=True)
p.add_argument('--project', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
here = Path(__file__).resolve().parent
a.output.mkdir(parents=True, exist_ok=True)
classes = a.output / 'classes'
classes.mkdir(exist_ok=True)
sources = [a.project / 'phone-hub/src/main/java/com/anezium/rokidbus/phone/SppClientSessionOwner.kt',
           a.project / 'glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SppSessionOwner.kt',
           here / 'SppSessionRecoveryRegression.kt', here / 'SppSessionRecoveryBaseline.kt']
command = [a.java, '-cp', a.compiler_classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
           '-no-stdlib', '-no-reflect', '-classpath', a.compiler_classpath, '-jvm-target', '11',
           '-d', str(classes), *map(str, sources)]
r = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', timeout=60)
(a.output / 'compile.log').write_text(r.stdout, encoding='utf-8')
assert r.returncode == 0, r.stdout
import os
r = subprocess.run([a.java, '-cp', str(classes) + os.pathsep + a.compiler_classpath,
                    'com.buildground.spprecovery.SppSessionRecoveryRegressionKt'],
                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', timeout=40)
(a.output / 'tests.log').write_text(r.stdout, encoding='utf-8')
result = {'compile_exit': 0, 'test_exit': r.returncode, 'passed': r.stdout.count('PASS '), 'hardware': 'NOT_RUN'}
(a.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(r.stdout)
assert r.returncode == 0 and result['passed'] == 20
