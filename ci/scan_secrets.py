#!/usr/bin/env python3
"""Repo-wide secret scanner: any secret-shaped string not in ci/synthetic_whitelist.txt fails.
Covers 32-hex (AES keys), 40-hex (20-byte BLE responses), 46-hex (X-Bt-Token - NEVER allowed),
MAC addresses, and IL phone numbers (PII). Unknown tokens are reported by hash only, never value.
Run from repo root. Exit 1 on any violation."""
import re, sys, os, subprocess, hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

SKIP_DIRS = ("tools/", ".git/")
SKIP_EXT = (".jar", ".zip", ".png", ".jpg", ".so", ".bin")

def load_whitelist():
    wl = set()
    path = "ci/synthetic_whitelist.txt"
    if not os.path.exists(path):
        print("FAIL: ci/synthetic_whitelist.txt missing"); sys.exit(1)
    for line in open(path):
        tok = line.split("#", 1)[0].strip()
        if tok:
            wl.add(tok.upper())
    return wl

WL = load_whitelist()
PATTERNS = [
    ("32-hex key", re.compile(r"\b[0-9A-Fa-f]{32}\b"), True),
    ("40-hex blob", re.compile(r"\b[0-9A-Fa-f]{40}\b"), True),
    ("46-hex X-Bt-Token", re.compile(r"\b[0-9A-Fa-f]{46}\b"), False),
    ("MAC address", re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b"), True),
]
PHONE = re.compile(r"\+?972[-\s]?5\d[-\s]?\d{3}[-\s]?\d{4}")

files = subprocess.run(["git", "ls-files"], capture_output=True, text=True).stdout.split()
violations = 0
for f in files:
    if f.startswith(SKIP_DIRS) or f.endswith(SKIP_EXT):
        continue
    try:
        text = open(f, encoding="utf-8", errors="ignore").read()
    except OSError:
        continue
    for lineno, line in enumerate(text.splitlines(), 1):
        for label, pat, whitelistable in PATTERNS:
            for m in pat.finditer(line):
                tok = m.group(0).upper()
                if whitelistable and tok in WL:
                    continue
                h = hashlib.sha256(tok.encode()).hexdigest()[:12]
                why = "not in synthetic whitelist" if whitelistable else "X-Bt-Token shape is never permitted"
                print(f"VIOLATION {f}:{lineno}: {label} sha256:{h} ({why})")
                violations += 1
        for m in PHONE.finditer(line):
            print(f"VIOLATION {f}:{lineno}: IL phone number (PII) sha256:{hashlib.sha256(m.group(0).encode()).hexdigest()[:12]}")
            violations += 1

if violations:
    print(f"scan_secrets: {violations} violation(s)")
    sys.exit(1)
print(f"scan_secrets: clean ({len(files)} tracked files scanned, {len(WL)} whitelisted synthetic tokens)")
