#!/usr/bin/env python3
"""Reliable driver for the official Moondrop app on a multi-window desktop.

  python3 tools/mdctl.py dump [--all]     dump app window (retries until it is the app)
  python3 tools/mdctl.py tap X Y
  python3 tools/mdctl.py swipe X1 Y1 X2 Y2 [MS]
  python3 tools/mdctl.py find REGEX
  python3 tools/mdctl.py focus
"""
import subprocess, sys, re, html, time

PKG = "com.moondroplab.moondrop.moondrop_app"
ACT = f"{PKG}/.MainActivity"
XMLW = "/storage/emulated/0/HyperPods for Moondrop/_refs/_device/_ui.xml"


def sh(c):
    return subprocess.run(["su", "-c", c], capture_output=True, text=True).stdout


def dump_xml(tries=8):
    for i in range(tries):
        sh(f"am start -n {ACT} >/dev/null 2>&1; sleep 1; rm -f /data/local/tmp/ui.xml; "
           f"uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml")
        x = subprocess.run(["su", "-c", "cat /data/local/tmp/ui.xml"],
                           capture_output=True, text=True).stdout
        if PKG in x:
            open(XMLW, "w").write(x)
            return x
    open(XMLW, "w").write(x)
    return x


def nodes(x):
    out = []
    for m in re.finditer(r"<node\b([^>]*?)/?>", x):
        a = m.group(1)
        def g(k):
            mm = re.search(r'%s="([^"]*)"' % k, a)
            return html.unescape(mm.group(1)) if mm else ""
        bm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", g("bounds"))
        if not bm:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        out.append(dict(cls=g("class").split(".")[-1], text=g("text"),
                        desc=g("content-desc"), rid=g("resource-id").split("/")[-1],
                        click=g("clickable") == "true", scroll=g("scrollable") == "true",
                        chk=g("checked"), cx=(x1 + x2) // 2, cy=(y1 + y2) // 2,
                        box=(x1, y1, x2, y2)))
    return out


def show(ns, all_=False):
    print("  box[x1,y1][x2,y2]      ctr     clk scrl chk  class            text / desc")
    for r in ns:
        if not all_ and not (r["text"] or r["desc"] or r["click"]):
            continue
        lab = r["text"] + ((" || " + r["desc"]) if r["desc"] else "")
        lab = lab.replace("\n", " ⏎ ")
        print("  [%4d,%4d][%4d,%4d] (%4d,%4d) %-3s %-4s %-4s %-16s %s" % (
            r["box"][0], r["box"][1], r["box"][2], r["box"][3], r["cx"], r["cy"],
            "C" if r["click"] else "-", "S" if r["scroll"] else "-",
            r["chk"][:4], r["cls"], lab[:110]))


def main():
    c = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if c == "dump":
        x = dump_xml()
        print("pkg_present=%s" % (PKG in x))
        show(nodes(x), "--all" in sys.argv)
    elif c == "tap":
        x, y = sys.argv[2], sys.argv[3]
        print("TAP", x, y, "at", time.strftime("%H:%M:%S"))
        sh(f"input tap {x} {y}")
    elif c == "swipe":
        a = sys.argv[2:7]
        print("SWIPE", a, "at", time.strftime("%H:%M:%S"))
        sh("input swipe " + " ".join(a) + (" " if len(a) == 5 else " 300"))
    elif c == "find":
        x = dump_xml()
        pat = re.compile(sys.argv[2], re.I)
        show([r for r in nodes(x)
              if pat.search(r["text"] or "") or pat.search(r["desc"] or "")
              or pat.search(r["rid"] or "")])
    elif c == "focus":
        sh(f"am start -n {ACT} >/dev/null 2>&1")
        print(sh("dumpsys window | grep -E 'mCurrentFocus'"))


if __name__ == "__main__":
    main()
