"""Package the rebuilt JNI libraries without replacing the previous Maven artifact."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

PROJECT = pathlib.Path(__file__).resolve().parents[2]
KIT = pathlib.Path(sys.argv[1]).resolve()
VERSION = "8.1.0-mpv3"
ROOT = PROJECT / "app/libs/ffmpeg-kit-next-maven/com/arthenica/ffmpeg-kit-next"
BASE = ROOT / "8.1.0-mpv2/ffmpeg-kit-next-8.1.0-mpv2.aar"
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
FLAGS = ("--enable-libx264", "--enable-libx265", "--enable-libxvid", "--enable-libaom", "--enable-libtheora", "--enable-libopus", "--enable-libvpx", "--enable-libvorbis", "--enable-libspeex", "--enable-libopencore-amrnb", "--enable-libvo-amrwbenc")
LICENSES = {
    "license_x265": "src/x265/COPYING",
    "license_xvidcore": "src/xvidcore/xvidcore/LICENSE",
    "license_libaom": "src/libaom/LICENSE",
    "patents_libaom": "src/libaom/PATENTS",
    "license_libtheora": "src/libtheora/COPYING",
    "license_speex": "src/speex/COPYING",
    "license_opencore_amr": "src/opencore-amr/LICENSE",
    "license_vo_amrwbenc": "src/vo-amrwbenc/COPYING",
}


def exports(path):
    output = subprocess.check_output(["readelf", "--dyn-syms", "--wide", str(path)], text=True)
    return {fields[-1] for line in output.splitlines() if len(fields := line.split()) >= 8
            and fields[4] in ("GLOBAL", "WEAK") and fields[6] != "UND"}


with zipfile.ZipFile(BASE) as base, tempfile.TemporaryDirectory(prefix="subtitleedit-codecs-") as work:
    # Fail before publishing when an ABI is missing an encoder or the public API changed.
    configs = {}
    for abi in ABIS:
        directory = KIT / "android/libs" / abi
        codec = (directory / "libavcodec.so").read_bytes()
        configurations = [v.decode("ascii") for v in re.findall(rb"[ -~]{80,}", codec)
                          if b"--cross-prefix=" in v and b"--enable-gpl" in v]
        assert len(configurations) == 1, f"Missing FFmpeg configuration for {abi}"
        assert all(flag in configurations[0] for flag in FLAGS), f"Missing codec library for {abi}"
        configs[abi] = configurations[0]
        for name in base.namelist():
            if not name.startswith(f"jni/{abi}/") or not name.endswith(".so"):
                continue
            replacement = directory / pathlib.PurePosixPath(name).name
            assert replacement.is_file(), f"Missing {replacement}"
            old = pathlib.Path(work) / f"old-{abi}-{replacement.name}"
            old.write_bytes(base.read(name))
            missing = exports(old) - exports(replacement)
            assert not missing, f"Removed public symbols from {abi}/{replacement.name}: {missing}"
            if abi in ("arm64-v8a", "x86_64"):
                headers = subprocess.check_output(["readelf", "-lW", str(replacement)], text=True)
                assert all(int(line.split()[-1], 16) >= 0x4000 for line in headers.splitlines()
                           if line.strip().startswith("LOAD")), f"16 KB alignment failed: {replacement}"

    destination = ROOT / VERSION
    destination.mkdir(exist_ok=True)
    output = destination / f"ffmpeg-kit-next-{VERSION}.aar"
    staged = pathlib.Path(work) / output.name
    with zipfile.ZipFile(staged, "w", zipfile.ZIP_DEFLATED, strict_timestamps=False) as archive:
        for name in base.namelist():
            if name.endswith("/") or name.startswith("jni/") or name in ("R.txt", "META-INF/subtitleedit-codecs.json") or name in {f"res/raw/{key}.txt" for key in LICENSES}:
                continue
            archive.writestr(name, base.read(name))
        for abi in ABIS:
            for library in sorted((KIT / "android/libs" / abi).glob("*.so")):
                archive.write(library, f"jni/{abi}/{library.name}")
        for resource, source in LICENSES.items():
            archive.write(KIT / source, f"res/raw/{resource}.txt")
        resource_lines = base.read("R.txt").decode().splitlines()
        existing_resources = {line.split()[2] for line in resource_lines if len(line.split()) >= 3}
        resource_lines += [f"int raw {name} 0x0" for name in LICENSES if name not in existing_resources]
        archive.writestr("R.txt", "\n".join(resource_lines) + "\n")
        archive.writestr("META-INF/subtitleedit-codecs.json", json.dumps({"ffmpeg": "n8.1.2", "configurations": configs}, indent=2))
    with zipfile.ZipFile(staged) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist())), "Duplicate AAR entries"
    output.write_bytes(staged.read_bytes())
    pom = (ROOT / "8.1.0-mpv2/ffmpeg-kit-next-8.1.0-mpv2.pom").read_text()
    (destination / f"ffmpeg-kit-next-{VERSION}.pom").write_text(pom.replace("8.1.0-mpv2", VERSION))
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"Verified all four ABIs, public symbols and 16 KB alignment.\n{output}\nSHA-256: {digest}")
