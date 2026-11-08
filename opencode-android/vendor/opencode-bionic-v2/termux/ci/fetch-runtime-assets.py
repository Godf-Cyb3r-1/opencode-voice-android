"""Fetch pinned runtime assets on the runner, before entering Termux."""
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

root, target = map(Path, sys.argv[1:3])
versions = json.loads((root / "versions.json").read_text())
version = versions["bunTermux"]["version"]
assets = {
    "bun.deb": f"https://github.com/bd-loser/bun-termux/releases/download/v{version}/bun_{version}_aarch64.deb",
    "librust_pty_arm64.so": versions["bunPty"]["assetUrl"],
    "opencode-pty": versions["ptyService"]["assetUrl"],
}
target.mkdir(parents=True, exist_ok=True)

for filename, url in assets.items():
    parsed = urlparse(url)
    parts = parsed.path.strip("/").split("/")
    if parsed.hostname != "github.com" or len(parts) != 6 or parts[2:4] != ["releases", "download"]:
        raise ValueError(f"Unsupported pinned release URL for {filename}")
    owner, repo, _, _, tag, name = map(unquote, parts)
    release_path = f"repos/{owner}/{repo}/releases/tags/{quote(tag, safe='')}"
    destination = target / filename
    partial = target / (filename + ".partial")
    for attempt in range(4):
        try:
            release = json.loads(subprocess.check_output(["gh", "api", release_path], timeout=60))
            asset = next(item for item in release["assets"] if item["name"] == name)
            digest = asset.get("digest")
            if not digest or not digest.startswith("sha256:"):
                raise ValueError(f"Missing published SHA-256 for {filename}")
            expected = digest.removeprefix("sha256:")
            def valid(path):
                if not path.is_file() or path.stat().st_size != asset["size"]:
                    return False
                with path.open("rb") as stream:
                    return hashlib.file_digest(stream, "sha256").hexdigest() == expected
            if not valid(destination):
                with partial.open("wb") as output:
                    subprocess.run(["gh", "api", f"repos/{owner}/{repo}/releases/assets/{asset['id']}",
                                    "-H", "Accept: application/octet-stream"],
                                   stdout=output, check=True, timeout=240)
                if not valid(partial):
                    raise ValueError(f"Asset verification failed for {filename}")
                partial.replace(destination)
            destination.chmod(0o644)
            print(f"Verified {filename} sha256:{expected}", flush=True)
            break
        except (subprocess.SubprocessError, OSError, ValueError, StopIteration) as error:
            partial.unlink(missing_ok=True)
            if attempt == 3:
                raise
            print(f"Retrying {filename}: {type(error).__name__}", flush=True)
            time.sleep(3)
