cmake_minimum_required(VERSION 3.22.1)

# Some Windows NDK installations include a Unix shell script named `ld.lld`
# next to the actual `ld.lld.exe`. Point Clang at the executable explicitly.
if (CMAKE_HOST_WIN32 AND ANDROID_NDK)
    set(SEVENZIP_LLD "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/bin/ld.lld.exe")
    set(CMAKE_LINKER "${SEVENZIP_LLD}" CACHE FILEPATH "" FORCE)
    # The Android toolchain rewrites the *_INIT variables during its compiler
    # probe. Set the cache flags as well so a fresh Release configure uses the
    # executable linker instead of the extensionless ld.lld helper script.
    # The compiler probe itself consumes the *_INIT variables before the
    # project cache entries are available, so include the flag there too.
    set(CMAKE_C_FLAGS_INIT "-fuse-ld=${SEVENZIP_LLD}" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_INIT "-fuse-ld=${SEVENZIP_LLD}" CACHE STRING "" FORCE)
    set(CMAKE_EXE_LINKER_FLAGS "-fuse-ld=${SEVENZIP_LLD}" CACHE STRING "" FORCE)
    set(CMAKE_SHARED_LINKER_FLAGS "-fuse-ld=${SEVENZIP_LLD}" CACHE STRING "" FORCE)
    set(CMAKE_MODULE_LINKER_FLAGS "-fuse-ld=${SEVENZIP_LLD}" CACHE STRING "" FORCE)
    set(CMAKE_C_LINK_EXECUTABLE "<CMAKE_C_COMPILER> <FLAGS> <CMAKE_C_LINK_FLAGS> <LINK_FLAGS> -fuse-ld=${SEVENZIP_LLD} <OBJECTS> -o <TARGET> <LINK_LIBRARIES>" CACHE STRING "" FORCE)
    set(CMAKE_CXX_LINK_EXECUTABLE "<CMAKE_CXX_COMPILER> <FLAGS> <CMAKE_CXX_LINK_FLAGS> <LINK_FLAGS> -fuse-ld=${SEVENZIP_LLD} <OBJECTS> -o <TARGET> <LINK_LIBRARIES>" CACHE STRING "" FORCE)
endif()

project(subtitleedit_archive LANGUAGES C CXX)

# CMake's Android toolchain can accidentally prefer the extensionless helper
# script shipped beside ld.lld.exe on Windows. Force the real executable for
# every target, including llama.cpp's Threads probe.

# Text-only local LLM runtime. Build ggml backends as shared modules so
# llama.cpp can select the best CPU instruction-set variant at runtime. The
# submodule is pinned to the LMPlayground Android fork; SAF descriptors are
# exposed to llama.cpp as /proc/self/fd/N paths by the bound service.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

# llama.cpp normally redirects its shared/module outputs to its own
# ``${CMAKE_BINARY_DIR}/bin`` directory.  Android Gradle Plugin only packages
# native libraries emitted into the ABI object directory that it provides via
# CMAKE_LIBRARY_OUTPUT_DIRECTORY/CMAKE_RUNTIME_OUTPUT_DIRECTORY.  Preserve
# that directory before adding llama.cpp so the dynamically selected CPU
# variants are included in the APK alongside libsubtitleedit_llama.so.
set(SUBTITLEEDIT_NATIVE_OUTPUT_DIR "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
if (NOT SUBTITLEEDIT_NATIVE_OUTPUT_DIR)
    set(SUBTITLEEDIT_NATIVE_OUTPUT_DIR "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")
endif()

# Match LMPlayground's arm64 CPU path. KleidiAI provides runtime-selected
# NEON dot-product/i8mm kernels while retaining the generic fallback on
# devices that do not expose those instructions.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

set(LLAMA_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if (EXISTS "${LLAMA_SOURCE_DIR}/CMakeLists.txt" AND
    (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64"))
    add_subdirectory("${LLAMA_SOURCE_DIR}")

    if (GGML_BACKEND_DL AND SUBTITLEEDIT_NATIVE_OUTPUT_DIR)
        # ggml_add_backend_library() creates CPU variants as dynamic targets in
        # the ggml-cpu subdirectory. Android uses SHARED targets so AGP keeps
        # them in the APK, while desktop builds use MODULE targets. Repoint
        # both forms after llama.cpp applies its global ``bin`` output override.
        get_property(SUBTITLEEDIT_GGML_CPU_TARGETS
            DIRECTORY "${LLAMA_SOURCE_DIR}/ggml/src"
            PROPERTY BUILDSYSTEM_TARGETS)
        foreach (GGML_CPU_TARGET IN LISTS SUBTITLEEDIT_GGML_CPU_TARGETS)
            if (TARGET "${GGML_CPU_TARGET}")
                get_target_property(GGML_CPU_TARGET_TYPE "${GGML_CPU_TARGET}" TYPE)
                if (GGML_CPU_TARGET MATCHES "^ggml-cpu-" AND
                    (GGML_CPU_TARGET_TYPE STREQUAL "MODULE_LIBRARY" OR
                     GGML_CPU_TARGET_TYPE STREQUAL "SHARED_LIBRARY"))
                    set_target_properties("${GGML_CPU_TARGET}" PROPERTIES
                        LIBRARY_OUTPUT_DIRECTORY "${SUBTITLEEDIT_NATIVE_OUTPUT_DIR}"
                        RUNTIME_OUTPUT_DIRECTORY "${SUBTITLEEDIT_NATIVE_OUTPUT_DIR}")
                endif()
            endif()
        endforeach()
    endif()

    # Keep the local runtime optimized even if a stale/debug native cache is
    # selected by AGP; an unoptimized ggml build makes token generation much
    # slower than the Release build used by LMPlayground.
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        foreach (LLAMA_PERF_TARGET ggml-base ggml-cpu ggml llama llama-common llama-common-base kleidiai)
            if (TARGET ${LLAMA_PERF_TARGET})
                target_compile_options(${LLAMA_PERF_TARGET} PRIVATE "$<$<CONFIG:Debug>:-O2>")
            endif()
        endforeach()
    endif()

    add_library(subtitleedit_llama SHARED local_llama_jni.cpp)
    target_include_directories(subtitleedit_llama PRIVATE
        "${LLAMA_SOURCE_DIR}/include"
        "${LLAMA_SOURCE_DIR}/common"
        "${LLAMA_SOURCE_DIR}/ggml/include")
    target_compile_options(subtitleedit_llama PRIVATE -fexceptions -frtti)
    target_link_options(subtitleedit_llama PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(subtitleedit_llama PRIVATE llama-common llama log android)
else()
    # Keep the Gradle native target available on legacy 32-bit splits. The
    # local model UI can still be installed there, but inference reports an
    # unavailable runtime instead of making the whole APK variant fail.
    add_library(subtitleedit_llama SHARED local_llama_stub.cpp)
    target_link_libraries(subtitleedit_llama PRIVATE log)
endif()

set(SEVENZIP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/7zip")
file(GLOB SEVENZIP_C CONFIGURE_DEPENDS "${SEVENZIP_DIR}/C/*.c")
file(GLOB SEVENZIP_COMMON CONFIGURE_DEPENDS "${SEVENZIP_DIR}/CPP/Common/*.cpp")
file(GLOB SEVENZIP_MYWIN CONFIGURE_DEPENDS "${SEVENZIP_DIR}/CPP/myWindows/*.cpp")
file(GLOB SEVENZIP_ARCHIVE CONFIGURE_DEPENDS
    "${SEVENZIP_DIR}/CPP/7zip/Archive/*.cpp"
    "${SEVENZIP_DIR}/CPP/7zip/Archive/*/*.cpp")
file(GLOB SEVENZIP_COMPRESS CONFIGURE_DEPENDS "${SEVENZIP_DIR}/CPP/7zip/Compress/*.cpp")
file(GLOB SEVENZIP_CORE CONFIGURE_DEPENDS "${SEVENZIP_DIR}/CPP/7zip/Common/*.cpp")
file(GLOB SEVENZIP_CRYPTO CONFIGURE_DEPENDS "${SEVENZIP_DIR}/CPP/7zip/Crypto/*.cpp")
set(SEVENZIP_WIN
    "${SEVENZIP_DIR}/CPP/Windows/FileDir.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/FileFind.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/FileIO.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/FileName.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/PropVariant.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/PropVariantConv.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/PropVariantUtils.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/System.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/TimeUtils.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/ErrorMsg.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/FileLink.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/SystemInfo.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/Synchronization.cpp")
set(SEVENZIP_UI_COMMON_NAMES
    ArchiveCommandLine ArchiveExtractCallback ArchiveOpenCallback Bench DefaultName
    EnumDirItems Extract ExtractingFilePath HashCalc LoadCodecs OpenArchive PropIDUtils
    SetProperties SortUtils TempFiles Update UpdateAction UpdateCallback UpdatePair UpdateProduce)
set(SEVENZIP_UI_COMMON)
foreach(name IN LISTS SEVENZIP_UI_COMMON_NAMES)
    list(APPEND SEVENZIP_UI_COMMON "${SEVENZIP_DIR}/CPP/7zip/UI/Common/${name}.cpp")
endforeach()
set(SEVENZIP_UI_CONSOLE_NAMES
    BenchCon ConsoleClose ExtractCallbackConsole HashCon List Main MainAr
    OpenCallbackConsole PercentPrinter UpdateCallbackConsole UserInputUtils)
set(SEVENZIP_UI_CONSOLE)
foreach(name IN LISTS SEVENZIP_UI_CONSOLE_NAMES)
    list(APPEND SEVENZIP_UI_CONSOLE "${SEVENZIP_DIR}/CPP/7zip/UI/Console/${name}.cpp")
endforeach()
set(SEVENZIP_SOURCES
    ${SEVENZIP_C} ${SEVENZIP_COMMON} ${SEVENZIP_MYWIN} ${SEVENZIP_ARCHIVE}
    ${SEVENZIP_COMPRESS} ${SEVENZIP_CORE} ${SEVENZIP_CRYPTO} ${SEVENZIP_WIN}
    ${SEVENZIP_UI_COMMON} ${SEVENZIP_UI_CONSOLE})
list(REMOVE_DUPLICATES SEVENZIP_SOURCES)
list(FILTER SEVENZIP_SOURCES EXCLUDE REGEX "/C/Util/")
list(FILTER SEVENZIP_SOURCES EXCLUDE REGEX "/7zip/Bundles/")
list(FILTER SEVENZIP_SOURCES EXCLUDE REGEX "/7zip/UI/(Agent|Client7z|Explorer|Far|FileManager|GUI)/")
list(FILTER SEVENZIP_SOURCES EXCLUDE REGEX "/Windows/(Control|Process|Resource|Shell|Memory|FileMapping|Dll|Registry)")
list(FILTER SEVENZIP_SOURCES EXCLUDE REGEX "/7zip/(Archive|Compress)/DllExports")
list(FILTER SEVENZIP_SOURCES EXCLUDE REGEX "/7zip/UI/Common/CompressCall")
list(FILTER SEVENZIP_SOURCES EXCLUDE REGEX "/7zip/Archive/ArchiveExports")

set(SEVENZIP_REQUIRED_WIN
    "${SEVENZIP_DIR}/CPP/Windows/ErrorMsg.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/FileLink.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/SystemInfo.cpp"
    "${SEVENZIP_DIR}/CPP/Windows/Synchronization.cpp")
list(APPEND SEVENZIP_SOURCES ${SEVENZIP_REQUIRED_WIN})

add_library(subtitleedit_7zip SHARED ${SEVENZIP_SOURCES} official7zip_jni.cpp)
target_include_directories(subtitleedit_7zip PRIVATE
    "${SEVENZIP_DIR}/C" "${SEVENZIP_DIR}/CPP" "${SEVENZIP_DIR}/CPP/Common"
    "${SEVENZIP_DIR}/CPP/Windows" "${SEVENZIP_DIR}/CPP/myWindows"
    "${SEVENZIP_DIR}/CPP/include_windows")
target_compile_definitions(subtitleedit_7zip PRIVATE ENV_UNIX NDEBUG _FILE_OFFSET_BITS=64 Z7_PROG_VARIANT_Z)
target_compile_options(subtitleedit_7zip PRIVATE -O2 -fexceptions -fvisibility=hidden -Wno-error=c++11-narrowing)
if (SEVENZIP_LLD AND EXISTS "${SEVENZIP_LLD}")
    target_link_options(subtitleedit_7zip PRIVATE "-fuse-ld=${SEVENZIP_LLD}")
endif()
target_link_options(subtitleedit_7zip PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(subtitleedit_7zip PRIVATE log)
