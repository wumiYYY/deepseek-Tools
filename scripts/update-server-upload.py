"""Temporary HTTPS PUT location used only while deploying one APK over an unstable SSH link."""
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys


stage = Path(__file__).resolve().parent
config = json.loads((stage / "config.json").read_bytes())
manifest = json.loads((stage / "update.json").read_bytes())
identity, token, version = config["uploadId"], config["token"], manifest["versionName"]
assert re.fullmatch(r"[a-f0-9]{32}", identity)
assert re.fullmatch(r"[a-f0-9]{64}", token)
assert re.fullmatch(r"[0-9]{1,3}\.[0-9]{1,2}\.[0-9]{1,2}", version)
site = Path("/etc/nginx/sites-available/sub2api")
snippet = Path("/etc/nginx/snippets/whale-vault-temporary-upload.conf")
include = "include /etc/nginx/snippets/whale-vault-temporary-upload.conf;"
directory = Path("/var/lib/whale-vault-upload") / identity
name = f"jingyuniang-v{version}.apk"
marker = f"# whale upload {identity}"
original = site.read_bytes()

if sys.argv[1] == "open":
    if snippet.exists():
        raise RuntimeError("Another temporary upload is active; finish or close it first")
    directory.mkdir(parents=True, exist_ok=False)
    shutil.chown(directory, user="www-data", group="www-data")
    directory.chmod(0o700)
    snippet.write_text(f'''{marker}
location = /whale-vault-upload/{identity}/{name} {{
    if ($http_x_whale_update_token != "{token}") {{ return 404; }}
    root /var/lib;
    dav_methods PUT;
    dav_access user:rw;
    client_max_body_size 128m;
    client_body_timeout 120s;
    limit_except PUT {{ deny all; }}
}}
''')
    snippet.chmod(0o600)
    text = original.decode()
    start = text.index("listen 443 ssl")
    anchor = text.index("server_name 161.118.233.45;", start)
    end = text.index(";", anchor) + 1
    text = text[:end] + "\n " + include + text[end:]
    (stage / "nginx-before-upload").write_bytes(original)
    try:
        site.write_text(text)
        subprocess.run(["nginx", "-t"], check=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True)
    except Exception:
        site.write_bytes(original)
        snippet.unlink(missing_ok=True)
        subprocess.run(["nginx", "-t"], check=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True)
        raise
    print("Temporary HTTPS upload ready")
elif sys.argv[1] in ("finish", "close"):
    if snippet.exists() and marker not in snippet.read_text():
        raise RuntimeError("Refusing to close another deployment's upload")
    site.write_text(original.decode().replace("\n " + include, "").replace(include, ""))
    snippet.unlink(missing_ok=True)
    subprocess.run(["nginx", "-t"], check=True)
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
    if sys.argv[1] == "finish":
        if (directory / name).is_file():
            shutil.move(str(directory / name), str(stage / name))
        elif not (stage / name).is_file():
            raise RuntimeError("The uploaded APK is missing")
        (stage / name).chmod(0o600)
    if directory.exists():
        for child in directory.iterdir():
            if child.is_file():
                child.unlink()
        directory.rmdir()
    print("Temporary HTTPS upload closed")
else:
    raise ValueError("Unknown upload action")
