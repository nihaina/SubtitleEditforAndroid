#!/usr/bin/env python3
"""Export the Qwen3-ForcedAligner thinker for the Android ONNX runner.

The official Qwen package publishes SafeTensors weights and a Transformers model. Android
cannot load that model directly, so this script exports the non-autoregressive token-classification
forward pass used by Qwen3ForcedAligner.align(). The tokenizer and the Qwen log-mel processor are
kept outside the graph and must produce the tensors documented in app/libs/README.md.

Example:
  python tools/export_qwen3_forced_aligner_onnx.py \
    --model Qwen/Qwen3-ForcedAligner-0.6B-hf \
    --output build/qwen3-forced-aligner/forced_aligner.onnx

The export is intentionally a separate step because it needs a desktop PyTorch installation and
the official qwen-asr package. The generated file is not checked into the Android repository.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch


class ThinkerForExport(torch.nn.Module):
    def __init__(self, thinker: torch.nn.Module) -> None:
        super().__init__()
        self.thinker = thinker

    def forward(
        self,
        input_ids: torch.Tensor,
        input_features: torch.Tensor,
        attention_mask: torch.Tensor,
        feature_attention_mask: torch.Tensor,
    ) -> torch.Tensor:
        output = self.thinker(
            input_ids=input_ids,
            input_features=input_features,
            attention_mask=attention_mask,
            feature_attention_mask=feature_attention_mask,
            use_cache=False,
            return_dict=True,
        )
        return output.logits


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Qwen3-ForcedAligner model directory or Hub id")
    parser.add_argument("--output", required=True, type=Path)
    # The default 3000 raw feature frames become 390 audio placeholder tokens in the
    # Qwen3 audio tower, so the dummy sequence must be longer than that wrapper.
    parser.add_argument("--sequence-length", type=int, default=512)
    parser.add_argument("--frames", type=int, default=3000)
    parser.add_argument("--mel-bins", type=int, default=128)
    parser.add_argument("--opset", type=int, default=18)
    args = parser.parse_args()

    try:
        from qwen_asr import Qwen3ForcedAligner
    except ImportError as exc:  # pragma: no cover - desktop export helper
        raise SystemExit(
            "请先安装官方依赖：pip install 'qwen-asr[transformers]'"
        ) from exc

    if args.sequence_length <= 0 or args.frames <= 0 or args.mel_bins <= 0:
        raise SystemExit("sequence-length、frames 和 mel-bins 必须为正数")

    try:
        aligner = Qwen3ForcedAligner.from_pretrained(
            args.model,
            dtype=torch.float32,
            device_map="cpu",
        )
    except Exception as exc:
        raise SystemExit(
            f"无法加载 Qwen3-ForcedAligner 模型 {args.model!r}：{exc}"
        ) from exc
    thinker = getattr(getattr(aligner, "model", None), "thinker", None)
    if thinker is None:
        raise SystemExit(
            "加载的模型没有 model.thinker；请确认输入的是 Qwen3-ForcedAligner，"
            "而不是普通 Qwen3-ASR decoder。"
        )
    model = ThinkerForExport(thinker).eval().float()

    def feature_output_length(input_length: int) -> int:
        # Keep this in sync with qwen_asr.core.transformers_backend.processing_qwen3_asr.
        input_lengths_leave = input_length % 100
        feat_lengths = (input_lengths_leave - 1) // 2 + 1
        return ((feat_lengths - 1) // 2 + 1 - 1) // 2 + 1 + (input_length // 100) * 13

    audio_feature_length = feature_output_length(args.frames)
    if args.sequence_length < audio_feature_length + 2:
        raise SystemExit(
            "sequence-length 必须容纳音频 placeholder 和首尾标记；"
            f"当前至少需要 {audio_feature_length + 2}"
        )

    # The thinker checks that the number of <|audio_pad|> IDs matches the number of
    # downsampled audio features. A zero-filled dummy sequence therefore cannot be
    # exported: it would fail before torch.onnx.export sees the graph.
    audio_token_id = int(getattr(thinker.config, "audio_token_id", 151676))
    audio_start_token_id = int(getattr(thinker.config, "audio_start_token_id", 151669))
    audio_end_token_id = int(getattr(thinker.config, "audio_end_token_id", 151670))
    input_ids = torch.zeros((1, args.sequence_length), dtype=torch.long)
    input_ids[0, 0] = audio_start_token_id
    input_ids[0, 1 : 1 + audio_feature_length] = audio_token_id
    input_ids[0, 1 + audio_feature_length] = audio_end_token_id
    input_features = torch.zeros((1, args.mel_bins, args.frames), dtype=torch.float32)
    attention_mask = torch.ones((1, args.sequence_length), dtype=torch.long)
    # Qwen3ASRProcessor returns an int64 attention mask after WhisperFeatureExtractor.
    feature_attention_mask = torch.ones((1, args.frames), dtype=torch.long)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        (input_ids, input_features, attention_mask, feature_attention_mask),
        str(args.output),
        input_names=[
            "input_ids",
            "input_features",
            "attention_mask",
            "feature_attention_mask",
        ],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {1: "sequence"},
            "input_features": {2: "frames"},
            "attention_mask": {1: "sequence"},
            "feature_attention_mask": {1: "frames"},
            "logits": {1: "sequence"},
        },
        opset_version=args.opset,
        do_constant_folding=True,
    )
    print(f"已导出：{args.output}")
    # The official config stores this value in milliseconds (currently 80), while some
    # older qwen-asr releases exposed seconds through the wrapper. Normalize both forms.
    step = float(aligner.timestamp_segment_time)
    step_ms = step * 1000.0 if step < 1.0 else step
    print(f"timestamp_segment_time_ms={step_ms:g}")
    print(f"timestamp_token_id={aligner.timestamp_token_id}")


if __name__ == "__main__":
    main()
