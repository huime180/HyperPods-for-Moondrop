#!/usr/bin/env python3
"""Extract real Qualcomm GAIA frames from the Moondrop app's own logcat.

Why this tool exists: on HyperOS 4.0 the btsnoop HCI log truncates every
RFCOMM-bearing ACL record to 10 bytes of ACL data, which ends exactly at the
app's SPP wrapper ('FF 04 00') -- the GAIA frame itself is never written to the
snoop log.  The app, however, logs the exact bytes it hands to the RFCOMM
OutputStream and every GAIA packet it receives, so its logcat is the
authoritative record of real on-wire GAIA traffic.

  adb shell logcat -s System.out > cap.log     # or the in-device capture
  python3 tools/parse_gaia_logcat.py cap.log [--csv out.csv]

Emits: time | dir | transport bytes (SPP) | feature(id/name) | type | cmd | payload
"""
import re, sys, csv, collections

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
TS = re.compile(r"(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})")


def hexbytes(s):
    return bytes(int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", s))


def bytearr(s):
    return bytes(int(x) & 0xFF for x in re.findall(r"-?\d+", s))


def decode(frame):
    if len(frame) < 4:
        return None
    vend = (frame[0] << 8) | frame[1]
    cw = (frame[2] << 8) | frame[3]
    return vend, (cw >> 9) & 0x7F, (cw >> 7) & 3, cw & 0x7F, frame[4:]



# ---- feature 22 TOUCHV2: payload is 5 bytes, one per gesture -----------------
# byte i encodes BOTH ears as two 4-bit action ids:  high nibble = LEFT, low = RIGHT
# proven from the app's own bytecode
# (com.qualcomm.qti.gaiaclient...TouchNewInfo.<init>: singleL=(b>>4)&0xF,
#  singleR=b&0xF, then double/triple/ones1s/threes3s over bytes 1..4).
TOUCH_GESTURES = ["single-tap", "double-tap", "triple-tap", "long-press-1s", "long-press-3s"]
TOUCH_ACTIONS = {0: "NONE", 1: "PLAY/PAUSE", 2: "PREV", 3: "NEXT",
                 4: "VOL+", 5: "VOL-", 6: "VOICE-ASSISTANT", 7: "ANC-TOGGLE"}


def touchv2_str(pay):
    out = []
    for i, b in enumerate(pay[:5]):
        L, R = (b >> 4) & 0xF, b & 0xF
        out.append("%s L=%d/%s R=%d/%s" % (TOUCH_GESTURES[i], L,
                                           TOUCH_ACTIONS.get(L, "id%d" % L),
                                           R, TOUCH_ACTIONS.get(R, "id%d" % R)))
    return " | ".join(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    csvp = None
    if "--csv" in sys.argv:
        csvp = sys.argv[sys.argv.index("--csv") + 1]
        args = [a for a in args if a != csvp]
    for path in args:
        sends, raw_send, rows = [], {}, []
        for line in open(path, errors="replace"):
            m = TS.search(line)
            ts = m.group(1)[-12:] if m else ""
            m = re.search(r"SendingThread: sendData: bytes = (\[[^\]]*\])", line)
            if m:
                raw_send[ts] = bytearr(m.group(1))
                continue
            m = re.search(r"Plugin: send: packet = ((?:0x[0-9A-Fa-f]{2}\s*)+)", line)
            if m:
                rows.append((ts, "app->bud", "SEND", hexbytes(m.group(1))))
                continue
            m = re.search(r"onPacketReceived received = ((?:0x[0-9A-Fa-f]{2}\s*)+)", line)
            if m:
                rows.append((ts, "bud->app", "RECV", hexbytes(m.group(1))))
                continue
            m = re.search(r"onReceiveGaiaPacket: received = (\[[^\]]*\])", line)
            if m:
                rows.append((ts, "bud->app", "RECV", bytearr(m.group(1))))
        # de-duplicate: the app logs each received frame twice (array + hex form)
        ded, seen = [], set()
        for r in rows:
            k = (r[0], r[1], r[3])
            if k in seen:
                continue
            seen.add(k)
            ded.append(r)
        rows = ded
        print("\n" + "=" * 108)
        print("=== %s   (%d GAIA frames)" % (path, len(rows)))
        print("  %-12s %-8s %-6s %-12s %-16s %-12s %-4s  %-28s %s"
              % ("time", "dir", "kind", "vendor", "feature", "type", "cmd", "payload",
                 "SPP bytes written"))
        agg = collections.OrderedDict()
        for ts, d, kind, fr in rows:
            dec = decode(fr)
            if not dec:
                continue
            vend, f, t, c, pay = dec
            if vend == 0x000A:      # GAIA V2 header layout -> not a V3 feature
                print("  %-12s %-8s %-6s 0x%04X  GAIA-V2 (vendor 0x000A, V2 layout)      %s"
                      % (ts, d, kind, vend, fr.hex(" ")))
                continue
            spp = raw_send.get(ts, b"") if kind == "SEND" else b""
            print("  %-12s %-8s %-6s 0x%04X       %-16s %-12s %-4d  %-28s %s"
                  % (ts, d, kind, vend, "%d/%s" % (f, FEATURES.get(f, "?")),
                     TYPES.get(t, "?"), c, pay.hex(" ") or "-",
                     spp.hex(" ") if spp else ""))
            if f == 22 and len(pay) >= 5:
                print("        ^ TOUCHV2 cmd %d (%s): %s" % (
                    c, "GET_CURRENT_ACTION" if c == 2 else
                       "GET_DEFAULT_ACTION" if c == 1 else
                       "SET_CURRENT_ACTION" if c == 3 else "?", touchv2_str(pay)))
            k = (f, t, c, d)
            a = agg.setdefault(k, {"n": 0, "pay": set(), "spp": set()})
            a["n"] += 1
            a["pay"].add(pay.hex(" ") or "-")
            if spp:
                a["spp"].add(spp.hex(" "))
        print("\n  --- distinct (feature, type, cmd, direction) ---")
        print("  %-22s %-12s %-4s %-8s %-5s %s"
              % ("feature", "type", "cmd", "dir", "n", "payload(s)"))
        for (f, t, c, d), a in sorted(agg.items()):
            print("  %-22s %-12s %-4d %-8s %-5d %s"
                  % ("%d/%s" % (f, FEATURES.get(f, "?")), TYPES.get(t, "?"), c, d, a["n"],
                     " | ".join(sorted(a["pay"]))))
        if csvp:
            with open(csvp, "w", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(["time", "dir", "kind", "vendor", "feature", "feature_name",
                            "type", "type_name", "cmd", "payload", "spp_bytes"])
                for ts, d, kind, fr in rows:
                    dec = decode(fr)
                    if not dec:
                        continue
                    vend, f, t, c, pay = dec
                    w.writerow([ts, d, kind, "0x%04X" % vend, f, FEATURES.get(f, "?"),
                                t, TYPES.get(t, "?"), c, pay.hex(" "),
                                raw_send.get(ts, b"").hex(" ")])
            print("  (csv -> %s)" % csvp)


if __name__ == "__main__":
    main()
