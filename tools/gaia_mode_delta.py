#!/usr/bin/env python3
"""按 MARK 窗口差分 GAIA 帧 —— 找「四击切换音乐/游戏模式」到底发了什么。

配合 tools/gaia_mode_capture.sh 用（那个脚本把 `### MARK <标签> <时间>` 直接写进
和帧同一条流里，所以窗口切分不需要猜时间）：

    python3 tools/gaia_mode_delta.py gaia_mode.log [--csv out.csv]

输出三块：
  1) 每个窗口的帧数，以及 (feature, type, cmd) 的计数；
  2) **只在 tap 窗口出现、基线窗口没有**的 (feature, type, cmd) —— 模式切换的候选；
  3) 这些候选帧的原文与解码（payload 逐字节），供进一步判定语义。

帧的识别/解码完全沿用 tools/parse_gaia_logcat.py（同一套正则与 GAIA 头解码），
避免两处实现漂移。
"""
import collections
import csv
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parse_gaia_logcat as P

# 两种来源都认：脚本用 `log -t PODMARK` 打的（推荐，不会被 logcat 覆盖），
# 以及离线手写的 "### MARK <标签> <时间>"。
MARK = re.compile(r"(?:PODMARK:?\s*)?### MARK (\S+)\s+(\d\d:\d\d:\d\d)")
SEND_HEX = re.compile(r"Plugin: send: packet = ((?:0x[0-9A-Fa-f]{2}\s*)+)")
RECV_HEX = re.compile(r"onPacketReceived received = ((?:0x[0-9A-Fa-f]{2}\s*)+)")
RECV_ARR = re.compile(r"onReceiveGaiaPacket: received = (\[[^\]]*\])")


def load(path):
    """-> marks: [(label, time)]；wins: OrderedDict[label -> [(ts, dir, kind, bytes)]]"""
    marks = [("start", "")]
    wins = collections.OrderedDict([("start", [])])
    cur = "start"
    for line in open(path, errors="replace"):
        m = MARK.search(line)
        if m:
            cur = m.group(1)
            marks.append((cur, m.group(2)))
            wins.setdefault(cur, [])
            continue
        ts = P.TS.search(line).group(1)[-12:] if P.TS.search(line) else ""
        fr = d = k = None
        if SEND_HEX.search(line):
            fr, d, k = P.hexbytes(SEND_HEX.search(line).group(1)), "app->bud", "SEND"
        elif RECV_HEX.search(line):
            fr, d, k = P.hexbytes(RECV_HEX.search(line).group(1)), "bud->app", "RECV"
        elif RECV_ARR.search(line):
            fr, d, k = P.bytearr(RECV_ARR.search(line).group(1)), "bud->app", "RECV"
        if fr:
            wins[cur].append((ts, d, k, fr))
    # 去重：APP 把同一帧打印两次
    for label, rows in wins.items():
        seen, ded = set(), []
        for r in rows:
            key = (r[0], r[1], r[3])
            if key in seen:
                continue
            seen.add(key)
            ded.append(r)
        wins[label] = ded
    return marks, wins


def keyof(frame):
    dec = P.decode(frame)
    if not dec:
        return None
    vend, f, t, c, pay = dec
    return (f, P.TYPES.get(t, "type%d" % t), c)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    csvp = sys.argv[sys.argv.index("--csv") + 1] if "--csv" in sys.argv else None
    if not args:
        print(__doc__)
        return 1

    marks, wins = load(args[0])
    print("窗口标记：", " -> ".join("%s(%s)" % (l, t) if t else l for l, t in marks))
    counts = {}
    for label, rows in wins.items():
        c = collections.Counter()
        for ts, d, k, fr in rows:
            key = keyof(fr)
            if key:
                c[(key[0], key[1], key[2], d)] += 1
        counts[label] = c
        print("\n=== 窗口 %-10s 帧数 %d" % (label, len(rows)))
        for (f, t, cmd, d), n in sorted(c.items(), key=lambda x: -x[1])[:12]:
            print("   %-18s %-11s cmd=%-4d %-8s x%d"
                  % ("%d/%s" % (f, P.FEATURES.get(f, "?")), t, cmd, d, n))

    # tap 窗口 = 标签以 tap 开头的窗口；基线 = 其余
    taps = [l for l in wins if l.lower().startswith("tap")]
    base_keys = set()
    for l, c in counts.items():
        if l not in taps:
            base_keys |= set((f, t, cmd) for (f, t, cmd, d) in c)
    cand = collections.OrderedDict()
    for l in taps:
        for (f, t, cmd, d), n in counts[l].items():
            if (f, t, cmd) not in base_keys:
                cand.setdefault((f, t, cmd, d), []).append((l, n))
    print("\n########## 只在四击窗口出现的帧（模式切换候选）##########")
    if not cand:
        print("（无）—— 说明四击切换**没有**任何 GAIA 帧上报；"
              "改走「GET 轮询可疑 feature 比对前后值」那条路")
    for (f, t, cmd, d), occ in cand.items():
        print("\n* feature %d/%s  %s  cmd=%d  方向=%s  出现: %s"
              % (f, P.FEATURES.get(f, "?"), t, cmd, d,
                 ", ".join("%s x%d" % o for o in occ)))
        for label in taps:
            for ts, dd, kk, fr in wins[label]:
                key = keyof(fr)
                if key and (key[0], key[1], key[2]) == (f, t, cmd) and dd == d:
                    print("    %s  %-12s %s" % (label, ts, fr.hex(" ")))
                    break

    if csvp:
        with open(csvp, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["window", "time", "dir", "kind", "vendor", "feature", "ftype", "cmd", "payload"])
            for label, rows in wins.items():
                for ts, d, k, fr in rows:
                    dec = P.decode(fr)
                    if not dec:
                        continue
                    vend, f, t, c, pay = dec
                    w.writerow([label, ts, d, k, "0x%04X" % vend,
                                "%d/%s" % (f, P.FEATURES.get(f, "?")),
                                P.TYPES.get(t, t), c, pay.hex(" ")])
        print("\nCSV ->", csvp)
    return 0


if __name__ == "__main__":
    sys.exit(main())
