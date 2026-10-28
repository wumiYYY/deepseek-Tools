"""Export/import private release credentials without copying machine-specific settings."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
KEYS = ("WHALE_STORE_PASSWORD", "WHALE_KEY_ALIAS", "WHALE_KEY_PASSWORD", "WHALE_UPDATE_TOKEN")
CERT = "e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975"


def read_properties(path):
    result = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith(("#", "!")):
                key, value = line.split("=", 1)
                result[key.strip()] = value.strip()
    return result


def release_values(source):
    props = read_properties(source / "local.properties")
    values = {key: os.environ.get(key) or props.get(key, "") for key in KEYS}
    validate_values(values)
    return values


def validate_values(values):
    missing = [key for key in KEYS if not values.get(key)]
    if missing:
        raise ValueError("Missing private release settings: " + ", ".join(missing))
    if not re.fullmatch(r"[a-f0-9]{64}", values["WHALE_UPDATE_TOKEN"]):
        raise ValueError("WHALE_UPDATE_TOKEN must be the existing 64-character update token; do not generate a new one")
    if any("\n" in values[key] or "\r" in values[key] for key in KEYS):
        raise ValueError("Release settings cannot contain newlines")


def verify_keystore(path, values):
    bundled = ROOT / ".toolchain/jdk17/bin/keytool.exe"
    keytool = str(bundled) if bundled.exists() else shutil.which("keytool")
    if not keytool:
        raise RuntimeError("Install JDK 17 or run scripts/setup-toolchain.ps1 before importing release credentials")
    env = dict(os.environ, WHALE_HANDOVER_STORE_PASSWORD=values["WHALE_STORE_PASSWORD"])
    result = subprocess.run([keytool, "-J-Duser.language=en", "-list", "-v", "-keystore", str(path),
                             "-storepass:env", "WHALE_HANDOVER_STORE_PASSWORD", "-alias", values["WHALE_KEY_ALIAS"]],
                            env=env, capture_output=True)
    match = re.search(rb"SHA256:\s*([a-fA-F0-9:]+)", result.stdout)
    if result.returncode or not match or match[1].decode().replace(":", "").lower() != CERT:
        raise ValueError("Keystore password/alias or original release certificate verification failed")


def export_bundle(source, output, include_apk=False):
    values = release_values(source)
    jks = source / "keystore/jingyuniang-release.jks"
    verify_keystore(jks, values)
    entries = {"private/local.properties": ("\n".join(key + "=" + values[key] for key in KEYS) + "\n").encode(),
               "private/keystore/jingyuniang-release.jks": jks.read_bytes()}
    for name in ("合作者交接.md", "AGENT_HANDOFF.md", "在线升级.md", "RELEASE.md"):
        entries["docs/" + name] = (ROOT / "docs" / name).read_bytes()
    version = re.search(r'versionNameValue\s*=\s*"([0-9.]+)"', (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8-sig"))[1]
    entries["README-先读.txt"] = ("此包含正式签名与现有更新 token，仅通过私密渠道交给受信任的发布者，不上传 Git/Release。\n"
        "先 git pull 获取最新脚本，在仓库根目录执行：\n"
        "python scripts/publishing-config.py import --bundle <本压缩包完整路径>\n"
        "python scripts/deploy-update.py --check\n"
        "导入会保留本机 sdk.dir 和其他设置，拒绝替换不同签名库或不同更新 token。\n"
        "更新 token 保持与已安装 1.4.7 一致，不能重新生成。\n"
        "服务器：161.118.233.45，SSH 用户：ubuntu。服务器 SSH 密码请由所有者另行私密告知；\n"
        "检查或部署时由 SSH 交互输入，或在同事自己的机器配置获准的 SSH 身份。\n"
        "此包不包含所有者的 SSH 私钥、SSH 密码、AI Key、SDK 路径或真实账本。\n"
        "--check 只读验证 HTTPS token、SSH 登录、sudo 和更新目录，不发布、不改服务器。\n"
        "验证通过后提高版本号，运行 release.ps1 -PrepareUpdate，再运行 deploy-update.py。\n"
        "手机更新是否可用仍需在实际手机上点「检查更新」确认。\n").encode()
    entries["VERSION.json"] = json.dumps({"version": version, "certificateSha256": CERT,
        "server": "161.118.233.45", "username": "ubuntu"}, indent=2).encode()
    # Public server keys only; never copy the owner's SSH identity or SSH config.
    known = ROOT / ".toolchain/update_known_hosts"
    if known.exists():
        entries["private/update_known_hosts"] = known.read_bytes()
    if include_apk:
        for apk in (ROOT / "dist" / version).glob("*.apk"):
            entries["release/" + apk.name] = apk.read_bytes()
    entries["SHA256SUMS.txt"] = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n"
        for name, data in sorted(entries.items())).encode()
    output = output.resolve()
    # Credentials may only be exported to ignored paths when inside this checkout.
    if output.is_relative_to(ROOT):
        ignored = subprocess.run(["git", "check-ignore", "-q", "--", str(output)], cwd=ROOT)
        if ignored.returncode:
            raise ValueError("Private output must be outside the checkout or in a Git-ignored directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise ValueError("Output already exists; select a new private bundle filename")
    with output.open("xb") as stream:
        os.chmod(output, 0o600)
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as package:
            for name, data in entries.items():
                package.writestr(name, data)
    print("Private publishing bundle created: " + str(output))


def import_bundle(bundle, root=ROOT):
    import tempfile
    with zipfile.ZipFile(bundle) as package:
        # Read named entries only; no archive extraction or arbitrary paths.
        props_data = package.read("private/local.properties")
        jks_data = package.read("private/keystore/jingyuniang-release.jks")
        known = package.read("private/update_known_hosts") if "private/update_known_hosts" in package.namelist() else None
    with tempfile.TemporaryDirectory() as scratch:
        temp = Path(scratch)
        (temp / "local.properties").write_bytes(props_data)
        (temp / "release.jks").write_bytes(jks_data)
        values = read_properties(temp / "local.properties")
        validate_values(values)
        verify_keystore(temp / "release.jks", values)
    props = root / "local.properties"
    existing = read_properties(props)
    for key in KEYS:
        effective = os.environ.get(key) or existing.get(key)
        if effective and effective != values[key]:
            raise ValueError("Existing " + key + " differs from bundle; resolve explicitly before importing")
    dest = root / "keystore/jingyuniang-release.jks"
    if dest.exists() and dest.read_bytes() != jks_data:
        raise ValueError("Existing keystore differs; it was not overwritten")
    known_path = root / ".toolchain/update_known_hosts"
    if known and known_path.exists() and known_path.read_bytes() != known:
        raise ValueError("Existing SSH known hosts differs; verify host keys before importing")
    lines = props.read_text(encoding="utf-8-sig").splitlines() if props.exists() else []
    lines = [line for line in lines if line.split("=", 1)[0].strip() not in KEYS]
    lines.extend(key + "=" + values[key] for key in KEYS)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(jks_data)
    os.chmod(dest, 0o600)
    props.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.chmod(props, 0o600)
    if known:
        known_path.parent.mkdir(parents=True, exist_ok=True)
        known_path.write_bytes(known)
    print("Imported original signing configuration and existing update token; local SDK settings preserved.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export")
    export.add_argument("--source", type=Path, default=ROOT)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--include-apk", action="store_true")
    importer = commands.add_parser("import")
    importer.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "export":
        export_bundle(args.source, args.output, args.include_apk)
    else:
        import_bundle(args.bundle)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, zipfile.BadZipFile) as error:
        print("Publishing configuration failed: " + str(error), file=sys.stderr)
        sys.exit(1)
