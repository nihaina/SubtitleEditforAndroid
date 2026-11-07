#!/usr/bin/env bash
# FFmpegKit recipe: subtitle fonts use TTF/OTF, so WOFF2/Brotli is unnecessary.
export LIBPNG_CFLAGS="-I${LIB_INSTALL_BASE}/libpng/include"
export LIBPNG_LIBS="-L${LIB_INSTALL_BASE}/libpng/lib"

make distclean >/dev/null 2>&1 || true

if [[ ! -f "${BASEDIR}/src/${LIB_NAME}/builds/unix/configure" ]] || [[ ${RECONF_freetype} -eq 1 ]]; then
  ./autogen.sh || return 1
fi

./configure \
  --prefix="${LIB_INSTALL_PREFIX}" \
  --with-pic \
  --with-zlib \
  --with-png \
  --without-brotli \
  --with-sysroot="${ANDROID_SYSROOT}" \
  --without-harfbuzz \
  --without-bzip2 \
  --without-fsref \
  --without-quickdraw-toolbox \
  --without-quickdraw-carbon \
  --without-ats \
  --enable-static \
  --disable-shared \
  --disable-fast-install \
  --disable-mmap \
  --host="${HOST}" || return 1

make -j"$(get_cpu_count)" || return 1
make install || return 1
create_freetype_package_config "26.6.20" || return 1
# The upstream helper assumes WOFF2 support; keep pkg-config consistent with this build.
sed -i 's/Requires.private: zlib, libbrotlidec/Requires.private: zlib/' "${INSTALL_PKG_CONFIG_DIR}/freetype2.pc"
