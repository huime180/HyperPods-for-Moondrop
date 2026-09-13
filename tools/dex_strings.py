#!/usr/bin/env python3
"""极简 DEX 字符串表提取器（只依赖标准库）。
用途：在没有 jadx/baksmali 的环境下，从 dex 里拿到真实的类描述符，
核对 Xposed hook 目标类名是否与 ROM 一致。"""
import struct, sys, zipfile, re

def uleb128(b, off):
    result = 0; shift = 0
    while True:
        x = b[off]; off += 1
        result |= (x & 0x7f) << shift
        if not (x & 0x80): break
        shift += 7
    return result, off

def dex_strings(data):
    if data[:4] != b'dex\n': return []
    string_ids_size, string_ids_off = struct.unpack_from('<II', data, 56)
    out = []
    for i in range(string_ids_size):
        off = struct.unpack_from('<I', data, string_ids_off + i*4)[0]
        _, p = uleb128(data, off)
        e = data.index(b'\x00', p)
        out.append(data[p:e].decode('utf-8', 'replace'))
    return out

def apk_strings(path):
    z = zipfile.ZipFile(path)
    for n in z.namelist():
        if n.endswith('.dex'):
            yield n, dex_strings(z.read(n))

if __name__ == '__main__':
    apk = sys.argv[1]
    pats = [re.compile(p) for p in sys.argv[2:]] or [re.compile('Headset')]
    for dexname, strs in apk_strings(apk):
        for s in strs:
            if any(p.search(s) for p in pats):
                print(f"{dexname}\t{s}")
