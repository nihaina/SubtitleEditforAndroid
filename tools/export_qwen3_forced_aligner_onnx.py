#!/usr/bin/env python3
"""Export official Qwen3-ForcedAligner weights with dynamic audio/text lengths.

Requires qwen-asr==0.0.6 (transformers==4.57.6), torch, onnx and onnxruntime.
Use the original Qwen/Qwen3-ForcedAligner-0.6B checkpoint for this backend;
the -hf checkpoint uses a different Transformers-native implementation.

Example:
  python tools/export_qwen3_forced_aligner_onnx.py \
    --model build/qwen3-forced-aligner/official \
    --output build/qwen3-forced-aligner/forced_aligner.onnx

FP32 weights exceed ONNX's 2 GiB protobuf limit. This writes an ONNX graph and
an adjacent .onnx.data file, plus reproducible parity cases and a JSON report.
Both model files must stay together. Tokenization and log-mel remain outside
the graph, as in the official processor. Batch size is fixed at one.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

INPUT_NAMES = ["input_ids", "input_features", "attention_mask", "feature_attention_mask"]


def feature_output_length(frames):
    return ((frames % 100 + 7) // 8) + (frames // 100) * 13


class ThinkerForExport(torch.nn.Module):
    """Tensor-only, batch-one equivalent of the official eager thinker.

    The official audio encoder's .tolist(), loops over variable chunks, and
    pad_sequence cannot be traced with dynamic lengths. Gather/pad the same
    chunks here, then reuse the original convolutions and layers.
    Eager/SDPA in qwen-asr 0.0.6 uses full audio self-attention (no block mask).
    This follows that backend, not FlashAttention 2.
    """

    def __init__(self, thinker):
        super().__init__()
        self.thinker = thinker
        self.chunk_size = thinker.audio_tower.n_window * 2
        if self.chunk_size != 100:
            raise ValueError("Expected official 100-frame convolution chunks")
        if thinker.model.rotary_emb.rope_type != "default":
            raise ValueError("Only the official default RoPE is supported")

    def audio_features(self, features, feature_mask):
        tower = self.thinker.audio_tower
        length = feature_mask[0].sum()
        width = torch.minimum(length, length.new_tensor(self.chunk_size))
        starts = torch.arange(0, length, self.chunk_size, device=features.device)
        offsets = torch.arange(width, device=features.device)
        indices = starts[:, None] + offsets[None, :]
        valid = indices < length
        gathered = features[0, :, indices.clamp(max=length - 1)]
        chunks = gathered.permute(1, 0, 2) * valid[:, None, :].to(features.dtype)
        chunks = F.gelu(tower.conv2d1(chunks.unsqueeze(1)))
        chunks = F.gelu(tower.conv2d2(chunks))
        chunks = F.gelu(tower.conv2d3(chunks))
        batch, channels, bins, time = chunks.shape
        hidden = tower.conv_out(chunks.permute(0, 3, 1, 2).reshape(batch, time, channels * bins))
        hidden = hidden + tower.positional_embedding.positional_embedding[:time].to(hidden.dtype)
        chunk_lengths = (length - starts).clamp(max=self.chunk_size)
        cnn_lengths = (chunk_lengths + 7) // 8
        cnn_mask = torch.arange(time, device=features.device)[None, :] < cnn_lengths[:, None]
        hidden = hidden[cnn_mask]
        # cu_seqlens is only consumed by FlashAttention; eager ignores it.
        cumulative = torch.stack((length.new_zeros(()), cnn_lengths.sum())).to(torch.int32)
        for layer in tower.layers:
            hidden = layer(hidden, cu_seqlens=cumulative)[0]
        return tower.proj2(tower.act(tower.proj1(tower.ln_post(hidden))))

    def forward(self, input_ids, input_features, attention_mask, feature_attention_mask):
        audio = self.audio_features(input_features, feature_attention_mask)
        text_model = self.thinker.model
        hidden = text_model.embed_tokens(input_ids)
        audio_mask = input_ids == self.thinker.config.audio_token_id
        # Avoid masked_scatter's scalar expansion of [sequence, hidden] indices.
        audio_index = (audio_mask.long().cumsum(-1) - 1).clamp(min=0)
        audio_index = torch.minimum(audio_index, audio_index.new_ones(()) * (audio.shape[0] - 1))
        hidden = torch.where(audio_mask.unsqueeze(-1), audio[audio_index], hidden)

        positions = (attention_mask.float().cumsum(-1) - 1).masked_fill(attention_mask == 0, 1)
        rotary = text_model.rotary_emb
        # All three official mRoPE axes are identical for this audio/text input.
        angles = positions.unsqueeze(-1) * rotary.inv_freq[None, None, :].float()
        angles = torch.cat((angles, angles), dim=-1)
        cos_sin = (angles.cos() * rotary.attention_scaling, angles.sin() * rotary.attention_scaling)
        order = torch.arange(input_ids.shape[1], device=input_ids.device)
        allowed = (order[None, :] <= order[:, None])[None, None, :, :]
        allowed = allowed & attention_mask[:, None, None, :].bool()
        mask = torch.zeros_like(allowed, dtype=hidden.dtype).masked_fill(~allowed, torch.finfo(hidden.dtype).min)
        for layer in text_model.layers:
            hidden = layer(hidden, position_embeddings=cos_sin, attention_mask=mask, use_cache=False)
        return self.thinker.lm_head(text_model.norm(hidden)).float()


def make_inputs(aligner, frames, text="Hello world", language="English", padded_frames=0):
    _, wrapper = aligner.aligner_processor.encode_timestamp(text, language)
    count = feature_output_length(frames)
    wrapper = wrapper.replace("<|audio_pad|>", "<|audio_pad|>" * count)
    ids = aligner.processor.tokenizer(wrapper, return_tensors="pt")["input_ids"]
    generator = torch.Generator().manual_seed(frames)
    features = torch.randn(1, 128, frames + padded_frames, generator=generator) * 0.3
    feature_mask = torch.ones(1, frames + padded_frames, dtype=torch.long)
    feature_mask[:, frames:] = 0
    return ids, features, torch.ones_like(ids), feature_mask


def externalize_parameters(graph_path, output, model):
    """Attach weights one tensor at a time, avoiding a >2 GiB protobuf in RAM."""
    import onnx

    graph = onnx.load(str(graph_path), load_external_data=False)
    tensors = dict(model.named_parameters())
    tensors.update(dict(model.named_buffers()))
    data_path = output.with_suffix(output.suffix + ".data")
    retained_inputs = []
    offset = 0
    with data_path.open("wb") as stream:
        for value in graph.graph.input:
            if value.name in INPUT_NAMES:
                retained_inputs.append(value)
                continue
            tensor = tensors.get(value.name)
            if tensor is None:
                raise ValueError(f"Unresolved graph parameter: {value.name}")
            array = tensor.detach().cpu().contiguous().numpy()
            raw = array.tobytes()
            initializer = onnx.TensorProto()
            initializer.name = value.name
            initializer.dims.extend(array.shape)
            initializer.data_type = onnx.helper.np_dtype_to_tensor_dtype(array.dtype)
            initializer.data_location = onnx.TensorProto.EXTERNAL
            for key, item in (("location", data_path.name), ("offset", str(offset)), ("length", str(len(raw)))):
                entry = initializer.external_data.add()
                entry.key, entry.value = key, item
            stream.write(raw)
            offset += len(raw)
            graph.graph.initializer.append(initializer)
    del graph.graph.input[:]
    graph.graph.input.extend(retained_inputs)
    onnx.helper.set_model_props(graph, {
        "source_model": "Qwen/Qwen3-ForcedAligner-0.6B",
        "timestamp_segment_time_ms": "80", "timestamp_token_id": "151705",
        "batch_size": "1", "backend": "qwen-asr 0.0.6 eager",
        "audio_placeholder": "Expand audio_pad to ((frames%100+7)//8)+(frames//100)*13",
    })
    onnx.save_model(graph, str(output))
    onnx.checker.check_model(str(output))
    return data_path


def verify_onnx(output, case_paths, threads):
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    session = ort.InferenceSession(str(output), options, providers=["CPUExecutionProvider"])
    report = {"onnxruntime": ort.__version__, "inputs": [x.name for x in session.get_inputs()], "cases": []}
    assert report["inputs"] == INPUT_NAMES, report["inputs"]
    for path in case_paths:
        case = np.load(path)
        actual = session.run(["logits"], {name: case[name] for name in INPUT_NAMES})[0]
        expected = case["logits"]
        np.testing.assert_allclose(actual, expected, rtol=2e-3, atol=3e-3)
        timestamp_mask = case["input_ids"] == 151705
        selected = actual.argmax(-1)[timestamp_mask]
        reference = expected.argmax(-1)[timestamp_mask]
        item = {
            "case": path.name, "frames": int(case["feature_attention_mask"].sum()),
            "sequence": int(case["input_ids"].shape[1]),
            "max_abs_logit_error": float(np.abs(actual - expected).max()),
            "timestamp_ids_match": bool(np.array_equal(selected, reference)),
        }
        if not item["timestamp_ids_match"]:
            raise AssertionError(f"Timestamp classes differ: {item}")
        report["cases"].append(item)
        print(f"ONNX parity: {item}", flush=True)
    return report


def main():
    from qwen_asr import Qwen3ForcedAligner

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--frames", type=int, default=200, help="Trace example only; exported frame axis is dynamic")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--opset", type=int, default=18)
    args = parser.parse_args()
    if args.frames < 1 or args.threads < 1:
        parser.error("frames and threads must be positive")
    torch.set_num_threads(args.threads)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    print("Loading official checkpoint (FP32 / eager)...", flush=True)
    aligner = Qwen3ForcedAligner.from_pretrained(args.model, dtype=torch.float32, device_map="cpu", attn_implementation="eager")
    thinker = aligner.model.thinker.eval()
    # Nested audio/text configs in qwen-asr 0.0.6 otherwise default to SDPA,
    # even when the top-level from_pretrained receives eager.
    for module in thinker.modules():
        config = getattr(module, "config", None)
        if config is not None:
            config._attn_implementation = "eager"
    if "forced_aligner" not in thinker.config.model_type:
        raise ValueError("Expected ForcedAligner checkpoint, not ASR weights")
    model = ThinkerForExport(thinker).eval()
    cases = []
    with torch.inference_mode():
        for frames, text, language, padding in (
            (51, "Hello world", "English", 0),
            (200, "你好世界", "Chinese", 0),
            (337, "This is a longer alignment example", "English", 17),
        ):
            inputs = make_inputs(aligner, frames, text, language, padding)
            reference = thinker(**dict(zip(INPUT_NAMES, inputs)), use_cache=False).logits
            actual = model(*inputs)
            torch.testing.assert_close(actual, reference, rtol=2e-4, atol=2e-4)
            path = args.output.parent / f"parity_{frames}.npz"
            np.savez(path, **{key: value.numpy() for key, value in zip(INPUT_NAMES, inputs)}, logits=reference.numpy())
            cases.append(path)
            print(f"Official/wrapper parity: frames={frames}, max_error={(actual-reference).abs().max().item():.7g}", flush=True)
        example = make_inputs(aligner, args.frames)
        graph_path = args.output.with_suffix(".graph.onnx")
        print("Tracing dynamic graph without embedding weights...", flush=True)
        torch.onnx.export(
            model, example, str(graph_path), export_params=False,
            input_names=INPUT_NAMES, output_names=["logits"],
            dynamic_axes={"input_ids": {1: "sequence"}, "input_features": {2: "frames"},
                          "attention_mask": {1: "sequence"}, "feature_attention_mask": {1: "frames"},
                          "logits": {1: "sequence"}},
            opset_version=args.opset, do_constant_folding=False, dynamo=False,
        )
        print("Writing official FP32 weights as external data...", flush=True)
        data_path = externalize_parameters(graph_path, args.output, model)
    del aligner, thinker, model, example, inputs, reference, actual
    gc.collect()
    print("Checking ONNX Runtime with different audio and text lengths...", flush=True)
    report = verify_onnx(args.output, cases, args.threads)
    report["model"] = args.model
    report["files"] = {}
    for path in (args.output, data_path):
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        report["files"][path.name] = {"bytes": path.stat().st_size, "sha256": digest}
    report_path = args.output.with_suffix(".validation.json")
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Export and validation complete: {args.output.resolve()}", flush=True)


if __name__ == "__main__":
    main()
