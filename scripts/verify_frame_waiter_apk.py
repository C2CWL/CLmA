"""CI only: inspect final optimized APK DEX, not source strings or a parent-clock wrapper."""
import hashlib
import json
import os
import struct
import sys
import zipfile
from pathlib import Path


class Dex:
    def __init__(self, data):
        self.b = data
        assert data[:4] == b"dex\n", "not DEX"
        self.strings = []
        for i in range(self.u32(56)):
            off = self.u32(self.u32(60) + 4 * i)
            _, off = self.uleb(off)
            end = data.index(0, off)
            self.strings.append(data[off:end].decode("utf-8", "replace"))
        self.types = [self.strings[self.u32(self.u32(68) + i * 4)] for i in range(self.u32(64))]
        self.methods = []
        for i in range(self.u32(88)):
            cls, proto, name = struct.unpack_from("<HHI", data, self.u32(92) + i * 8)
            self.methods.append((self.types[cls], self.strings[name]))

    def u32(self, off):
        return struct.unpack_from("<I", self.b, off)[0]

    def uleb(self, off):
        value = shift = 0
        while True:
            byte = self.b[off]
            off += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, off
            shift += 7
            assert shift <= 35

    def clock_entry_hooks(self):
        found = []
        for i in range(self.u32(96)):
            base = self.u32(100) + i * 32
            if self.types[self.u32(base)] != "Landroidx/compose/runtime/BroadcastFrameClock;":
                continue
            off = self.u32(base + 24)
            counts = []
            for _ in range(4):
                value, off = self.uleb(off)
                counts.append(value)
            for _ in range(counts[0] + counts[1]):
                for _ in range(2):
                    _, off = self.uleb(off)
            for count in counts[2:]:
                method_id = 0
                for _ in range(count):
                    delta, off = self.uleb(off)
                    method_id += delta
                    _, off = self.uleb(off)
                    code, off = self.uleb(off)
                    if self.methods[method_id][1] != "withFrameNanos":
                        continue
                    assert code, "clock method has no code"
                    size = self.u32(code + 12)
                    units = struct.unpack_from("<" + "H" * size, self.b, code + 16)
                    # Kept, non-optimized entry: injected static call must be in its first instructions.
                    hits = 0
                    for j in range(min(16, len(units) - 2)):
                        if units[j] & 255 in (0x71, 0x77):
                            ref = units[j + 1]
                            if ref < len(self.methods) and self.methods[ref] == (
                                "Lcom/clma/app/diagnostics/FrameWaiterProbe;", "onAwait"
                            ):
                                hits += 1
                    assert hits == 1, f"expected one actual entry hook, got {hits}"
                    found.append({"dex_method_id": method_id, "entry_hook_count": hits})
        return found



def manifest_info(data):
    u16 = lambda p: struct.unpack_from("<H", data, p)[0]
    u32 = lambda p: struct.unpack_from("<I", data, p)[0]
    strings, stack, result = [], [], {}
    def text(i):
        return None if i == 0xffffffff else strings[i]
    def length(p, utf8):
        n = data[p] if utf8 else u16(p)
        if utf8:
            return (((n & 127) << 8) | data[p + 1], p + 2) if n & 128 else (n, p + 1)
        return (((n & 32767) << 16) | u16(p + 2), p + 4) if n & 32768 else (n, p + 2)
    p = 8
    while p < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, p)
        assert size >= header and size > 0
        if kind == 1:
            utf8 = bool(u32(p + 16) & 256)
            start = p + u32(p + 20)
            for i in range(u32(p + 8)):
                off = start + u32(p + header + 4 * i)
                n, off = length(off, utf8)
                if utf8:
                    n, off = length(off, True)
                strings.append(data[off:off + n * (1 if utf8 else 2)].decode("utf-8" if utf8 else "utf-16le"))
        elif kind == 0x102:
            name = text(u32(p + 20))
            stack.append(name)
            attrs = {}
            for i in range(u16(p + 28)):
                a = p + 16 + u16(p + 24) + i * u16(p + 26)
                ns, key, raw = struct.unpack_from("<III", data, a)
                value, typ = u32(a + 16), data[a + 15]
                key = ("android:" if text(ns) == "http://schemas.android.com/apk/res/android" else "") + text(key)
                attrs[key] = text(raw) if raw != 0xffffffff else text(value) if typ == 3 else bool(value) if typ == 18 else value
            if name == "manifest":
                result.update(package=attrs.get("package"), version=attrs.get("android:versionName"), code=attrs.get("android:versionCode"))
            elif stack == ["manifest", "application"]:
                result["debuggable"] = attrs.get("android:debuggable", False)
            elif stack == ["manifest", "application", "profileable"]:
                result["profileable_shell"] = attrs.get("android:shell")
        elif kind == 0x103:
            assert stack.pop() == text(u32(p + 20))
        p += size
    assert result == {"package": "com.clma.app", "version": "3.2", "code": 67, "debuggable": False, "profileable_shell": True}, result
    return result


def verify(path):
    all_strings = set()
    entries = []
    with zipfile.ZipFile(path) as apk:
        manifest = manifest_info(apk.read("AndroidManifest.xml"))
        abis = sorted({n.split("/")[1] for n in apk.namelist() if n.startswith("lib/") and n.count("/") >= 2})
        assert abis == ["arm64-v8a"], abis
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                dex = Dex(apk.read(name))
                all_strings.update(dex.strings)
                entries.extend(dex.clock_entry_hooks())
    assert len(entries) == 1, f"actual BroadcastFrameClock entry unavailable: {entries}"
    origins = sorted(s for s in all_strings if s.startswith("framewaiter-origin:v1:"))
    for required in ("InfiniteTransition", "SuspendAnimation", "HazeSourceNode"):
        assert any(required in s for s in origins), f"missing transformed request source: {required}"
    assert "Lcom/clma/app/diagnostics/FrameWaiterProbe;" in all_strings
    return {"apk": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "manifest": manifest, "abis": abis, "head": os.environ.get("GITHUB_SHA", "unknown"), "entry": entries,
            "stable_request_sites": origins, "status": "verified_final_dex"}


if __name__ == "__main__":
    paths = list(Path("app/build/outputs/apk/clma").glob("*arm64-v8a*.apk"))
    assert len(paths) == 1, paths
    report = verify(paths[0])
    out = Path("app/build/reports/frame-waiter-probe.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
