cmake_minimum_required(VERSION 3.22.1)

# Some Windows NDK installations include a Unix shell script named `ld.lld`
# next to the actual `ld.lld.exe`. Point Clang at the executable explicitly.
if (WIN32 AND ANDROID_NDK)
    set(SEVENZIP_LLD "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/bin/ld.lld.exe")
endif()

project(subtitleedit_archive LANGUAGES C CXX)

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
