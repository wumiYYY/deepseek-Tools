"""Publish a locally built update through OpenSSH; signing keys stay on this machine."""
import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import uuid
import urllib.request

try:
    import paramiko
except ImportError:
    paramiko = None


def verify_https(host, token, manifest):
    base = f"https://{host}/whale-vault/"
    headers = {"X-Whale-Update-Token": token, "Cache-Control": "no-cache"}
    with urllib.request.urlopen(urllib.request.Request(base + "update.json", headers=headers), timeout=30) as response:
        if json.load(response) != manifest:
            raise RuntimeError("Server manifest differs from this release")
    digest, size = hashlib.sha256(), 0
    with urllib.request.urlopen(urllib.request.Request(base + manifest["apk"], headers=headers), timeout=90) as response:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    if digest.hexdigest() != manifest["sha256"] or size != manifest["sizeBytes"]:
        raise RuntimeError("Server APK failed post-deployment hash/size verification")
    print("HTTPS manifest and complete APK download verified against the local release.")


def openssh_options(root):
    if not shutil.which("ssh"):
        raise RuntimeError("OpenSSH ssh is required; alternatively use --transport paramiko")
    known = root / ".toolchain/update_known_hosts"
    known.parent.mkdir(exist_ok=True)
    options = ["-o", "StrictHostKeyChecking=accept-new", "-o", f"UserKnownHostsFile={known.as_posix()}",
               "-o", "ConnectTimeout=20", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3"]
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY", "")
    if proxy.startswith("http://") and os.environ.get("WHALE_SSH_PROXY") != "0":
        relay = subprocess.list2cmdline([sys.executable, str(root / "scripts/ssh-proxy.py"), "%h", "%p"])
        options += ["-o", "ProxyCommand=" + relay]
        print("SSH will use the configured desktop network proxy.", flush=True)
    return options


def check_publishing(args, root, token):
    headers = {"X-Whale-Update-Token": token, "Cache-Control": "no-cache"}
    request = urllib.request.Request(f"https://{args.host}/whale-vault/update.json", headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        manifest = json.load(response)
    print("HTTPS update access verified; current server version: " + manifest["versionName"], flush=True)
    command = ("set -e; command -v python3 >/dev/null; sudo -n true; "
               "sudo -n test -w /var/www/whale-vault; "
               "sudo -n test -r /etc/nginx/snippets/whale-vault.conf; "
               "sudo -n nginx -t; printf 'SSH and deployment permissions verified.\\n'")
    result = subprocess.run(["ssh", "-T", *openssh_options(root), f"{args.username}@{args.host}", command])
    if result.returncode:
        raise RuntimeError("SSH login or sudo/update directory check failed; configure your own SSH identity or enter the server password")
    print("Read-only publishing check passed. No release files or nginx configuration changed.", flush=True)


def deploy_openssh(args, root, token, manifest_path, apk, manifest):
    options = openssh_options(root)
    target = f"{args.username}@{args.host}"
    identity = uuid.uuid4().hex
    stage = f"/home/{args.username}/.cache/whale-vault-upload/{identity}"

    def execute(command):
        for attempt in range(3):
            result = subprocess.run(["ssh", "-n", "-T", *options, target, command])
            if result.returncode == 0:
                return
            if result.returncode != 255 or attempt == 2:
                raise RuntimeError(f"Remote deployment command failed (SSH exit {result.returncode})")
            print("SSH control connection interrupted; retrying...", flush=True)
            time.sleep(2)

    files = {
        "update.json": manifest_path.read_bytes(),
        "install.py": (root / "scripts/update-server-install.py").read_bytes(),
        "upload.py": (root / "scripts/update-server-upload.py").read_bytes(),
        "config.json": json.dumps({"token": token, "configureNginx": args.configure_nginx, "uploadId": identity}).encode()
    }
    encoded = json.dumps({name: base64.b64encode(data).decode() for name, data in files.items()})
    setup = (f"import base64,json,pathlib; p=pathlib.Path({stage!r}); p.mkdir(parents=True,exist_ok=True,mode=448); "
             f"d=json.loads({encoded!r}); [(p/n).write_bytes(base64.b64decode(v)) for n,v in d.items()]")
    execute("python3 -c " + shlex.quote(setup))
    opened = False
    try:
        opened = True
        execute(f"sudo -n python3 {shlex.quote(stage + '/upload.py')} open")
        print("Uploading APK through the temporary HTTPS endpoint...", flush=True)
        with apk.open("rb") as source:
            request = urllib.request.Request(
                f"https://{args.host}/whale-vault-upload/{identity}/{apk.name}", method="PUT", data=source,
                headers={"X-Whale-Update-Token": token, "Content-Length": str(apk.stat().st_size),
                         "Content-Type": "application/octet-stream"})
            with urllib.request.urlopen(request, timeout=120) as response:
                if response.status not in (200, 201, 204):
                    raise RuntimeError("HTTPS upload failed")
        execute(f"sudo -n python3 {shlex.quote(stage + '/upload.py')} finish")
        opened = False
        execute(f"sudo -n python3 {shlex.quote(stage + '/install.py')}")
        verify_https(args.host, token, manifest)
    finally:
        if opened:
            execute(f"sudo -n python3 {shlex.quote(stage + '/upload.py')} close")
        names = ("update.json", apk.name, "install.py", "upload.py", "config.json", "nginx-before-upload")
        paths = [stage + "/" + name for name in names]
        # Some files were created by the root helper; remove only this deployment's explicit files.
        cleanup = "sudo -n rm -f -- " + " ".join(map(shlex.quote, paths)) + "; rmdir -- " + shlex.quote(stage)
        try:
            execute(cleanup)
        except RuntimeError:
            print("Deployment staging cleanup requires a reconnect.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="161.118.233.45")
    parser.add_argument("--username", default="ubuntu")
    parser.add_argument("--publish-dir", type=Path, default=Path("dist/publish/whale-vault"))
    parser.add_argument("--configure-nginx", action="store_true", help="Install the isolated update location in the existing HTTPS IP site")
    parser.add_argument("--transport", choices=("openssh", "paramiko"), default="openssh")
    parser.add_argument("--check", action="store_true", help="Read-only check of existing token, HTTPS, SSH and deployment permissions; no APK required")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    props_path = root / "local.properties"
    props = props_path.read_text(encoding="utf-8-sig") if props_path.exists() else ""
    match = re.search(r"^\s*WHALE_UPDATE_TOKEN\s*=\s*([a-f0-9]{64})\s*$", props, re.M)
    token = os.environ.get("WHALE_UPDATE_TOKEN") or (match[1] if match else "")
    if not re.fullmatch(r"[a-f0-9]{64}", token):
        raise RuntimeError("WHALE_UPDATE_TOKEN is missing/invalid; import the owner's private publishing bundle with scripts/publishing-config.py import --bundle <zip>. Do not generate a new token.")
    if args.check:
        check_publishing(args, root, token)
        return
    directory = args.publish_dir.resolve()
    manifest_path = directory / "update.json"
    manifest = json.loads(manifest_path.read_bytes())
    version = manifest["versionName"]
    if not re.fullmatch(r"[0-9]{1,3}\.[0-9]{1,2}\.[0-9]{1,2}", version):
        raise ValueError("Invalid version")
    apk_name = f"jingyuniang-v{version}.apk"
    if manifest["apk"] != f"{version}/{apk_name}":
        raise ValueError("Unexpected APK path")
    apk = directory / version / apk_name
    if hashlib.sha256(apk.read_bytes()).hexdigest() != manifest["sha256"]:
        raise ValueError("APK hash mismatch")
    if args.transport == "openssh":
        deploy_openssh(args, root, token, manifest_path, apk, manifest)
        return
    if paramiko is None:
        raise RuntimeError("--transport paramiko requires the paramiko package")
    known = root / ".toolchain/update_known_hosts"
    known.parent.mkdir(exist_ok=True)
    ssh = paramiko.SSHClient()
    ssh.load_system_host_keys()
    if known.exists():
        ssh.load_host_keys(str(known))
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    password = getpass.getpass(f"SSH password for {args.username}@{args.host}: ")
    stage = f"/home/{args.username}/.cache/whale-vault-upload/{uuid.uuid4().hex}"

    def execute(command):
        _, out, err = ssh.exec_command(command, timeout=90)
        stdout, stderr = out.read().decode(), err.read().decode()
        if out.channel.recv_exit_status() != 0:
            raise RuntimeError(stderr or stdout or "Remote operation failed")
        return stdout.strip()

    def connect():
        for attempt in range(3):
            try:
                ssh.connect(args.host, username=args.username, password=password, look_for_keys=False,
                            allow_agent=False, timeout=30, banner_timeout=60, auth_timeout=45, compress=True)
                break
            except paramiko.BadHostKeyException:
                raise
            except (OSError, EOFError, paramiko.SSHException):
                ssh.close()
                if attempt == 2:
                    raise
                print("SSH connection interrupted; retrying...", flush=True)
                time.sleep(2)
        ssh.get_transport().set_keepalive(20)
        ssh.save_host_keys(str(known))
        fingerprint = base64.b64encode(hashlib.sha256(ssh.get_transport().get_remote_server_key().asbytes()).digest()).decode().rstrip("=")
        print(f"SSH host key SHA256:{fingerprint}", flush=True)

    def upload_file(local, name):
        total = local.stat().st_size
        for attempt in range(6):
            try:
                with ssh.open_sftp() as sftp:
                    sftp.get_channel().settimeout(60)
                    try:
                        offset = sftp.stat(stage + "/" + name).st_size
                    except FileNotFoundError:
                        offset = 0
                    if offset > total:
                        raise RuntimeError("Remote staging file has unexpected size")
                    if offset == total:
                        return
                    with sftp.open(stage + "/" + name, "r+b" if offset else "wb") as remote, local.open("rb") as source:
                        # Wait for write acknowledgements: resumable even on unstable SSH links.
                        remote.set_pipelined(False)
                        remote.seek(offset)
                        source.seek(offset)
                        reported = offset // (2 * 1024 * 1024)
                        while chunk := source.read(64 * 1024):
                            remote.write(chunk)
                            offset += len(chunk)
                            step = offset // (2 * 1024 * 1024)
                            if total > 1024 * 1024 and step > reported:
                                reported = step
                                print(f"APK uploaded: {offset * 100 // total}%", flush=True)
                return
            except (OSError, EOFError, paramiko.SSHException):
                if attempt == 5:
                    raise
                print("Upload interrupted; reconnecting and resuming...", flush=True)
                ssh.close()
                connect()

    try:
        connect()
        execute(f"umask 077; mkdir -p {shlex.quote(stage)}")
        # Explicit allowlist; never upload local.properties, keystores or SSH credentials.
        upload_file(manifest_path, "update.json")
        upload_file(apk, apk_name)
        upload_file(root / "scripts/update-server-install.py", "install.py")
        with ssh.open_sftp() as sftp:
            with sftp.open(stage + "/config.json", "w") as config:
                config.write(json.dumps({"token": token, "configureNginx": args.configure_nginx}))
        print(execute(f"sudo -n python3 {shlex.quote(stage + '/install.py')}"))
        base = f"https://{args.host}/whale-vault/"
        headers = {"X-Whale-Update-Token": token, "Cache-Control": "no-cache"}
        with urllib.request.urlopen(urllib.request.Request(base + "update.json", headers=headers), timeout=30) as response:
            published = json.load(response)
        if published != manifest:
            raise RuntimeError("Server manifest differs from this release")
        digest, size = hashlib.sha256(), 0
        with urllib.request.urlopen(urllib.request.Request(base + manifest["apk"], headers=headers), timeout=45) as response:
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                size += len(chunk)
        if digest.hexdigest() != manifest["sha256"] or size != manifest["sizeBytes"]:
            raise RuntimeError("Server APK failed post-deployment hash/size verification")
        print("HTTPS manifest and complete APK download verified against the local release.")
    finally:
        if ssh.get_transport() is not None and ssh.get_transport().is_authenticated():
            # The staging path is generated above and contains only this deployment's four files.
            try:
                with ssh.open_sftp() as sftp:
                    for name in ("update.json", apk_name, "install.py", "config.json"):
                        try:
                            sftp.remove(stage + "/" + name)
                        except OSError:
                            pass
                    try:
                        sftp.rmdir(stage)
                    except OSError:
                        pass
            except (OSError, EOFError, paramiko.SSHException):
                print("Deployment staging cleanup requires a reconnect.", file=sys.stderr)
        ssh.close()


if __name__ == "__main__":
    main()
