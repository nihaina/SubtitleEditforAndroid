#!/usr/bin/env bash
set -eu

ndk="${1:?NDK path required}"
prebuilt="$ndk/toolchains/llvm/prebuilt"
windows="$prebuilt/windows-x86_64"
linux="$prebuilt/linux-x86_64"
mkdir -p "$linux/bin"
for directory in lib lib64 include sysroot; do
  ln -sfn "../windows-x86_64/$directory" "$linux/$directory"
done
for tool in clang clang++ clang-18 clang++-18 ld.lld llvm-ar llvm-ranlib llvm-strip ar ranlib strip; do
  cat > "$linux/bin/$tool" <<EOF
#!/bin/sh
exec "$windows/bin/$tool.exe" "\$@"
EOF
  chmod +x "$linux/bin/$tool"
done
