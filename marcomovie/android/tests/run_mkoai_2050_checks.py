from pathlib import Path
import subprocess,os,hashlib
r=Path(__file__).resolve().parents[2];jar=r/'android/tests/lib/json-20240303.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
cp=os.pathsep.join([str(jar),str(r/'android/build/classes'),os.environ.get('MARCOMOVIE_ANDROID_JAR',str(r/'tools/android-35/android.jar'))]+[str(p) for p in (r/'tools/java-dependencies').glob('*.jar')]);out=r/'android/build/mkoai-2050-checks';out.mkdir(exist_ok=True);j=str(Path(os.environ.get('MARCOMOVIE_JDK',os.environ.get('JAVA_HOME','missing-jdk')))/'bin')+os.sep
tests=['HistoryClearChecks','MetadataSchedulingChecks','GlobalSearchChecks','FavoritesChecks']
sources=[str(r/'android/tests'/f'{t}.java') for t in tests]+[str(r/'android/tests/HomeAvailabilityChecks.java')]
subprocess.run([j+'javac','-encoding','UTF-8','-cp',cp,'-d',str(out),*sources],check=True)
failed=[];log=[]
for t in ['HomeAvailabilityChecks']+tests:
 p=subprocess.run([j+'java','-cp',str(out)+os.pathsep+cp,'app.mkoai.player.'+t,str(r)],text=True,capture_output=True,timeout=90)
 log.append(f'== {t} ==\n'+p.stdout+p.stderr)
 print(t, 'PASS' if p.returncode==0 else 'FAIL',p.stdout.strip().split('\n')[-1],flush=True)
 if p.returncode: failed.append(t); print(p.stderr[:500],flush=True)
r.joinpath('android/build/mkoai-2050-results.txt').write_text('\n'.join(log))
if failed: raise SystemExit('Failed: '+str(failed))
