#!/usr/bin/env python3
"""Validate assets/gates.json fixture schema and safety markers.

Guards two things:
1. The fixture carries every field the app and tests rely on, with sane types.
2. SAFETY: every gate must be marked synthetic, so real gate data (names,
   keys, MACs, addresses from a live account) can never land in this public
   repo unnoticed.
"""
import json
import re
import sys

FAILS = []


def check(cond, msg):
    if not cond:
        FAILS.append(msg)


def main():
    with open("assets/gates.json", encoding="utf-8") as f:
        doc = json.load(f)

    gates = doc.get("gates")
    check(isinstance(gates, list), "gates must be a list")
    if not isinstance(gates, list):
        return report()
    check(len(gates) == 2, f"expected 2 synthetic gates, got {len(gates)}")

    hex32 = re.compile(r"^[0-9A-Fa-f]{32}$")
    mac_re = re.compile(r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    iso_re = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
    fw_re = re.compile(r"^\d+\.\d+$")

    for g in gates:
        gid = g.get("id", "?")
        check(isinstance(g.get("id"), str) and g["id"].startswith("TEST"),
              f"{gid}: id must be a TEST* synthetic id")
        check("SYNTHETIC" in str(g.get("name", "")),
              f"{gid}: name must carry the (SYNTHETIC) marker")
        check(isinstance(g.get("lat"), (int, float)) and -90 <= g["lat"] <= 90,
              f"{gid}: lat out of range")
        check(isinstance(g.get("lng"), (int, float)) and -180 <= g["lng"] <= 180,
              f"{gid}: lng out of range")
        check(isinstance(g.get("key"), str) and hex32.match(g["key"]) is not None,
              f"{gid}: key must be 32 hex chars (synthetic AES-128)")
        check(g.get("model") == "SG304GI", f"{gid}: model must be SG304GI")
        check(g.get("type") == "4G", f"{gid}: type must be 4G")
        check(g.get("subType") == "I", f"{gid}: subType must be I")
        check(isinstance(g.get("firmware"), str) and fw_re.match(g["firmware"]) is not None,
              f"{gid}: firmware must look like 5.054")
        check(isinstance(g.get("outputCount"), int) and g["outputCount"] >= 1,
              f"{gid}: outputCount must be >= 1")
        for flag in ("admin", "secondaryDevice", "dialToOpen", "localOnly"):
            check(isinstance(g.get(flag), bool), f"{gid}: {flag} must be boolean")
        check(g.get("admin") is False, f"{gid}: fixture role is non-admin (secondary device)")
        check(g.get("mac") is None or (isinstance(g.get("mac"), str) and mac_re.match(g["mac"]) is not None),
              f"{gid}: mac must be AA:BB:CC:DD:EE:FF or null")
        check(isinstance(g.get("relay"), str) and g["relay"].isdigit(),
              f"{gid}: relay must be a numeric string like '02'")
        check(isinstance(g.get("validUntil"), str) and iso_re.match(g["validUntil"]) is not None,
              f"{gid}: validUntil must be ISO-8601 millis Z")

    profile = doc.get("profile", {})
    check(profile.get("appPackage") == "com.bluegate.app", "profile.appPackage mismatch")
    check(isinstance(profile.get("appVersionCode"), int), "profile.appVersionCode must be int")

    return report()


def report():
    if FAILS:
        for m in FAILS:
            print("FAIL:", m)
        return 1
    print("gates.json fixture schema OK (2 synthetic gates, reference profile fields present)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
