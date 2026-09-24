# Qwen HuggingFace tokenizer JNI

This crate builds the experimental `libqwen_tokenizer.so` used by the Android
Qwen3 forced-alignment path. It embeds the HuggingFace `tokenizers` Rust crate
and loads the official Qwen tokenizer assets. It accepts `tokenizer.json` when a converted bundle
contains one, and otherwise reconstructs the ByteLevel BPE from `vocab.json`, `merges.txt` and
`tokenizer_config.json` (the format published by the current Qwen3 repositories).

The current JNI contract returns tokenizer ids, timestamp placeholder positions,
and the text units used by the experimental aligner. The forced-aligner wrapper
is reproduced directly from Qwen's `Qwen3ForceAlignProcessor.encode_timestamp()`:
the audio start/pad/end tokens are prepended and every text unit is followed by
two `<timestamp>` tokens. The chat template is intentionally not used because
the official forced-aligner path passes this raw wrapper string to the tokenizer.

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
export ANDROID_NDK_HOME=/mnt/f/android-ndk/android-ndk-r28b
tools/build_qwen_tokenizer_android.sh
```

The helper builds `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` and copies the
libraries into `app/src/main/jniLibs/<abi>/`.

If the NDK is installed elsewhere, set `ANDROID_NDK_HOME` to that WSL path. The
helper does not modify the Windows Android SDK. When Windows Gradle packaging is
used, keep the generated `.so` files in the repository's `jniLibs` directories.
