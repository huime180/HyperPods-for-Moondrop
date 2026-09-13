#!/usr/bin/env python3
"""btsnoop -> Qualcomm GAIA (QTiL) extractor  (stack-walking, v3)

WHAT THIS ROM ACTUALLY DOES  (verified: Xiaomi Pad 8 Pro / HyperOS 4.0)
---------------------------------------------------------------------
* datalink 0x03EA = HCI H4; records use the standard 24-byte btsnoop header.
* ACL records are TRUNCATED: `incl_len < orig_len` and only the first
  **10 bytes of ACL data** are stored (158/1747 records in a 86 KB sample).
  Those 10 bytes are consumed by L2CAP(4) + RFCOMM(3) + the app's 3-byte SPP
  wrapper, so the GAIA frame itself is NEVER present in the snoop log.
  => GAIA bytes must be taken from the app's own logcat
     (tools/parse_gaia_logcat.py).  This tool still proves the TRANSPORT.
* The Moondrop app talks GAIA over **RFCOMM / SPP on BR/EDR** (it opens an
  RFCOMM socket to the buds), NOT over BLE GATT.  dumpsys reports the bond as
  `[ACL BR/EDR:Y LE:N]` and there is no LE connection at all.
* RFCOMM traffic appears on a dynamic L2CAP CID (this ROM logs the per-record
  channel id: 0x0040..0x0052 / 0x0140..0x0144), so we do NOT filter on CID 3.
* The app wraps each GAIA frame in a 4-byte SPP header:
        FF 04 00 <n>   +   <GAIA frame>
  where 0x04 is the app's "protocolVersion 4" and <n> counts the GAIA bytes
  that follow the 4-byte GAIA header (vendor u16 + commandWord u16).

Walk: HCI H4 -> ACL (reassembled per handle/PB) -> L2CAP -> payload
        payload -> RFCOMM frame -> info -> GAIA   (this device's real path)
        payload -> ATT PDU (cid 0x0004)  -> value -> GAIA   (BLE path)
        payload -> raw GAIA scan                            (fallback)

Usage:
  python3 tools/btsnoop_gaia.py <log> [...] [--att] [--spp] [--csv F]
"""
import struct, sys, datetime, collections, csv

FEATURES = {
    0: "BASIC", 1: "EARBUD", 2: "ANC_V1", 3: "VOICE_UI", 4: "DEBUG", 5: "MUSIC",
    6: "UPGRADE", 7: "HANDSET", 8: "AUDIO_CURATION", 9: "EARBUD_FIT",
    10: "VOICE_PROC", 11: "GESTURE", 12: "STATS", 13: "BATTERY", 14: "VOICE",
    15: "DAC_GAIN", 16: "CODEC_TYPE", 17: "LIGHT", 18: "SPATIAL", 19: "LED",
    20: "ONEBRINGTWO", 21: "BT_ADDR", 22: "TOUCHV2", 23: "AUDIO_RESOURCE",
    24: "POWER", 25: "POWER_TIMEOUT", 26: "TOUCHV3", 27: "DYBASS",
    29: "FILE_STORAGE", 30: "LR_CHANNEL", 32: "ANC_V2",
}
TYPES = {0: "COMMAND", 1: "NOTIFICATION", 2: "RESPONSE", 3: "ERROR"}
RFCOMM_CTRL = {0x2F, 0x3F, 0x63, 0x73, 0x0F, 0x1F, 0x43, 0x53,
               0xEF, 0xFF, 0x03, 0x13, 0xE3, 0xF3}
SPP_MAGIC = b"\xff\x04\x00"


def ts_str(us):
    try:
        return datetime.datetime.fromtimestamp(
            946684800 + us / 1e6 + 8 * 3600, datetime.UTC).strftime("%H:%M:%S.%f")[:12]
    except Exception:
        return str(us)


def iter_records(path):
    data = open(path, "rb").read()
    if not data.startswith(b"btsnoop\x00"):
        raise SystemExit("%s: not a btsnoop file" % path)
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
    """GAIA V3 frame = [vendor u16][commandWord u16][payload]. Vendor 0x001D."""
    if len(buf) < 4:
        return None
    vend = (buf[0] << 8) | buf[1]
    if vend != 0x001D:                     # only V3 -- avoids 00 00 false hits
        return None
    cw = (buf[2] << 8) | buf[3]
    f, t, c = (cw >> 9) & 0x7F, (cw >> 7) & 3, cw & 0x7F
    if f > 33:                             # implausible feature -> not GAIA
        return None
    return vend, f, t, c, bytes(buf[4:])


def gaia_in(info):
    """Find GAIA in an RFCOMM/ATT payload: SPP-wrapped or bare."""
    if info.startswith(SPP_MAGIC):
        f = decode_gaia(info[4:])
        if f:
            return f, True
        return ("TRUNCATED-BY-ROM",) + (info[4:].hex(" "),), True
    f = decode_gaia(info)
    if f:
        return f, False
    i = info.find(b"\x00\x1d")
    if 0 < i < 8:
        f = decode_gaia(info[i:])
        if f:
            return f, False
    return None


def rfcomm_infos(pl):
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
            if j + 1 >= n:
                return
            ln = (b >> 1) | (pl[j + 1] << 7)
            j += 2
        yield dlci, bytes(pl[j:j + ln])
        i = j + ln


def analyze(path, args):
    frames, spp_ev, cidstat, gatt, aclbuf = [], [], collections.Counter(), {}, {}
    trunc_n = acl_n = 0
    for ts, flags, orig, incl, pkt in iter_records(path):
        if not pkt:
            continue
        d = "bud->app" if (flags & 1) else "app->bud"
        if pkt[0] != 0x02:
            continue
        acl_n += 1
        if incl < orig:
            trunc_n += 1
        hp = struct.unpack("<H", pkt[1:3])[0]
        dlen = struct.unpack("<H", pkt[3:5])[0]
        pb, h = (hp >> 12) & 3, hp & 0xFFF
        key = (h, d)
        if pb == 1:
            aclbuf[key] = aclbuf.get(key, b"") + pkt[5:5 + dlen]
            continue
        aclbuf[key] = pkt[5:5 + dlen]
        buf = aclbuf[key]
        if len(buf) < 4:
            continue
        l2len = struct.unpack("<H", buf[0:2])[0]
        cid = struct.unpack("<H", buf[2:4])[0]
        pl = buf[4:4 + l2len]
        if not pl:
            continue
        cidstat[(hex(cid), incl < orig)] += 1
        if cid == 0x0004:                                  # ATT
            if pl[0] in (0x12, 0x52, 0x1B, 0x1D):
                r = gaia_in(pl[3:])
                if r:
                    frames.append((ts, d, "ATT", h, None, r))
            continue
        got = False
        for dlci, info in rfcomm_infos(pl):
            if not info:
                continue
            if info.startswith(SPP_MAGIC):
                spp_ev.append((ts, d, cid, info))
            r = gaia_in(info)
            if r:
                frames.append((ts, d, "RFCOMM", h, dlci, r))
                got = True
                break
        if not got:
            r = gaia_in(pl)
            if r:
                frames.append((ts, d, "RFCOMM", h, None, r))

    print("\n" + "=" * 100)
    print("=== %s" % path)
    print("    ACL records: %d   truncated by ROM: %d (%.0f%%)   L2CAP CIDs: %s"
          % (acl_n, trunc_n, 100.0 * trunc_n / max(acl_n, 1),
             ", ".join("%s%s" % (c, "*" if t else "")
                       for (c, t) in sorted(cidstat))))
    print("    ('*' = that CID only ever appeared in truncated ACL records)")
    print("    SPP-wrapped GAIA writes seen (FF 04 00 ...): %d" % len(spp_ev))
    if spp_ev:
        print("      first few: " + "; ".join(
            "%s %s cid=0x%04X %s" % (ts_str(t), dd, c, i.hex(" "))
            for t, dd, c, i in spp_ev[:5]))
    if frames:
        print("\n  %-12s %-8s %-7s %-5s %-5s %-16s %-12s %-4s  %s"
              % ("time", "dir", "trans", "hdl", "dlci", "feature", "type", "cmd", "payload"))
        for ts, d, tr, h, dlci, r in frames:
            inner, wrapped = r
            if inner[0] == "TRUNCATED-BY-ROM":
                print("  %-12s %-8s %-7s %-5s %-5s GAIA frame cut by ROM truncation: %s"
                      % (ts_str(ts), d, tr, h, dlci if dlci is not None else "-", inner[1]))
                continue
            vend, f, t, c, pay = inner
            print("  %-12s %-8s %-7s %-5s %-5s %-16s %-12s %-4d  %s"
                  % (ts_str(ts), d, tr, h, dlci if dlci is not None else "-",
                     "%d/%s" % (f, FEATURES.get(f, "?")), TYPES.get(t, "?"), c,
                     pay.hex(" ")))
    else:
        print("\n    No complete GAIA frame is recoverable from this log.")
        print("    Reason: every RFCOMM-bearing ACL record was truncated to 10 bytes")
        print("    of ACL data, which ends inside the app's SPP wrapper. Use the app's")
        print("    own logcat (tools/parse_gaia_logcat.py) for the GAIA bytes.")


def main():
    argv = sys.argv[1:]
    csvp = argv[argv.index("--csv") + 1] if "--csv" in argv else None
    files = [a for a in argv if not a.startswith("--") and a != csvp]
    if not files:
        print(__doc__)
        return
    for p in files:
        analyze(p, type("A", (), {})())


if __name__ == "__main__":
    main()
