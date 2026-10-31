#!/usr/bin/env python3
"""Build a signed APK and publish a complete, versioned GitHub Release.

Authentication stays on the publishing computer: GH_TOKEN / GITHUB_TOKEN,
GitHub CLI, or the configured Git credential helper. Secrets are never printed.
"""
from pathlib import Path
import argparse
import contextlib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = "solobot2/omodash"
API = "https://api.github.com"


def run(*args, capture=False, env=None):
    return subprocess.run(args, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None, env=env).stdout


def properties(path):
    return dict(line.strip().split("=", 1) for line in path.read_text().splitlines()
                if line.strip() and not line.lstrip().startswith("#"))


def token():
    value = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if value:
        return value
    if shutil.which("gh"):
        p = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True)
        if p.returncode == 0 and p.stdout.strip():
            return p.stdout.strip()
    p = subprocess.run(["git", "credential", "fill"], cwd=ROOT, capture_output=True, text=True,
                       input=f"protocol=https\nhost=github.com\npath={REPO}.git\n\n",
                       env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    return dict(line.split("=", 1) for line in p.stdout.splitlines() if "=" in line).get("password")


def request(url, *, auth=None, method="GET", payload=None, binary=None, content_type=None):
    # Never send a publishing credential to download redirects or arbitrary hosts.
    host = urllib.parse.urlparse(url).hostname
    if auth and host not in {"api.github.com", "uploads.github.com"}:
        raise RuntimeError("Refusing to send GitHub credentials to a non-GitHub API host")
    headers = {"User-Agent": "Omodash-release-tool", "Accept": "application/vnd.github+json",
               "X-GitHub-Api-Version": "2022-11-28"}
    if auth:
        headers["Authorization"] = f"Bearer {auth}"
    data = binary
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError(f"GitHub returned HTTP {error.code} for {method} {urllib.parse.urlparse(url).path}") from None


def version_tuple(value):
    if not re.fullmatch(r"\d+\.\d+\.\d+", value):
        raise RuntimeError("Version must be MAJOR.MINOR.PATCH, for example 0.1.0")
    return tuple(map(int, value.split(".")))


def choose_version(explicit, local, releases, remote_code):
    versions = [r["tag_name"][1:] for r in releases if not r.get("draft") and not r.get("prerelease")
                and re.fullmatch(r"v\d+\.\d+\.\d+", r.get("tag_name", ""))]
    highest = max((version_tuple(v) for v in versions), default=None)
    if explicit:
        version = explicit
        if highest is not None and version_tuple(version) <= highest:
            raise RuntimeError("A new release version must be greater than published versions")
    else:
        candidate = version_tuple(local["versionName"])
        if highest is not None and candidate <= highest:
            candidate = highest[:2] + (highest[2] + 1,)
        version = ".".join(map(str, candidate))
    version_tuple(version)
    return version, max(int(local["versionCode"]), remote_code + 1)


def java_env():
    env = dict(os.environ)
    if not env.get("JAVA_HOME"):
        for path in [Path.home() / "Applications/Android Studio.app/Contents/jbr/Contents/Home",
                     Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")]:
            if path.is_dir():
                env["JAVA_HOME"] = str(path)
                break
    return env


def git_env(auth):
    helper = ROOT / ".release-work" / "git-askpass.py"
    helper.write_text("#!/usr/bin/env python3\nimport os, sys\n"
                      "print('x-access-token' if 'username' in sys.argv[1].lower() else os.environ['OMODASH_GITHUB_TOKEN'])\n")
    helper.chmod(0o700)
    return {**os.environ, "GIT_ASKPASS": str(helper), "GIT_TERMINAL_PROMPT": "0",
            "OMODASH_GITHUB_TOKEN": auth, "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "credential.helper", "GIT_CONFIG_VALUE_0": ""}


def sdk_build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and (ROOT / "local.properties").is_file():
        sdk = properties(ROOT / "local.properties").get("sdk.dir")
    if not sdk:
        sdk = str(Path.home() / "Library/Android/sdk")
    dirs = [p for p in (Path(sdk) / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
    return max(dirs, key=lambda p: tuple(map(int, p.name.split("."))))


def signing_fingerprints(output):
    # Build Tools 37 prints "V2 Signer:"; older tools print "Signer #1".
    # Match certificate digests only, never the separate public-key digest.
    return {value.lower() for value in re.findall(
        r"(?m)^.*?certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", output)}


def usb_bundle(out):
    """Fixed allowlist: never package signing keys, ADB keys, reports or local configuration."""
    files = {"README.txt": ROOT / "tools/USB-README.txt"}
    for name in ("tools/vehicle_usb.py", "tools/setup-usb.command", "tools/setup-usb.cmd", "docs/usb-setup.md"):
        files[name] = ROOT / name
    payloads = {name: path.read_bytes() for name, path in files.items()}
    payloads["SHA256SUMS.txt"] = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n"
                                       for name, data in sorted(payloads.items())).encode()
    target = out / "omodash-usb-tools.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(payloads.items()):
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100755 if name.endswith((".command", ".py")) else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    return target


def prepare_assets(version, code):
    metadata = json.loads((ROOT / "app/build/outputs/apk/release/output-metadata.json").read_text())
    element = metadata["elements"][0]
    if metadata["applicationId"] != "win.solobot.omodash":
        raise RuntimeError("Refusing to publish a debug or update-test package")
    if element["versionCode"] != code or element["versionName"] != version:
        raise RuntimeError("APK version does not match the release")
    source = ROOT / "app/build/outputs/apk/release" / element["outputFile"]
    tools = sdk_build_tools()
    env = java_env()
    verification = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(source),
                       capture=True, env=env)
    fingerprints = signing_fingerprints(verification)
    if not fingerprints:
        raise RuntimeError("Cannot read APK signing certificate")
    expected = (ROOT / "keystore/certificate-sha256.txt").read_text().strip()
    if fingerprints != {expected.lower()}:
        raise RuntimeError("APK is not signed with the permanent release key")
    badging = run(str(tools / "aapt"), "dump", "badging", str(source), capture=True)
    minimum = re.search(r"sdkVersion:'(\d+)'", badging)
    if not minimum:
        raise RuntimeError("Cannot read the APK's minimum Android version")
    out = ROOT / "release" / f"v{version}"
    out.mkdir(parents=True, exist_ok=True)
    apk = out / "omodash.apk"
    shutil.copy2(source, apk)
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    manifest = {
        "schemaVersion": 1, "applicationId": metadata["applicationId"],
        "versionCode": code, "versionName": version, "minSdk": int(minimum.group(1)),
        "apkUrl": f"https://github.com/{REPO}/releases/download/v{version}/omodash.apk",
        "sha256": sha, "size": apk.stat().st_size, "certificateSha256": expected,
        "commit": run("git", "rev-parse", "HEAD", capture=True).strip(),
    }
    (out / "update.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (out / "SHA256SUMS.txt").write_text(f"{sha}  omodash.apk\n")
    mapping = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    if not mapping.is_file():
        raise RuntimeError("Missing R8 mapping: release optimization did not run")
    shutil.copy2(mapping, out / "mapping.txt")
    bundle = usb_bundle(out)
    with (out / "SHA256SUMS.txt").open("a") as checksums:
        checksums.write(f"{hashlib.sha256(bundle.read_bytes()).hexdigest()}  {bundle.name}\n")
    return out


@contextlib.contextmanager
def release_lock():
    # One publisher per checkout; protects version allocation from concurrent deploys.
    import fcntl
    folder = ROOT / ".release-work"
    folder.mkdir(exist_ok=True)
    with (folder / "deploy.lock").open("w") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another deploy is running in this checkout") from None
        yield


def deploy(args):
    with release_lock():
        releases = request(f"{API}/repos/{REPO}/releases?per_page=100") or []
        codes = []
        # Read published manifests, never infer Android versionCode from a tag string.
        for release in releases:
            if release.get("draft") or release.get("prerelease"):
                continue
            asset = next((a for a in release.get("assets", []) if a["name"] == "update.json"), None)
            if asset:
                manifest = request(asset["browser_download_url"])
                codes.append(int(manifest["versionCode"]))
        local = properties(ROOT / "version.properties")
        version, code = choose_version(args.version, local, releases, max(codes, default=0))
        print(f"Release v{version}, Android versionCode {code}", flush=True)
        auth = None
        if not args.dry_run:
            auth = token()
            if not auth:
                raise RuntimeError("GitHub login is required. Run gh auth login, or set GH_TOKEN in your local shell.")
            repo = request(f"{API}/repos/{REPO}", auth=auth)
            if not repo.get("permissions", {}).get("push"):
                raise RuntimeError("GitHub credential does not have write access to the repository")
            if run("git", "status", "--porcelain", capture=True).strip():
                raise RuntimeError("Commit the source changes before deploy; a release must identify its exact source commit")
            if run("git", "branch", "--show-current", capture=True).strip() != "main":
                raise RuntimeError("Publish releases from the main branch")
            run("git", "fetch", "origin", "main", "--tags", env=git_env(auth))
            run("git", "merge-base", "--is-ancestor", "origin/main", "HEAD")
            desired = f"versionCode={code}\nversionName={version}\n"
            if (ROOT / "version.properties").read_text() != desired:
                (ROOT / "version.properties").write_text(desired)
                run("git", "add", "version.properties")
                run("git", "commit", "-m", f"Release v{version} ({code})")
        run(sys.executable, "-m", "unittest", "discover", "-s", "tools/tests", "-v")
        run(str(ROOT / "gradlew"), "--project-cache-dir", str(ROOT / ".release-work/gradle-cache"),
            ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease", f"-PreleaseVersion={version}",
            f"-PreleaseCode={code}", env=java_env())
        out = prepare_assets(version, code)
        if args.dry_run:
            print(f"Dry run complete: {out}. No commits, tags, pushes or GitHub releases were created.")
            return
        tag = f"v{version}"
        existing = request(f"{API}/repos/{REPO}/releases/tags/{tag}", auth=auth)
        if existing and not existing["draft"]:
            raise RuntimeError("Release is already published; published assets are never overwritten")
        head = run("git", "rev-parse", "HEAD", capture=True).strip()
        tag_result = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"],
                                    cwd=ROOT, capture_output=True, text=True)
        if tag_result.returncode == 0:
            if tag_result.stdout.strip() != head:
                raise RuntimeError("Release tag already points to a different commit")
        else:
            run("git", "tag", "-a", tag, "-m", f"Omodash {version}")
        run("git", "push", "--atomic", "origin", "HEAD:refs/heads/main", f"refs/tags/{tag}", env=git_env(auth))
        notes = Path(args.notes_file).read_text() if args.notes_file else f"Omodash {version}\n\nAndroid versionCode: {code}\nSource commit: {head}\n"
        release = existing or request(f"{API}/repos/{REPO}/releases", auth=auth, method="POST", payload={
            "tag_name": tag, "target_commitish": head, "name": f"Omodash {version}",
            "body": notes, "draft": True, "prerelease": False,
        })
        uploaded = {a["name"]: a for a in release.get("assets", [])}
        for name in ("omodash.apk", "update.json", "SHA256SUMS.txt", "mapping.txt", "omodash-usb-tools.zip"):
            data = (out / name).read_bytes()
            if name in uploaded:
                if uploaded[name].get("digest") != "sha256:" + hashlib.sha256(data).hexdigest():
                    raise RuntimeError(f"Draft already contains a different {name}; refusing to overwrite it")
                continue
            upload = release["upload_url"].split("{", 1)[0] + "?name=" + urllib.parse.quote(name)
            request(upload, auth=auth, method="POST", binary=data, content_type="application/octet-stream")
        published = request(release["url"], auth=auth, method="PATCH", payload={"draft": False, "make_latest": "true"})
        print(f"Published: {published['html_url']}")
        print(f"APK: https://github.com/{REPO}/releases/download/{tag}/omodash.apk")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["deploy"])
    parser.add_argument("--version", help="Human version MAJOR.MINOR.PATCH; omitted = next patch")
    parser.add_argument("--notes-file", help="UTF-8 release notes file")
    parser.add_argument("--dry-run", action="store_true", help="Build and validate locally without publishing")
    args = parser.parse_args()
    try:
        deploy(args)
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
