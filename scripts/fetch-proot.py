"""Fetch proot from Termux's package repo and lay it out as jniLibs.

Android only unpacks native libraries named lib*.so, so proot, its loader and libtalloc get renamed,
and the libtalloc.so.2 reference inside proot is rewritten to libtalloc.so.
Run with: python scripts/fetch-proot.py
"""
import hashlib, io, pathlib, tarfile, urllib.request

REPO = "https://packages.termux.dev/apt/termux-main/"
ABIS = {"arm64-v8a": "aarch64", "x86_64": "x86_64"}
WANTED = {  # path inside the .deb -> name in jniLibs
    "usr/bin/proot": "libproot.so",
    "usr/libexec/proot/loader": "libproot-loader.so",
    "usr/lib/libtalloc.so.2.5.0": "libtalloc.so",
    "usr/lib/libandroid-shmem.so": "libandroid-shmem.so",
}
ROOT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/jniLibs"


def packages(arch):
    text = urllib.request.urlopen(f"{REPO}dists/stable/main/binary-{arch}/Packages").read().decode()
    stanzas = [dict(l.split(": ", 1) for l in s.splitlines() if ": " in l) for s in text.split("\n\n")]
    return {s["Package"]: s for s in stanzas if "Package" in s}


def deb_files(data):
    pos = 8  # skip "!<arch>\n"
    while pos < len(data):
        name, size = data[pos:pos + 16].decode().strip(), int(data[pos + 48:pos + 58])
        body, pos = data[pos + 60:pos + 60 + size], pos + 60 + size + (size & 1)
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body)) as t:
                for m in t.getmembers():
                    if m.isfile():
                        yield m.name.split("com.termux/files/", 1)[-1], t.extractfile(m).read()


for abi, arch in ABIS.items():
    index = packages(arch)
    out = ROOT / abi
    out.mkdir(parents=True, exist_ok=True)
    for pkg in ("proot", "libtalloc", "libandroid-shmem"):
        meta = index[pkg]
        data = urllib.request.urlopen(REPO + meta["Filename"]).read()
        assert hashlib.sha256(data).hexdigest() == meta["SHA256"], pkg
        for path, blob in deb_files(data):
            if path in WANTED:
                blob = blob.replace(b"libtalloc.so.2\0", b"libtalloc.so\0\0\0")
                (out / WANTED[path]).write_bytes(blob)
                print(abi, pkg, meta["Version"], "->", WANTED[path])
