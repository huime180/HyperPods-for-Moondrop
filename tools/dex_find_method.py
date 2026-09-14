#!/usr/bin/env python3
"""在 DEX 里定位「引用了某个字符串」的方法，并按代码顺序列出它用到的全部 const-string。

用途：证明某个 JSON / Bundle 的键名（例如 miui.focus.param 的内容）到底长什么样，
而不是靠记忆猜。只用标准库。
"""
import struct, sys

U16 = lambda b, o: struct.unpack_from('<H', b, o)[0]
U32 = lambda b, o: struct.unpack_from('<I', b, o)[0]

def uleb128(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1; r |= (x & 0x7f) << s
        if not (x & 0x80): return r, o
        s += 7

SIZE = [1]*256
for op, s in [(0x02,2),(0x03,3),(0x13,2),(0x14,3),(0x15,2),(0x16,2),(0x17,3),(0x18,5),
              (0x19,2),(0x1a,2),(0x1b,3),(0x1c,2),(0x1f,2),(0x20,2),(0x22,2),(0x23,2),
              (0x24,3),(0x25,3),(0x26,3),(0x29,2),(0x2a,3),(0x2b,3),(0x2c,3)]:
    SIZE[op] = s
for op in range(0x2d, 0x32): SIZE[op] = 2
for op in range(0x32, 0x3e): SIZE[op] = 2
for op in range(0x44, 0x52): SIZE[op] = 2
for op in range(0x52, 0x6e): SIZE[op] = 2
for op in range(0x6e, 0x73): SIZE[op] = 3
for op in range(0x74, 0x79): SIZE[op] = 3
for op in range(0x90, 0xb0): SIZE[op] = 2
for op in range(0xd0, 0xe3): SIZE[op] = 2
SIZE[0xfa] = SIZE[0xfb] = 4
SIZE[0xfc] = SIZE[0xfd] = 3
SIZE[0xfe] = SIZE[0xff] = 2

class Dex:
    def __init__(self, data):
        self.d = data
        self.string_off = U32(data, 0x3c); self.string_size = U32(data, 0x38)
        self.type_ids = U32(data, 0x44); self.type_size = U32(data, 0x40)
        self.proto_ids = U32(data, 0x4c); self.field_ids = U32(data, 0x54)
        self.method_ids = U32(data, 0x5c); self.method_size = U32(data, 0x58)
        self.class_defs = U32(data, 0x64); self.class_size = U32(data, 0x60)
        self.strings = [self.string(i) for i in range(self.string_size)]
        self.index = {s: i for i, s in enumerate(self.strings)}

    def string(self, i):
        o = U32(self.d, self.string_off + i*4)
        n, o = uleb128(self.d, o)          # utf16 size
        end = self.d.index(b'\x00', o)
        return self.d[o:end].decode('utf-8', 'replace')

    def type_name(self, i):
        # type_id 表项本身就是 string_ids 的下标（不是文件偏移），直接取串。
        return self.string(U32(self.d, self.type_ids + i*4))

    def method_name(self, i):
        o = self.method_ids + i*8
        return self.string(U32(self.d, o+4)), self.type_name(U16(self.d, o))

    def class_name(self, i):
        o = self.class_defs + i*32
        return self.type_name(U32(self.d, o))

    def walk(self):
        for ci in range(self.class_size):
            base = self.class_defs + ci*32
            cname = self.class_name(ci)
            cd_off = U32(self.d, base+24)
            if cd_off == 0: continue
            o = cd_off
            sf, o = uleb128(self.d, o); inf, o = uleb128(self.d, o)
            dm, o = uleb128(self.d, o); vm, o = uleb128(self.d, o)
            for _ in range(sf): _, o = uleb128(self.d, o); _, o = uleb128(self.d, o)
            for _ in range(inf): _, o = uleb128(self.d, o); _, o = uleb128(self.d, o)
            for _ in range(dm + vm):
                mi, o = uleb128(self.d, o)
                acc, o = uleb128(self.d, o)
                co, o = uleb128(self.d, o)
                yield cname, mi, co

    def method_strings(self, code_off):
        """按代码顺序返回该方法里所有 const-string 的字面量。"""
        if code_off == 0: return []
        insns_size = U32(self.d, code_off + 12)
        base = code_off + 16
        out = []; i = 0
        while i < insns_size:
            op = self.d[base + i*2]
            if op == 0x00:
                hi = self.d[base + i*2 + 1]
                if hi == 0: i += 1; continue
                kind = (hi << 8) | 0x00
                if kind == 0x0100:
                    n = U16(self.d, base + i*2 + 2); i += n*2 + 4
                elif kind == 0x0200:
                    n = U16(self.d, base + i*2 + 2); i += n*3 + 2
                elif kind == 0x0300:
                    w = U16(self.d, base + i*2 + 2); n = U32(self.d, base + i*2 + 4)
                    i += (n*w + 1)//2 + 4
                else: i += 1
                continue
            if op == 0x1a:
                idx = U16(self.d, base + i*2 + 2)
                if idx < self.string_size: out.append((i, self.strings[idx]))
                else: i += 1; continue      # 解码错位：退一格重新同步
                i += 2; continue
            if op == 0x1b:
                idx = U32(self.d, base + i*2 + 2)
                if idx < self.string_size: out.append((i, self.strings[idx]))
                else: i += 1; continue
                i += 3; continue
            i += SIZE[op]
        return out

MAGIC = b'dex\n'

def split_dexes(blob):
    """从「多个 dex 首尾相接」的 blob 里切出每一个 dex（按 header.file_size）。"""
    out = []
    i = 0
    while True:
        j = blob.find(MAGIC, i)
        if j < 0: break
        size = struct.unpack_from('<I', blob, j + 0x20)[0]
        if size <= 0 or j + size > len(blob):
            i = j + 4; continue
        out.append(blob[j:j+size])
        i = j + size
    return out


def main(path, needles):
    blob = open(path, 'rb').read()
    parts = split_dexes(blob)
    print(f"embedded dex files: {len(parts)}")
    for pi, part in enumerate(parts):
        print(f"\n########## dex[{pi}] ##########")
        scan(Dex(part), needles)
        print(f"  strings={len(parts[pi])}")


def scan(dex, needles):
    print(f"strings={dex.string_size} methods={dex.method_size} classes={dex.class_size}")
    for needle in needles:
        idx = dex.index.get(needle)
        print(f"\n===== 引用 \"{needle}\" 的方法 (string_idx={idx}) =====")
        if idx is None:
            print("  该字符串不在串池里"); continue
        hits = 0
        for cname, mi, co in dex.walk():
            if co == 0: continue
            try:
                ss = dex.method_strings(co)
            except Exception:
                continue
            if any(s == needle for _, s in ss):
                hits += 1
                mn, mdesc = dex.method_name(mi)
                print(f"\n--- {cname}->{mn}{mdesc}")
                for off, s in ss:
                    print(f"      [{off:5d}] {s!r}")
                if hits >= 6: print("  …（同类命中过多，已截断）"); break
        if hits == 0: print("  没有方法直接引用它")

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])


# ── 反汇编模式：把引用了某字符串的那个方法的指令流打出来（含 const 数值、字段、方法引用）──
FIELD_GET = {0x44:"aget",0x45:"aget-wide",0x46:"aget-object",0x47:"aget-boolean",0x48:"aget-byte",
             0x49:"aget-char",0x4a:"aget-short",0x4b:"aput",0x4c:"aput-wide",0x4d:"aput-object",
             0x4e:"aput-boolean",0x4f:"aput-byte",0x50:"aput-char",0x51:"aput-short"}
FIELD_OP = {0x52:"iget",0x53:"iget-wide",0x54:"iget-object",0x55:"iget-boolean",0x56:"iget-byte",
            0x57:"iget-char",0x58:"iget-short",0x59:"iput",0x5a:"iput-wide",0x5b:"iput-object",
            0x5c:"iput-boolean",0x5d:"iput-byte",0x5e:"iput-char",0x5f:"iput-short"}
SFLD_OP = {0x60:"sget",0x61:"sget-wide",0x62:"sget-object",0x63:"sget-boolean",0x64:"sget-byte",
           0x65:"sget-char",0x66:"sget-short",0x67:"sput",0x68:"sput-wide",0x69:"sput-object",
           0x6a:"sput-boolean",0x6b:"sput-byte",0x6c:"sput-char",0x6d:"sput-short"}
INVOKE_OP = {0x6e:"invoke-virtual",0x6f:"invoke-super",0x70:"invoke-direct",0x71:"invoke-static",
             0x72:"invoke-interface",0x74:"invoke-virtual/range",0x75:"invoke-super/range",
             0x76:"invoke-direct/range",0x77:"invoke-static/range",0x78:"invoke-interface/range"}

def _field_name(dex, fi):
    o = dex.field_ids + fi*8
    return dex.type_name(U16(dex.d, o+2)) + "." + dex.string(U32(dex.d, o+4))

def disasm(dex, code_off, limit=260):
    insns_size = U32(dex.d, code_off + 12); base = code_off + 16
    i = 0; n = 0
    while i < insns_size and n < limit:
        op = dex.d[base + i*2]; n += 1
        text = None
        if op == 0x00:
            hi = dex.d[base + i*2 + 1]
            if hi == 0: i += 1; continue
            k = hi << 8
            if k == 0x0100: sz = U16(dex.d, base+i*2+2); text = f"packed-switch-payload size={sz}"; i += sz*2+4
            elif k == 0x0200: sz = U16(dex.d, base+i*2+2); text = f"sparse-switch-payload size={sz}"; i += sz*3+2
            elif k == 0x0300:
                w = U16(dex.d, base+i*2+2); sz = U32(dex.d, base+i*2+4)
                text = f"fill-array-data width={w} size={sz}"; i += (sz*w+1)//2+4
            else: text = f"??payload {k:#x}"; i += 1
        elif op == 0x12:
            b = dex.d[base+i*2+1]; text = f"const/4 v{b & 0xf}, {((b >> 4) ^ 8) - 8}"; i += 1
        elif op == 0x13:
            text = f"const/16 v{dex.d[base+i*2+1]}, {struct.unpack_from('<h', dex.d, base+i*2+2)[0]}"; i += 2
        elif op == 0x14:
            text = f"const v{dex.d[base+i*2+1]}, {struct.unpack_from('<i', dex.d, base+i*2+2)[0]}"; i += 3
        elif op == 0x15:
            text = (f"const/high16 v{dex.d[base+i*2+1]}, "
                    f"{struct.unpack_from('<h', dex.d, base+i*2+2)[0] << 16}"); i += 2
        elif op == 0x16:
            text = f"const-wide/16 v{dex.d[base+i*2+1]}, {struct.unpack_from('<h', dex.d, base+i*2+2)[0]}"; i += 2
        elif op == 0x1a:
            idx = U16(dex.d, base+i*2+2)
            text = f"const-string v{dex.d[base+i*2+1]}, {dex.strings[idx]!r}" if idx < dex.string_size else "??string"
            i += 2
        elif op == 0x1b:
            idx = U32(dex.d, base+i*2+2)
            text = f"const-string/jumbo v{dex.d[base+i*2+1]}, {dex.strings[idx]!r}" if idx < dex.string_size else "??jumbo"
            i += 3
        elif op == 0x1c:
            text = f"const-class v{dex.d[base+i*2+1]}, {dex.type_name(U16(dex.d, base+i*2+2))}"; i += 2
        elif op == 0x22:
            text = f"new-instance v{dex.d[base+i*2+1]}, {dex.type_name(U16(dex.d, base+i*2+2))}"; i += 2
        elif op in SFLD_OP:
            text = f"{SFLD_OP[op]} {_field_name(dex, U16(dex.d, base+i*2+2))}"; i += 2
        elif op in INVOKE_OP:
            mi = U16(dex.d, base+i*2+2)
            mn, mc = dex.method_name(mi)
            count = (dex.d[base+i*2+3] >> 4) & 0xf
            regs = [f"v{(dex.d[base+i*2+2+ (k//2)] >> (0 if k % 2 == 0 else 4)) & 0xf}" for k in range(count)]
            text = f"{INVOKE_OP[op]} {{{', '.join(regs)}}}, {mc}->{mn}"; i += 3
        elif op in FIELD_OP:
            text = f"{FIELD_OP[op]} {_field_name(dex, U16(dex.d, base+i*2+2))}"; i += 2
        else:
            i += SIZE[op]
        if text: print(f"   [{i:5d}] {text}")

def dump_methods(path, cls_sub, needle):
    blob = open(path, 'rb').read()
    for part in split_dexes(blob):
        dex = Dex(part)
        for cname, mi, co in dex.walk():
            if co == 0 or cls_sub not in cname: continue
            try: ss = dex.method_strings(co)
            except Exception: continue
            if not any(s == needle for _, s in ss): continue
            mn, md = dex.method_name(mi)
            print(f"\n===== {cname}->{mn}  (code_off={co}, insns={U32(dex.d, co+12)}) =====")
            disasm(dex, co)
