"""Read-only usage poll for ticket b0fb747d. Every 30 s for 90 min, appends one JSON line to poll.jsonl.
Works on scratch copies of the live databases; never opens or writes the live files."""
import datetime
import json
import os
import shutil
import sqlite3
import time

STATE = os.path.join(os.environ["LOCALAPPDATA"], "SuperiorSoft", "AgentHQ")
HERE = os.path.dirname(os.path.abspath(__file__))
SCRATCH = os.path.join(HERE, "copy")
OUT = os.path.join(HERE, "poll.jsonl")
PACE = os.path.join(STATE, "plugin-data", "Infantry_Pace", "samples.jsonl")
DURATION = 90 * 60
INTERVAL = 30


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def append(rec):
    with open(OUT, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec) + "\n")


def copy_db(name):
    os.makedirs(SCRATCH, exist_ok=True)
    dst = os.path.join(SCRATCH, name)
    for suffix in ("", "-wal", "-shm"):
        src = os.path.join(STATE, name + suffix)
        try:
            shutil.copy2(src, dst + suffix)
        except FileNotFoundError:
            # The daemon checkpoints and removes -wal/-shm; a missing one is normal.
            if suffix == "":
                raise
            if os.path.exists(dst + suffix):
                os.remove(dst + suffix)
    return dst


def ro(path):
    return sqlite3.connect("file:" + path.replace("\\", "/") + "?mode=ro", uri=True)


def pace_claude():
    last = {}
    try:
        with open(PACE, encoding="utf-8", errors="replace") as f:
            for line in f.readlines()[-400:]:
                try:
                    s = json.loads(line)
                except ValueError:
                    continue
                if s.get("p") == "claude":
                    last[s.get("w")] = {"u": s.get("u"), "t": s.get("t")}
    except OSError as e:
        return {"error": str(e)}
    return last


def poll_once(st, now_ms):
    rec = {"at": utc_now(), "pace": pace_claude()}
    try:
        m = ro(copy_db("usage-meter.db"))
        rows = m.execute(
            "select id, ts, session_id, role, coalesce(harness,''), coalesce(model,''), "
            "coalesce(input,0), coalesce(output,0), coalesce(cache_read,0), coalesce(cache_write,0) "
            "from meter_request where id > ? order by id", (st["req"],)).fetchall()
        events = m.execute(
            "select id, ts, session_id, harness, kind, coalesce(trigger_kind,''), coalesce(tokens_before,0) "
            "from meter_event where id > ? order by id", (st["event"],)).fetchall()
        m.close()
        groups = {}
        tot = [0, 0, 0, 0]
        for r in rows:
            key = "%s|%s|%s|%s" % (r[2][:8], r[3], r[4], r[5])
            g = groups.setdefault(key, {"req": 0, "in": 0, "out": 0, "cr": 0, "cw": 0})
            g["req"] += 1
            g["in"] += r[6]; g["out"] += r[7]; g["cr"] += r[8]; g["cw"] += r[9]
            tot[0] += r[6]; tot[1] += r[7]; tot[2] += r[8]; tot[3] += r[9]
            st["req"] = max(st["req"], r[0])
        rec["requests"] = {"count": len(rows), "in": tot[0], "out": tot[1], "cache_read": tot[2],
                           "cache_write": tot[3], "groups": groups}
        rec["meter_events"] = [{"session": e[2][:8], "harness": e[3], "kind": e[4], "trigger": e[5],
                                "tokens_before": e[6]} for e in events]
        if events:
            st["event"] = max(e[0] for e in events)
    except (sqlite3.Error, OSError) as e:
        rec["meter_error"] = repr(e)
    try:
        a = ro(copy_db("agenthq.db"))
        started = a.execute(
            "select ticket_id, session_id, started_at from ticket_runs where started_at > ?", (st["ts"],)).fetchall()
        ended = a.execute(
            "select ticket_id, session_id, ended_at, outcome from ticket_runs where ended_at > ?", (st["ts"],)).fetchall()
        changes = a.execute(
            "select seq, ticket_id, kind, from_status, to_status, at from ticket_events where seq > ? order by seq",
            (st["seq"],)).fetchall()
        a.close()
        rec["sessions_started"] = [{"ticket": s[0][:13], "session": s[1][:8], "at": s[2]} for s in started]
        rec["sessions_ended"] = [{"ticket": s[0][:13], "session": s[1][:8], "at": s[2], "outcome": s[3]} for s in ended]
        rec["ticket_status_changes"] = [{"ticket": c[1][:13], "kind": c[2], "from": c[3], "to": c[4], "at": c[5]}
                                        for c in changes]
        if changes:
            st["seq"] = max(c[0] for c in changes)
    except (sqlite3.Error, OSError) as e:
        rec["agenthq_error"] = repr(e)
    st["ts"] = now_ms
    append(rec)


def main():
    start = time.time()
    st = {"req": 0, "event": 0, "seq": 0, "ts": 0}
    # Baseline at the current end so only new activity is recorded.
    try:
        m = ro(copy_db("usage-meter.db"))
        st["req"] = m.execute("select coalesce(max(id),0) from meter_request").fetchone()[0]
        st["event"] = m.execute("select coalesce(max(id),0) from meter_event").fetchone()[0]
        m.close()
    except (sqlite3.Error, OSError) as e:
        append({"at": utc_now(), "baseline_error": repr(e)})
    try:
        a = ro(copy_db("agenthq.db"))
        st["seq"] = a.execute("select coalesce(max(seq),0) from ticket_events").fetchone()[0]
        a.close()
    except (sqlite3.Error, OSError) as e:
        append({"at": utc_now(), "baseline_error": repr(e)})
    st["ts"] = int(time.time() * 1000)

    while time.time() - start < DURATION:
        time.sleep(INTERVAL)
        try:
            poll_once(st, int(time.time() * 1000))
        except Exception as e:  # keep the run alive; record the failure
            append({"at": utc_now(), "loop_error": repr(e)})

    append({"done": True, "at": utc_now()})


if __name__ == "__main__":
    main()
