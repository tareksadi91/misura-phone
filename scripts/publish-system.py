#!/usr/bin/env python3
"""Render docs/SYSTEM.md into a read-only HTML view (build/system.html) for the claude.ai artifact.

One way only: SYSTEM.md is the source of truth; never edit the artifact. Run, then publish
build/system.html with the Artifact tool to the same URL (recorded in docs/SYSTEM.md §8).
"""
import html, re, subprocess, sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Pages: (markdown source, output file, tab title). Running with no args builds all of them.
PAGES = [("docs/SYSTEM.md", "system.html", "Misura Core System"),
         ("docs/design/design-system.md", "design-spec.html", "Misura Design Spec")]
if len(sys.argv) == 1:
    for md, out_name, _ in PAGES:
        subprocess.run([sys.executable, __file__, md, out_name], check=True)
    import shutil
    for f in ("design-system.html", "flow-mockup.html"):
        shutil.copy(ROOT / "docs/design" / f, ROOT / "build" / f)
    sys.exit(0)
MD, OUT = sys.argv[1], sys.argv[2]
PAGE_TITLE = dict((o, t) for _, o, t in PAGES).get(OUT, "Misura")
src = (ROOT / MD).read_text()
try:
    commit = subprocess.run(["git", "-C", str(ROOT), "log", "-1", "--format=%h", "--", MD],
                            capture_output=True, text=True).stdout.strip() or "uncommitted"
    dirty = subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain", MD],
                           capture_output=True, text=True).stdout.strip()
    if dirty: commit += " + local edits"
except Exception:
    commit = "unknown"

def inline(t):
    t = html.escape(t, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<![\w*])\*([^*]+)\*(?!\w)", r"<em>\1</em>", t)
    return t

def slug(t): return re.sub(r"[^a-z0-9]+", "-", t.lower()).strip("-")

out, toc, lines, i = [], [], src.splitlines(), 0
title = "Misura core system"
while i < len(lines):
    ln = lines[i]
    if ln.startswith("# "):
        title = ln[2:].strip(); i += 1; continue
    if ln.startswith("## "):
        h = ln[3:].strip(); s = slug(h); toc.append((s, h))
        out.append(f'<h2 id="{s}">{inline(h)}</h2>'); i += 1; continue
    if ln.startswith("|"):
        rows = []
        while i < len(lines) and lines[i].startswith("|"):
            cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
            if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells): rows.append(cells)
            i += 1
        head, body = rows[0], rows[1:]
        out.append('<div class="tw"><table><thead><tr>' + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr></thead><tbody>"
                   + "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in body) + "</tbody></table></div>")
        continue
    m = re.match(r"^(\d+)\. (.*)", ln)
    if ln.startswith("- ") or m:
        tag = "ol" if m else "ul"; items = []
        while i < len(lines) and (re.match(r"^(\d+)\. ", lines[i]) if tag == "ol" else lines[i].startswith("- ")):
            items.append(re.sub(r"^(\d+\. |- )", "", lines[i])); i += 1
        out.append(f"<{tag}>" + "".join(f"<li>{inline(x)}</li>" for x in items) + f"</{tag}>")
        continue
    if ln.strip():
        para = [ln]; i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r"^(#|\||- |\d+\. )", lines[i]):
            para.append(lines[i]); i += 1
        out.append(f"<p>{inline(' '.join(para))}</p>"); continue
    i += 1

page = f"""<title>{PAGE_TITLE}</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Hanken+Grotesk:wght@300;400;600;800&family=JetBrains+Mono:wght@400&display=swap">
<style>
/* Layout: the phone's own world. Dark screen, warm-grey surfaces, one orange. Sticky contents rail beside the doc on wide screens. */
:root {{ --screen:#080706; --surface:#1f1d1c; --line:#3a3735; --text:#efeeec; --text2:#adaaa8; --orange:#f47b30;
  --display:"Hanken Grotesk", system-ui, sans-serif; --body:"Hanken Grotesk", system-ui, sans-serif; --mono:"JetBrains Mono", ui-monospace, monospace;
  color-scheme:dark; }}
body {{ background:var(--screen); color:var(--text); font:15px/1.6 var(--body); padding-left:max(16px, env(safe-area-inset-left, 0px)); padding-right:max(16px, env(safe-area-inset-right, 0px)); padding-block:40px 80px; }}
.wrap {{ max-width:1080px; margin:0 auto; display:grid; grid-template-columns:200px minmax(0,1fr); gap:56px; }}
nav {{ position:sticky; top:calc(24px + env(safe-area-inset-top, 0px)); align-self:start; font-size:13px; }}
nav a {{ display:block; color:var(--text2); text-decoration:none; padding:5px 0; }} nav a:hover, nav a:focus-visible {{ color:var(--text); outline:none; }}
nav .sep {{ display:block; height:1px; background:var(--line); margin:10px 0; }}
nav .dot {{ display:inline-block; width:7px; height:7px; border-radius:50%; background:var(--orange); margin-right:8px; vertical-align:1px; }}
header {{ margin-bottom:28px; }}
h1 {{ font:800 40px/1.05 var(--display); letter-spacing:-.035em; margin:0 0 10px; text-wrap:balance; }}
.stamp {{ font:12px var(--mono); color:var(--text2); }} .stamp b {{ color:var(--orange); font-weight:400; }}
h2 {{ font:300 26px/1.2 var(--display); letter-spacing:-.02em; margin:48px 0 14px; padding-top:18px; border-top:1px solid var(--line); text-wrap:balance; }}
p, li {{ max-width:68ch; }} p {{ margin:0 0 12px; }} ul, ol {{ margin:0 0 14px; padding-left:20px; }} li {{ margin:4px 0; }}
li::marker {{ color:var(--text2); }}
strong {{ font-weight:600; color:var(--text); }} em {{ font-style:normal; color:var(--orange); }}
code {{ font:13px var(--mono); background:var(--surface); padding:1px 5px; border-radius:4px; color:var(--text); }}
.tw {{ overflow-x:auto; margin:6px 0 18px; border-radius:14px; background:var(--surface); }}
table {{ border-collapse:collapse; width:100%; font-size:14px; }} th, td {{ text-align:left; vertical-align:top; padding:10px 14px; }}
th {{ font-size:12px; font-weight:600; color:var(--text2); }} tbody tr + tr td {{ border-top:1px solid var(--line); }}
.lead {{ background:var(--surface); border-radius:14px; padding:14px 18px; color:var(--text2); font-size:14px; max-width:68ch; margin-bottom:20px; }} .lead strong {{ color:var(--orange); }}
@media (max-width:760px) {{ .wrap {{ grid-template-columns:minmax(0,1fr); gap:0; }} nav {{ position:static; display:flex; flex-wrap:wrap; gap:4px 14px; margin-bottom:24px; }} h1 {{ font-size:32px; }} }}
@media (prefers-reduced-motion:reduce) {{ * {{ scroll-behavior:auto; }} }}
</style>
<div class="wrap">
<nav aria-label="Contents"><a href="#top"><span class="dot"></span>Misura</a><a href="system.html">Core system</a><a href="design-spec.html">Design spec</a><a href="design-system.html">Design system (visual) ↗</a><a href="flow-mockup.html">Square flow (landing page) ↗</a><span class="sep"></span>{''.join(f'<a href="#{s}">{inline(h)}</a>' for s, h in toc)}</nav>
<main id="top">
<header><h1>{inline(title)}</h1><div class="stamp">From <b>{html.escape(MD)}</b> · commit {html.escape(commit)} · rendered {date.today().isoformat()}</div></header>
<div class="lead">Read-only view. The source is <strong>{html.escape(MD)}</strong> in the repo (docs/SYSTEM.md wins over everything); this page is republished from it.</div>
{''.join(out)}
</main></div>
"""
if OUT != "system.html":  # supporting pages are served as written, so they carry their own document head
    page = '<!doctype html>\n<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n' + page
dest = ROOT / "build" / OUT
dest.parent.mkdir(exist_ok=True)
dest.write_text(page)
print(dest)
