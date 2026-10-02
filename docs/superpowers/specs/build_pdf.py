#!/usr/bin/env python
# Собирает PDF дизайн-документа прямо из спеки, чтобы не поддерживать вторую копию.
# Запуск: python build_pdf.py   (из каталога specs)
import re, pathlib, subprocess, sys

HERE = pathlib.Path(__file__).parent
SRC = HERE / "2026-09-30-vpn-app-design.md"
HTML = HERE / "_pdf_build.html"
PDF = HERE / "2026-09-30-vpn-app-design.pdf"

try:
    import markdown
except ImportError:
    sys.exit("нет python-markdown: pip install markdown")

md_text = SRC.read_text(encoding="utf-8")

# таблицы в спеке есть -> расширение tables; code fences -> fenced_code
body = markdown.markdown(md_text, extensions=["tables", "fenced_code", "sane_lists"])

CSS = """
@page { size: A4; margin: 18mm 16mm 20mm 16mm; }
*{box-sizing:border-box}
body{font-family:"Segoe UI",Arial,sans-serif;font-size:10.5pt;line-height:1.55;color:#1a1a1a;margin:0}
h1{font-size:19pt;line-height:1.25;margin:0 0 10pt;color:#0b2545;
   border-bottom:2.5pt solid #0b2545;padding-bottom:6pt}
h2{font-size:13pt;margin:20pt 0 7pt;color:#0b2545;page-break-after:avoid}
h3{font-size:11pt;margin:13pt 0 5pt;color:#14375e;page-break-after:avoid}
h4{font-size:10.5pt;margin:11pt 0 4pt;color:#14375e;page-break-after:avoid}
p{margin:0 0 7pt}
ul,ol{margin:0 0 7pt;padding-left:18pt}
li{margin-bottom:3pt}
strong{color:#0b2545}
code{font-family:Consolas,"Courier New",monospace;background:#eef2f6;
     padding:.5pt 3pt;border-radius:2pt;font-size:9.5pt;color:#14375e}
pre{font-family:Consolas,"Courier New",monospace;font-size:8pt;line-height:1.35;
    background:#f6f8fa;border:.5pt solid #d0d7de;border-left:2.5pt solid #0b2545;
    padding:8pt 10pt;margin:8pt 0 12pt;white-space:pre;page-break-inside:avoid}
table{width:100%;border-collapse:collapse;margin:8pt 0 12pt;font-size:9.5pt;page-break-inside:avoid}
th{background:#0b2545;color:#fff;text-align:left;padding:5pt 7pt;font-weight:600;font-size:9.5pt}
td{border:.5pt solid #c8d0da;padding:5pt 7pt;vertical-align:top}
tr:nth-child(even) td{background:#f4f7fa}
hr{border:none;border-top:.5pt solid #ccc;margin:16pt 0}
blockquote{margin:8pt 0;padding:6pt 12pt;border-left:2.5pt solid #c9a227;
           background:#fff8e6;font-size:10pt}
blockquote p:last-child{margin-bottom:0}
"""

html = f"""<!DOCTYPE html>
<html lang="ru"><head><meta charset="utf-8">
<title>iOS-приложение для VPN-сервиса с продажей через Telegram</title>
<style>{CSS}</style></head><body>
{body}
</body></html>"""

HTML.write_text(html, encoding="utf-8")
print(f"HTML собран: {HTML} ({len(html)} байт)")

chrome = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
cmd = [chrome, "--headless", "--disable-gpu", "--no-pdf-header-footer",
       f"--print-to-pdf={PDF}", HTML.as_uri()]
r = subprocess.run(cmd, capture_output=True, text=True)
out = (r.stdout or "") + (r.stderr or "")
print("chrome:", "ok" if PDF.exists() else "ОШИБКА")
if not PDF.exists():
    print(out[-800:])
else:
    print(f"PDF: {PDF} ({PDF.stat().st_size} байт)")
