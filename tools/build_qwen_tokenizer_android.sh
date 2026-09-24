#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
crate_dir="$repo_root/native/qwen-tokenizer"
output_dir="$repo_root/app/src/main/jniLibs"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk is required; install it with: cargo install cargo-ndk" >&2
  exit 1
fi

ndk_home="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk_home" || ! -d "$ndk_home" ]]; then
  echo "ANDROID_NDK_HOME must point to an Android NDK directory visible in WSL" >&2
  exit 1
fi

export ANDROID_NDK_HOME="$ndk_home"
export CARGO_REGISTRIES_CRATES_IO_PROTOCOL="sparse"

# Rust panic locations can retain absolute source paths even in release binaries.
# Encode arguments separately so workspace/toolchain paths may contain spaces.
encoded_rustflags="${CARGO_ENCODED_RUSTFLAGS:-}"
if [[ -z "${CARGO_ENCODED_RUSTFLAGS+x}" ]]; then
  read -r -a inherited_rustflags <<< "${RUSTFLAGS:-}"
  for flag in "${inherited_rustflags[@]}"; do
    encoded_rustflags+="${encoded_rustflags:+$'\x1f'}$flag"
  done
fi
for mapping in \
  "${HOME}=/build-home" \
  "${CARGO_HOME:-${HOME}/.cargo}=/cargo" \
  "${RUSTUP_HOME:-${HOME}/.rustup}=/rustup" \
  "$ndk_home=/android-ndk" \
  "$repo_root=/src/subtitleedit"; do
  encoded_rustflags+="${encoded_rustflags:+$'\x1f'}--remap-path-prefix=$mapping"
done
export CARGO_ENCODED_RUSTFLAGS="$encoded_rustflags"

mkdir -p "$output_dir"
cd "$crate_dir"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  echo "Building qwen_tokenizer for $abi"
  cargo ndk -t "$abi" -o "$output_dir" build --release
done

echo "Built libraries:"
find "$output_dir" -mindepth 2 -maxdepth 2 -name 'libqwen_tokenizer.so' -print
