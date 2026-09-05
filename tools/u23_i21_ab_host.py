#!/usr/bin/env python3
"""U2.3-I2.1 host orchestrator: true process-cold paired A/B A/B evidence generator.

This is a bounded, deterministic, host-side driver. It does NOT rely on a human or a
model copying log lines into JSON. Every paired run is:

  1. force-stopped and proven process-absent on-device (pidof + scoped ps) BEFORE start
  2. launched as one fresh `am instrument -w` invocation
  3. handed back its authoritative counters/timing via a test-only per-invocation file
     (filesDir/u23i21-last-run.json), correlated to the expected runId

The committed evidence JSON is emitted HERE, from this run, using the live HOST clock.
Nothing is reconstructed or hand-authored.

Usage:
  python tools/u23_i21_ab_host.py --serial R3CX40A15GB
  python tools/u23_i21_ab_host.py --serial R3CX40A15GB --install app/build/outputs/apk/debug/app-debug.apk --install-test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

Exit code is 0 only when the ENTIRE sequence is valid. Any invalid step aborts and the
committed evidence file is NOT (re)written.
"""

import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
from datetime import datetime, timezone

PACKAGE = "com.projectnuke.keplernightlab"
TEST_PKG = PACKAGE + ".test"
TEST_CLASS = PACKAGE + ".U23I21ActivationReadinessTest"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
RESULT_ABS = "/data/data/%s/files/u23i21-last-run.json" % PACKAGE
MANIFEST_ABS = "/data/data/%s/files/u23i21-manifest.json" % PACKAGE
EXTERNAL_ROOT_ABS = "/storage/emulated/0/Android/data/%s/files/Pictures/U23I21Activation/KeplerYuvFusion" % PACKAGE

EVIDENCE_REL = os.path.join("docs", "evidence", "U2_3_I2_1_host_evidence.json")

# Ordered invocations: (testMethod, mode, expectedRunId)
RUNS = [
    ("i21Seed46", "SEED", "i21Seed46"),
    ("i21ColdRunOff1", "OFF", "OFF-1"),
    ("i21ColdRunOn1", "ON", "ON-1"),
    ("i21ColdRunOff2", "OFF", "OFF-2"),
    ("i21ColdRunOn2", "ON", "ON-2"),
    ("i21ColdRunOff3", "OFF", "OFF-3"),
    ("i21ColdRunOn3", "ON", "ON-3"),
    ("i21ZeroWrite", "VERIFY", "i21ZeroWrite"),
    ("i21FinalSweep", "CLEANUP", "i21FinalSweep"),
]


class Fail(RuntimeError):
    pass


def _force_utf8_stdio():
    """Host consoles (e.g. cp949 on Windows) cannot encode the em-dash in our
    classification string; force UTF-8 so the tool never crashes on a print."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass


def find_adb():
    env = os.environ.get("ADB")
    if env and os.path.isfile(env):
        return env
    local = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
    if os.path.isfile(local):
        return local
    found = shutil.which("adb")
    if found:
        return found
    raise Fail("adb not found (set $ADB or install platform-tools)")


def adb_base(adb, serial):
    return [adb, "-s", serial]


def run(cmd, timeout=600):
    """Run a host command; return (rc, stdout, stderr). Never raises on non-zero rc."""
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    out = proc.stdout or ""
    err = proc.stderr or ""
    return proc.returncode, out, err


def adb_shell(adb, serial, cmd, timeout=600):
    base = adb_base(adb, serial)
    argv = base + ["shell"] + cmd if isinstance(cmd, list) else base + ["shell", cmd]
    rc, out, err = run(argv, timeout=timeout)
    return rc, out, err


def pids_out(adb, serial):
    rc, out, _ = adb_shell(adb, serial, "pidof %s" % PACKAGE)
    return [p for p in out.split() if p.strip().isdigit()]


def ps_out(adb, serial):
    rc, out, _ = adb_shell(adb, serial, "ps -A 2>/dev/null | grep -F %s" % PACKAGE)
    return out.strip()


def host_now():
    now = datetime.now(timezone.utc)
    iso = now.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (now.microsecond // 1000)
    return iso, int(now.timestamp() * 1000)


def device_facts(adb, serial):
    def prop(p):
        _, out, _ = adb_shell(adb, serial, "getprop %s" % p)
        return out.strip()

    model = prop("ro.product.model")
    release = prop("ro.build.version.release")
    try:
        api = int(prop("ro.build.version.sdk"))
    except ValueError:
        raise Fail("cannot parse ro.build.version.sdk=%r" % release)
    _, user_out, _ = adb_shell(adb, serial, "am get-current-user")
    try:
        user = int(user_out.strip().splitlines()[0])
    except Exception:
        raise Fail("cannot resolve current user from `am get-current-user`=%r" % user_out)
    build_id = prop("ro.build.version.incremental")
    return {
        "deviceModel": model,
        "androidRelease": release,
        "androidApi": api,
        "androidUser": user,
        "buildId": build_id,
    }


def check_online(adb, serial):
    rc, out, _ = run(adb_base(adb, serial) + ["get-state"])
    if "device" not in out:
        raise Fail("device %s not online (state=%r)" % (serial, out.strip()))


def verify_packages(adb, serial):
    _, out, _ = adb_shell(adb, serial, "pm list packages")
    pkgs = out
    if ("package:" + PACKAGE) not in pkgs:
        raise Fail("app package %s not installed" % PACKAGE)
    if ("package:" + TEST_PKG) not in pkgs:
        raise Fail("test package %s not installed" % TEST_PKG)


def install(adb, serial, apk):
    if not os.path.isfile(apk):
        raise Fail("apk not found: %s" % apk)
    rc, out, err = run(adb_base(adb, serial) + ["install", "-r", "-t", apk], timeout=600)
    if rc != 0 or "Success" not in out:
        raise Fail("install failed rc=%d out=%r err=%r" % (rc, out, err))


def process_cold_block(adb, serial, prev_rc):
    """Record the process-cold proof and force-stop the app BEFORE a fresh invocation."""
    block = {
        "prevInstrumentationExitStatus": prev_rc,
    }
    pre_pids = pids_out(adb, serial)
    pre_ps = ps_out(adb, serial)
    block["preForceStopProcessQuery"] = "pidof %s -> %r; ps -A|grep -F %s -> %r" % (
        PACKAGE, " ".join(pre_pids) if pre_pids else "(none)", PACKAGE, pre_ps if pre_ps else "(none)")
    block["preForceStopPids"] = pre_pids

    force_cmd = "am force-stop %s" % PACKAGE
    block["forceStopCommand"] = force_cmd
    rc, out, _ = adb_shell(adb, serial, force_cmd)
    block["forceStopExitStatus"] = rc
    if rc != 0:
        raise Fail("force-stop failed rc=%d out=%r" % (rc, out))

    # Brief settle, then prove absence with TWO independent queries.
    import time
    time.sleep(0.5)
    post_pids = pids_out(adb, serial)
    post_ps = ps_out(adb, serial)
    block["postForceStopProcessQuery"] = "pidof %s -> %r; ps -A|grep -F %s -> %r" % (
        PACKAGE, " ".join(post_pids) if post_pids else "(none)", PACKAGE, post_ps if post_ps else "(none)")
    block["postForceStopPids"] = post_pids
    absent = (len(post_pids) == 0) and (post_ps == "")
    block["processAbsentAfterForceStop"] = absent
    if not absent:
        raise Fail("RUN INVALID: target process still present after force-stop: pids=%r ps=%r" % (post_pids, post_ps))
    return block


def run_instrumentation(adb, serial, method):
    inner = "am instrument -w -e class %s#%s %s/%s" % (TEST_CLASS, method, TEST_PKG, RUNNER)
    full = adb_base(adb, serial) + ["shell", inner]
    rc, out, err = run(full, timeout=900)
    combined = out + "\n" + err
    ok_marker = "OK (1 test)" in combined
    fail_marker = "FAILURES" in combined or "Instrumentation run failed" in combined
    passed = (rc == 0) and ok_marker and not fail_marker
    return {
        "instrumentationCommand": "adb -s %s shell %s" % (serial, inner),
        "instrumentationExitStatus": rc,
        "instrumentationPassed": passed,
        "instrumentationTail": combined.strip().splitlines()[-12:],
    }


def result_exists(adb, serial):
    _, out, _ = adb_shell(adb, serial, "run-as %s sh -c 'test -f %s && echo EXISTS || echo ABSENT'" % (PACKAGE, RESULT_ABS))
    return "EXISTS" in out


def reset_result(adb, serial):
    _, out, _ = adb_shell(adb, serial, "run-as %s rm -f %s" % (PACKAGE, RESULT_ABS))


def pull_result(adb, serial):
    _, out, _ = adb_shell(adb, serial, "run-as %s cat %s" % (PACKAGE, RESULT_ABS))
    text = out.strip()
    if not text or not text.startswith("{"):
        return None
    try:
        return json.loads(text)
    except Exception:
        return None


def delete_and_verify_result_absent(adb, serial):
    _, _o, _ = adb_shell(adb, serial, "run-as %s rm -f %s" % (PACKAGE, RESULT_ABS))
    return not result_exists(adb, serial)


def cleanup_cohort(adb, serial):
    """Remove any on-device I2.1 cohort: every manifest-referenced URI, the external root,
    the manifest, and the per-run result file. Touches only this test's owned data."""
    _, mout, _ = adb_shell(adb, serial, "run-as %s cat %s" % (PACKAGE, MANIFEST_ABS))
    uris = []
    text = mout.strip()
    if text:
        try:
            manifest = json.loads(text)
            uris = [j.get("uri") for j in manifest.get("jobs", []) if j.get("uri")]
        except Exception:
            uris = []
    for u in uris:
        adb_shell(adb, serial, "content delete --uri %s" % u)
    adb_shell(adb, serial, "run-as %s rm -rf %s" % (PACKAGE, EXTERNAL_ROOT_ABS))
    adb_shell(adb, serial, "run-as %s rm -f %s %s" % (PACKAGE, MANIFEST_ABS, RESULT_ABS))
    root_ls = adb_shell(adb, serial, "run-as %s ls %s 2>&1" % (PACKAGE, EXTERNAL_ROOT_ABS))[1]
    manifest_ls = adb_shell(adb, serial, "run-as %s ls %s 2>&1" % (PACKAGE, MANIFEST_ABS))[1]
    print("[cleanup] deleted %d manifest URIs" % len(uris))
    print("[cleanup] external root absent=%s (ls=%r)" % ("No such file" in root_ls, root_ls.strip()))
    print("[cleanup] manifest absent=%s (ls=%r)" % ("No such file" in manifest_ls, manifest_ls.strip()))


def median(xs):
    xs = list(xs)
    return statistics.median(xs)


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return 0.0


def build_performance(runs):
    off = [fnum(r["appResult"]["totalMs"]) for r in runs if r["mode"] == "OFF"]
    on = [fnum(r["appResult"]["totalMs"]) for r in runs if r["mode"] == "ON"]
    if len(off) != 3 or len(on) != 3:
        raise Fail("expected 3 OFF and 3 ON runs for medians (got %d/%d)" % (len(off), len(on)))
    m_off = median(off)
    m_on = median(on)
    delta = m_on - m_off
    pct = (delta / m_off * 100.0) if m_off else 0.0

    def stage(key_total, key_avg, mode="ON"):
        vals = [r["appResult"].get("timingsMs", {}) for r in runs if r["mode"] == mode]
        totals = [fnum(v.get(key_total, 0.0)) for v in vals]
        avgs = [fnum(v.get(key_avg, 0.0)) for v in vals]
        return {"perRunTotalsMs": [round(t, 3) for t in totals],
                "sumTotalMs": round(sum(totals), 3),
                "avgPerRunTotalMs": round(statistics.mean(totals), 3) if totals else 0.0,
                "avgPerRowMs": round(statistics.mean(avgs), 4) if avgs else 0.0}

    return {
        "offTotalMs": [round(x, 3) for x in off],
        "onTotalMs": [round(x, 3) for x in on],
        "medianOffMs": round(m_off, 3),
        "medianOnMs": round(m_on, 3),
        "absoluteDeltaMs": round(delta, 3),
        "percentageDelta": round(pct, 2),
        "onStageMs": {
            "rowQuery": stage("rowTotalMs", "rowQueryAvgMs"),
            "getVersion": stage("versionTotalMs", "versionAvgMs"),
            "getGeneration": stage("generationTotalMs", "generationAvgMs"),
            "predicate": stage("predicateTotalMs", "predicateAvgMs"),
        },
    }


def validate_counters(rec, mode, app):
    c = app.get("counters", {})
    def g(k):
        return c.get(k)
    recovered = app.get("recovered")
    if mode in ("OFF", "ON"):
        if recovered != 46:
            raise Fail("%s: recovered=%r != 46" % (rec["runId"], recovered))
        if not app.get("zeroWriteVerified"):
            raise Fail("%s: zeroWriteVerified not true" % rec["runId"])
        if mode == "OFF":
            expect = {"cheapInspections": 0, "fastPathHits": 0, "fullVerifierRuns": 46, "fallbacks": 0}
        else:
            expect = {"cheapInspections": 46, "fastPathHits": 46, "fullVerifierRuns": 0, "fallbacks": 0}
        for k, v in expect.items():
            if g(k) != v:
                raise Fail("%s: counter %s=%r != %d (expected exact)" % (rec["runId"], k, g(k), v))
    if mode == "SEED":
        if app.get("recovered") != 46 or app.get("jobsTotal") != 46:
            raise Fail("SEED: not 46/46 recovered")
        if app.get("jpeg") != 23 or app.get("heif") != 23:
            raise Fail("SEED: jpeg/heif not 23/23")
    if mode == "VERIFY":
        if not app.get("zeroWriteVerified"):
            raise Fail("ZERO-WRITE: zeroWriteVerified not true")
        cc = app.get("counters", {})
        offc, onc = cc.get("OFF", {}), cc.get("ON", {})
        if offc.get("fullVerifierRuns") != 46 or offc.get("fallbacks") != 0:
            raise Fail("ZERO-WRITE OFF not exactly 46 full / 0 fallback: %r" % offc)
        if onc.get("fastPathHits") != 46 or onc.get("fullVerifierRuns") != 0 or onc.get("fallbacks") != 0:
            raise Fail("ZERO-WRITE ON not exactly 46 hit / 0 full / 0 fallback: %r" % onc)
    if mode == "CLEANUP":
        if not app.get("cleanupVerified"):
            raise Fail("FINAL-SWEEP: cleanupVerified not true")
        if app.get("urisAbsent") != 46 or app.get("urisTotal") != 46:
            raise Fail("FINAL-SWEEP: urisAbsent/urisTotal != 46/46: %r" % (app.get("urisAbsent"), app.get("urisTotal")))
        if app.get("jobDirsRemoved") != 46:
            raise Fail("FINAL-SWEEP: jobDirsRemoved != 46")
        if app.get("rootAbsent") is not True or app.get("manifestAbsent") is not True:
            raise Fail("FINAL-SWEEP: root/manifest not both absent")
        if app.get("leftoverTestRows") != 0:
            raise Fail("FINAL-SWEEP: leftoverTestRows=%r != 0" % app.get("leftoverTestRows"))


def main():
    _force_utf8_stdio()
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--install", default=None, help="app debug apk to install before running")
    ap.add_argument("--install-test", default=None, help="androidTest apk to install before running")
    ap.add_argument("--skip-install", action="store_true")
    ap.add_argument("--cleanup", action="store_true",
                    help="remove any on-device I2.1 cohort (manifest URIs + external root + manifest + result) and exit")
    ap.add_argument("--stayon", default="true", choices=["true", "false"],
                    help="svc power stayon state to set during the run (restore false at the end)")
    args = ap.parse_args()

    adb = find_adb()
    repo = os.getcwd()
    git_head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True, text=True).stdout.strip()
    if not git_head:
        raise Fail("cannot resolve git HEAD in %s" % repo)

    check_online(adb, args.serial)
    if not args.skip_install:
        if args.install:
            install(adb, args.serial, args.install)
        if args.install_test:
            install(adb, args.serial, args.install_test)
    verify_packages(adb, args.serial)
    if args.cleanup:
        cleanup_cohort(adb, args.serial)
        return
    facts = device_facts(adb, args.serial)

    print("[host] serial=%s model=%s release=%s api=%d user=%d git=%s" % (
        args.serial, facts["deviceModel"], facts["androidRelease"], facts["androidApi"], facts["androidUser"], git_head[:12]))

    # Keep the screen on so MediaStore/export work is not throttled mid-run.
    adb_shell(adb, args.serial, "svc power stayon %s" % args.stayon)

    device_wallclock, _dw_err = None, None
    _rc, _dwo, _dwe = adb_shell(adb, args.serial, "date -u +'%Y-%m-%dT%H:%M:%SZ'")
    device_wallclock = _dwo.strip()

    records = []
    prev_rc = None
    try:
        for method, mode, expected_run_id in RUNS:
            print("[host] === %s (%s) ===" % (expected_run_id, mode))
            cold = process_cold_block(adb, args.serial, prev_rc)

            # Reset any stale per-invocation handoff BEFORE launch (staleness guard).
            reset_result(adb, args.serial)
            if result_exists(adb, args.serial):
                raise Fail("%s: result file still present after reset (run-as permission?)" % expected_run_id)

            inst = run_instrumentation(adb, args.serial, method)
            prev_rc = inst["instrumentationExitStatus"]
            if not inst["instrumentationPassed"]:
                print("[host] instrumentation tail:\n" + "\n".join(inst["instrumentationTail"]))
                raise Fail("%s: instrumentation not a clean pass (rc=%d)" % (expected_run_id, inst["instrumentationExitStatus"]))

            app = pull_result(adb, args.serial)
            if app is None:
                raise Fail("%s: no valid per-invocation result file after instrumentation" % expected_run_id)
            if app.get("runId") != expected_run_id:
                raise Fail("%s: result runId mismatch expected=%r got=%r (stale or wrong run)" % (expected_run_id, expected_run_id, app.get("runId")))

            iso, epoch = host_now()
            rec = {
                "runId": expected_run_id,
                "mode": mode,
                "testMethod": method,
                "gitHead": git_head,
                "adbSerial": args.serial,
                "deviceModel": facts["deviceModel"],
                "androidRelease": facts["androidRelease"],
                "androidApi": facts["androidApi"],
                "androidUser": facts["androidUser"],
                "buildId": facts["buildId"],
                "hostTimestampUtc": iso,
                "hostTimestampEpochMs": epoch,
                "deviceWallClockUtc": device_wallclock,
            }
            rec.update(cold)
            rec.update(inst)
            rec["appResult"] = app
            if mode == "ON" or mode == "OFF":
                rec["zeroWriteVerified"] = bool(app.get("zeroWriteVerified"))
            validate_counters(rec, mode, app)
            records.append(rec)
            print("[host] OK %s totalMs=%s absentAfterForceStop=true" % (
                expected_run_id, app.get("totalMs", app.get("cleanupVerified"))))

        # Host-side authoritative proof that the on-device per-run result file is gone.
        result_file_absent = delete_and_verify_result_absent(adb, args.serial)
        records[-1]["resultFileAbsentAfterPull"] = result_file_absent
        if not result_file_absent:
            raise Fail("FINAL-SWEEP: on-device per-run result file still present after host pull")
    finally:
        # Restore power state and turn the screen off, regardless of outcome.
        adb_shell(adb, args.serial, "svc power stayon false")
        adb_shell(adb, args.serial, "input keyevent KEYCODE_POWER")
        adb_shell(adb, args.serial, "dumpsys power | grep -E 'mWakefulness=Awake' >/dev/null && echo screen-on || echo screen-off")

    perf = build_performance(records)
    off_full = [r["appResult"]["counters"].get("fullVerifierRuns") for r in records if r["mode"] == "OFF"]
    on_hits = [r["appResult"]["counters"].get("fastPathHits") for r in records if r["mode"] == "ON"]
    all_zero_write = all(r.get("zeroWriteVerified", r["appResult"].get("zeroWriteVerified", False)) for r in records if r["mode"] in ("OFF", "ON"))
    classification = "U2.3-I2.1 ACTIVATION READINESS PASS — PAIRED CURRENT-BUILD A/B + HOST PROCESS-COLD PROVEN"

    gen_iso, gen_epoch = host_now()
    doc = {
        "schema": "u23-i21-host-evidence/v1",
        "machineGenerated": True,
        "generator": "tools/u23_i21_ab_host.py",
        "productionGateDefault": "OFF",
        "note": "Every OFF/ON run is independently true process-cold: force-stopped and proven absent (pidof + scoped ps) before each fresh instrumentation invocation. Timestamps are the live HOST clock. The appResult block is the verbatim per-invocation handoff from filesDir/u23i21-last-run.json, correlated by runId.",
        "gitHead": git_head,
        "adbSerial": args.serial,
        "device": facts,
        "generatedAt": {"hostTimestampUtc": gen_iso, "hostTimestampEpochMs": gen_epoch},
        "sequence": ["SEED", "OFF-1", "ON-1", "OFF-2", "ON-2", "OFF-3", "ON-3", "ZERO-WRITE", "FINAL-SWEEP"],
        "runs": records,
        "performance": perf,
        "invariants": {
            "offFullVerifierRuns": off_full,
            "onFastPathHits": on_hits,
            "allPairedZeroWrite": all_zero_write,
            "processAbsentAfterForceStopAll": all(r["processAbsentAfterForceStop"] for r in records),
        },
        "classification": classification,
    }

    out_path = os.path.join(repo, EVIDENCE_REL)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("[host] evidence written to %s" % out_path)
    print("[host] median OFF=%s ms  median ON=%s ms  delta=%s ms (%s%%)" % (
        perf["medianOffMs"], perf["medianOnMs"], perf["absoluteDeltaMs"], perf["percentageDelta"]))
    print("[host] ALL RUNS VALID — " + classification)


if __name__ == "__main__":
    try:
        main()
    except Fail as e:
        sys.stderr.write("\n[FATAL] %s\n" % e)
        sys.exit(1)
