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


def fetch_description(destination):
    spec = json.loads((ROOT / "description-model.lock.json").read_text())
    destination.mkdir(parents=True, exist_ok=True)
    mapping = [(spec["visionFile"], "cnclip-image-int8.onnx"), (spec["textFile"], "cnclip-text-int8.onnx"), ("vocab.txt", "cnclip-vocab.txt")]
    def metadata(name):
        return spec.get("derivedFiles", {}).get(name) or spec["files"][name]
    if all((destination / dst).exists() and hashlib.sha256((destination / dst).read_bytes()).hexdigest() == metadata(src)["sha256"] for src, dst in mapping):
        print("Verified Chinese-CLIP assets")
        return
    with tempfile.TemporaryDirectory() as folder:
        temporary = Path(folder)
        for src, dst in mapping:
            if src == "vocab.txt":
                with urllib.request.urlopen(f"https://huggingface.co/{spec['repository']}/resolve/{spec['revision']}/{src}", timeout=120) as response:
                    (destination / dst).write_bytes(response.read())
            else:
                import onnx
                source = temporary / "source.onnx"
                if not source.exists():
                    with urllib.request.urlopen(f"https://huggingface.co/{spec['repository']}/resolve/{spec['revision']}/onnx/model_quantized.onnx", timeout=180) as response, source.open("wb") as out:
                        shutil.copyfileobj(response, out)
                    if hashlib.sha256(source.read_bytes()).hexdigest() != spec["files"]["onnx/model_quantized.onnx"]["sha256"]:
                        raise SystemExit("Chinese-CLIP source checksum mismatch")
                image = src == spec["visionFile"]
                onnx.utils.extract_model(str(source), str(destination / dst), ["pixel_values"] if image else ["input_ids", "attention_mask"], ["image_embeds"] if image else ["text_embeds"])
            if hashlib.sha256((destination / dst).read_bytes()).hexdigest() != metadata(src)["sha256"]:
                raise SystemExit("Chinese-CLIP asset checksum mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edition", choices=("lite", "standard"), default="standard")
    edition = parser.parse_args().edition
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
        if edition == "standard":
            fetch_description(ROOT / "app/src/standard/assets/models")
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
    if edition == "standard":
        fetch_description(ROOT / "app/src/standard/assets/models")


if __name__ == "__main__":
    main()
