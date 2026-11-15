#!/usr/bin/env python3
"""Pure-Java regression checks; does not compile, sign or build an APK."""
from pathlib import Path
import os,shutil,subprocess,tempfile
root=Path(__file__).resolve().parents[2]
java_home=os.environ.get('JAVA_HOME')
javac=str(Path(java_home)/'bin/javac') if java_home else shutil.which('javac')
java=str(Path(java_home)/'bin/java') if java_home else shutil.which('java')
if not javac or not java:raise SystemExit('Install JDK 17 / set JAVA_HOME')
with tempfile.TemporaryDirectory(prefix='mkoai-hls-ad-check-') as temp:
 subprocess.run([javac,'-source','8','-target','8','-Xlint:-options','-d',temp,
  str(root/'android/src/main/java/app/mkoai/player/HlsAdFilter.java'),
  str(root/'android/tests/HlsAdFilterChecks.java')],check=True)
 subprocess.run([java,'-cp',temp,'app.mkoai.player.HlsAdFilterChecks'],check=True)
