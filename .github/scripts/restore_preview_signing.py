"""Restore the explicitly approved repository secret in runner temporary storage only."""
import base64
import json
import os
from pathlib import Path

raw = os.environ.get('OCTOPUS_PREVIEW_SIGNING', '')
if not raw:
    raise SystemExit('OCTOPUS_PREVIEW_SIGNING is missing. Refusing to publish with a changing debug key.')
config = json.loads(raw)
keys = ('keystoreBase64', 'storePassword', 'keyPassword', 'keyAlias')
if not all(isinstance(config.get(k), str) and config[k] for k in keys):
    raise SystemExit('Signing secret is incomplete.')
if any('\n' in config[k] or '\r' in config[k] for k in keys):
    raise SystemExit('Signing secret contains unexpected line breaks.')
for k in ('keystoreBase64', 'storePassword', 'keyPassword'):
    print('::add-mask::' + config[k])
destination = Path(os.environ['RUNNER_TEMP']) / 'octopus-preview.p12'
destination.write_bytes(base64.b64decode(config['keystoreBase64'], validate=True))
destination.chmod(0o600)
values = {'OCTOPUS_SIGNING_STORE_FILE': str(destination), 'OCTOPUS_SIGNING_STORE_PASSWORD': config['storePassword'],
          'OCTOPUS_SIGNING_KEY_PASSWORD': config['keyPassword'], 'OCTOPUS_SIGNING_KEY_ALIAS': config['keyAlias']}
with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as output:
    for name, value in values.items():
        output.write(f'{name}={value}\n')
print('Signing key restored into runner temporary storage; values are not logged.')
