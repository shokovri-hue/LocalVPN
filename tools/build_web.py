#!/usr/bin/env python3
"""Собирает офлайн-страницу приложения: web/tunnels.html -> app/src/main/assets/index.html.
Заменяет ссылки на Google Fonts и cdnjs локальными файлами и добавляет оболочку документа."""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent
src = (root / "web" / "tunnels.html").read_text(encoding="utf-8")

# Убираем внешние подключения
src = re.sub(r'<link rel="preconnect"[^>]*>\s*', '', src)
src = re.sub(r'<link rel="stylesheet" href="https://fonts\.googleapis\.com[^>]*>\s*', '', src)
src = re.sub(r'<script src="https://cdnjs\.cloudflare\.com/ajax/libs/tweetnacl/[^"]*"></script>',
             '<script src="js/nacl-fast.min.js"></script>', src)
if "cdnjs" in src or "googleapis" in src:
    sys.exit("В странице остались внешние ссылки")

LAT = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"
LATX = "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF"
CYR = "U+0301,U+0400-045F,U+0490-0491,U+04B0-04B1,U+2116"
def faces(family, file, weights):
    return "".join(
        f"@font-face{{font-family:'{family}';font-style:normal;font-display:swap;font-weight:{weights};"
        f"src:url(fonts/{file}-{sub}-wght-normal.woff2) format('woff2');unicode-range:{rng}}}\n"
        for sub, rng in (("latin", LAT), ("latin-ext", LATX), ("cyrillic", CYR)))
fonts = faces("Manrope", "manrope", "200 800") + faces("JetBrains Mono", "jetbrains-mono", "100 800")

shell = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>
{fonts}:root{{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}}
body{{margin:0;font:14px system-ui,-apple-system,sans-serif;-webkit-tap-highlight-color:transparent}}
img{{max-width:100%}}
[hidden]{{display:none!important}}
</style>
"""
# <title> и <meta> страницы идут первыми — оставляем их в <head>, остальное в <body>
head_end = src.index("<style>")
out = shell + src[:head_end] + src[head_end:src.index("</style>") + 8] + "\n</head>\n<body>\n" + src[src.index("</style>") + 8:] + "\n</body>\n</html>\n"
dst = root / "app" / "src" / "main" / "assets" / "index.html"
dst.write_text(out, encoding="utf-8")
print("OK:", dst.relative_to(root))
