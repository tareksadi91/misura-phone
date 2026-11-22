"""Builds docs/design/flow-mockup.html (square) and design-system.html (3a). Usage: python3 scripts/build-mockups.py docs/design [3a]"""
import sys
D=sys.argv[1]
FORM=sys.argv[2] if len(sys.argv)>2 else 'lp'
s=open(D+'/archive/trail-mockup.html').read()
head=s[:s.index('</style></head>')].replace('--o: oklch(0.66 0.21 40);','--o: #f47b30;').replace('#fe843d','#f47b30')
css=r"""
  /* Type: exactly three styles inside cards. label = 11.5/600 grey, body = 14/400, big = 28/800. */
  .lb { font-size:11.5px; font-weight:600; color:var(--text-2); }
  .bd { font-size:14px; line-height:1.35; color:var(--text); }
  .bg { font-size:28px; font-weight:800; letter-spacing:-.045em; line-height:1; color:var(--text); }
  .bd.dim { color:var(--text-2); }
  .work { position:absolute; left:16px; right:16px; top:30px; color:var(--text); }
  .echo { font-size:16px; font-weight:300; line-height:1.3; color:var(--text-2); }
  .steps { display:flex; flex-direction:column; gap:10px; margin-top:16px; }
  .steps .tl { font-size:13.5px; }
  .ic { width:16px; height:16px; flex:none; color:var(--text-2); }
  .mdot { width:8px; height:8px; border-radius:50%; background:var(--o); flex:none; margin:4px 4px 0; }
  .tl .mdot { margin-top:0; }
  .why .mdot { margin-top:5px; }
  .li .ic { margin-top:1px; }
  /* nav line: top of the screen, plain text. Left = back one card. Right = where you are / steps. */
  .nav { position:absolute; left:14px; right:14px; top:13px; display:flex; justify-content:space-between; font-size:12px; font-weight:600; color:var(--text-2); }
  .nav .bk { color:var(--text); }
  .ap.mp { background:#4285f4; color:#fff; } .ap.rv { background:#fbbc04; color:#3a2a00; } .ap.ms { background:var(--o); color:var(--ink); }
  .fold { position:absolute; left:16px; right:16px; top:14px; display:flex; justify-content:space-between; font-size:11.5px; font-weight:600; color:var(--text-2); }
  /* carousel of place cards + shared map below */
  .car2 { position:absolute; left:0; right:0; top:36px; overflow:hidden; }
  .car2 .trk { display:flex; gap:6px; padding-left:var(--m); }
  .pc2 { flex:none; width:238px; background:var(--surface); border-radius:var(--card-r); padding:var(--p); display:flex; flex-direction:column; gap:8px; color:var(--text); }
  .mos { display:grid; grid-template-columns:2fr 1fr; grid-template-rows:1fr 1fr; gap:3px; height:104px; border-radius:var(--btn-r); overflow:hidden; }
  .mos img { width:100%; height:100%; object-fit:cover; display:block; } .mos img:first-child { grid-row:span 2; }
  .pc2 .c2 { padding:0 4px 2px; display:flex; flex-direction:column; gap:4px; }
  .nmrow { display:flex; justify-content:space-between; align-items:baseline; }
  .nmrow b { font-size:17px; font-weight:700; }
  .star { font-size:13px; font-weight:600; } .star i { font-style:normal; color:#fbbc04; }
  .why { display:flex; gap:6px; align-items:flex-start; } .why .ap { margin-top:1px; width:16px; height:16px; font-size:8px; }
  .pickt { font-size:10px; font-weight:800; background:var(--o); color:var(--ink); padding:2px 5px; border-radius:4px; vertical-align:2px; margin-left:6px; }
  .dots2 { position:absolute; left:0; right:0; display:flex; gap:4px; justify-content:center; } .dots2 i { width:5px; height:5px; border-radius:50%; background:oklch(0.4 0.004 60); } .dots2 i.on { background:var(--text); width:14px; border-radius:3px; }
  .mapc { position:absolute; left:var(--m); right:var(--m); bottom:var(--m); height:96px; border-radius:var(--card-r); overflow:hidden; background:oklch(0.22 0.004 60); }
  .mapc svg { width:100%; height:100%; display:block; } .mapc .r, .map3 .r { stroke:oklch(0.34 0.004 60); fill:none; stroke-linecap:round; } .water { fill:oklch(0.26 0.02 240); }
  .pn { position:absolute; transform:translate(-50%,-50%); width:20px; height:20px; border-radius:50%; background:var(--paper); color:var(--ink); font-size:10px; font-weight:800; display:grid; place-items:center; }
  .pn.on { background:var(--o); width:24px; height:24px; }
  .you { position:absolute; width:10px; height:10px; border-radius:50%; background:#4285f4; box-shadow:0 0 0 3px oklch(0.22 0.004 60), 0 0 0 4.5px #4285f4; transform:translate(-50%,-50%); }
  /* full-screen place detail */
  .full { position:absolute; left:var(--m); right:var(--m); top:var(--m); bottom:var(--m); background:var(--surface); border-radius:var(--card-r); padding:var(--p); display:flex; flex-direction:column; gap:8px; color:var(--text); }
  .full .c2 { padding:0 4px; display:flex; flex-direction:column; gap:5px; }
  .rev { display:flex; gap:6px; } .rev .bd { font-size:13px; }
  .map3 { height:58px; border-radius:var(--btn-r); overflow:hidden; position:relative; background:oklch(0.22 0.004 60); } .map3 svg { width:100%; height:100%; display:block; }
  .full .acts { margin-top:auto; }
  .mini { position:absolute; left:var(--m); right:var(--m); bottom:var(--m); background:var(--surface); border-radius:var(--card-r); padding:6px; display:flex; gap:10px; align-items:center; color:var(--text); }
  .mini .th { width:40px; height:40px; }
  /* simple result cards: label / big / body only */
  .rc { position:absolute; left:var(--m); right:var(--m); bottom:var(--m); top:var(--m); background:var(--surface); border-radius:var(--card-r); padding:var(--p); display:flex; flex-direction:column; color:var(--text); }
  .rc .c2 { padding:6px 6px 0; display:flex; flex-direction:column; gap:12px; }
  .rc .ph { height:84px; border-radius:var(--btn-r); overflow:hidden; } .rc .ph img { width:100%; height:100%; object-fit:cover; display:block; }
  .li { display:flex; gap:8px; align-items:flex-start; } .li .ap { margin-top:1px; } .li .ok { margin-left:auto; }
  .rc .acts { margin-top:auto; }
  .tl.will .ok { font-size:11px; font-weight:600; color:var(--text-2); }
  .out { font-size:34px; font-weight:300; letter-spacing:-.03em; line-height:1; color:var(--text); margin-bottom:4px; }
  /* question card: only when there's a decision; sits at the bottom, sized to its content */
  .qc { position:absolute; left:var(--m); right:var(--m); bottom:var(--m); background:var(--surface); border-radius:var(--card-r); padding:var(--p); display:flex; flex-direction:column; gap:var(--p); color:var(--text); }
  .qc .c2 { padding:6px 6px 2px; display:flex; flex-direction:column; gap:4px; }
  /* event list: one baseline per row (time · title), detail under the title, even rhythm */
  .evs { display:flex; flex-direction:column; gap:12px; }
  .ev { display:grid; grid-template-columns:78px 1fr; column-gap:8px; align-items:baseline; }
  .ev .when { font-size:14px; font-weight:600; color:var(--text); font-variant-numeric:tabular-nums; white-space:nowrap; }
  .ev .when span { color:var(--text-2); font-weight:500; margin-right:4px; }
  .ev .bd { color:var(--text); } .ev .esub { grid-column:2; font-size:12.5px; color:var(--text-2); margin-top:2px; }
  .ev.on .when { color:var(--o); }
  .mapt { position:absolute; left:var(--m); right:var(--m); top:34px; height:96px; border-radius:var(--card-r); overflow:hidden; background:oklch(0.22 0.004 60); }
  .mapt svg, .mapf svg { width:100%; height:100%; display:block; } .mapt .r, .mapf .r { stroke:oklch(0.34 0.004 60); fill:none; stroke-linecap:round; }
  .car2.bot { top:auto; bottom:var(--m); } .car2.bot .mos { height:92px; } .car2 .trk { transition:none; }
  .mapf { position:absolute; left:var(--m); right:var(--m); top:34px; bottom:74px; border-radius:var(--card-r); overflow:hidden; background:oklch(0.22 0.004 60); }
  .plab2 { position:absolute; transform:translate(14px,-50%); font-size:10.5px; font-weight:700; color:var(--text); white-space:nowrap; text-shadow:0 1px 2px #000; }
  .plab2.l { transform:translate(calc(-100% - 14px),-50%); }
  .step-n { font-size:12px; font-weight:800; color:oklch(0.5 0.004 60); margin-bottom:6px; }
  .flow { row-gap:34px; }
  .clock { position:absolute; top:22px; left:16px; color:var(--text); } .clock .t { font-size:46px; font-weight:300; letter-spacing:-.04em; line-height:1; } .clock .d { font-size:12px; color:var(--text-2); margin-top:4px; }
  .rc.home { top:auto; padding-bottom:14px; }
  .avr { display:flex; gap:8px; align-items:center; } .avr i { width:22px; height:22px; border-radius:50%; display:grid; place-items:center; font-style:normal; font-size:10px; font-weight:800; color:var(--ink); } .avr .ok { margin-left:auto; }
"""
U_='https://images.unsplash.com/photo-'
import urllib.parse
def ph(label, bg, fg):
    svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 200"><rect width="300" height="200" fill="{bg}"/><text x="150" y="108" font-family="Helvetica,Arial,sans-serif" font-size="20" font-weight="600" text-anchor="middle" fill="{fg}">{label}</text></svg>'
    return 'data:image/svg+xml,'+urllib.parse.quote(svg)
BIB=ph('photo · dish','#6b5443','#d9c7b6')
IN=[ph('photo · room','#4a4642','#bdb6ae'),ph('photo · room','#57504a','#c9c0b7'),ph('photo · table','#5f5348','#cfc2b4')]
def ic(path): return f'<svg class="ic" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">{path}</svg>'
W=ic('<path d="M3 12.5V4.5a1.5 1.5 0 0 1 1.5-1.5h7A1.5 1.5 0 0 1 13 4.5v5a1.5 1.5 0 0 1-1.5 1.5H6z"/>')
C=ic('<rect x="2.5" y="3.5" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11M5.5 2v3M10.5 2v3"/>')
N=ic('<path d="M2.5 8h11a5.5 5.5 0 0 1-11 0z"/><path d="M5 5.5l6-3"/>')
Ub=ic('<path d="M3 10.5l1.2-4A1.5 1.5 0 0 1 5.6 5.5h4.8a1.5 1.5 0 0 1 1.4 1l1.2 4v2H3z"/><path d="M5 12.5v1M11 12.5v1"/>')
M=ic('<path d="M8 14s4.5-4.2 4.5-7.5a4.5 4.5 0 0 0-9 0C3.5 9.8 8 14 8 14z"/><circle cx="8" cy="6.5" r="1.5"/>')
R=ic('<path d="M8 2.5l1.7 3.5 3.8.5-2.8 2.6.7 3.8L8 11.1l-3.4 1.8.7-3.8L2.5 6.5l3.8-.5z"/>')
S='<span class="mdot"></span>'
done=lambda ic,t: f'<div class="tl">{ic}<span>{t}</span><span class="ok">✓</span></div>'
now=lambda ic,t: f'<div class="tl now">{ic}<span>{t}</span><span class="ok"></span></div>'
wave='<div class="wv" style="margin:0"><i style="height:6px"></i><i style="height:12px"></i><i style="height:18px"></i><i style="height:9px"></i><i style="height:15px"></i><i style="height:7px"></i><i style="height:13px"></i></div>'
MAP='<svg viewBox="0 0 280 96" preserveAspectRatio="xMidYMid slice"><path class="water" d="M-10 76 Q90 64 160 80 T300 72 L300 110 L-10 110Z"/><g class="r" stroke-width="5"><path d="M-10 28 Q90 36 300 20"/><path d="M70 -10 Q84 40 60 110"/><path d="M190 -10 Q176 40 210 110"/></g><g class="r" stroke-width="1.6"><path d="M120 0 L134 96"/><path d="M0 52 L280 47"/><path d="M240 0 L250 96"/></g></svg>'
mini=lambda sub,name: f'<div class="mini"><div class="th"><img src="{BIB}"></div><div><div class="lb">{sub}</div><div class="bd" style="font-weight:700">{name}</div></div></div>'
def place(name,star,n,price,walk,why,imgs,pick=False):
    return f'''<div class="pc2"><div class="mos">{''.join(f'<img src="{u}">' for u in imgs)}</div><div class="c2">
<div class="nmrow"><b>{name}{'<span class="pickt">PICK</span>' if pick else ''}</b><span class="star"><i>★</i> {star} <span class="lb">({n})</span></span></div>
<div class="lb">Korean · {price} · {walk}</div>
<div class="why" style="margin-top:3px">{S}<span class="bd">{why}</span></div></div></div>'''
F=[]
def fr(n,inner,cap): F.append(f'<div><div class="step-n">{n}</div><div class="pho">{inner}</div><div class="fcap">{cap}</div></div>')
fr('1',f'<div class="said">Find the best bibimbap in Kreuzberg.</div><div class="lst">{wave}Listening</div>','<b>Hold the key, say it.</b>')
fr('2',f'<div class="work"><div class="echo">“Find the best bibimbap in Kreuzberg.”</div><div class="steps">{done(M,"Found 9 Korean places nearby")}{done(R,"Read reviews, kept 3 for bibimbap")}{now(S,"Checking who’s open Friday")}</div></div>','<b>Working layer.</b> Your words, then each step. Plain text, nothing tappable.')
cards=(place('Namsan','4.7','418','€€','6 min','Quiet room, the stone bowl arrives still crackling.',[BIB,IN[0],IN[1]],True)
 +place('Kimchi Princess','4.4','2.1k','€€','9 min','Lively and late. Loud for a calm dinner.',[IN[1],IN[2],IN[0]])
 +place('Ixthys','4.6','730','€','15 min','Tiny and homely, cash only.',[IN[2],IN[0],IN[1]]))
PINS=[(42,44),(74,30),(22,64)]
def pins(on, labels=False):
    out=''
    for k,(x,y) in enumerate(PINS):
        out+=f'<span class="pn{" on" if k==on else ""}" style="left:{x}%;top:{y}%">{k+1}</span>'
        if labels: out+=f'<span class="plab2{" l" if x>60 else ""}" style="left:{x}%;top:{y}%">{["Namsan","Kimchi Princess","Ixthys"][k]}</span>'
    return out+'<span class="you" style="left:55%;top:60%"></span>'
def f3(on, cap, n):
    shift = on*(196 if FORM=='3a' else 244)
    fr(n,f"""<div class="nav"><span>best bibimbap · Kreuzberg</span><span>3 steps ›</span></div>
<div class="mapt">{MAP}{pins(on)}</div>
<div class="car2 bot"><div class="trk" style="transform:translateX(-{shift}px)">{cards}</div></div>""",cap)
f3(0,'<b>Decide here.</b> Map on top, place cards at the bottom where your thumb is. Photos, rating, and why Misura picks it (orange dot = Misura’s voice).','3a')
f3(1,'<b>Swipe the cards.</b> The map follows: the pin of the card you’re on turns orange. You swipe; the map reacts.','3b')
fr('3c',f"""<div class="nav"><span class="bk">‹ 3 places</span><span>Map</span></div>
<div class="mapf">{MAP}{pins(0,True)}</div>
<div class="qc"><div class="c2" style="flex-direction:row;align-items:center;gap:10px;padding:2px"><div class="th" style="width:44px;height:44px"><img src="{BIB}"></div><div style="flex:1"><div class="bd" style="font-weight:700">Namsan</div><div class="lb">★ 4.7 · €€ · 6 min walk</div></div></div></div>""",
'<b>Tap the map (optional).</b> It opens full screen with names on the pins; the place you were on stays docked. ‹ goes back to the cards.')
fr('4',f'''<div class="nav"><span class="bk">‹ 3 places</span><span>Namsan</span></div><div class="full" style="top:36px"><div class="mos" style="height:132px"><img src="{BIB}"><img src="{IN[0]}"><img src="{IN[1]}"></div><div class="c2">
<div class="nmrow"><b>Namsan</b><span class="star"><i>★</i> 4.7 <span class="lb">(418)</span></span></div>
<div class="lb">Korean · €€ · open Fri until 22:30 · 6 min walk</div>
<div class="rev" style="margin-top:4px">{R}<span class="bd">“Best dolsot bibimbap in Berlin, and you can actually talk.”</span></div>
<div class="rev">{R}<span class="bd">“Small, calm, book on weekends.”</span></div></div>
<div class="acts"><div class="btn pick" style="flex:1.6">Reserve <span>→</span></div><div class="btn ghost">Directions</div></div></div>''',
'<b>Tap Namsan.</b> The card opens to fill the screen: more photos, reviews, hours, where it is. ‹ 3 places goes back to the carousel + map. Reserve moves forward; nothing commits.')
fr('5',f'<div class="said" style="font-size:19px">Friday 19:30 for four, with FriendC, FriendB and ColleagueA. <em>Let them know.</em></div><div class="lst" style="bottom:70px">{wave}Listening · about Namsan</div>{mini("Reserving","Namsan")}','<b>Talk to the card.</b> It stays docked, so what you say is about Namsan.')
fr('6',f'<div class="work"><div class="echo">“Friday 19:30 for four, with FriendC, FriendB and ColleagueA.”</div><div class="steps">{done(N,"Table for 4 free at 19:30")}{done(C,"Your Friday evening is free")}{now(W,"Writing to FriendC, FriendB, ColleagueA")}</div></div>{mini("Reserving","Namsan")}','<b>Working again.</b> It only looks; nothing is booked or sent.')
will=lambda ic,t: f'<div class="tl will">{ic}<span>{t}</span><span class="ok">will</span></div>'
qcard=lambda inner: f'<div class="qc">{inner}</div>'
fr('7',f"""<div class="nav"><span class="bk">‹ Namsan</span><span>Fri for 4 · 3 steps ›</span></div>
<div class="work" style="top:40px"><div class="steps" style="margin-top:0">{will(N,"Book Namsan, Fri 19:30, 4 people")}{will(C,"Add it to your calendar")}</div></div>
{qcard(f'<div class="c2"><div class="lb">WhatsApp · FriendC, FriendB, ColleagueA</div><div class="bd" style="font-size:16px">“Booked Namsan for Friday 19:30, bibimbap on me 🍜”</div></div><div class="acts"><div class="btn go">Book &amp; send <span>→</span></div><div class="btn ghost">Edit</div></div>')}""",
'<b>Approve.</b> The card is only the decision: the exact message and the one button. What the tap also does sits above as plain steps marked “will”.')
fr('8',f"""<div class="nav"><span class="bk">‹ Namsan</span><span>Fri for 4 · 5 steps ›</span></div>
<div class="work" style="top:44px"><div class="out">Booked.</div><div class="steps">{done(N,"Namsan, Fri 19:30, 4 people")}{done(C,"In your calendar")}{done(W,"Sent, seen by FriendB")}</div></div>
""",
'<b>Done.</b> Just the outcome, as text: “Booked.” and the ✓ steps. No card, nothing to do.')
fr('9',f"""<div class="clock"><div class="t">16:42</div><div class="d">Wednesday</div></div>
<div class="homeev"><div class="lb" style="margin-bottom:8px">Coming up</div><div class="evs"><div class="ev on"><div class="when"><span>Fri</span>19:30</div><div class="bd">Namsan</div><div class="esub">2 of 3 said yes</div></div><div class="ev"><div class="when"><span>Sat</span>11:00</div><div class="bd">Flea market</div></div><div class="ev"><div class="when"><span>Mon</span>09:30</div><div class="bd">Dentist</div></div></div></div>""",
'<b>Home.</b> The end of the run: upcoming events as plain text, the new one on top. No cards, because nothing needs you.')
HL=ic('<path d="M8 13.5S2.5 10.2 2.5 6.3A2.8 2.8 0 0 1 8 5a2.8 2.8 0 0 1 5.5 1.3C13.5 10.2 8 13.5 8 13.5z"/>')
WG=ic('<rect x="2.5" y="2.5" width="4.5" height="4.5" rx="1"/><rect x="9" y="2.5" width="4.5" height="4.5" rx="1"/><rect x="2.5" y="9" width="4.5" height="4.5" rx="1"/><path d="M11.25 9.5v3.5M9.5 11.25H13"/>')
def stepw(prev=False):
    return f'<div class="stw{" pv" if prev else ""}"><div class="lb">Steps today</div><div class="stn">6,412</div><div class="stb"><i style="width:64%"></i></div><div class="lb" style="margin-top:6px">Goal 10,000 · usually 7,800 by tonight</div></div>'
EVS='<div class="lb" style="margin-bottom:8px">Coming up</div><div class="evs"><div class="ev on"><div class="when"><span>Fri</span>19:30</div><div class="bd">Namsan</div><div class="esub">2 of 3 said yes</div></div><div class="ev"><div class="when"><span>Sat</span>11:00</div><div class="bd">Flea market</div></div></div>'
fr('10',f'<div class="said">Build me a little step counter for my home screen.</div><div class="lst">{wave}Listening</div>','<b>It makes tools, too.</b> Hold the key and ask for one.')
fr('11',f'<div class="work"><div class="echo">“Build me a little step counter for my home screen.”</div><div class="steps">{done(HL,"Health: can read your steps")}{done(HL,"You usually walk 7,800 a day")}{now(WG,"Building the widget")}</div></div>','<b>Working.</b> Same steps layer: where the data comes from, then the build.')
fr('12',f'<div class="work"><div class="echo">“Build me a little step counter for my home screen.”</div><div class="steps">{done(HL,"Health: can read your steps")}{done(WG,"Built the widget")}</div></div><div class="qc"><div class="c2"><div class="lb">New widget · home screen</div>{stepw(True)}</div><div class="acts"><div class="btn go">Add to home <span>→</span></div><div class="btn ghost">Change</div></div></div>','<b>Preview, then add.</b> Creating something is a commit, so it’s a card with the real widget inside. Say “make the goal 8,000” and it updates.')
fr('13',f'<div class="clock"><div class="t">16:42</div><div class="d">Wednesday</div></div><div class="stc"><div class="lb">Steps</div><div class="n">6,412</div><div class="stb"><i style="width:64%"></i></div><div class="lb" style="margin-top:5px">of 10,000</div></div><div class="homeev">{EVS}</div>','<b>Home.</b> The step counter lives on the screen layer like the events: it shows state, so no card.')
css += r"""
  .stw .stn { font-size:34px; font-weight:300; letter-spacing:-.03em; line-height:1; color:var(--text); margin-top:4px; font-variant-numeric:tabular-nums; }
  .stb { height:6px; border-radius:3px; background:oklch(0.36 0.004 60); margin-top:10px; overflow:hidden; } .stb i { display:block; height:100%; background:var(--o); border-radius:3px; }
  .stw.pv { padding:4px 0 2px; } .qc .stw .stb { background:oklch(0.42 0.004 60); }
  /* home: "now" group on top (clock + compact widgets on the right), "coming up" group at the bottom */
  .stc { position:absolute; right:16px; top:30px; width:84px; text-align:right; color:var(--text); }
  .stc .n { font-size:22px; font-weight:400; letter-spacing:-.02em; line-height:1.1; margin-top:2px; font-variant-numeric:tabular-nums; }
  .stc .stb { height:4px; margin-top:7px; }
  .homeev { position:absolute; left:16px; right:16px; bottom:22px; color:var(--text); }
"""
if FORM=='3a':
    css += r"""
  h2 { margin-top:46px; }
  .sw { display:grid; grid-template-columns:repeat(auto-fill,minmax(200px,1fr)); gap:10px; max-width:1100px; }
  .sw div { display:grid; grid-template-columns:34px 1fr; column-gap:10px; align-items:center; font-size:13px; } .sw i { grid-row:span 2; width:34px; height:34px; border-radius:8px; box-shadow:inset 0 0 0 1px #0002; } .sw span { font-size:11.5px; color:oklch(0.45 0.004 60); }
  .tiles { display:grid; grid-template-columns:repeat(auto-fill,minmax(250px,1fr)); gap:16px; max-width:1200px; }
  .tile { background:var(--screen); border-radius:20px; padding:6px; --card-r:14px; --btn-r:8px; display:flex; flex-direction:column; }
  .tbox { padding:14px 10px; color:var(--text); min-height:120px; position:relative; }
  .tile .meta { padding:4px 8px 8px; color:var(--text); } .tile .meta b { font-size:14px; display:block; } .tile .meta span { font-size:12px; color:var(--text-2); line-height:1.35; display:block; margin-top:3px; }
  .s { position:relative !important; left:auto !important; right:auto !important; top:auto !important; bottom:auto !important; }
  .said.s { font-size:19px; } .nav.s { display:flex; }
  .car2.s .pc2 { width:100%; } .mapt.s { height:130px; }
  .tl.fail .ok { color:var(--o); font-weight:700; }
  .map-t { border-collapse:collapse; font-size:14px; max-width:900px; } .map-t th, .map-t td { text-align:left; padding:8px 14px 8px 0; border-bottom:1px solid #0001; vertical-align:top; } .map-t th { font-size:12px; color:oklch(0.45 0.004 60); }

  /* ---- Nothing Phone (3a): 1080x2392, 20:9. Frames at 228x506 (≈ 360 dp wide). ---- */
  .pho { width:228px !important; height:506px !important; border-radius:20px !important; --R:20px; --card-r:14px; --btn-r:8px; }
  .pc2 { width:190px; } .car2.bot .mos { height:120px; }
  .mapt { height:236px; } .mapf { bottom:78px; }
  .said { font-size:19px; top:56px; } .work { top:44px; } .clock { top:40px; }
  .nav { top:26px; } .mapt, .mapf { top:48px; } .full, .rc { top:48px !important; }
  .work[style*="top:40px"], .work[style*="top:44px"] { top:56px !important; }
  .work[style*="top:118px"] { top:130px !important; }
  .stc { top:44px; } .homeev { bottom:28px; }
  .full .mos { height:200px !important; }
"""
    F.insert(0,'')
    F.pop(0)

fail=lambda ic,t: f'<div class="tl fail">{ic}<span>{t}</span><span class="ok">✕</span></div>'
def tile(name, rule, inner, h=None):
    return f'<div class="tile"><div class="tbox"{f" style=\'min-height:{h}px\'" if h else ""}>{inner}</div><div class="meta"><b>{name}</b><span>{rule}</span></div></div>'
SW=[('screen','#080706','Behind everything'),('surface','#32302e','Cards'),('text','#efeeec','Primary text'),('text-2','#adaaa8','Labels, steps in progress'),('paper','#efeeec','Option tiles'),('ink','#110f0e','Text on paper/orange'),('orange','#f47b30','Commit, active pin, Misura’s dot'),('you','#4285f4','Your location only'),('star','#fbbc04','Ratings only')]
SHEET = '<h2>Rules</h2><div class="rules">' + ''.join(f'<div><b>{a}</b>{b}</div>' for a,b in [
 ('Screen talks, cards ask.','The working layer is plain text on the screen: your words, Misura’s steps, outcomes, home. A card appears only when you have something to do: pick, decide, answer.'),
 ('✓ means done. Nothing else.','● orange = Misura is working (or speaking: its reasons). “will” = happens when you tap. ✕ = failed, always with a card to fix it.'),
 ('One tap commits.','Solid orange commits (sends, books, creates). Orange outline moves forward. Grey outline is everything else. Everything the tap does is on screen when you tap.'),
 ('Back walks the cards.','Nav line on top: ‹ back one card on the left, where you are and “n steps ›” on the right. The system back gesture does the same. Steps › opens the history.'),
 ('Few voices.','Screen: said 19/300, echo 16/300, outcome 34/300, step 13.5/400, nav 12/600. Cards: label 11.5/600, body 14/400, title 17/700, big 28/800; at most three per card besides the button.'),
 ('Corners nest.','Screen 20 → card 14 → button 8 (each = outer − 6 px inset). No gradients, glows or decorative lines.'),
]) + '</div>'
SHEET += '<h2>Tokens</h2><div class="sw">' + ''.join(f'<div><i style="background:{c}"></i><b>{n}</b><span>{c} · {u}</span></div>' for n,c,u in SW) + '</div>'
SHEET += '<h2>Screen layer (never tappable, except the nav line)</h2><div class="tiles">' + ''.join([
 tile('Said','Your words while you hold the key. Big, light; the part after a pause in text-2.', '<div class="said s">Find the best bibimbap in Kreuzberg.</div>'),
 tile('Echo + steps','Your words, then one line per step: app icon, plain sentence, state on the right.', f'<div class="echo">“Friday 19:30 for four…”</div><div class="steps">{done(N,"Table for 4 free at 19:30")}{now(W,"Writing to FriendC, FriendB, ColleagueA")}</div>'),
 tile('Step states','done ✓ · working ● · will (on your tap) · failed ✕.', f'<div class="steps" style="margin:0">{done(C,"In your calendar")}{now(S,"Checking who’s open")}{will(N,"Book Namsan, Fri 19:30")}{fail(W,"Couldn’t reach ColleagueA")}</div>'),
 tile('Outcome','The result of a run, as text. No card: nothing to do.', f'<div class="out">Booked.</div><div class="steps">{done(N,"Namsan, Fri 19:30, 4 people")}</div>'),
 tile('Nav line','Top of the screen. Root of a task: what you asked. Deeper: ‹ back.', '<div class="nav s"><span>best bibimbap · Kreuzberg</span><span>3 steps ›</span></div><div class="nav s" style="margin-top:14px"><span class="bk">‹ 3 places</span><span>Namsan</span></div>'),
 tile('Event row','Home. What’s coming up; the newest time in orange.', '<div class="evs"><div class="ev on"><div class="when"><span>Fri</span>19:30</div><div class="bd">Namsan</div><div class="esub">2 of 3 said yes</div></div><div class="ev"><div class="when"><span>Sat</span>11:00</div><div class="bd">Flea market</div></div></div>'),
]) + '</div>'
SHEET += '<h2>Cards (only when you have something to do)</h2><div class="tiles">' + ''.join([
 tile('Place card','Carousel item. Photos, name + rating, kind · price · walk, Misura’s reason (orange dot). PICK on the recommendation.', f'<div class="car2 s"><div class="trk" style="padding:0">{place("Namsan","4.7","418","€€","6 min","Quiet room, the stone bowl arrives still crackling.",[BIB,IN[0],IN[1]],True)}</div></div>'),
 tile('Map','Pins: paper = option, orange = the card you’re on, blue = you. Strip above the carousel; tap → full screen with names.', f'<div class="mapt s">{MAP}{pins(0)}</div>'),
 tile('Question card','Label (who/where) + body (the exact thing) + buttons. Approve, offers, incoming messages, problems: all this one card.', '<div class="qc s"><div class="c2"><div class="lb">WhatsApp · FriendC, FriendB, ColleagueA</div><div class="bd">“Booked Namsan for Friday 19:30, bibimbap on me 🍜”</div></div><div class="acts"><div class="btn go">Book &amp; send <span>→</span></div><div class="btn ghost">Edit</div></div></div>'),
 tile('Question card · problem','After a ✕ step. Says what went out and offers the fix.', '<div class="qc s"><div class="c2"><div class="lb">WhatsApp · ColleagueA</div><div class="bd">Not sent: ColleagueA isn’t on WhatsApp.</div></div><div class="acts"><div class="btn pick" style="flex:1.6">Send as SMS <span>→</span></div><div class="btn ghost">Skip</div></div></div>'),
 tile('Docked card','The card you’re talking about, shrunk to the bottom while you speak or Misura works.', f'<div class="mini s"><div class="th"><img src="{BIB}"></div><div><div class="lb">Reserving</div><div class="bd" style="font-weight:700">Namsan</div></div></div>'),
 tile('Buttons','Solid orange commits · orange outline forward · grey outline other.', '<div class="acts"><div class="btn go">Send <span>→</span></div></div><div class="acts" style="margin-top:6px"><div class="btn pick" style="flex:1.6">Reserve <span>→</span></div><div class="btn ghost">Directions</div></div>'),
]) + '</div>'
SHEET += '<h2>Old card types → new system</h2><table class="map-t"><tr><th>Before (card-system.md)</th><th>Now</th></tr>' + ''.join(f'<tr><td>{a}</td><td>{b}</td></tr>' for a,b in [
 ('Progress card','Steps on the screen (working ●)'),('Text card','Outcome / plain text on the screen'),('Choices, Gallery','Place carousel (or plain rows for non-places)'),('Map card','Map strip above the carousel; full screen on tap'),
 ('Approve','“will” steps on the screen + question card'),('Done','Outcome on the screen, ✓ steps'),('Waiting','Event row on home (“2 said yes”)'),('Incoming','Question card'),('Problem / may have sent','✕ step + question card with the fix'),('Live · music','Unchanged for now (it’s a control: a card)')]) + '</table>'

TITLE='Misura on the Phone (3a): the flow + the system' if FORM=='3a' else 'Misura: working layer + result cards (landing-page screen 584×706)'
body=f'<h1>{TITLE}</h1>'+'<p class="sub">Working layer: your words and the steps as plain text on the screen. Result: cards. Nav line on top of the screen: ‹ back one card on the left, where you are and the steps on the right. A card only when you have something to do; state is plain text. Ratings, reviews and photos are placeholders.</p><div class="flow">'+"\n".join(F)+'</div>'+(SHEET if FORM=='3a' else '')
PT='Misura Design System' if FORM=='3a' else 'Misura Square Flow'
head=head.replace('<meta charset="utf-8">', f'<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{PT}</title>',1)
open(D+('/design-system.html' if FORM=='3a' else '/flow-mockup.html'),'w').write(head+css+'</style></head><body>'+body+'</body></html>\n')
