#!/usr/bin/env python3
"""Regenerates shared/data/archives.json (the list of archives and catalogues both apps search).

The ESA Sky TAP service publishes its tables in TAP_SCHEMA; this script reads them, finds the position / id / name /
footprint / link columns of each table, keeps the ones listed in labels.json (with the label, mission, band and type they
get in the app) and appends the hand-written sources of extras.json (MAST, ESO, IRSA, HEASARC, Gaia, exoplanets...).

    python3 tools/archives/build_registry.py            # rewrite shared/data/archives.json
    python3 tools/archives/build_registry.py --check    # only report differences with the current file
Needs network access to sky.esa.int. Run tools/archives/check_registry.py afterwards to test every source live.
"""
import csv, io, json, os, sys, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "shared", "data", "archives.json")
TAP = "https://sky.esa.int/esasky-tap/tap"
SCHEMAS = "('observations','catalogues','alerts','sso','images')"
RA = ["ra_deg", "ra", "raj2000", "ra_icrs", "_raj2000", "ra_deg_1", "s_ra", "raicrs", "ra_j2000"]
DEC = ["dec_deg", "dec", "dej2000", "de_icrs", "_dej2000", "dec_deg_1", "s_dec", "deicrs", "dec_j2000"]
ID = ["observation_id", "obs_id", "obsid", "source_id", "name", "target_name", "designation", "id", "sso_name", "event_id", "recno", "proposal_id"]
NAME = ["target_name", "targname", "name", "object", "sso_name", "title", "description"]
LINK = ["product_url", "access_url", "postcard_url", "url", "link", "archive_link"]


def adql(q, maxrec=100000):
    data = urllib.parse.urlencode({"QUERY": q, "REQUEST": "doQuery", "LANG": "ADQL", "FORMAT": "csv", "MAXREC": maxrec}).encode()
    return list(csv.DictReader(io.StringIO(urllib.request.urlopen(urllib.request.Request(TAP + "/sync", data), timeout=120).read().decode())))


def pick(cols, cands):
    low = {c.lower(): c for c in cols}
    for k in cands:
        if k in low:
            return low[k]


def generated():
    cols = {}
    for r in adql("SELECT table_name,column_name FROM TAP_SCHEMA.columns WHERE schema_name IN " + SCHEMAS):
        cols.setdefault(r["table_name"], []).append(r["column_name"])
    tables = [r["table_name"] for r in adql("SELECT table_name FROM TAP_SCHEMA.tables WHERE schema_name IN " + SCHEMAS)]
    names = set(tables)
    out = {}
    for t in tables:
        if t.endswith("_fdw") and t[:-4] in names:
            continue                                      # the plain table wins over its foreign-data wrapper
        c = cols.get(t, [])
        ra, dec = pick(c, RA), pick(c, DEC)
        if not ra or not dec:
            continue
        out[t.split(".", 1)[1]] = dict(table=t, ra=ra, dec=dec, idCol=pick(c, ID), nameCol=pick(c, NAME), regionCol=pick(c, ["stc_s", "s_region"]), linkCol=pick(c, LINK))
    return out


def build():
    labels = json.load(open(os.path.join(HERE, "labels.json")))
    extras = json.load(open(os.path.join(HERE, "extras.json")))
    gen = generated()
    sources, seen = [], set()
    for key, lab in labels.items():
        g = gen.get(key)
        if g is None:
            print("WARNING: table %s no longer exists in the ESA Sky TAP service" % key, file=sys.stderr)
            continue
        s = dict(id=key, label=lab["label"], mission=lab["mission"], band=lab["band"], kind=lab["kind"], table=g["table"], ra=g["ra"], dec=g["dec"],
                 idCol=g["idCol"], nameCol=g["nameCol"], regionCol=g["regionCol"], linkCol=g["linkCol"], centre="ESA")
        if lab.get("orderCol"):
            s["orderCol"] = lab["orderCol"]
        if (s["label"], s["kind"]) in seen:
            continue
        seen.add((s["label"], s["kind"]))
        sources.append({k: v for k, v in s.items() if v is not None})
    sources.sort(key=lambda x: (x["kind"], x["mission"], x["label"]))
    extras = [{k: v for k, v in e.items() if v is not None} for e in extras]      # a missing key and a null mean the same to both apps
    return {"tap": TAP, "sources": sources + extras}


if __name__ == "__main__":
    new = build()
    old = json.load(open(OUT))
    same = json.dumps(new, sort_keys=True) == json.dumps(old, sort_keys=True)
    print("%d sources; %s the current file" % (len(new["sources"]), "identical to" if same else "DIFFERENT from"))
    if "--check" not in sys.argv and not same:
        json.dump(new, open(OUT, "w"), indent=1, ensure_ascii=False)
        print("written", os.path.normpath(OUT))
    sys.exit(0 if same or "--check" not in sys.argv else 1)
