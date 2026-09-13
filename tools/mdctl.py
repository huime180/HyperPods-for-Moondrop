#!/usr/bin/env python3
"""Driver for the official Moondrop app on a multi-window desktop.

  dump [--all] [--noraise]   dump app window (retries; raises window by title-bar tap)
  tap X Y | swipe X1 Y1 X2 Y2 [MS] | key K
  find REGEX
"""
import subprocess, sys, re, html, time

PKG = "com.moondroplab.moondrop.moondrop_app"
XMLW = "/storage/emulated/0/HyperPods for Moondrop/_refs/_device/_ui.xml"
TITLE_TAP = "1600 167"


def sh(c, t=20):
    try:
        return subprocess.run(["su", "-c", c], capture_output=True, text=True,
                              timeout=t).stdout
    except subprocess.TimeoutExpired:
        return ""


def dump_xml(tries=6, raise_=True):
    x = ""
    for i in range(tries):
        sh("rm -f /data/local/tmp/ui.xml", 10)
        if raise_:
            sh("input tap " + TITLE_TAP, 10)
            time.sleep(0.4)
        sh("timeout 12 uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1", 18)
        x = sh("cat /data/local/tmp/ui.xml", 10)
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
                        chk=g("checked"), sel=g("selected"),
                        cx=(x1 + x2) // 2, cy=(y1 + y2) // 2, box=(x1, y1, x2, y2)))
    return out


def show(ns, all_=False):
    print("  box[x1,y1][x2,y2]      ctr      clk scrl chk sel class            text / desc")
    for r in ns:
        if not all_ and not (r["text"] or r["desc"] or r["click"] or r["scroll"]):
            continue
        lab = (r["text"] + ((" || " + r["desc"]) if r["desc"] else "")).replace("\n", " / ")
        print("  [%4d,%4d][%4d,%4d] (%4d,%4d) %-3s %-4s %-3s %-3s %-16s %s" % (
            r["box"][0], r["box"][1], r["box"][2], r["box"][3], r["cx"], r["cy"],
            "C" if r["click"] else "-", "S" if r["scroll"] else "-",
            r["chk"][:3], r["sel"][:3], r["cls"], lab[:104]))


def main():
    c = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if c == "dump":
        x = dump_xml(raise_="--noraise" not in sys.argv)
        print("pkg=%s len=%d" % (PKG in x, len(x)))
        show(nodes(x), "--all" in sys.argv)
    elif c == "tap":
        print("TAP %s %s @%s" % (sys.argv[2], sys.argv[3], time.strftime("%H:%M:%S")))
        sh("input tap %s %s" % (sys.argv[2], sys.argv[3]), 10)
    elif c == "key":
        sh("input keyevent %s" % sys.argv[2], 10)
        print("KEY", sys.argv[2], time.strftime("%H:%M:%S"))
    elif c == "swipe":
        a = sys.argv[2:7]
        print("SWIPE", a, time.strftime("%H:%M:%S"))
        sh("input swipe " + " ".join(a) + ("" if len(a) == 5 else " 400"), 15)
    elif c == "tapdesc":
        _tapdesc_main()
    elif c == "find":
        x = dump_xml()
        pat = re.compile(sys.argv[2], re.I)
        show([r for r in nodes(x)
              if pat.search(r["text"] or "") or pat.search(r["desc"] or "")])




# ---- adaptive helpers (appended) ----
def _tapdesc_main():
    import sys as _s
    pat = re.compile(_s.argv[2], re.I)
    want_last = "--last" in _s.argv
    want_all = "--all" in _s.argv
    x = dump_xml(raise_=False, tries=10)
    cands = [r for r in nodes(x)
             if r["click"] and (pat.search(r["desc"] or "") or pat.search(r["text"] or ""))]
    if not cands:
        x = dump_xml(raise_=True, tries=10)
        cands = [r for r in nodes(x)
                 if r["click"] and (pat.search(r["desc"] or "") or pat.search(r["text"] or ""))]
    if not cands:
        print("NO MATCH for %r" % _s.argv[2]); return
    if want_all:
        for r in cands:
            print("  match (%d,%d) %r" % (r["cx"], r["cy"], (r["desc"] or r["text"])[:50]))
        return
    r = cands[-1] if want_last else cands[0]
    print("TAPDESC %r -> (%d,%d)" % ((r["desc"] or r["text"])[:40], r["cx"], r["cy"]))
    sh("input tap %d %d" % (r["cx"], r["cy"]), 10)


if __name__ == "__main__":
    main()
