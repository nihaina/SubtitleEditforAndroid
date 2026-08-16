# Introduction

Note that if you use Android Studio, then you only need to
copy libonnxruntime.so and libsherpa-onnx-jni.so
to your jniLibs, and you don't need libsherpa-onnx-c-api.so or
libsherpa-onnx-cxx-api.so.

libsherpa-onnx-c-api.so and libsherpa-onnx-cxx-api.so are for users
who don't use JNI. In that case, libsherpa-onnx-jni.so is not needed.

In any case, libonnxruntime.so is always needed.

## Qualcomm QNN support

`libsherpa-onnx-jni.so` is built from sherpa-onnx v1.13.5 with
`SHERPA_ONNX_ENABLE_QNN=ON` and ONNX Runtime 1.27.1.

The QNN 2.40.0.251030 runtime libraries are taken from the upstream
`asr-models-qnn` release. V68, V69, V73, V75, V79, and V81 HTP Stub/Skel
libraries are packaged so the runtime can select the architecture supported by
the current Snapdragon device.
