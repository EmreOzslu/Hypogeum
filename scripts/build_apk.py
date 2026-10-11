#!/usr/bin/env python3
"""
Hypogeum APK'sını Google'ın Android derleme zinciri olmadan üretir.

Kullanılan araçlar (hepsi Maven Central'dan veya bu JDK'dan):
  - dx (dalvik-dx 16.0.1, Maven Central): sınıfları classes.dex'e çevirir
  - android-all 4.1.2 (Robolectric, Maven Central): derleme için framework sınıfları (API 16)
  - AndroidManifest.xml için ikili (AXML) kodlayıcı (bu dosyada)
  - jarsigner / keytool (JDK): debug anahtarıyla v1 imza

Sınırlar: aapt2/r8/apksigner yoktur; kaynak (res) yoktur; v2/v3 imza yoktur.
targetSdk 29 ile v1 imza kabul edilir. Cihazda test edilmedi.
"""
import glob
import os
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "apk", "build")
TOOLS = os.path.join(BUILD, "tools")
OUT = os.path.join(BUILD, "outputs", "apk", "debug")
DX_URL = "https://repo.maven.apache.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar"
FRAMEWORK_URL = "https://repo.maven.apache.org/maven2/org/robolectric/android-all/4.1.2_r1-robolectric-r1/android-all-4.1.2_r1-robolectric-r1.jar"
MAVEN = "https://repo.maven.apache.org/maven2/"
PACKAGE = "hypogeum.apk"
MIN_SDK = 16
TARGET_SDK = 29
VERSION_CODE = 1
VERSION_NAME = "0.6.0"


def run(cmd, cwd=ROOT):
    print("+", " ".join(cmd))
    subprocess.run(cmd, cwd=cwd, check=True)


def fetch(url, dest):
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        run(["curl", "-sSfL", "-o", dest, url])


def strip_jar(src, dst):
    """dx Java 9+ modül dosyalarını (META-INF/versions, module-info) okuyamaz: bunları çıkararak kopyalar."""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/versions/") or name.endswith("module-info.class"):
                continue
            zout.writestr(info, zin.read(name))
    return dst


# ---------- AXML (ikili AndroidManifest.xml) ----------

RES_ID = {
    "label": 0x01010001,
    "name": 0x01010003,
    "exported": 0x01010010,
    "versionCode": 0x0101021B,
    "versionName": 0x0101021C,
    "minSdkVersion": 0x0101020C,
    "targetSdkVersion": 0x01010270,
}
ANDROID_NS = "http://schemas.android.com/apk/res/android"
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12


class Pool:
    def __init__(self):
        self.items = []

    def idx(self, s):
        if s not in self.items:
            self.items.append(s)
        return self.items.index(s)


def encode_manifest(tree):
    """tree: (name, [attrs], [children]); attrs: (ns_android, name, value) value: str|int|bool."""
    pool = Pool()
    pool.idx("android")
    pool.idx(ANDROID_NS)

    def collect(node):
        name, attrs, children = node
        pool.idx(name)
        for ns, k, v in attrs:
            pool.idx(k)
            if isinstance(v, str):
                pool.idx(v)
        for c in children:
            collect(c)

    collect(tree)

    strings = []
    for s in pool.items:
        data = s.encode("utf-16-le")
        strings.append(struct.pack("<H", len(s)) + data + b"\x00\x00")
    offsets = []
    pos = 0
    for b in strings:
        offsets.append(pos)
        pos += len(b)
    while pos % 4:
        pos += 1
    header_size = 28
    strings_start = header_size + 4 * len(pool.items)
    body = b"".join(strings)
    body += b"\x00" * (pos - len(body))
    sp_size = strings_start + len(body)
    sp = struct.pack("<HHIIIIII", 0x0001, header_size, sp_size, len(pool.items), 0, 0, strings_start, 0)
    sp += b"".join(struct.pack("<I", o) for o in offsets) + body

    rmap_ids = [RES_ID.get(s, 0) for s in pool.items]
    rmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(rmap_ids)) + b"".join(struct.pack("<I", i) for i in rmap_ids)

    android_idx = pool.idx("android")
    uri_idx = pool.idx(ANDROID_NS)
    start_ns = struct.pack("<HHIIIII", 0x0100, 16, 24, 1, 0xFFFFFFFF, android_idx, uri_idx)
    end_ns = struct.pack("<HHIIIII", 0x0101, 16, 24, 1, 0xFFFFFFFF, android_idx, uri_idx)

    def element(node):
        name, attrs, children = node
        out = b""
        attr_bytes = b""
        for ns, k, v in attrs:
            ns_i = uri_idx if ns else 0xFFFFFFFF
            name_i = pool.idx(k)
            if isinstance(v, bool):
                raw, dtype, data = 0xFFFFFFFF, TYPE_INT_BOOLEAN, 0xFFFFFFFF if v else 0
            elif isinstance(v, int):
                raw, dtype, data = 0xFFFFFFFF, TYPE_INT_DEC, v
            else:
                i = pool.idx(v)
                raw, dtype, data = i, TYPE_STRING, i
            attr_bytes += struct.pack("<IIIHBBI", ns_i, name_i, raw, 8, 0, dtype, data)
        size = 16 + 20 + len(attr_bytes)
        head = struct.pack("<HHI", 0x0102, 16, size) + struct.pack("<II", 1, 0xFFFFFFFF)
        head += struct.pack("<II", 0xFFFFFFFF, pool.idx(name))
        head += struct.pack("<HHHHHH", 20, 20, len(attrs), 0, 0, 0)
        out += head + attr_bytes
        for c in children:
            out += element(c)
        out += struct.pack("<HHI", 0x0103, 16, 24) + struct.pack("<II", 1, 0xFFFFFFFF)
        out += struct.pack("<II", 0xFFFFFFFF, pool.idx(name))
        return out

    body = sp + rmap + start_ns + element(tree) + end_ns
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def manifest_tree():
    return (
        "manifest",
        [(False, "package", PACKAGE), (False, "versionCode", VERSION_CODE), (False, "versionName", VERSION_NAME)],
        [
            ("uses-sdk", [(True, "minSdkVersion", MIN_SDK), (True, "targetSdkVersion", TARGET_SDK)], []),
            (
                "application",
                [(True, "label", "Hypogeum")],
                [
                    (
                        "activity",
                        [(True, "name", PACKAGE + ".MainActivity"), (True, "exported", True)],
                        [
                            (
                                "intent-filter",
                                [],
                                [
                                    ("action", [(True, "name", "android.intent.action.MAIN")], []),
                                    ("category", [(True, "name", "android.intent.category.LAUNCHER")], []),
                                ],
                            )
                        ],
                    )
                ],
            ),
        ],
    )


# ---------- derleme, dex, paketleme, imza ----------

def class_refs(data):
    """Bir .class dosyasının constant pool'undaki sınıf adlarını döndürür (Class sabitleri, tag 7)."""
    import struct as st
    count = st.unpack(">H", data[8:10])[0]
    pos = 10
    utf8 = {}
    classes = []
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = st.unpack(">H", data[pos + 1:pos + 3])[0]
            utf8[i] = data[pos + 3:pos + 3 + n].decode("utf-8", "replace")
            pos += 3 + n
        elif tag == 7:
            classes.append(st.unpack(">H", data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag == 8 or tag == 16 or tag == 19 or tag == 20:
            pos += 3
        elif tag == 15:
            pos += 4
        else:
            raise ValueError(f"unknown constant tag {tag}")
        i += 1
    return {utf8[c] for c in classes if c in utf8}


def check_linkage(class_dirs, jars):
    """Uygulama sınıflarının başvurduğu kotlin/kotlinx sınıfları çalışma zamanı jar'larında var mı?"""
    available = set()
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            available |= {n[:-6] for n in z.namelist() if n.endswith(".class")}
    for d in class_dirs:
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".class"):
                    available.add(os.path.relpath(os.path.join(root, f), d)[:-6].replace(os.sep, "/"))
    missing = set()
    for d in class_dirs:
        for root, _, files in os.walk(d):
            for f in files:
                if not f.endswith(".class"):
                    continue
                with open(os.path.join(root, f), "rb") as fh:
                    for ref in class_refs(fh.read()):
                        if (ref.startswith("kotlin/") or ref.startswith("kotlinx/")) and ref not in available:
                            missing.add(ref)
    if missing:
        sys.exit("runtime-missing classes referenced by app code:\n  " + "\n  ".join(sorted(missing)))
    print("linkage check: all kotlin/kotlinx class references resolve in runtime jars")


def main():
    run(["gradle", ":apk:classes", ":domain:classes", ":data:classes", "--console=plain", "-q"])

    os.makedirs(os.path.join(BUILD, "stripped"), exist_ok=True)
    dx = os.path.join(TOOLS, "dx.jar")
    fetch(DX_URL, dx)
    framework = os.path.join(TOOLS, "android-all-4.1.2.jar")
    fetch(FRAMEWORK_URL, framework)

    # Kotlin 2.0 stdlib, Kotlin 1.9+ stdlib ve serialization 1.7 invokedynamic içerir (dx desteklemez).
    # Kotlin 1.7.22 stdlib ve serialization 1.6.3 dx'ten geçer. Derleme 2.0.21 ile yapılır; çalışma zamanı
    # sürümü eski olduğundan, aşağıdaki sınıf-bağlantı denetimi eksik sınıfları yakalar (metotlar denetlenmez).
    libs = [
        (MAVEN + "org/jetbrains/kotlin/kotlin-stdlib/1.7.22/kotlin-stdlib-1.7.22.jar", "kotlin-stdlib.jar"),
        (MAVEN + "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.6.3/kotlinx-serialization-core-jvm-1.6.3.jar", "serialization-core.jar"),
        (MAVEN + "org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.6.3/kotlinx-serialization-json-jvm-1.6.3.jar", "serialization-json.jar"),
    ]
    lib_jars = []
    for url, name in libs:
        raw = os.path.join(TOOLS, name.replace(".jar", "-raw.jar"))
        fetch(url, raw)
        lib_jars.append(strip_jar(raw, os.path.join(BUILD, "stripped", name)))
    # Kotlin 2.0 her enum için kotlin.enums.EnumEntriesKt'ye başvurur (1.9+ stdlib). Yalnızca o paket
    # 1.9.24 stdlib'den alınır; geri kalan stdlib 1.7.22'dir.
    enums_src = os.path.join(TOOLS, "kotlin-stdlib-1.9.24.jar")
    fetch(MAVEN + "org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar", enums_src)
    enums_dir = os.path.join(BUILD, "stripped", "enums-classes")
    with zipfile.ZipFile(enums_src) as zin:
        for n in zin.namelist():
            if n.startswith("kotlin/enums/") and n.endswith(".class"):
                target = os.path.join(enums_dir, n)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as fh:
                    fh.write(zin.read(n))
    inputs = [
        os.path.join(ROOT, "apk", "build", "classes", "kotlin", "main"),
        os.path.join(ROOT, "domain", "build", "classes", "kotlin", "main"),
        os.path.join(ROOT, "data", "build", "classes", "kotlin", "main"),
        enums_dir,
    ] + lib_jars
    os.makedirs(OUT, exist_ok=True)
    dex = os.path.join(BUILD, "classes.dex")
    check_linkage(inputs[:4], lib_jars)
    run(["java", "-cp", dx, "com.android.dx.command.Main", "--dex", f"--min-sdk-version={MIN_SDK}", f"--output={dex}"] + inputs)

    unsigned = os.path.join(BUILD, "unsigned.apk")
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", encode_manifest(manifest_tree()))
        z.write(dex, "classes.dex")

    keystore = os.path.join(TOOLS, "debug.keystore")
    if not os.path.exists(keystore):
        run([
            "keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US",
        ])
    final = os.path.join(OUT, "app-debug.apk")
    shutil.copy(unsigned, final)
    run([
        "jarsigner", "-keystore", keystore, "-storepass", "android", "-keypass", "android",
        "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", final, "androiddebugkey",
    ])
    print("APK:", final, os.path.getsize(final), "bytes")


if __name__ == "__main__":
    main()
