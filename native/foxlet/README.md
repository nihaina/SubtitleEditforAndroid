# Foxlet Android 8 构建

应用保留 [Foxlet v0.5.0](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0) 的 JVM SDK 和模型索引，只重新编译 ARM64 原生库。Android 上 Pathie 直接使用 UTF-8 路径；目标低于 API 28 时不编入 Foxlet 未使用的 `glob`/`dglob`，避免依赖 Android 9 新增的 libc 函数。

使用 NDK r28c（`28.2.13676358`，与应用相同）、CMake 3.31.6、Ninja 和 Python 3。从应用仓库根目录在 x86_64 Linux/WSL 运行，`ANDROID_NDK_HOME` 指向 Linux 版 NDK：

```bash
workdir=$(mktemp -d)
git clone --branch v0.5.0 --depth 1 https://github.com/yinvoke/foxlet-translate.git "$workdir/src"
git -C "$workdir/src" apply "$PWD/native/foxlet/android-api26.patch"

cmake -S "$workdir/src" -B "$workdir/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DBUILD_ARCH=armv8-a \
  -DCMAKE_BUILD_TYPE=Release -DCOMPILE_TESTS=OFF -DGIT_SUBMODULE=OFF
cmake --build "$workdir/build" --target foxlet

llvm_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$llvm_bin/llvm-strip" --strip-unneeded -o "$workdir/libfoxlet.so" \
  "$workdir/build/native/jni/libfoxlet.so"
curl -fL https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-v0.5.0.aar \
  -o "$workdir/upstream.aar"
python3 native/foxlet/package_aar.py "$workdir/upstream.aar" "$workdir/libfoxlet.so" \
  app/libs/foxlet-v0.5.0.aar
```

脚本校验原版 AAR 的 SHA-256，保留上游 `classes.jar` 与随附许可证，将清单最低版本改为 API 26，并省去重复的 `libc++_shared.so`，沿用应用现有的共享运行库。
