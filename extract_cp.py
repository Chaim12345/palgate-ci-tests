#!/usr/bin/env python3
"""Extract the merged compile/dex classpath from downloaded AARs/JARs.

Picks the highest version per artifact, extracts classes.jar (+ embedded libs/)
from AARs and plain jars into <dir>/cp/, reports AARs that carry resources.
"""
import os
import re
import glob
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
AARS = os.path.join(HERE, "aars")
CP = os.path.join(HERE, "cp")


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
        import shutil
        shutil.rmtree(CP)
    os.makedirs(os.path.join(CP, "_tmp"))

    for name, (ver, f) in sorted(arts.items()):
        print(f"{name:45s} {os.path.basename(f)}")
        with zipfile.ZipFile(f) as z:
            names = z.namelist()
            if f.endswith(".aar"):
                z.extract("classes.jar", os.path.join(CP, "_tmp"))
                with zipfile.ZipFile(os.path.join(CP, "_tmp", "classes.jar")) as cj:
                    cj.extractall(CP)
                os.remove(os.path.join(CP, "_tmp", "classes.jar"))
                for n in names:
                    if n.startswith("libs/") and n.endswith(".jar"):
                        z.extract(n, os.path.join(CP, "_tmp"))
                        with zipfile.ZipFile(os.path.join(CP, "_tmp", n)) as cj:
                            cj.extractall(CP)
                if any(n.startswith("res/") for n in names):
                    print(f"   !! HAS RES: {[n for n in names if n.startswith('res/')][:6]}")
            else:
                with zipfile.ZipFile(f) as z:
                    z.extractall(CP)
    prune_non_classes(CP)
    print("\nDONE — merged classpath at", CP)


if __name__ == "__main__":
    main()
