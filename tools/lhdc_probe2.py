import sys, time, re, json
sys.path.insert(0, 'tools')
import mdctl as M

LOG = "/data/local/tmp/gaia_cap.log"
PKG = "com.moondroplab.moondrop.moondrop_app"
out = []


def log(*a):
    s = " ".join(str(x) for x in a)
    out.append(s)
    print(s, flush=True)
    open("/storage/emulated/0/HyperPods for Moondrop/_refs/_device/lhdc_probe2.out", "w").write("\n".join(out))


def L():
    return int(M.sh("wc -l < %s" % LOG).strip() or 0)


def page(raise_=True):
    x = M.dump_xml(raise_=raise_, tries=10)
    t = " ".join((r['desc'] or '') + " " + (r['text'] or '') for r in M.nodes(x))
    return x, t


def codec():
    return M.sh("dumpsys bluetooth_manager 2>/dev/null | grep -oE "
                "'mCodecConfig: .codecName:[A-Za-z0-9_]+' | tail -1").strip()


log("=== restart app ===")
M.sh("am force-stop " + PKG); time.sleep(1)
M.sh("am start -n %s/.MainActivity" % PKG); time.sleep(10)
log("codec:", codec())

for step in range(14):
    x, t = page()
    if 'Codec设置' in t:
        log("on codec page after step", step); break
    row = [r for r in M.nodes(x) if r['click'] and '功能设置' in (r['desc'] or '')]
    if row:
        log("tap 功能设置", row[0]['cx'], row[0]['cy'])
        M.sh("input tap %d %d" % (row[0]['cx'], row[0]['cy'])); time.sleep(3); continue
    card = [r for r in M.nodes(x) if r['click'] and 'Pudding' in (r['desc'] or '')]
    if card:
        log("tap device card", card[0]['cx'], card[0]['cy'])
        M.sh("input tap %d %d" % (card[0]['cx'], card[0]['cy'])); time.sleep(3.5); continue
    tab = [r for r in M.nodes(x) if r['click'] and '我的设备' in (r['desc'] or '')]
    if tab:
        log("tap tab 我的设备")
        M.sh("input tap %d %d" % (tab[0]['cx'], tab[0]['cy'])); time.sleep(2.5); continue
    M.sh("input keyevent 4"); time.sleep(2)

x, t = page()
row = [r for r in M.nodes(x) if 'Codec设置' in (r['desc'] or '')]
if not row:
    log("NO CODEC ROW; page:", t[:200]); sys.exit(0)
b = row[0]['box']
log("codec row box:", b, "codec:", codec())
pts = [(b[2]-220, b[3]-60), (b[2]-320, b[3]-90), (b[0]+int((b[2]-b[0])*0.78), b[1]+int((b[3]-b[1])*0.76)),
       (b[0]+int((b[2]-b[0])*0.85), b[1]+int((b[3]-b[1])*0.85)), (b[0]+int((b[2]-b[0])*0.9), b[3]-40)]
for i, hit in enumerate(pts):
    M.sh("input tap 1600 167"); time.sleep(0.7)
    M.sh("input tap %d %d" % hit); time.sleep(2.4)
    x2, t2 = page(raise_=False)
    if '确认' in t2:
        conf = [q for q in M.nodes(x2) if (q['desc'] or q['text']) == '确认']
        log("DIALOG opened with hit", hit)
        log("dialog text:", t2[:260])
        log("codec before:", codec())
        n1 = L()
        M.sh("input tap %d %d" % (conf[0]['cx'], conf[0]['cy']))
        time.sleep(12)
        log("SENT:", M.sh("tail -n +%d %s | grep -E 'send: packet' | grep -vE '0x1A 0x0' | "
                          "sed 's/^.*System.out: //' | sort -u" % (n1 + 1, LOG)).strip() or "<none>")
        log("codec after:", codec())
        break
    log("no dialog for hit", hit)
log("DONE")
