import sys, time, re
sys.path.insert(0, 'tools')
import mdctl as M

LOG = "/data/local/tmp/gaia_cap.log"
PKG = "com.moondroplab.moondrop.moondrop_app"


def L():
    return int(M.sh("wc -l < %s" % LOG).strip() or 0)


def page():
    x = M.dump_xml(raise_=True, tries=10)
    t = " ".join((r['desc'] or '') + " " + (r['text'] or '') for r in M.nodes(x))
    return x, t


def codec():
    return M.sh("dumpsys bluetooth_manager 2>/dev/null | grep -oE "
                "'mCodecConfig: .codecName:[A-Za-z0-9_]+' | tail -1").strip()


def last(pattern, n=3):
    return M.sh("grep -E '%s' %s | tail -%d" % (pattern, LOG, n)).strip()


print("== BEFORE restart ==")
print("codec:", codec())
M.sh("am force-stop " + PKG)
time.sleep(1)
M.sh("am start -n %s/.MainActivity" % PKG)
time.sleep(9)
print("== AFTER restart ==")
print("codec:", codec())
print("cmd5 req/response (feat16):")
print(last("0x1D 0x20 0x05|0x1D 0x21 0x05", 3))

# navigate to 功能设置
x, t = page(), ""
for step in range(9):
    x, t = page()
    if 'Codec设置' in t:
        break
    row = [r for r in M.nodes(x) if r['click'] and '功能设置' in (r['desc'] or '')]
    if row:
        print("tap 功能设置 row", row[0]['cx'], row[0]['cy'])
        M.sh("input tap %d %d" % (row[0]['cx'], row[0]['cy'])); time.sleep(2.5); continue
    card = [r for r in M.nodes(x) if r['click'] and 'Pudding' in (r['desc'] or '')]
    if card:
        print("tap device card", card[0]['cx'], card[0]['cy'])
        M.sh("input tap %d %d" % (card[0]['cx'], card[0]['cy'])); time.sleep(3); continue
    M.sh("input keyevent 4"); time.sleep(1.6)
print("reached codec page:", 'Codec设置' in t)

row = [r for r in M.nodes(x) if 'Codec设置' in (r['desc'] or '')]
if not row:
    print("NO CODEC ROW"); sys.exit(0)
b = row[0]['box']
print("codec row box:", b)
hit = (b[0] + int((b[2] - b[0]) * 0.78), b[1] + int((b[3] - b[1]) * 0.76))
for attempt in range(4):
    M.sh("input tap 1600 167"); time.sleep(0.6)
    n0 = L()
    M.sh("input tap %d %d" % hit); time.sleep(2.2)
    x2, t2 = page()
    if '确认' in t2:
        conf = [q for q in M.nodes(x2) if (q['desc'] or q['text']) == '确认']
        print("DIALOG at attempt %d; text: %s" % (attempt, t2[:160]))
        print("codec before confirm:", codec())
        n1 = L()
        M.sh("input tap %d %d" % (conf[0]['cx'], conf[0]['cy']))
        time.sleep(10)
        print("SENT:", M.sh("tail -n +%d %s | grep -E 'send: packet' | "
                            "grep -vE '0x1A 0x0' | sed 's/^.*System.out: //' | sort -u"
                            % (n1 + 1, LOG)).strip() or "<none>")
        print("codec after confirm:", codec())
        break
    print("no dialog attempt %d hit=%s" % (attempt, hit))
