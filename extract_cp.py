#!/usr/bin/env python3
"""Extract the merged compile/dex classpath from downloaded AARs/JARs.

Picks the highest version per artifact, extracts classes.jar (+ embedded libs/)
from AARs and plain jars into <dir>/cp/. AARs that carry res/ are extracted into
<dir>/res_libs/<artifact>/res so build.sh can link them into the APK, and the
R packages their classes reference are written to res_libs/packages.txt for
aapt2 --extra-packages (without this, androidx.car.app.R is missing from the
dex and the car app crashes with NoClassDefFoundError on Android Auto only).
"""
import os
import re
import glob
import shutil
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
AARS = os.path.join(HERE, "aars")
CP = os.path.join(HERE, "cp")
RES_LIBS = os.path.join(HERE, "res_libs")

R_REF = re.compile(rb"([a-z][a-z0-9_]*(?:/[a-z][a-z0-9_]*)*)/R\$")

# resource references like @color/foo, @style/bar, ?attr/baz (non-framework)
RES_REF = re.compile(r"(?<![\w@?])@([a-z_]+)/([A-Za-z0-9_.]+)|\?attr/([A-Za-z0-9_.]+)")
# resource definitions like <color name="foo">, <string name="bar">, <attr name="baz">
RES_DEF = re.compile(r"<(?:[a-z-]+)\s+[^>]*?name=\"([A-Za-z0-9_.]+)\"")


def res_self_contained(res_root):
    """True if every @type/name / ?attr ref in the lib's res resolves inside the
    lib itself or the android framework (so merging it into the app package
    cannot fail the aapt2 link)."""
    defined = set()
    refs = []
    for dirpath, _dirs, files in os.walk(res_root):
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            try:
                text = open(os.path.join(dirpath, fn), encoding="utf-8").read()
            except Exception:
                continue
            if os.path.basename(dirpath).startswith("values"):
                for m in RES_DEF.finditer(text):
                    defined.add(m.group(1))
            for m in RES_REF.finditer(text):
                t = m.group(1) or "attr"
                n = m.group(2) or m.group(3)
                if n:
                    refs.append((t, n))
    for t, n in refs:
        if n not in defined:
            return False
    return True


def prune_non_classes(root):
    """d8 only wants .class files: drop META-INF, module-info, kotlin metadata."""
    for dirpath, dirnames, filenames in os.walk(root, topdown=False):
        for f in filenames:
            if not f.endswith(".class"):
                os.remove(os.path.join(dirpath, f))
        for d in list(dirnames):
            full = os.path.join(dirpath, d)
            if not os.listdir(full):
                os.rmdir(full)


def main():
    arts = {}
    for f in glob.glob(os.path.join(AARS, "*")):
        m = re.match(r"(.+)-(\d+(?:\.\d+){1,2}(?:-[a-z0-9]+)?)\.(aar|jar)$", os.path.basename(f))
        if m:
            key = m.group(1)
            ver = tuple(int(x) for x in re.findall(r"\d+", m.group(2)))
            if key not in arts or ver > arts[key][0]:
                arts[key] = (ver, f)

    if os.path.isdir(CP):
        shutil.rmtree(CP)
    os.makedirs(os.path.join(CP, "_tmp"))
    if os.path.isdir(RES_LIBS):
        shutil.rmtree(RES_LIBS)
    os.makedirs(RES_LIBS)

    for name, (ver, f) in sorted(arts.items()):
        print(f"{name:45s} {os.path.basename(f)}")
        with zipfile.ZipFile(f) as z:
            names = z.namelist()
            if f.endswith(".aar"):
                z.extract("classes.jar", os.path.join(CP, "_tmp"))
                r_packages = set()
                with zipfile.ZipFile(os.path.join(CP, "_tmp", "classes.jar")) as cj:
                    for cn in cj.namelist():
                        if cn.endswith(".class"):
                            for m2 in R_REF.finditer(cj.read(cn)):
                                pkg = m2.group(1).decode().replace("/", ".")
                                if pkg != "android" and not pkg.startswith("android."):
                                    r_packages.add(pkg)
                    cj.extractall(CP)
                os.remove(os.path.join(CP, "_tmp", "classes.jar"))
                for n in names:
                    if n.startswith("libs/") and n.endswith(".jar"):
                        z.extract(n, os.path.join(CP, "_tmp"))
                        with zipfile.ZipFile(os.path.join(CP, "_tmp", n)) as cj:
                            cj.extractall(CP)
                res_entries = [n for n in names if n.startswith("res/") and n != "res/"]
                if res_entries:
                    dest = os.path.join(RES_LIBS, name)
                    for n in res_entries:
                        z.extract(n, dest)
                    # Only keep res that is self-contained (all @type/name refs
                    # resolve inside the lib itself or the framework) — merging
                    # res with dangling refs (e.g. media -> appcompat) into the
                    # app package makes aapt2 link fail. The R package this
                    # library's classes reference (ground truth from bytecode)
                    # is recorded for aapt2 --extra-packages.
                    if r_packages and res_self_contained(dest):
                        with open(os.path.join(dest, "package.txt"), "w") as fh:
                            fh.write(sorted(r_packages)[0])
                        print(f"   res -> {dest} ({len(res_entries)} entries, R: {sorted(r_packages)})")
                    else:
                        print(f"   res skipped (not self-contained / no R refs): {dest}")
            else:
                with zipfile.ZipFile(f) as z:
                    z.extractall(CP)
    prune_non_classes(CP)
    print("\nDONE — merged classpath at", CP)


if __name__ == "__main__":
    main()
