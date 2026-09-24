# Qwen HuggingFace tokenizer JNI

This crate builds the experimental `libqwen_tokenizer.so` used by the Android
Qwen3 forced-alignment path. It embeds the HuggingFace `tokenizers` Rust crate
and loads the official Qwen tokenizer assets. It accepts `tokenizer.json` when a converted bundle
contains one, and otherwise reconstructs the ByteLevel BPE from `vocab.json`, `merges.txt` and
`tokenizer_config.json` (the format published by the current Qwen3 repositories).

The app's ASR tokenizer ends at `<asr_text>=151704`, while the official
ForcedAligner tokenizer adds `<timestamp>=151705`. For this compatible ASR
vocabulary, initialization adds the official non-special, non-normalized timestamp
token to the private in-memory tokenizer. Existing ASR files are never changed.
Both loading paths check the official audio/timestamp IDs and reject occupied or
incompatible IDs instead of silently assigning a different timestamp ID. JNI
passes initialization/encoding errors to Kotlin with their original explanation.
The app opens this tokenizer before starting recognition so incompatible assets
fail before ASR and ONNX model loading.

The current JNI contract returns tokenizer ids, timestamp placeholder positions,
and the text units used by the experimental aligner. The forced-aligner wrapper
is reproduced directly from Qwen's `Qwen3ForceAlignProcessor.encode_timestamp()`:
the audio start/pad/end tokens are prepended and every text unit is followed by
two `<timestamp>` tokens. The chat template is intentionally not used because
the official forced-aligner path passes this raw wrapper string to the tokenizer.

JNI returns a single audio-pad token. After log-mel extraction, Kotlin's
`Qwen3ForcedAlignmentInput` expands it to `(frames / 100) * 13 + ceil((frames % 100) / 8)`,
shifts every timestamp position by the inserted token count, and the caller builds
the attention mask using the expanded sequence length. No JNI ABI change is needed.

Chinese mixed text follows the official character/word split. Japanese and
Korean use a dependency-free fallback (whitespace and script runs) instead of
the optional Python `nagisa`/`soynlp` packages; validate those languages against
the desktop processor before using the result for measurements.

## Build in WSL

The repository includes a Cargo source replacement at `.cargo/config.toml`; it
uses the rsproxy sparse registry so a fresh build does not depend on the default
crates.io index. Install Rust, `cargo-ndk`, and the Android targets in WSL, then
run the repository helper from WSL:

```bash
export ANDROID_NDK_HOME="/path/to/android-ndk-r28b"
tools/build_qwen_tokenizer_android.sh
```

The helper builds `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` and copies the
libraries into `app/src/main/jniLibs/<abi>/`.

The helper remaps Rust source paths to generic prefixes before compilation,
including dependency paths used in panic messages. This keeps local usernames,
workspace paths and toolchain locations out of the generated Rust library.
Keep build logs and personal environment notes in ignored local directories.

Run the regression tests with the actual downloaded ASR assets, then with the
official ForcedAligner assets (substitute each directory below):

```bash
cd native/qwen-tokenizer
QWEN_TOKENIZER_DIR=/path/to/tokenizer cargo test
```

The fixture test compares every raw token ID and timestamp position against
the official Python processor outputs in
`app/src/test/resources/qwen3_forced_alignment_inputs.json`.

Replace the example NDK path with its actual WSL-visible location. The
helper does not modify the Windows Android SDK. When Windows Gradle packaging is
used, keep the generated `.so` files in the repository's `jniLibs` directories.
