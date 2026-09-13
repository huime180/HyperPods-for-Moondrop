#!/usr/bin/env python3
"""Dump the on-screen UI of the Moondrop app as text + tap coordinates."""
import subprocess, sys, re, html

TMP = "/data/local/tmp/ui.xml"

def sh(cmd):
    return subprocess.run(["su", "-c", cmd], capture_output=True, text=True).stdout

def dump():
    sh("rm -f %s" % TMP)
    sh("uiautomator dump %s" % TMP)
    sh("chmod 666 %s" % TMP)
    return sh("cat %s" % TMP)

def parse(xml):
    rows = []
    for m in re.finditer(r"<node\b([^>]*?)/?>", xml):
        a = m.group(1)
        def g(k):
            mm = re.search(r'%s="([^"]*)"' % k, a)
            return html.unescape(mm.group(1)) if mm else ""
        b = g("bounds")
        bm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if not bm: continue
        x1,y1,x2,y2 = map(int, bm.groups())
        rows.append(dict(cls=g("class").split(".")[-1], text=g("text"),
            desc=g("content-desc"), rid=g("resource-id").split("/")[-1],
            click=g("clickable")=="true", scroll=g("scrollable")=="true",
            check=g("checked"), foc=g("focused"), sel=g("selected"),
            x=(x1+x2)//2, y=(y1+y2)//2, box=(x1,y1,x2,y2)))
    return rows

def main():
    xml = dump()
    if not xml.strip():
        print("(empty dump)"); return
    rows = parse(xml)
    print("# nodes=%d (screen 2136x3200)" % len(rows))
    if "--raw" in sys.argv: print(xml)
    i = 0
    for r in rows:
        if not (r["text"] or r["desc"] or r["click"] or r["rid"]) and "--all" not in sys.argv:
            continue
        i += 1
        flags = ("C" if r["click"] else "-") + ("S" if r["scroll"] else "-")
        if r["check"] not in ("", "false"): flags += " chk=%s" % r["check"]
        if r["foc"] == "true": flags += " FOC"
        label = r["text"] + ((" | desc=" + r["desc"]) if r["desc"] else "")
        print("[%3d] (%4d,%4d) %-22s %-14s %-28s %s" % (
            i, r["x"], r["y"], r["cls"], flags, r["rid"], label[:90]))
    print("# listed=%d" % i)

if __name__ == "__main__":
    main()
