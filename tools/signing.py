#!/usr/bin/env python3
"""Create the permanent local Android release key. Never prints passwords."""
from pathlib import Path
import hashlib
import os
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / name)
    candidates += [Path.home() / "Applications/Android Studio.app/Contents/jbr/Contents/Home/bin" / name,
                   Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin") / name]
    return str(next((p for p in candidates if p.is_file()), shutil.which(name) or name))


def main():
    key_dir = ROOT / "keystore"
    key_dir.mkdir(mode=0o700, exist_ok=True)
    key = key_dir / "omodash-release.p12"
    config = key_dir / "signing.properties"
    if key.exists() or config.exists():
        raise SystemExit("Signing files already exist; refusing to replace the permanent key.")
    old_mask = os.umask(0o077)
    try:
        password = secrets.token_urlsafe(36)
        env = dict(os.environ, OMODASH_KEY_PASSWORD=password)
        subprocess.run([java_tool("keytool"), "-genkeypair", "-keystore", str(key),
                        "-storetype", "PKCS12", "-alias", "omodash", "-keyalg", "RSA",
                        "-keysize", "3072", "-validity", "10000", "-dname", "CN=Omodash, O=solobot2",
                        "-storepass:env", "OMODASH_KEY_PASSWORD", "-keypass:env", "OMODASH_KEY_PASSWORD"],
                       env=env, check=True, capture_output=True)
        config.write_text("storeFile=keystore/omodash-release.p12\nkeyAlias=omodash\n"
                          f"storePassword={password}\nkeyPassword={password}\n")
        subprocess.run([java_tool("keytool"), "-exportcert", "-rfc", "-keystore", str(key),
                        "-alias", "omodash", "-storepass:env", "OMODASH_KEY_PASSWORD",
                        "-file", str(key_dir / "omodash-certificate.pem")],
                       env=env, check=True, capture_output=True)
        der = subprocess.run([java_tool("keytool"), "-exportcert", "-keystore", str(key),
                              "-alias", "omodash", "-storepass:env", "OMODASH_KEY_PASSWORD"],
                             env=env, check=True, capture_output=True).stdout
        fingerprint = hashlib.sha256(der).hexdigest()
        (key_dir / "certificate-sha256.txt").write_text(fingerprint + "\n")
        print(f"Release key created: {key}\nPrivate signing configuration: {config}\n"
              f"Certificate SHA-256: {fingerprint}\nBack up the keystore directory to a private offline location.")
    finally:
        os.umask(old_mask)


if __name__ == "__main__":
    main()
