#!/usr/bin/env python3
"""Compare an ONNX export against official Qwen preprocessing and inference.

Example (local copy of the official Chinese demo):
  python tools/verify_qwen3_forced_aligner_onnx.py --model MODEL_DIR \
    --onnx forced_aligner.onnx --audio asr_zh.wav \
    --text "甚至出现交易几乎停滞的情况。" --language Chinese
"""

from __future__ import annotations

import argparse
import gc
import json
from pathlib import Path

import numpy as np
import torch

from export_qwen3_forced_aligner_onnx import INPUT_NAMES, ThinkerForExport, verify_onnx


def main():
    from qwen_asr import Qwen3ForcedAligner
    from qwen_asr.inference.utils import normalize_audios

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--onnx", required=True, type=Path)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--text", required=True)
    parser.add_argument("--language", required=True)
    parser.add_argument("--threads", default=4, type=int)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.threads < 1:
        parser.error("threads must be positive")
    torch.set_num_threads(args.threads)
    report_path = args.report or args.onnx.with_suffix(".audio-validation.json")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    case_path = report_path.with_suffix(".npz")

    print("Loading original weights and official audio/text processors...", flush=True)
    aligner = Qwen3ForcedAligner.from_pretrained(
        args.model, dtype=torch.float32, device_map="cpu", attn_implementation="eager"
    )
    thinker = aligner.model.thinker.eval()
    for module in thinker.modules():
        config = getattr(module, "config", None)
        if config is not None:
            config._attn_implementation = "eager"
    units, wrapper = aligner.aligner_processor.encode_timestamp(args.text, args.language)
    audio = normalize_audios(args.audio)
    inputs = aligner.processor(text=[wrapper], audio=audio, return_tensors="pt", padding=True)
    # WhisperFeatureExtractor may return int32 masks on Windows. The graph's
    # public contract (and Android OnnxTensor) uses int64 for every integer input.
    feeds = {name: inputs[name].to(torch.float32 if name == "input_features" else torch.int64)
             for name in INPUT_NAMES}
    report = {
        "audio": args.audio, "text": args.text, "language": args.language,
        "duration_seconds": len(audio[0]) / 16000,
        "input_shapes": {name: list(value.shape) for name, value in feeds.items()},
        "timestamp_segment_time_ms": aligner.timestamp_segment_time,
        "reference_backend": "qwen-asr 0.0.6 / FP32 / eager",
    }
    with torch.inference_mode():
        reference = thinker(**feeds, use_cache=False).logits
        wrapped = ThinkerForExport(thinker)(**feeds)
        torch.testing.assert_close(wrapped, reference, rtol=2e-4, atol=2e-4)
        report["wrapper_max_abs_error"] = float((wrapped - reference).abs().max())
        selected = reference.argmax(-1)[feeds["input_ids"] == aligner.timestamp_token_id]
        timestamps = (selected * aligner.timestamp_segment_time).cpu().numpy()
        report["alignment_ms"] = aligner.aligner_processor.parse_timestamp(units, timestamps)
        np.savez(case_path, **{name: value.numpy() for name, value in feeds.items()}, logits=reference.numpy())
    print(f"Official reference: {report['duration_seconds']:.3f}s, {len(units)} units", flush=True)
    # Release PyTorch weights before loading the independent ONNX Runtime session.
    del aligner, thinker, module, reference, wrapped, inputs, feeds
    gc.collect()
    report["onnx_parity"] = verify_onnx(args.onnx, [case_path], args.threads)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    print(f"Verified: {report_path.resolve()}", flush=True)


if __name__ == "__main__":
    main()
