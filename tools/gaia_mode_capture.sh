#!/system/bin/sh
# 采一次「四击切换音乐/游戏模式」的 GAIA 帧（需 root；在设备上跑）
#
#   su -c 'sh /data/local/tmp/gaia_mode_capture.sh'
#
# 为什么要这么抓：本 ROM（Xiaomi Pad 8 Pro / HyperOS 4.0）的 btsnoop HCI log 会把
# 带 RFCOMM 的 ACL 记录截到 10 字节 ACL 数据，正好停在 APP 的 SPP 包装（FF 04 00）上，
# GAIA 帧本身**根本不进 snoop 日志**。真正在线的字节只有官方 APP 自己打的 System.out：
#   Plugin: send: packet = 0x.. 0x..   /   onPacketReceived received = 0x.. 0x..
# 所以这里全程只抓 `logcat -s System.out`，并且把 MARK 直接写进同一个文件 ——
# 时间轴与帧在同一条流里，差分时才不会靠猜。
#
# 采样设计：静默一段（拿基线噪音：电量 / ANC / 状态上报）→ 四击切到游戏 → 再四击切回。
# 三次 MARK 之间的帧分别落到 w1/w2/w3 三个窗口，绝对差分交给 tools/gaia_mode_delta.py。

OUT=${OUT:-/data/local/tmp/gaia_mode.log}
DEST=${DEST:-/storage/emulated/0/HyperPods for Moondrop/_captures/gaia_mode.log}
QUIET_S=${QUIET_S:-20}
NEXT_S=${NEXT_S:-10}
WAIT_S=${WAIT_S:-8}

mark() { echo "### MARK $1 $(date +%T)" >> "$OUT"; }

logcat -c 2>/dev/null
pkill -f "logcat -s System.out" 2>/dev/null
: > "$OUT"
nohup logcat -s System.out >> "$OUT" 2>&1 &
echo "已开始抓取 -> $OUT"
echo
echo "[1/3] 静默 ${QUIET_S}s：手别碰耳机（拿基线噪音）"
sleep "$QUIET_S"; mark quiet-end
echo "      -> 现在【四击耳机背】：音乐 → 游戏"; sleep "$NEXT_S"
mark tap4-a
echo "[2/3] 再等 ${NEXT_S}s 后，【再四击耳机背】：游戏 → 音乐"; sleep "$NEXT_S"
mark tap4-b
echo "[3/3] 收尾 ${WAIT_S}s 静默"; sleep "$WAIT_S"
mark done
pkill -f "logcat -s System.out" 2>/dev/null
mkdir -p "$(dirname "$DEST")"
cp -f "$OUT" "$DEST"
echo
echo "抓完：$OUT  ->  $DEST"
grep -c "onPacketReceived\|send: packet" "$OUT" | sed 's/^/GAIA 行数: /'
grep -n "### MARK" "$OUT"
