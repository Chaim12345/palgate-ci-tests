#!/usr/bin/env python3
"""Resolve + download the AAR/JAR closure for androidx.car.app:app:1.4.0.

Walks Gradle .module (preferred) or .pom metadata recursively, downloads each
artifact from dl.google.com (androidx) or repo.maven.apache.org (others) into
./aars/, and reports the flat list + packaging type.
"""
import json
import os
import sys
import urllib.request

import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
AARS = os.path.join(HERE, "aars")
GM = "https://dl.google.com/android/maven2"
CM = ["https://repo.maven.apache.org/maven2", "https://repo1.maven.org/maven2"]

import time

START = [("androidx.car.app", "app", "1.4.0")]

# artifacts that are compile-only / already provided by android.jar
SKIP = {
    ("androidx.annotation", "annotation"),
    ("androidx.annotation", "annotation-jvm"),
    ("com.google.guava", "listenablefuture"),   # empty placeholder jar
}


def _get_with_retry(urls, tries=6):
    last = None
    for attempt in range(tries):
        for u in urls:
            try:
                with urllib.request.urlopen(u, timeout=90) as r:
                    return r.read()
            except urllib.error.HTTPError as e:
                last = e
                if e.code == 404:
                    return None
                time.sleep(1.5 + attempt)   # 429 / 5xx: back off, rotate mirror
            except urllib.error.URLError as e:
                last = e
                time.sleep(1.5 + attempt)
    raise last


def url_for(group, artifact, version, ext):
    if group.startswith("androidx.") or group.startswith("com.android."):
        return [f"{GM}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"]
    path = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"
    return [f"{c}/{path}" for c in CM]


def fetch(group, artifact, version):
    base = os.path.join(AARS, f"{artifact}-{version}")
    if os.path.exists(base + ".done"):
        return
    # try AAR then JAR
    for ext in ("aar", "jar"):
        data = _get_with_retry(url_for(group, artifact, version, ext))
        if data is None:
            continue
        with open(base + "." + ext, "wb") as f:
            f.write(data)
        open(base + ".done", "w").write(ext)
        print(f"  downloaded {group}:{artifact}:{version} ({ext}, {len(data)//1024} KB)")
        time.sleep(0.4)
        return
    raise SystemExit(f"404 for both aar/jar: {group}:{artifact}:{version}")


def deps_of(group, artifact, version):
    """Return list of (g, a, v) runtime/compile deps from .module or .pom."""
    out = []
    # prefer .module
    data = _get_with_retry(url_for(group, artifact, version, "module"))
    if data:
        try:
            m = json.loads(data)
            for var in m.get("variants", []):
                name = var.get("name", "")
                if "Release" not in name or "Sources" in name.lower():
                    continue
                for d in var.get("dependencies", []):
                    ver = d.get("version", {})
                    req = ver.get("requires") or ver.get("prefers") or ver.get("strictly")
                    if req:
                        out.append((d["group"], d["module"], req))
            if out:
                return out
        except (ValueError, KeyError):
            pass
    # fall back to .pom
    data = _get_with_retry(url_for(group, artifact, version, "pom"))
    if data is None:
        return out
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    root = ET.fromstring(data)
    for dep in root.findall(".//m:dependencies/m:dependency", ns):
        g = dep.findtext("m:groupId", "", ns)
        a = dep.findtext("m:artifactId", "", ns)
        v = dep.findtext("m:version", "", ns)
        scope = dep.findtext("m:scope", "compile", ns)
        if v and scope in ("compile", "runtime"):
            out.append((g, a, v))
    return out


def main():
    os.makedirs(AARS, exist_ok=True)
    seen = set()
    queue = list(START)
    while queue:
        g, a, v = queue.pop(0)
        v = v.strip("[]")          # some POMs use version ranges "[2.1.0]"
        if (g, a) in SKIP or (g, a, v) in seen:
            continue
        seen.add((g, a, v))
        fetch(g, a, v)
        for dep in deps_of(g, a, v):
            if dep not in seen:
                queue.append(dep)
    print("\n== closure ==")
    for g, a, v in sorted(seen):
        print(f"{g}:{a}:{v}")


if __name__ == "__main__":
    main()
