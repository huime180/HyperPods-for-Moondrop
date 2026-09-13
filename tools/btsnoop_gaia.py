#!/usr/bin/env python3
"""btsnoop -> Qualcomm GAIA (QTiL) extractor.   v2, stack-walking.

Reality of this ROM's snoop log (verified on Xiaomi Pad 8 / HyperOS 4.0):
  * datalink 0x03EA = HCI H4, records are standard 24-byte-header btsnoop records
  * ACL packets ARE TRUNCATED: incl_len < orig_len, only the first 10 bytes of
    ACL data survive (158/1747 records in a sample).  HCI identity (handle +
    dlen) and the 4-byte L2CAP header always survive; GAIA payload is partial.
  * the Moondrop app talks GAIA over **RFCOMM / SPP (BR/EDR)**, not BLE GATT.
    Its RFCOMM channel shows up on a dynamic L2CAP CID (0x0140/0x0046/... on
    individual records), so we do NOT filter on CID 3.
  * the app wraps each GAIA frame in a 4-byte SPP header:
        FF 04 00 <n>  +  <GAIA frame>          (0x04 = protocolVersion 4)
    where <n> = number of GAIA bytes AFTER the 4-byte GAIA header.

Walk:  HCI H4 -> ACL(reassemble by handle/PB) -> L2CAP -> payload
         payload -> RFCOMM frame -> info -> GAIA
         payload -> ATT PDU (cid 4)      -> value -> GAIA        (BLE path)
         payload -> raw GAIA scan                                 (fallback)

Usage:
  python3 tools/btsnoop_gaia.py <log> [more logs] [--all] [--att] [--csv F]
"""
import struct, sys, datetime, csv, collections

FEATURES = {
    0: "BASIC", 1: "EARBUD", 2: "ANC_V1", 3: "VOICE_UI", 4: "DEBUG", 5: "MUSIC",
    6: "UPGRADE", 7: "HANDSET", 8: "AUDIO_CURATION", 9: "EARBUD_FIT",
    10: "VOICE_PROC", 11: "GESTURE", 12: "STATS", 13: "BATTERY", 14: "VOICE",
    15: "DAC_GAIN", 16: "CODEC_TYPE", 17: "LIGHT", 18: "SPATIAL", 19: "LED",
    20: "ONEBRINGTWO", 21: "BT_ADDR", 22: "TOUCHV2", 23: "AUDIO_RESOURCE",
    24: "POWER", 25: "POWER_TIMEOUT", 26: "TOUCHV3", 27: "DYBASS",
    28: "?28", 29: "FILE_STORAGE", 30: "LR_CHANNEL", 31: "?31", 32: "ANC_V2",
    33: "?33", 40: "?40", 41: "?41", 65: "?65",
}
TYPES = {0: "COMMAND", 1: "NOTIFICATION", 2: "RESPONSE", 3: "ERROR"}
RFCOMM_CTRL = {0x2F, 0x3F, 0x63, 0x73, 0x0F, 0x1F, 0x43, 0x53,
               0xEF, 0xFF, 0x03, 0x13, 0xE3, 0xF3}


def uuid_str(b):
    if len(b) == 2:
        return "0x%04X" % struct.unpack("<H", b)[0]
    if len(b) == 16:
        s = b[::-1].hex()
        return "%s-%s-%s-%s-%s" % (s[0:8], s[8:12], s[12:16], s[16:20], s[20:32])
    return b.hex()


def ts_str(us):
    try:
        return datetime.datetime.fromtimestamp(
            946684800 + us / 1e6, datetime.UTC).strftime("%H:%M:%S.%f")[:12]
    except Exception:
        return str(us)


def iter_records(path):
    data = open(path, "rb").read()
    if not data.startswith(b"btsnoop\x00"):
        raise SystemExit("%s: not btsnoop" % path)
    off, n = 16, len(data)
    while off + 24 <= n:
        orig, incl, flags, drops = struct.unpack(">IIII", data[off:off + 16])
        ts = struct.unpack(">Q", data[off + 16:off + 24])[0]
        off += 24
        if off + incl > n:
            break
        yield ts, flags, orig, incl, data[off:off + incl]
        off += incl


def decode_gaia(buf):
    """Return (vendor, feature, type, cmd, payload) if buf starts with a GAIA frame."""
    if len(buf) < 4:
        return None
    vend = (buf[0] << 8) | buf[1]
    if vend not in (0x001D, 0x000A, 0x0000):
        return None
    cw = (buf[2] << 8) | buf[3]
    return vend, (cw >> 9) & 0x7F, (cw >> 7) & 3, cw & 0x7F, bytes(buf[4:])


def gaia_in(info):
    """Find a GAIA frame inside an RFCOMM/ATT payload, with or without the
    app's 'FF 04 00 <n>' SPP wrapper.  Returns (frame_tuple, offset, wrapped)."""
    if len(info) >= 6 and info[0] == 0xFF and info[1] == 0x04:
        f = decode_gaia(info[4:])
        if f:
            return f, 4, True
    f = decode_gaia(info)
    if f:
        return f, 0, False
    for i in range(1, min(len(info), 8)):
        f = decode_gaia(info[i:])
        if f:
            return f, i, False
    return None


def rfcomm_infos(pl):
    """Yield (dlci, info) for each RFCOMM frame in an L2CAP payload."""
    i, n = 0, len(pl)
    while i + 3 <= n:
        addr, ctrl = pl[i], pl[i + 1]
        if not (addr & 1) or ctrl not in RFCOMM_CTRL:
            return
        dlci = (addr >> 2) & 0x3F
        j = i + 2
        b = pl[j]
        if b & 1:
            ln = b >> 1
            j += 1
        else:
            ln = (b >> 1) | (pl[j + 1] << 7)
            j += 2
        yield dlci, bytes(pl[j:j + ln])
        i = j + ln


def analyze(path, args, acc):
    frames, attlog, gatt = [], [], {}
    aclbuf, trunc = {}, collections.Counter()
    for ts, flags, orig, incl, pkt in iter_records(path):
        if not pkt:
            continue
        d = "bud->app" if (flags & 1) else "app->bud"
        if pkt[0] != 0x02:
            continue
        if incl < orig:
            trunc["truncated_acl"] += 1
        hp = struct.unpack("<H", pkt[1:3])[0]
        dlen = struct.unpack("<H", pkt[3:5])[0]
        pb, h = (hp >> 12) & 3, hp & 0xFFF
        body = pkt[5:5 + dlen]
        key = (h, d)
        if pb == 1:
            aclbuf[key] = bytes(aclbuf.get(key, b"")) + body
            continue
        aclbuf[key] = body
        buf = aclbuf[key]
        if len(buf) < 4:
            continue
        l2len = struct.unpack("<H", buf[0:2])[0]
        cid = struct.unpack("<H", buf[2:4])[0]
        pl = buf[4:4 + l2len]
        if not pl:
            continue
        hit = None
        if cid == 0x0004:                      # ATT (BLE / EDR)
            attlog.append((ts, d, pl))
            hit = gaia_in(pl[3:]) if pl[0] in (0x12, 0x52, 0x1B, 0x1D) else None
        else:
            for dlci, info in rfcomm_infos(pl):
                if not info:
                    continue
                hit = gaia_in(info)
                if hit:
                    hit = (hit[0], hit[1], hit[2], dlci)
                    break
            if not hit:
                hit = gaia_in(pl)
                if hit:
                    hit = (hit[0], hit[1], hit[2], None)
        if hit:
            (vend, feat, typ, cmd, pay), off, wrapped, *rest = hit if len(hit) == 4 else (*hit, None)
            dlci = rest[0] if rest else None
            frames.append((ts, d, "RFCOMM" if cid != 4 else "ATT", h, dlci,
                           vend, feat, typ, cmd, pay, wrapped, incl < orig))
    # ---------------- report
    print("\n" + "=" * 104)
    print("=== %s" % path)
    print("    GAIA frames: %d   (ACL records truncated by ROM: %d)"
          % (len(frames), trunc["truncated_acl"]))
    if not frames:
        print("    (no GAIA frames)")
        return frames
    print("  %-12s %-8s %-7s %-6s %-6s %-16s %-12s %-4s  %s"
          % ("time", "dir", "trans", "hdl", "dlci", "feature", "type", "cmd", "payload"))
    for ts, d, tr, h, dlci, vend, f, t, c, pay, wrapped, wastr in frames:
        print("  %-12s %-8s %-7s %-6s %-6s %-16s %-12s %-4d  %s"
              % (ts_str(ts), d, tr, h, dlci if dlci is not None else "-",
                 "%d/%s" % (f, FEATURES.get(f, "?")), TYPES.get(t, "?"), c,
                 pay.hex(" ")))
    print("\n  --- distinct (feature,type,cmd,direction) ---")
    agg = collections.OrderedDict()
    for ts, d, tr, h, dlci, vend, f, t, c, pay, wrapped, wastr in frames:
        k = (f, t, c, d)
        a = agg.setdefault(k, {"n": 0, "pay": set(), "first": ts})
        a["n"] += 1
        a["pay"].add(pay.hex(" ") or "(none)")
        a["first"] = min(a["first"], ts)
    print("  %-22s %-12s %-4s %-8s %-5s %s"
          % ("feature", "type", "cmd", "dir", "n", "payload(s)"))
    for (f, t, c, d), a in sorted(agg.items()):
        print("  %-22s %-12s %-4d %-8s %-5d %s"
              % ("%d/%s" % (f, FEATURES.get(f, "?")), TYPES.get(t, "?"), c, d, a["n"],
                 " | ".join(sorted(a["pay"]))))
    acc.append(path)
    return frames


def main():
    argv = sys.argv[1:]
    csvp = argv[argv.index("--csv") + 1] if "--csv" in argv else None
    files = [a for a in argv if not a.startswith("--") and a != csvp]
    if not files:
        print(__doc__)
        return
    acc = []
    for p in files:
        analyze(p, type("A", (), {})(), acc)


if __name__ == "__main__":
    main()
