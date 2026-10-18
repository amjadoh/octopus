"""Validate a fresh CI build before publishing an explicitly experimental release."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[2]
out = root / 'release'
out.mkdir(exist_ok=True)
tool = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
suites = [ET.parse(p).getroot() for p in (root / 'app/build/test-results').rglob('TEST-*.xml')]
assert suites and sum(int(s.get('tests', 0)) for s in suites) >= 23
assert all(int(s.get(k, 0)) == 0 for s in suites for k in ('failures', 'errors', 'skipped'))
lint = {}
for variant in ('diagnosticDebug',):
    issues = ET.parse(root / f'app/build/reports/lint-results-{variant}.xml').getroot().findall('issue')
    assert not any(i.get('severity') in ('Fatal', 'Error') for i in issues)
    lint[variant] = {'errors': 0, 'warnings': sum(i.get('severity') == 'Warning' for i in issues)}
apks = []
for variant in ('diagnostic',):
    apk = root / f'app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk'
    subprocess.run([str(tool / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
    manifest = subprocess.check_output([str(tool / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True)
    assert 'android.permission.INTERNET' not in manifest
    with zipfile.ZipFile(apk) as z:
        dex = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    assert (b'Lapp/octopus/local/demo/DemoCatalog;' in dex) == (variant == 'demo')
    if variant == 'demo':
        blocks = manifest.split('E: service ')
        service = next(b for b in blocks[1:] if 'app.octopus.local.automation.OctopusAccessibilityService' in b)
        assert 'android:enabled' in service and '0x0' in service.split('E: intent-filter')[0]
    dest = out / 'octopus-0.3.0.apk'
    shutil.copy2(apk, dest)
    apks.append({'file': dest.name, 'bytes': dest.stat().st_size, 'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(), 'signatureVerified': True})
summary = {'version': '0.3.0-alpha', 'commit': os.environ['GITHUB_SHA'],
           'runUrl': f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
           'unitTestExecutions': sum(int(s.get('tests', 0)) for s in suites), 'lint': lint, 'apks': apks,
           'demoIsolatedFromDiagnosticApk': True, 'deviceUiTestsExecuted': False,
           'deliveryAppReadingsDeviceVerified': False, 'signing': 'fresh CI debug key; different from local debug key'}
(out / 'validation-summary-ci.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
