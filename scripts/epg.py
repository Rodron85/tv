#!/usr/bin/env python3
"""Builds public/epg.json: what's on now and next for PT and UK channels.
Sources: M3UPT EPG (Portugal) and Freeview UK (both public XMLTV files)."""
import gzip, io, json, lzma, re, sys, time, unicodedata, urllib.request
import xml.etree.ElementTree as ET

BASE = "https://raw.githubusercontent.com/LITUATUI/M3UPT/main/EPG/"
SOURCES = [("epg-m3upt.xml.xz", "pt"), ("epg-freeview-uk.xml.xz", "uk"),
           ("https://i.mjh.nz/PlutoTV/gb.xml.gz", "pl"), ("https://i.mjh.nz/PlutoTV/us.xml.gz", "pl")]
KIDS = re.compile(r"(?i)kid|junior|toon|nick|cartoon|anima|baby|bean|peppa|teletubb|sponge|lego|garfield|smurf|sonic|oggy|tom and|transformers|turtles|rugrats|dora|avatar|arnold|pok[eé]mon|barney|thomas|caillou|power rangers|family|pocket\.watch|ryan|blippi|moonbug|baby shark|ninja")
BEFORE, AFTER, MAXP = 3 * 3600, 26 * 3600, 120

def key(n):
    n = unicodedata.normalize("NFD", (n or "").lower())
    n = "".join(c for c in n if not unicodedata.combining(c))
    n = re.sub(r"\b(hd|sd|uhd|fhd)\b", "", n)
    return re.sub(r"[^a-z0-9]", "", n)

def ts(s):
    t = time.strptime(s[:14], "%Y%m%d%H%M%S")
    off = 0
    m = re.search(r"([+-])(\d\d)(\d\d)$", s.strip())
    if m: off = (int(m.group(2)) * 60 + int(m.group(3))) * 60 * (1 if m.group(1) == "+" else -1)
    import calendar
    return calendar.timegm(t) - off

def fetch(name):
    if name.startswith("http"):
        req = urllib.request.Request(name, headers={"User-Agent": "Mozilla/5.0 ForretaTV-EPG"})
        return urllib.request.urlopen(req, timeout=120).read()
    if len(sys.argv) > 1:                       # local folder for testing
        return open(sys.argv[1] + "/" + name, "rb").read()
    req = urllib.request.Request(BASE + name, headers={"User-Agent": "ForretaTV-EPG"})
    return urllib.request.urlopen(req, timeout=120).read()

now = int(time.time())
chans, alias = {}, {}
def add_alias(a, cid):
    if a and a not in alias: alias[a] = cid

for fname, tag in SOURCES:
    try: raw = fetch(fname)
    except Exception as e:
        print("falhou", fname, e); continue
    data = lzma.decompress(raw) if fname.endswith(".xz") else gzip.decompress(raw)
    names = {}
    for ev, el in ET.iterparse(io.BytesIO(data)):
        if el.tag == "channel":
            cid = el.get("id")
            dn = [d.text or "" for d in el.findall("display-name")]
            names[cid] = dn
            el.clear()
        elif el.tag == "programme":
            cid = el.get("channel")
            if tag == "pt" and not cid.lower().split("@")[0].endswith(".pt"):
                el.clear(); continue
            if tag == "pl" and not KIDS.search(" ".join(names.get(cid, []))):
                el.clear(); continue
            a, b = ts(el.get("start")), ts(el.get("stop") or el.get("start"))
            if b > now - BEFORE and a < now + AFTER:
                t = el.findtext("title") or ""
                d = (el.findtext("desc") or "").strip()
                k = cid.lower().split("@")[0] if tag == "pt" else tag + ":" + cid
                p = [a // 60, b // 60, t.strip()]
                if d: p.append(d[:160])
                chans.setdefault(k, []).append(p)
            el.clear()
    order = sorted(names.items(), key=lambda x: (0 if re.search(r"lon", x[0] + " ".join(x[1]), re.I) else 1, x[0]))
    for cid, dn in order:
        k = cid.lower().split("@")[0] if tag == "pt" else tag + ":" + cid
        if k not in chans: continue
        for n in dn:
            add_alias(key(n), k)
            if tag == "pl" and not n.lower().startswith("pluto"): add_alias(key("Pluto TV " + n), k)
            m = re.match(r"(?i)u\s*&\s*(.+)", n)             # U&Dave -> Dave
            if m: add_alias(key(m.group(1)), k)
            m = re.match(r"(?i)(bbc (one|two))\b", n)        # BBC ONE Lon -> BBC One
            if m: add_alias(key(m.group(1)), k)
            if re.fullmatch(r"(?i)itv1", n): add_alias("itv", k)
            if n.strip() == "5": add_alias("channel5", k)
            if k == "rtpnoticias.pt": add_alias("rtp3", k)

out = {}
for k, ps in chans.items():
    ps.sort(); seen, keep = set(), []
    for p in ps:
        if p[0] in seen: continue
        seen.add(p[0]); keep.append(p)
    keep = [p for p in keep if p[1] * 60 > now - 600][:MAXP]
    for i, p in enumerate(keep):
        if i > 1 and len(p) > 3: del p[3]          # description only for now / next
    if keep: out[k] = keep
alias = {a: k for a, k in alias.items() if k in out}
json.dump({"t": now, "c": out, "a": alias}, open("public/epg.json", "w"), ensure_ascii=False, separators=(",", ":"))
print(len(out), "canais,", len(alias), "nomes")
