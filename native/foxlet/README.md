# Foxlet Android 多架构构建

应用保留 [Foxlet v0.5.0](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0) 的 JVM SDK 和模型索引，重新编译 API 26 原生库。Android 上 Pathie 直接使用 UTF-8 路径；API 26 不编入 Foxlet 未使用的 `glob`/`dglob`。

x86、x86_64 沿用 Foxlet 的 Ruy 和量化模型，无需 intgemm 或更换语言包。ARM32 需要 NEON，但不使用 ARM64 专有的量化指令。模型词汇短表使用固定的 64 位校验，避免 32 位进程把哈希值截断。

使用 NDK r28c（`28.2.13676358`，与应用相同）、CMake 3.31.6、Ninja 和 Python 3。从应用仓库根目录在 x86_64 Linux/WSL 运行，`ANDROID_NDK_HOME` 指向 Linux 版 NDK：

```bash
set -e
workdir=$(mktemp -d)
git clone --branch v0.5.0 --depth 1 https://github.com/yinvoke/foxlet-translate.git "$workdir/src"
git -C "$workdir/src" apply "$PWD/native/foxlet/android-api26.patch"
git -C "$workdir/src" apply "$PWD/native/foxlet/android-multiabi.patch"

llvm_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for spec in arm64-v8a:armv8-a armeabi-v7a:armv7-a x86:i686 x86_64:x86-64; do
  abi=${spec%:*}
  arch=${spec#*:}
  cmake -S "$workdir/src" -B "$workdir/$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DBUILD_ARCH="$arch" \
    -DCMAKE_BUILD_TYPE=Release -DCOMPILE_TESTS=OFF -DGIT_SUBMODULE=OFF
  cmake --build "$workdir/$abi" --target foxlet
  "$llvm_bin/llvm-strip" --strip-unneeded "$workdir/$abi/native/jni/libfoxlet.so"
done

curl -fL https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-v0.5.0.aar \
  -o "$workdir/upstream.aar"
python3 native/foxlet/package_aar.py "$workdir/upstream.aar" app/libs/foxlet-v0.5.0.aar \
  --arm64-v8a "$workdir/arm64-v8a/native/jni/libfoxlet.so" \
  --armeabi-v7a "$workdir/armeabi-v7a/native/jni/libfoxlet.so" \
  --x86 "$workdir/x86/native/jni/libfoxlet.so" \
  --x86_64 "$workdir/x86_64/native/jni/libfoxlet.so"
```

脚本校验原版 AAR 的 SHA-256 和各共享库的 ELF 架构，保留上游 `classes.jar` 与随附许可证，将清单最低版本改为 API 26，并沿用应用现有的 `libc++_shared.so`。

已安装对应 ABI 的调试 APK 和 Android 测试 APK 后，可在有非计费网络的测试设备上运行下列命令。测试下载并校验官方模型，再验证直接翻译和经英语中转的翻译；不会重命名文件。

```bash
adb shell am instrument -w -e foxletDeviceSmoke true \
  -e class com.subtitleedit.translation.FoxletPageTranslationDeviceTest \
  com.subtitleedit.test/androidx.test.runner.AndroidJUnitRunner
```
