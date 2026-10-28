"""Run by deploy-update.py on the server; installs only update files and its nginx location."""
import hashlib
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


def atomic_write(path, data, mode=0o644):
    temp = path.with_name(path.name + ".whale-tmp")
    temp.write_bytes(data)
    temp.chmod(mode)
    os.replace(temp, path)


def main():
    stage = Path(__file__).resolve().parent
    config = json.loads((stage / "config.json").read_text())
    token = config["token"]
    if not re.fullmatch(r"[a-f0-9]{64}", token):
        raise ValueError("Invalid update access token")
    manifest_data = (stage / "update.json").read_bytes()
    manifest = json.loads(manifest_data)
    version = manifest["versionName"]
    if not re.fullmatch(r"[0-9]{1,3}\.[0-9]{1,2}\.[0-9]{1,2}", version):
        raise ValueError("Invalid version")
    name = f"jingyuniang-v{version}.apk"
    apk = stage / name
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if manifest["apk"] != f"{version}/{name}" or manifest["sha256"] != digest or manifest["sizeBytes"] != apk.stat().st_size:
        raise ValueError("Uploaded APK differs from manifest")
    if manifest["signatureSha256"] != "e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975":
        raise ValueError("Unexpected release certificate")

    root = Path("/var/www/whale-vault")
    if root.is_symlink():
        raise RuntimeError("Update directory must not be a symlink")
    root.mkdir(parents=True, exist_ok=True)
    previous = root / "update.json"
    if previous.exists() and json.loads(previous.read_bytes())["versionCode"] > manifest["versionCode"]:
        raise RuntimeError("Refusing to publish a lower version")
    directory = root / version
    directory.mkdir(exist_ok=True)
    destination = directory / name
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
        raise RuntimeError("Version already exists with different APK contents; increment the version")
    if not destination.exists():
        shutil.copyfile(apk, destination.with_suffix(".apk.upload"))
        destination.with_suffix(".apk.upload").chmod(0o644)
        os.replace(destination.with_suffix(".apk.upload"), destination)
    atomic_write(directory / "SHA256SUMS.txt", f"{digest}  {name}\n".encode())

    if config["configureNginx"]:
        site = Path("/etc/nginx/sites-available/sub2api")
        snippet = Path("/etc/nginx/snippets/whale-vault.conf")
        original = site.read_bytes()
        previous_snippet = snippet.read_bytes() if snippet.exists() else None
        text = original.decode()
        include = "include /etc/nginx/snippets/whale-vault.conf;"
        if include not in text:
            start = text.index("listen 443 ssl")
            anchor = text.index("server_name 161.118.233.45;", start)
            end = text.index(";", anchor) + 1
            text = text[:end] + "\n " + include + text[end:]
        location = f'''# Private app update channel. APK signing keys stay on the build machine.
location ^~ /whale-vault/ {{
    if ($http_x_whale_update_token != "{token}") {{ return 404; }}
    root /var/www;
    autoindex off;
    types {{ application/json json; application/vnd.android.package-archive apk; text/plain txt; }}
    default_type application/octet-stream;
    add_header Cache-Control "no-store" always;
    add_header X-Content-Type-Options "nosniff" always;
    limit_except GET {{ deny all; }}
    try_files $uri =404;
}}
'''.encode()
        backup = Path("/var/backups/whale-vault") / (time.strftime("%Y%m%d-%H%M%S") + f"-{time.time_ns() % 1000000000:09d}")
        backup.mkdir(parents=True, exist_ok=False)
        backup.chmod(0o700)
        (backup / "sub2api").write_bytes(original)
        if previous_snippet is not None:
            (backup / "whale-vault.conf").write_bytes(previous_snippet)
        try:
            atomic_write(snippet, location, 0o600)
            atomic_write(site, text.encode())
            subprocess.run(["nginx", "-t"], check=True)
            subprocess.run(["systemctl", "reload", "nginx"], check=True)
        except Exception:
            atomic_write(site, original)
            if previous_snippet is None:
                snippet.unlink(missing_ok=True)
            else:
                atomic_write(snippet, previous_snippet, 0o600)
            subprocess.run(["nginx", "-t"], check=True)
            subprocess.run(["systemctl", "reload", "nginx"], check=True)
            raise
    elif not Path("/etc/nginx/snippets/whale-vault.conf").exists():
        raise RuntimeError("First deployment requires --configure-nginx")

    # Publish last: clients never see a manifest whose APK is still being uploaded.
    atomic_write(previous, manifest_data)
    print(json.dumps({"published": version, "sha256": digest, "sizeBytes": destination.stat().st_size}))


if __name__ == "__main__":
    with open("/var/lock/whale-vault-update.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        main()
