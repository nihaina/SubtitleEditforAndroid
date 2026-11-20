import argparse
import hashlib
import io
from pathlib import Path
import struct
from zipfile import ZIP_DEFLATED, ZipFile


UPSTREAM_SHA256 = "5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e"
ABI_ELF = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86": (1, 3),
    "x86_64": (2, 62),
}


def main():
    parser = argparse.ArgumentParser(description="Package Foxlet 0.5.0 with rebuilt API 26 native libraries")
    parser.add_argument("upstream_aar", type=Path)
    parser.add_argument("output_aar", type=Path)
    for abi in ABI_ELF:
        parser.add_argument(f"--{abi}", dest=abi, type=Path, required=True)
    args = parser.parse_args()

    upstream = args.upstream_aar.read_bytes()
    if hashlib.sha256(upstream).hexdigest() != UPSTREAM_SHA256:
        parser.error("Expected the official Foxlet v0.5.0 AAR")
    libraries = {}
    for abi, (elf_class, machine) in ABI_ELF.items():
        path = vars(args)[abi]
        if args.output_aar.resolve() in (args.upstream_aar.resolve(), path.resolve()):
            parser.error("Choose a separate output file")
        data = path.read_bytes()
        if data[:6] != b"\x7fELF" + bytes([elf_class, 1]) or data[16:20] != struct.pack("<HH", 3, machine):
            parser.error(f"{path} is not an Android {abi} ELF shared library")
        libraries[f"jni/{abi}/libfoxlet.so"] = data

    with ZipFile(io.BytesIO(upstream)) as source, ZipFile(args.output_aar, "w") as output:
        for entry in source.infolist():
            if entry.filename.startswith("jni/"):
                continue
            data = source.read(entry)
            if entry.filename == "AndroidManifest.xml":
                data = data.replace(b'android:minSdkVersion="28"', b'android:minSdkVersion="26"')
            output.writestr(entry, data, compress_type=ZIP_DEFLATED, compresslevel=9)
        for path, data in libraries.items():
            output.writestr(path, data, compress_type=ZIP_DEFLATED, compresslevel=9)


if __name__ == "__main__":
    main()
