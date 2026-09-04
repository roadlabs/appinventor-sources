#!/usr/bin/env python3
"""Copy App Inventor built-in templates into an offline-webapp artifact."""

from __future__ import annotations

import base64
import json
import shutil
import sys
import zipfile
from pathlib import Path


def fail(message: str) -> int:
    print(f"error: {message}", file=sys.stderr)
    return 1


def validate_archive(path: Path) -> str | None:
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                return "contains a file with a failed CRC"
            names = [name.rstrip("/") for name in archive.namelist() if not name.endswith("/")]
    except (OSError, zipfile.BadZipFile) as exc:
        return str(exc)
    if "youngandroidproject/project.properties" not in names:
        return "is missing youngandroidproject/project.properties"
    if not any(name.endswith(".scm") for name in names):
        return "contains no .scm form file"
    if not any(name.endswith(".bky") for name in names):
        return "contains no .bky blocks file"
    return None


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} <source-templates-dir> <output-templates-dir>", file=sys.stderr)
        return 2

    source = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    if not source.is_dir():
        return fail(f"template source directory does not exist: {source}")

    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    descriptors: list[dict] = []
    for template_dir in sorted(p for p in source.iterdir() if p.is_dir()):
        name = template_dir.name
        descriptor_path = template_dir / f"{name}.json"
        archive_path = template_dir / f"{name}.zip"
        if not descriptor_path.is_file():
            print(f"warning: skipping {name}: missing {descriptor_path.name}", file=sys.stderr)
            continue
        if not archive_path.is_file():
            return fail(f"template {name} is missing {archive_path.name}")
        archive_error = validate_archive(archive_path)
        if archive_error:
            return fail(f"template {name} archive {archive_path.name} {archive_error}")
        try:
            descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            return fail(f"cannot parse {descriptor_path}: {exc}")
        if not isinstance(descriptor, dict) or not isinstance(descriptor.get("name"), str):
            return fail(f"invalid template descriptor: {descriptor_path}")
        if descriptor["name"] != name:
            return fail(f"descriptor name {descriptor['name']!r} does not match directory {name!r}")
        descriptors.append(descriptor)

    if not descriptors:
        return fail(f"no valid built-in templates found under {source}")

    for item in source.iterdir():
        destination = output / item.name
        if item.is_dir():
            shutil.copytree(item, destination)
        elif item.name != "templates.json":
            shutil.copy2(item, destination)

    (output / "templates.json").write_text(
        json.dumps(descriptors, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    # XHR/fetch of file:// URLs is blocked by Chromium, but script tags are
    # still allowed when the offline app is opened directly from disk. Embed
    # the archives in a small JS lookup table so template import works in both
    # file-origin browsers and the Tauri shell.
    bundled_archives = {}
    for descriptor in descriptors:
        name = descriptor["name"]
        archive = output / name / f"{name}.zip"
        bundled_archives[name] = base64.b64encode(archive.read_bytes()).decode("ascii")
    (output / "template-data.js").write_text(
        "window.__AI2_OFFLINE_TEMPLATES__ = "
        + json.dumps(bundled_archives, separators=(",", ":"))
        + ";\n",
        encoding="utf-8",
    )
    print(f"packaged {len(descriptors)} built-in templates at {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
