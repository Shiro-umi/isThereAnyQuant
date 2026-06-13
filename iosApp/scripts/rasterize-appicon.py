#!/usr/bin/env python3
"""把 iOS AppIcon 源 SVG 栅格化为无 alpha 的 1024x1024 PNG。

iOS AppIcon 必须是不透明位图：系统进程在安装时从编译后的 Assets.car 读取位图渲染
桌面/设置/App Store 图标，App 进程与任何运行时 SVG 库都参与不到这一步；且图标带
alpha 通道会被 App Store 资源校验拒绝、并在系统遮罩下渲染出黑边。

实现仅依赖 macOS 自带工具与 Python 标准库，零外部依赖：
  1. qlmanage：把 SVG 渲染成 1024 PNG（QuickLook 矢量渲染，系统自带）。
  2. zlib/struct：解码 PNG → 将像素合成到不透明主题底色 → 重编码为 24bit RGB PNG。
     合成而非简单丢弃 alpha，保证半透明边缘像素正确落到品牌底色上，无锯齿黑边。

用法：rasterize-appicon.py <src.svg> <dst.png>
"""

import os
import struct
import subprocess
import sys
import tempfile
import zlib

# 主题背景红，与 SVG <rect fill="#1A0F0E"> 及 Android ic_launcher_background 一致。
BG = (0x1A, 0x0F, 0x0E)
SIZE = 1024


def render_svg_to_png(svg_path: str, out_dir: str) -> str:
    """用系统 qlmanage 把 SVG 渲染成 PNG，返回生成的 PNG 路径。"""
    subprocess.run(
        ["qlmanage", "-t", "-s", str(SIZE), "-o", out_dir, svg_path],
        check=True,
        capture_output=True,
    )
    # qlmanage 产物名为 <源文件名>.png
    produced = os.path.join(out_dir, os.path.basename(svg_path) + ".png")
    if not os.path.isfile(produced):
        raise RuntimeError(f"qlmanage 未生成预期 PNG: {produced}")
    return produced


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def _decode_png(data: bytes):
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    i, chunks = 8, []
    while i < len(data):
        ln = struct.unpack(">I", data[i : i + 4])[0]
        typ = data[i + 4 : i + 8]
        body = data[i + 8 : i + 8 + ln]
        chunks.append((typ, body))
        i += 12 + ln
    ihdr = next(b for t, b in chunks if t == b"IHDR")
    w, h, depth, ctype = struct.unpack(">IIBB", ihdr[:10])
    assert depth == 8, f"unexpected bit depth {depth}"
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    raw = zlib.decompress(b"".join(b for t, b in chunks if t == b"IDAT"))
    stride = w * channels

    out, prev, pos = bytearray(), bytearray(stride), 0
    for _ in range(h):
        f = raw[pos]
        pos += 1
        line = bytearray(raw[pos : pos + stride])
        pos += stride
        if f == 1:
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 0xFF
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif f == 3:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif f == 4:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                c = prev[x - channels] if x >= channels else 0
                line[x] = (line[x] + _paeth(a, prev[x], c)) & 0xFF
        out.extend(line)
        prev = line
    return w, h, channels, out


def flatten_to_rgb_png(src_png: str, dst_png: str) -> None:
    """把带 alpha 的 PNG 合成到主题底色，输出 24bit RGB（无 alpha）PNG。"""
    w, h, channels, pixels = _decode_png(open(src_png, "rb").read())
    stride = w * channels

    rgb = bytearray()
    for y in range(h):
        rgb.append(0)  # PNG 行 filter type 0 (None)
        base = y * stride
        for x in range(w):
            px = base + x * channels
            if channels == 4:
                r, g, b, a = pixels[px], pixels[px + 1], pixels[px + 2], pixels[px + 3]
                af = a / 255.0
                rr = round(r * af + BG[0] * (1 - af))
                gg = round(g * af + BG[1] * (1 - af))
                bb = round(b * af + BG[2] * (1 - af))
            elif channels == 3:
                rr, gg, bb = pixels[px], pixels[px + 1], pixels[px + 2]
            else:
                rr = gg = bb = pixels[px]
            rgb.extend((rr, gg, bb))

    def chunk(typ: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body))
            + typ
            + body
            + struct.pack(">I", zlib.crc32(typ + body) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))  # ctype=2 RGB
    png += chunk(b"IDAT", zlib.compress(bytes(rgb), 9))
    png += chunk(b"IEND", b"")
    open(dst_png, "wb").write(png)


def main() -> int:
    if len(sys.argv) != 3:
        sys.stderr.write("用法: rasterize-appicon.py <src.svg> <dst.png>\n")
        return 2
    src_svg, dst_png = sys.argv[1], sys.argv[2]
    if not os.path.isfile(src_svg):
        sys.stderr.write(f"找不到源 SVG: {src_svg}\n")
        return 1
    os.makedirs(os.path.dirname(os.path.abspath(dst_png)), exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        rendered = render_svg_to_png(src_svg, tmp)
        flatten_to_rgb_png(rendered, dst_png)
    print(f"AppIcon 生成完成: {dst_png} ({SIZE}x{SIZE}, RGB no-alpha)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
