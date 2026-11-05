#!/usr/bin/env bash
# FFmpegKit custom-library recipe. Called with the current ABI's toolchain environment.
set -e
make clean >/dev/null 2>&1 || true
make -j"$(get_cpu_count)" lib/libgsm.a \
  CC="${CC}" AR="${AR}" RANLIB="${RANLIB}" \
  CCFLAGS="-c ${CFLAGS} -fPIC -DNeedFunctionPrototypes=1" || return 1
mkdir -p "${LIB_INSTALL_PREFIX}/lib" "${LIB_INSTALL_PREFIX}/include/gsm" "${INSTALL_PKG_CONFIG_DIR}"
cp lib/libgsm.a "${LIB_INSTALL_PREFIX}/lib/"
cp inc/gsm.h "${LIB_INSTALL_PREFIX}/include/"
cp inc/gsm.h "${LIB_INSTALL_PREFIX}/include/gsm/"
cat > "${INSTALL_PKG_CONFIG_DIR}/gsm.pc" <<EOF
prefix=${LIB_INSTALL_PREFIX}
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: gsm
Description: GSM 06.10 speech codec
Version: 1.0.16
Libs: -L\${libdir} -lgsm
Cflags: -I\${includedir}
EOF
