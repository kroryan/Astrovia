#!/usr/bin/env python3
"""Asks every source of shared/data/archives.json for one row around M42, so a table that disappeared or changed shows up.

    python3 tools/archives/check_registry.py [--fast]    (exit status 1 when a source fails)
EMPTY is fine (the survey may not cover M42). SLOW and ERROR are worth a look.
"""
import concurrent.futures as cf, json, os, sys, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REG = json.load(open(os.path.join(HERE, "..", "..", "shared", "data", "archives.json")))


def test(s):
    tap = s.get("tap") or REG["tap"]
    cols = [s["ra"], s["dec"]] + [s[k] for k in ("idCol", "nameCol") if s.get(k)]
    q = "SELECT TOP 1 %s FROM %s WHERE 1=CONTAINS(POINT('ICRS',%s,%s),CIRCLE('ICRS',83.82,-5.39,%s))%s" % (",".join(dict.fromkeys(cols)), s["table"], s["ra"], s["dec"], "0.5" if "--fast" in sys.argv else "3", " AND (%s)" % s["where"] if s.get("where") else "")
    data = urllib.parse.urlencode({"QUERY": q, "REQUEST": "doQuery", "LANG": "ADQL", "FORMAT": s.get("fmt", "csv")}).encode()
    t0 = time.time()
    try:
        body = urllib.request.urlopen(urllib.request.Request(tap + "/sync", data), timeout=60).read().decode("latin-1")
    except Exception as e:
        return s["id"], "ERROR", str(e)[:70]
    dt = time.time() - t0
    if 'QUERY_STATUS" value="ERROR' in body or body.lstrip().startswith("<html"):
        return s["id"], "ERROR", body[body.find("ERROR"):][:90].replace("\n", " ")
    rows = body.count("<TR>") if body.lstrip().startswith("<") else max(0, len(body.strip().splitlines()) - 1)
    return s["id"], ("SLOW" if dt > 30 else "OK" if rows else "EMPTY"), "%.1fs" % dt


with cf.ThreadPoolExecutor(6) as ex:
    res = list(ex.map(test, REG["sources"]))
bad = 0
for sid, status, info in res:
    if status in ("ERROR", "SLOW"):
        bad += 1
    if status != "OK":
        print("%-6s %-45s %s" % (status, sid, info))
print("%d sources: %d ok, %d empty, %d need a look" % (len(res), sum(r[1] == "OK" for r in res), sum(r[1] == "EMPTY" for r in res), bad))
sys.exit(1 if bad else 0)
