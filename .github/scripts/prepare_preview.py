"""Validate a fresh CI build before publishing an explicitly experimental release."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[2]
out = root / 'release'
out.mkdir(exist_ok=True)
tool = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
suites = [ET.parse(p).getroot() for p in (root / 'app/build/test-results').rglob('TEST-*.xml')]
assert suites and sum(int(s.get('tests', 0)) for s in suites) >= 535
assert all(int(s.get(k, 0)) == 0 for s in suites for k in ('failures', 'errors', 'skipped'))
ui_suites = [ET.parse(p).getroot() for p in (root / 'app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml')]
assert ui_suites and sum(int(s.get('tests', 0)) for s in ui_suites) >= 33, 'Actual emulator UI test results required'
assert all(int(s.get(k, 0)) == 0 for s in ui_suites for k in ('failures', 'errors', 'skipped'))
lint = {}
for variant in ('diagnosticDebug',):
    issues = ET.parse(root / f'app/build/reports/lint-results-{variant}.xml').getroot().findall('issue')
    assert not any(i.get('severity') in ('Fatal', 'Error') for i in issues)
    lint[variant] = {'errors': 0, 'warnings': sum(i.get('severity') == 'Warning' for i in issues)}
apks = []
for variant in ('diagnostic',):
    apk = root / f'app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk'
    signature = subprocess.check_output([str(tool / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    expected_cert = (root / 'docs/PREVIEW_SIGNING_CERT_SHA256.txt').read_text().strip().lower()
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)', signature)
    assert len(certs) == 1 and certs[0].replace(':', '').lower() == expected_cert, 'Unexpected APK signing certificate'
    manifest = subprocess.check_output([str(tool / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True)
    badging = subprocess.check_output([str(tool / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    assert "versionCode='21'" in badging and "versionName='1.3.13-diagnostic'" in badging, 'Wrong APK version'
    assert 'android.permission.INTERNET' in manifest, 'Private in-app updater requires HTTPS connectivity'
    with zipfile.ZipFile(apk) as z:
        dex = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    assert (b'Lapp/octopus/local/demo/DemoCatalog;' in dex) == (variant == 'demo')
    assert b'Lapp/octopus/local/automation/CatalogTestReceiver;' not in dex, 'Development broadcast bridge must not ship'
    if variant == 'demo':
        blocks = manifest.split('E: service ')
        service = next(b for b in blocks[1:] if 'app.octopus.local.automation.OctopusAccessibilityService' in b)
        assert 'android:enabled' in service and '0x0' in service.split('E: intent-filter')[0]
    dest = out / 'octopus-1.3.13.apk'
    shutil.copy2(apk, dest)
    apks.append({'file': dest.name, 'bytes': dest.stat().st_size, 'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(), 'signatureVerified': True})
summary = {'version': '1.3.13', 'versionCode': 21, 'commit': os.environ['GITHUB_SHA'],
           'runUrl': f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
           'unitTestExecutions': sum(int(s.get('tests', 0)) for s in suites), 'lint': lint, 'apks': apks,
           'demoIsolatedFromDiagnosticApk': True, 'deviceUiTestsExecuted': True,
           'uiTestExecutions': sum(int(s.get('tests', 0)) for s in ui_suites), 'uiTestDevice': 'Android API 35 emulator; not Honor',
           'deliveryAppReadingsDeviceVerified': False, 'realTwoProviderComparisonVerified': False,
           'realProviderCartPreparationVerified': False, 'realFinalTotalsVerified': False,
           'realSelectedCartHandoffVerified': False, 'releaseScope': 'current_work_update', 'signing': 'stable preview certificate',
           'signingCertificateSha256': expected_cert}
(out / 'validation-summary-ci.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))


