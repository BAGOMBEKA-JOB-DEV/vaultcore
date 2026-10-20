#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export VAULTCORE_PROJECT_DIR="$project_dir"

# Downloads tools only. Never starts services, containers, clusters, or IDE tasks.
python3 - <<'PY'
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

if os.uname().sysname != 'Linux' or os.uname().machine != 'x86_64':
    raise SystemExit('This setup script supports Linux x86_64; install Java 21 manually on other platforms.')

project = Path(os.environ['VAULTCORE_PROJECT_DIR'])
data_dir = Path(os.environ.get('XDG_DATA_HOME', str(Path.home() / '.local/share'))) / 'vaultcore'
bin_dir = Path.home() / '.local/bin'
bin_dir.mkdir(parents=True, exist_ok=True)
jdk_dir = data_dir / 'jdk-21'

def download(url, destination):
    subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                    '--retry', '2', '--max-time', '600', url, '-o', str(destination)], check=True)

def verify(path, checksum):
    with path.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != checksum.strip().split()[0]:
        raise SystemExit(f'Checksum verification failed: {path.name}')

with tempfile.TemporaryDirectory(prefix='vaultcore-tools-') as temporary:
    temp = Path(temporary)
    if not (jdk_dir / 'bin/java').is_file():
        metadata = temp / 'jdk.json'
        download('https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse', metadata)
        package = json.loads(metadata.read_text())[0]['binary']['package']
        archive = temp / 'jdk.tar.gz'
        download(package['link'], archive)
        verify(archive, package['checksum'])
        jdk_dir.mkdir(parents=True, exist_ok=True)
        subprocess.run(['tar', '-xzf', str(archive), '--strip-components=1', '-C', str(jdk_dir)], check=True)

    tools = {
        'kubectl': 'https://dl.k8s.io/release/v1.37.1/bin/linux/amd64/kubectl',
        'kind': 'https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64',
    }
    for name, url in tools.items():
        binary, checksum = temp / name, temp / f'{name}.sha256'
        download(url, binary)
        download(url + ('.sha256' if name == 'kubectl' else '.sha256sum'), checksum)
        verify(binary, checksum.read_text())
        binary.chmod(0o755)
        binary.replace(bin_dir / name)

# Expose Java without changing shell startup files or replacing existing commands.
for name in ('java', 'javac', 'jar'):
    link = bin_dir / name
    if not link.exists() and not link.is_symlink():
        link.symlink_to(jdk_dir / 'bin' / name)

environment = project / '.env'
if not environment.exists():
    content = (project / '.env.example').read_text().replace(
        'replace-with-a-local-password', secrets.token_urlsafe(24))
    descriptor = os.open(environment, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(content)

print(f'Java 21: {jdk_dir}')
print(f'Kubernetes CLI tools: {bin_dir}')
print('Local .env ready; no services started.')
PY

"$project_dir/scripts/mvn.sh" --version
