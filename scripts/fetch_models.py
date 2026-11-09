#!/usr/bin/env python3
"""Fetch pinned model assets for the OFFLINE app; never runs on the phone."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
import tempfile
import shutil

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "models.lock.json"


def prepare_ocr_charset(spec, destination):
    target = destination / spec["name"]
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == spec["sha256"]:
        print(f"Verified {target.name}")
        return
    import yaml
    config = spec["config"]
    with urllib.request.urlopen(config["url"], timeout=120) as response:
        source = response.read()
    if hashlib.sha256(source).hexdigest() != config["sha256"]:
        raise SystemExit("OCR recognizer config checksum mismatch")
    characters = [""] + yaml.safe_load(source)["PostProcess"]["character_dict"] + [" "]
    encoded = json.dumps(characters, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if hashlib.sha256(encoded).hexdigest() != spec["sha256"]:
        raise SystemExit("OCR charset checksum mismatch")
    temporary = target.with_suffix(".download")
    temporary.write_bytes(encoded)
    temporary.replace(target)
    print(f"Prepared and verified {target.name}")


def fetch_description(destination, source):
    spec = json.loads((ROOT / "description-model.lock.json").read_text())
    def matches(path, item):
        return path.is_file() and path.stat().st_size == item["bytes"] and hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"]

    files = spec["files"]
    if all(matches(destination / name, item) for name, item in files.items()):
        print("Verified Chinese-CLIP RN50 assets")
        return
    if source is None:
        raise SystemExit("Provide the RN50 test assets with --description-assets <directory>.")
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as folder:
        staging = Path(folder)
        for name, item in files.items():
            path = source / name
            if not path.is_file():
                raise SystemExit(f"Missing Chinese-CLIP RN50 asset: {path}")
            shutil.copyfile(path, staging / name)
            if not matches(staging / name, item):
                raise SystemExit(f"Chinese-CLIP RN50 asset checksum mismatch: {name}")
        for name in files:
            (staging / name).replace(destination / name)
    print("Installed and verified Chinese-CLIP RN50 assets")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edition", choices=("lite", "standard"), default="standard")
    parser.add_argument("--description-assets", type=Path, help="directory with the prepared RN50 ONNX assets")
    args = parser.parse_args()
    if args.edition == "standard":
        fetch_description(ROOT / "app/src/standard/assets/models", args.description_assets)
    manifest = json.loads(MANIFEST.read_text())
    destination = ROOT / "app/src/main/assets/models"
    destination.mkdir(parents=True, exist_ok=True)
    for item in manifest["files"]:
        target = destination / item["name"]
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == item["sha256"]:
            print(f"Verified {target.name}")
            continue
        temporary = target.with_suffix(target.suffix + ".download")
        with urllib.request.urlopen(item["url"], timeout=120) as source, temporary.open("wb") as output:
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != item["sha256"]:
            temporary.unlink(missing_ok=True)
            raise SystemExit(f"Checksum mismatch: {item['name']}")
        temporary.replace(target)
        print(f"Downloaded and verified {target.name}")

    prepare_ocr_charset(manifest["ocr_charset"], destination)

    # Keep only the pre-classifier feature output, using a pinned source graph.
    visual = json.loads((ROOT / "visual-model.lock.json").read_text())
    target = destination / visual["name"]
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == visual.get("asset_sha256"):
        print(f"Verified {target.name}")
        return
    try:
        import onnx
    except ImportError:
        raise SystemExit("Install build tools: python3 -m pip install -r scripts/model-requirements.txt")
    with tempfile.TemporaryDirectory() as folder:
        source = Path(folder) / "source.onnx"
        with urllib.request.urlopen(visual["url"], timeout=120) as response, source.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        if hashlib.sha256(source.read_bytes()).hexdigest() != visual["sha256"]:
            raise SystemExit("Visual model source checksum mismatch")
        extracted = Path(folder) / "features.onnx"
        onnx.utils.extract_model(str(source), str(extracted), ["pixel_values"], [visual["output"]])
        if hashlib.sha256(extracted.read_bytes()).hexdigest() != visual["asset_sha256"]:
            raise SystemExit("Visual model extraction mismatch; use pinned onnx version")
        target.write_bytes(extracted.read_bytes())
        print(f"Extracted and verified {target.name}")


if __name__ == "__main__":
    main()
