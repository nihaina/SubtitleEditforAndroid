import argparse
import hashlib
import io
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


UPSTREAM_SHA256 = "5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e"
NATIVE_LIBRARY = "jni/arm64-v8a/libfoxlet.so"
NATIVE_RUNTIME = "jni/arm64-v8a/libc++_shared.so"


def main():
    parser = argparse.ArgumentParser(description="Package Foxlet 0.5.0 with the rebuilt API 26 native library")
    parser.add_argument("upstream_aar", type=Path)
    parser.add_argument("native_library", type=Path)
    parser.add_argument("output_aar", type=Path)
    args = parser.parse_args()

    upstream = args.upstream_aar.read_bytes()
    if hashlib.sha256(upstream).hexdigest() != UPSTREAM_SHA256:
        parser.error("Expected the official Foxlet v0.5.0 AAR")
    if args.output_aar.resolve() in (args.upstream_aar.resolve(), args.native_library.resolve()):
        parser.error("Choose a separate output file")
    native_library = args.native_library.read_bytes()

    with ZipFile(io.BytesIO(upstream)) as source, ZipFile(args.output_aar, "w") as output:
        for entry in source.infolist():
            if entry.filename == NATIVE_RUNTIME:
                continue
            if entry.filename == NATIVE_LIBRARY:
                data = native_library
            else:
                data = source.read(entry)
                if entry.filename == "AndroidManifest.xml":
                    data = data.replace(b'android:minSdkVersion="28"', b'android:minSdkVersion="26"')
            output.writestr(entry, data, compress_type=ZIP_DEFLATED, compresslevel=9)


if __name__ == "__main__":
    main()
