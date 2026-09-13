#!/usr/bin/env python3
"""Proper btsnoop -> GAIA extractor.

Walks the real Bluetooth stack instead of scanning raw bytes:
  HCI H4 record -> ACL (0x02) -> L2CAP (cid) ->
      cid 0x0004 ATT  -> ATT PDU -> attribute value
      cid 0x0003 RFCOMM -> DLCI/UIH -> SPP payload
Also records GATT discovery (Read By Group Type / Read By Type / Find Info)
so ATT handles can be mapped back to the
000011xx-d102-11e1-9b23-00025b00a5a5 UUID family.

Usage:
  python3 tools/btsnoop_gaia.py <log> [<log> ...] [--all] [--att] [--csv out.csv]

  --all   also list every ATT write/notification value (not just GAIA frames)
  --att   dump the raw ATT/GATT descriptor traffic (discovery + handles)
  --csv   append every GAIA frame as CSV
"""
import struct, sys, datetime, csv

# ---------------------------------------------------------------- constants
FEATURES = {
    0: "BASIC", 1: "EARBUD", 2: "ANC_V1", 3: "VOICE_UI", 4: "DEBUG", 5: "MUSIC",
    6: "UPGRADE", 7: "HANDSET", 8: "AUDIO_CURATION", 9: "EARBUD_FIT",
    10: "VOICE_PROC", 11: "GESTURE", 12: "STATS", 13: "BATTERY", 14: "VOICE",
    15: "DAC_GAIN", 16: "CODEC_TYPE", 17: "LIGHT", 18: "SPATIAL", 19: "LED",
    20: "ONEBRINGTWO", 21: "BT_ADDR", 22: "TOUCHV2", 23: "AUDIO_RESOURCE",
    24: "POWER", 25: "POWER_TIMEOUT", 26: "TOUCHV3", 27: "DYBASS", 28: "?28",
    29: "FILE_STORAGE", 30: "LR_CHANNEL", 31: "?31", 32: "ANC_V2",
}
TYPES = {0: "COMMAND", 1: "NOTIFICATION", 2: "RESPONSE", 3: "ERROR"}

GAIA_SVC = "00001100-d102-11e1-9b23-00025b00a5a5"
GAIA_SVC_B = bytes.fromhex("a5a5005b0200 239be1 11 02d1 00110000".replace(" ", ""))


def uuid_str(b: bytes) -> str:
    """Bluetooth UUID bytes are little-endian on the wire."""
    if len(b) == 2:
        v = struct.unpack("<H", b)[0]
        return f"0x{v:04X}"
    if len(b) == 16:
        s = b[::-1].hex()
        return f"{s[0:8]}-{s[8:12]}-{s[12:16]}-{s[16:20]}-{s[20:32]}"
    return b.hex()


def ts_str(us_since_2000: int) -> str:
    # microseconds since 2000-01-01 00:00:00 UTC
    epoch2000 = 946684800
    try:
        return datetime.datetime.utcfromtimestamp(
            epoch2000 + us_since_2000 / 1e6).strftime("%H:%M:%S.%f")
    except Exception:
        return f"us={us_since_2000}"


# ---------------------------------------------------------------- btsnoop
def iter_records(path):
    with open(path, "rb") as f:
        data = f.read()
    if not data.startswith(b"btsnoop\x00"):
        raise SystemExit(f"{path}: not a btsnoop file")
    datalink = struct.unpack(">I", data[12:16])[0]
    if datalink != 1002:
        print(f"# warning: datalink={datalink} (expected 1002 HCI H4)")
    off, n = 16, len(data)
    while off + 24 <= n:
        orig, incl, flags, drops = struct.unpack(">IIII", data[off:off + 16])
        ts = struct.unpack(">Q", data[off + 16:off + 24])[0]
        off += 24
        if off + incl > n:
            break
        yield ts, flags, data[off:off + incl]
        off += incl


# ---------------------------------------------------------------- ATT table
class Gatt:
    def __init__(self):
        self.h2u = {}       # handle -> uuid bytes

    def note(self, handle, uuid_bytes):
        if handle not in self.h2u:
            self.h2u[handle] = uuid_bytes

    def name(self, handle):
        u = self.h2u.get(handle)
        return uuid_str(u) if u else "?"


def parse_att(payload, gatt, out):
    """payload = ATT PDU. out = list to append (opcode, handle, value)."""
    if not payload:
        return
    op = payload[0]
    try:
        if op in (0x12, 0x52, 0x1B, 0x1D):          # write req/cmd, notify, indicate
            h = struct.unpack("<H", payload[1:3])[0]
            out.append((op, h, payload[3:]))
        elif op in (0x0A, 0x0C, 0x0E):              # read req / read blob / read multi
            h = struct.unpack("<H", payload[1:3])[0]
            out.append((op, h, b""))
        elif op == 0x08:                            # read response
            out.append((op, None, payload[1:]))
        elif op in (0x08, 0x10, 0x06) and op != 0x08:
            pass
        elif op == 0x04:                            # find information request
            a, b = struct.unpack("<HH", payload[1:5])
            out.append((op, a, b""))
        elif op == 0x05:                            # find information response
            fmt = payload[1]
            step = 4 if fmt == 1 else 18
            for i in range(2, len(payload) - step + 1, step):
                h = struct.unpack("<H", payload[i:i + 2])[0]
                ub = payload[i + 2:i + 2 + (2 if fmt == 1 else 16)]
                gatt.note(h, ub)
            out.append((op, None, payload[1:]))
        elif op == 0x08 - 0 == 0x08:
            pass
        elif op == 0x09:                            # read by type response
            ln = payload[1]
            step = ln + 1
            for i in range(2, len(payload) - step + 1, step):
                h = struct.unpack("<H", payload[i:i + 2])[0]
                val = payload[i + 2:i + 1 + step]
                # 0x2803 characteristic declaration: props(1)+valuehandle(2)+uuid
                if len(val) >= 3:
                    vh = struct.unpack("<H", val[1:3])[0]
                    gatt.note(h, b"\x00\x00")
                    gatt.note(vh, val[3:])
                    gatt.h2u[h] = b"\x00\x00" if not val[3:] else val[3:]
            out.append((op, None, payload[1:]))
        elif op == 0x11:                            # read by group type response
            ln = payload[1]
            step = ln + 1
            for i in range(2, len(payload) - step + 1, step):
                h = struct.unpack("<H", payload[i:i + 2])[0]
                gatt.note(h, payload[i + 4:i + 1 + step])
            out.append((op, None, payload[1:]))
        else:
            out.append((op, None, payload[1:]))
    except Exception:
        pass


# ---------------------------------------------------------------- RFCOMM
def rfcomm_infos(pl):
    """Yield (dlci, info_bytes) for RFCOMM frames in an L2CAP payload."""
    i, n = 0, len(pl)
    while i + 4 <= n:
        addr = pl[i]
        ctrl = pl[i + 1]
        ea = addr & 1
        dlci = (addr >> 2) & 0x3F
        j = i + 2
        if j >= n:
            break
        ln = pl[j]
        if ln & 1:
            j += 1
        else:
            if j + 1 >= n:
                break
            ln = (pl[j] >> 1) | (pl[j + 1] << 7)
            j += 2
        if ctrl & 0xEF == 0xEF:          # UIH
            yield dlci, pl[j:j + ln]
            i = j + ln
        else:                            # MCC / SABM etc.
            yield dlci, pl[j:j + ln]
            i = j + ln
        if ea == 0:
            break


# ---------------------------------------------------------------- GAIA
def find_gaia(buf):
    """Return list of (offset, vendor, cmdword, payload) for GAIA frames."""
    res = []
    i, n = 0, len(buf)
    while i + 4 <= n:
        vend = (buf[i] << 8) | buf[i + 1]
        if vend in (0x001D, 0x000A, 0x0000):
            cw = (buf[i + 2] << 8) | buf[i + 3]
            res.append((i, vend, cw, bytes(buf[i + 4:])))
            break                        # one frame per ATT value
        i += 1
    return res


def decode_cw(cw):
    return ((cw >> 9) & 0x7F, (cw >> 7) & 0x03, cw & 0x7F)


# ---------------------------------------------------------------- main
def analyze(path, args):
    gatt = Gatt()
    frames = []          # (ts, dir, transport, handle, vid, feat, typ, cmd, payload)
    att_vals = []        # (ts, dir, handle, op, value)
    att_log = []
    le_peers = set()
    handles_l2cap_cids = {}
    acl_bufs = {}        # (handle,dir) -> bytearray

    for ts, flags, pkt in iter_records(path):
        if not pkt:
            continue
        direction = "bud->app" if (flags & 1) else "app->bud"
        h4 = pkt[0]

        if h4 == 0x04:                                   # HCI event
            ev = pkt[1]
            if ev == 0x3E and len(pkt) >= 7 and pkt[3] == 0x01:   # LE Conn Complete
                st, hh = pkt[4], pkt[5]
                peer = pkt[7:13]
                if st == 0:
                    le_peers.add((hh, peer[::-1].hex(":")))
            continue
        if h4 != 0x02:                                   # only ACL has L2CAP
            continue

        hp = struct.unpack("<H", pkt[1:3])[0]
        dlen = struct.unpack("<H", pkt[3:5])[0]
        body = pkt[5:5 + dlen]
        pb = (hp >> 12) & 0x3
        h = hp & 0x0FFF
        key = (h, direction)
        if pb == 0x01:                                   # continuation
            if key in acl_bufs:
                acl_bufs[key] += body
            else:
                acl_bufs[key] = bytearray(body)
                continue
        else:
            acl_bufs[key] = bytearray(body)
        buf = bytes(acl_bufs[key])
        if len(buf) < 4:
            continue
        l2len = struct.unpack("<H", buf[0:2])[0]
        cid = struct.unpack("<H", buf[2:4])[0]
        l2 = buf[4:4 + l2len]
        if pb == 0x01:
            continue
        handles_l2cap_cids[h] = cid

        if cid == 0x0004:                                # ATT
            got = []
            parse_att(l2, gatt, got)
            for op, ahandle, value in got:
                att_log.append((ts, direction, op, ahandle, value))
                if op in (0x12, 0x52, 0x1B, 0x1D):
                    att_vals.append((ts, direction, ahandle, op, value))
                    for off, vid, cw, pl in find_gaia(value):
                        f, t, c = decode_cw(cw)
                        frames.append((ts, direction, "ATT", ahandle, vid, f, t, c, pl))
        elif cid == 0x0003:                              # RFCOMM
            for dlci, info in rfcomm_infos(l2):
                if not info:
                    continue
                att_vals.append((ts, direction, dlci, 0xFF, info))
                for off, vid, cw, pl in find_gaia(info):
                    f, t, c = decode_cw(cw)
                    frames.append((ts, direction, "RFCOMM", dlci, vid, f, t, c, pl))

    # ---------------------------------------------------------- report
    print(f"\n{'='*100}\n=== {path}")
    print(f"    GAIA frames: {len(frames)}   ATT values: {len(att_vals)}   "
          f"GATT handles known: {len(gatt.h2u)}")
    if le_peers:
        print(f"    LE connections: " +
              ", ".join(f"handle={hh} peer={m}" for hh, m in sorted(le_peers)))

    if frames:
        print(f"\n  {'time':>15} {'dir':>8} {'trans':>7} {'hdl':>5} {'uuid':>38} "
              f"{'vendor':>6} {'feature':<16} {'type':<12} {'cmd':>4}  payload")
        for ts, d, tr, hd, vid, f, t, c, pl in frames:
            uu = gatt.name(hd) if tr == "ATT" else f"dlci{hd}"
            print(f"  {ts_str(ts):>15} {d:>8} {tr:>7} {hd:>5} {uu:>38} "
                  f"0x{vid:04X} {f:>3}/{FEATURES.get(f,'?'):<12} {TYPES.get(t,'?'):<12} "
                  f"{c:>4}  {pl.hex(' ')}")

        print("\n  --- distinct frames ---")
        seen = {}
        for ts, d, tr, hd, vid, f, t, c, pl in frames:
            k = (f, t, c, pl, d)
            seen.setdefault(k, []).append(ts)
        print(f"  {'feature':<22} {'type':<12} {'cmd':>4} {'dir':>8} {'n':>4}  payload")
        for (f, t, c, pl, d), tss in sorted(seen.items()):
            print(f"  {f:>3}/{FEATURES.get(f,'?'):<18} {TYPES.get(t,'?'):<12} {c:>4} "
                  f"{d:>8} {len(tss):>4}  {pl.hex(' ')}")
    else:
        print("  (no GAIA frames found)")

    if args.att:
        print("\n  --- ATT/GATT traffic ---")
        OPS = {0x04: "FindInfoReq", 0x05: "FindInfoRsp", 0x08: "ReadByTypeReq",
               0x09: "ReadByTypeRsp", 0x0A: "ReadReq", 0x0B: "ReadRsp",
               0x10: "ReadByGroupReq", 0x11: "ReadByGroupRsp", 0x12: "WriteReq",
               0x13: "WriteRsp", 0x52: "WriteCmd", 0x1B: "Notify", 0x1D: "Indicate",
               0x0C: "ReadBlobReq", 0x0D: "ReadBlobRsp", 0x01: "ErrRsp",
               0x02: "MtuReq", 0x03: "MtuRsp"}
        for ts, d, op, hd, value in att_log:
            print(f"  {ts_str(ts):>15} {d:>8} {OPS.get(op,hex(op)):<16} "
                  f"h={hd if hd is not None else '-':<5} {value.hex(' ')}")
        print("\n  --- handle -> UUID map ---")
        for hd, ub in sorted(gatt.h2u.items()):
            print(f"    h=0x{hd:04X} ({hd:>5})  {uuid_str(ub)}")

    if args.all:
        print("\n  --- every ATT/RFCOMM value ---")
        seen = {}
        for ts, d, hd, op, value in att_vals:
            k = (d, hd, op, value)
            seen.setdefault(k, []).append(ts)
        for (d, hd, op, value), tss in sorted(seen.items(), key=lambda x: x[1][0]):
            print(f"  {ts_str(tss[0]):>15}..{ts_str(tss[-1]):>15} {d:>8} "
                  f"h={hd:<5} op=0x{op:02X} x{len(tss):<4} {value.hex(' ')}")

    if args.csv:
        new = not getattr(args, "_csv_init", False)
        with open(args.csv, "a", newline="") as fh:
            w = csv.writer(fh)
            if new:
                w.writerow(["time", "dir", "transport", "handle", "uuid", "vendor",
                            "feature", "feature_name", "type", "type_name", "cmd", "payload"])
                args._csv_init = True
            for ts, d, tr, hd, vid, f, t, c, pl in frames:
                w.writerow([ts_str(ts), d, tr, hd,
                            gatt.name(hd) if tr == "ATT" else f"dlci{hd}",
                            f"0x{vid:04X}", f, FEATURES.get(f, "?"), t,
                            TYPES.get(t, "?"), c, pl.hex(" ")])
        print(f"  (appended {len(frames)} rows to {args.csv})")


def main():
    argv = sys.argv[1:]
    args = type("A", (), {})()
    args.all = "--all" in argv
    args.att = "--att" in argv
    args.csv = None
    if "--csv" in argv:
        args.csv = argv[argv.index("--csv") + 1]
    files = [a for a in argv if not a.startswith("--") and a != args.csv]
    if not files:
        print(__doc__)
        return
    for p in files:
        analyze(p, args)


if __name__ == "__main__":
    main()
