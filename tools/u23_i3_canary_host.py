#!/usr/bin/env python3
"""U2.3-I3 validated-target default-path production canary host orchestrator.

Bounded, deterministic, host-side driver for the I3 pilot
(U23I3ProductionCanaryTest, 6 jobs: 3 JPEG + 3 native HEIF). The test/debug
override is NEVER set; the gate is ON solely via the production rollout policy
on the validated target (Samsung SM-S921N, API 37, platform incremental
S921NKSUHZZHL).

Host provenance hardening (I3 section 2):
  A. pidof/ps are explicit result types (command, exit status, stdout, stderr).
     A query failure is NEVER interpreted as process absence. Absence is proven
     only if BOTH queries execute successfully with no target PID/process.
  B. force-stop uses the explicitly resolved Android user:
       am force-stop --user <resolvedUser> <package>
  C. final screen handling never blindly toggles KEYCODE_POWER. It reads the
     authoritative wakefulness, issues KEYCODE_SLEEP only when awake, and
     asserts the final state is not awake.
  D. this tool aborts invalid runs and does NOT rewrite the final evidence.
     There is no separate rejected-attempt artifact; a rejected run aborts.

Build/APK provenance (I3 section 3):
  clean worktree is required; git HEAD is recorded; both APKs are built from
  that HEAD by this tool; SHA-256 is computed for both; those exact artifacts
  are installed. The evidence binds gitHead + both SHAs + version + build type.

Usage:
  python tools/u23_i3_canary_host.py --serial R3CX40A15GB

Exit code is 0 only when the ENTIRE sequence is valid. Any invalid step aborts
and the committed evidence file is NOT (re)written.
"""

import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone

PACKAGE = "com.projectnuke.keplernightlab"
TEST_PKG = PACKAGE + ".test"
TEST_CLASS = PACKAGE + ".U23I3ProductionCanaryTest"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
RESULT_ABS = "/data/data/%s/files/u23i3-last-run.json" % PACKAGE
MANIFEST_ABS = "/data/data/%s/files/u23i3-manifest.json" % PACKAGE
EXTERNAL_ROOT_ABS = "/storage/emulated/0/Android/data/%s/files/Pictures/U23I3Canary/KeplerYuvFusion" % PACKAGE

EVIDENCE_REL = os.path.join("docs", "evidence", "U2_3_I3_canary_evidence.json")

RUNS_PREFIX = [
    ("i3Seed6", "SEED", "i3Seed6"),
]
STABILIZE = ("i3Stabilize", "STABILIZE", "i3Stabilize")
COLDHIT = ("i3ColdHit", "HIT", "i3ColdHit")
RUNS_SUFFIX = [
    ("i3GenMismatch", "GEN_MISMATCH", "i3GenMismatch"),
    ("i3JpegSigKill", "SIG_KILL", "i3JpegSigKill"),
    ("i3HeifFtypKill", "FTYP_KILL", "i3HeifFtypKill"),
    ("i3ExactDelete", "DELETE", "i3ExactDelete"),
    ("i3FinalSweep", "CLEANUP", "i3FinalSweep"),
]
MAX_COLDHIT_ATTEMPTS = 3


class Fail(RuntimeError):
    pass


def _force_utf8_stdio():
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


def run(cmd, timeout=900):
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def adb_shell(adb, serial, cmd, timeout=900):
    base = adb_base(adb, serial)
    argv = base + ["shell"] + cmd if isinstance(cmd, list) else base + ["shell", cmd]
    rc, out, err = run(argv, timeout=timeout)
    return rc, out, err


class QueryResult:
    """Explicit process-query result. Absence is a proven fact, never an inference
    from a failed command."""

    def __init__(self, command, exit_status, stdout, stderr):
        self.command = command
        self.exit_status = exit_status
        self.stdout = stdout
        self.stderr = stderr

    def to_dict(self):
        return {
            "command": self.command,
            "exitStatus": self.exit_status,
            "stdout": self.stdout,
            "stderr": self.stderr,
        }


def pidof_query(adb, serial):
    cmd = "pidof %s" % PACKAGE
    rc, out, err = adb_shell(adb, serial, cmd)
    return QueryResult(cmd, rc, out.strip(), err.strip())


def ps_query(adb, serial):
    cmd = "ps -A 2>/dev/null | grep -F %s" % PACKAGE
    rc, out, err = adb_shell(adb, serial, cmd)
    return QueryResult(cmd, rc, out.strip(), err.strip())


def pids_of(q):
    return [p for p in q.stdout.split() if p.strip().isdigit()]


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
        raise Fail("cannot parse ro.build.version.sdk")
    _, user_out, _ = adb_shell(adb, serial, "am get-current-user")
    try:
        user = int(user_out.strip().splitlines()[0])
    except Exception:
        raise Fail("cannot resolve current user from `am get-current-user`=%r" % user_out)
    return {
        "deviceModel": model,
        "androidRelease": release,
        "androidApi": api,
        "androidUser": user,
        "manufacturer": prop("ro.product.manufacturer"),
        "buildId": prop("ro.build.version.incremental"),
        "displayId": prop("ro.build.display.id"),
    }


def check_online(adb, serial):
    rc, out, _ = run(adb_base(adb, serial) + ["get-state"])
    if "device" not in out:
        raise Fail("device %s not online (state=%r)" % (serial, out.strip()))


def verify_packages(adb, serial):
    _, out, _ = adb_shell(adb, serial, "pm list packages")
    if ("package:" + PACKAGE) not in out:
        raise Fail("app package %s not installed" % PACKAGE)
    if ("package:" + TEST_PKG) not in out:
        raise Fail("test package %s not installed" % TEST_PKG)


def require_clean_worktree(repo):
    rc, out, _ = run(["git", "-C", repo, "status", "--porcelain"])
    if rc != 0:
        raise Fail("cannot inspect worktree")
    if out.strip():
        raise Fail("worktree is not clean; commit or stash first:\n%s" % out.strip())


def git_head(repo):
    rc, out, _ = run(["git", "-C", repo, "rev-parse", "HEAD"])
    head = out.strip()
    if rc != 0 or not head:
        raise Fail("cannot resolve git HEAD")
    return head


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_apks(repo):
    """Build app/debug + androidTest APKs from the committed HEAD and return
    (app_apk, test_apk) paths. Requires a clean worktree (checked by caller)."""
    gradlew = os.path.join(repo, "gradlew.bat" if os.name == "nt" else "gradlew")
    rc, out, err = run([gradlew, "assembleDebug", "assembleDebugAndroidTest", "--console=plain"],
                       timeout=1800)
    if rc != 0:
        raise Fail("gradle build failed rc=%d\n%s\n%s" % (rc, out[-4000:], err[-4000:]))
    apps = sorted(glob.glob(os.path.join(repo, "app", "build", "outputs", "apk", "debug", "*.apk")))
    tests = sorted(glob.glob(os.path.join(repo, "app", "build", "outputs", "apk", "androidTest", "debug", "*.apk")))
    if not apps or not tests:
        raise Fail("built APKs not found (app=%r test=%r)" % (apps, tests))
    app_apk = max(apps, key=os.path.getmtime)
    test_apk = max(tests, key=os.path.getmtime)
    return app_apk, test_apk


def apk_badging(sdk_dir, apk):
    """Read package/versionCode/versionName from the APK via aapt (best effort)."""
    cands = sorted(glob.glob(os.path.join(sdk_dir, "build-tools", "*", "aapt.exe" if os.name == "nt" else "aapt")))
    if not cands:
        return {}
    rc, out, _ = run([cands[-1], "dump", "badging", apk])
    m = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", out)
    if not m:
        return {}
    return {"package": m.group(1), "versionCode": m.group(2), "versionName": m.group(3)}


def install(adb, serial, apk, test_only=False):
    if not os.path.isfile(apk):
        raise Fail("apk not found: %s" % apk)
    args = ["install", "-r"]
    if test_only:
        args.append("-t")
    args.append(apk)
    rc, out, err = run(adb_base(adb, serial) + args, timeout=900)
    if rc != 0 or "Success" not in out:
        raise Fail("install failed rc=%d out=%r err=%r" % (rc, out, err))


def process_cold_block(adb, serial, user, prev_rc):
    """Proven process-cold. Absence requires BOTH queries to succeed with no target."""
    block = {"prevInstrumentationExitStatus": prev_rc}
    pre_pidof = pidof_query(adb, serial)
    pre_ps = ps_query(adb, serial)
    block["preForceStopPidof"] = pre_pidof.to_dict()
    block["preForceStopPs"] = pre_ps.to_dict()

    force_cmd = "am force-stop --user %d %s" % (user, PACKAGE)
    block["forceStopCommand"] = force_cmd
    rc, out, err = adb_shell(adb, serial, force_cmd)
    block["forceStopExitStatus"] = rc
    block["forceStopStdout"] = out.strip()
    block["forceStopStderr"] = err.strip()
    if rc != 0:
        raise Fail("force-stop failed rc=%d out=%r err=%r" % (rc, out, err))

    import time
    time.sleep(0.5)
    post_pidof = pidof_query(adb, serial)
    post_ps = ps_query(adb, serial)
    block["postForceStopPidof"] = post_pidof.to_dict()
    block["postForceStopPs"] = post_ps.to_dict()

    # Absence is proven ONLY if both queries execute successfully with no target.
    # A failed query is never absence. pidof reports "no process" as exit 1 with
    # empty stdout (exit 0 with PIDs means present); grep reports "no match" as
    # exit 1 with empty stdout. Any PIDs, any stderr, or any other failure is not absence.
    pidof_ok = (len(pids_of(post_pidof)) == 0) and (post_pidof.exit_status in (0, 1)) \
        and (post_pidof.stdout == "") and (post_pidof.stderr == "")
    ps_ok = (post_ps.stdout == "") and (post_ps.stderr == "") and (post_ps.exit_status in (0, 1))
    absent = pidof_ok and ps_ok
    block["processAbsentAfterForceStop"] = absent
    if not absent:
        raise Fail(
            "RUN INVALID: target process not proven absent after force-stop: "
            "pidof=%r ps=%r" % (post_pidof.to_dict(), post_ps.to_dict()))
    return block


def run_instrumentation(adb, serial, method):
    inner = "am instrument -w -e class %s#%s %s/%s" % (TEST_CLASS, method, TEST_PKG, RUNNER)
    rc, out, err = run(adb_base(adb, serial) + ["shell", inner], timeout=900)
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
    adb_shell(adb, serial, "run-as %s rm -f %s" % (PACKAGE, RESULT_ABS))


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
    adb_shell(adb, serial, "run-as %s rm -f %s" % (PACKAGE, RESULT_ABS))
    return not result_exists(adb, serial)


def do_invocation(adb, serial, facts, head, app_sha, test_sha, version_code, version_name,
                  device_wallclock, method, mode, expected_run_id, prev_rc):
    """Run ONE process-cold invocation and build its record. Does NOT validate the
    app result and does NOT require instrumentation to have passed, so callers can
    retain a drifted cold-hit failure handoff. Aborts only on host-hygiene violations
    (reset failure, missing handoff, runId mismatch). Returns (rec, app, inst, prev_rc).
    app is None only if the test wrote nothing at all (unexpected crash)."""
    print("[host] === %s (%s) ===" % (expected_run_id, mode))
    cold = process_cold_block(adb, serial, facts["androidUser"], prev_rc)
    reset_result(adb, serial)
    if result_exists(adb, serial):
        raise Fail("%s: result file still present after reset" % expected_run_id)
    inst = run_instrumentation(adb, serial, method)
    new_prev_rc = inst["instrumentationExitStatus"]
    app = pull_result(adb, serial)
    iso, epoch = host_now()
    rec = {
        "runId": expected_run_id,
        "mode": mode,
        "testMethod": method,
        "gitHead": head,
        "appApkSha256": app_sha,
        "testApkSha256": test_sha,
        "appVersionCode": version_code,
        "appVersionName": version_name,
        "buildType": "debug",
        "adbSerial": serial,
        "deviceModel": facts["deviceModel"],
        "androidRelease": facts["androidRelease"],
        "androidApi": facts["androidApi"],
        "androidUser": facts["androidUser"],
        "manufacturer": facts["manufacturer"],
        "buildId": facts["buildId"],
        "displayId": facts["displayId"],
        "hostTimestampUtc": iso,
        "hostTimestampEpochMs": epoch,
        "deviceWallClockUtc": device_wallclock,
    }
    rec.update(cold)
    rec.update(inst)
    rec["appResult"] = app
    if app is None:
        return rec, None, inst, new_prev_rc
    if app.get("runId") != expected_run_id:
        raise Fail("%s: result runId mismatch expected=%r got=%r (stale or wrong run)" % (
            expected_run_id, expected_run_id, app.get("runId")))
    return rec, app, inst, new_prev_rc


def run_must_pass(adb, serial, facts, head, app_sha, test_sha, version_code, version_name,
                  device_wallclock, method, mode, expected_run_id, prev_rc):
    """Run one invocation that must pass cleanly (seed, stabilize, C/D/E/F/sweep).
    Aborts on any instrumentation failure, missing handoff, or validation failure.
    Returns (rec, new_prev_rc)."""
    rec, app, inst, new_prev_rc = do_invocation(
        adb, serial, facts, head, app_sha, test_sha, version_code, version_name,
        device_wallclock, method, mode, expected_run_id, prev_rc)
    if not inst["instrumentationPassed"]:
        print("[host] instrumentation tail:\n" + "\n".join(inst["instrumentationTail"]))
        raise Fail("%s: instrumentation not a clean pass (rc=%d)" % (
            expected_run_id, inst["instrumentationExitStatus"]))
    if app is None:
        raise Fail("%s: no valid per-invocation result file after instrumentation" % expected_run_id)
    validate_i3(rec, mode, app)
    print("[host] OK %s absentAfterForceStop=true" % expected_run_id)
    return rec, new_prev_rc


def wakefulness(adb, serial):
    _, out, _ = adb_shell(adb, serial, "dumpsys power 2>/dev/null | grep -E 'mWakefulness='")
    m = re.search(r"mWakefulness=(\w+)", out)
    return m.group(1) if m else "UNKNOWN"


def screen_off(adb, serial):
    """Deterministically turn the screen off. Never blind-toggles power. Sends
    KEYCODE_SLEEP only when awake, then polls the authoritative wakefulness until
    it leaves Awake (the transition can take many seconds)."""
    import time
    before = wakefulness(adb, serial)
    after = before
    for attempt in range(2):
        if after != "Awake":
            break
        adb_shell(adb, serial, "input keyevent KEYCODE_SLEEP")
        for _ in range(15):
            time.sleep(2)
            after = wakefulness(adb, serial)
            if after != "Awake":
                break
    return {"wakefulnessBefore": before, "wakefulnessAfter": after, "screenOff": after != "Awake"}


def cleanup_cohort(adb, serial):
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
    print("[cleanup] deleted %d manifest URIs; removed external root, manifest, result" % len(uris))


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return 0.0


def validate_i3(rec, mode, app):
    if mode == "SEED":
        if app.get("recovered") != 6 or app.get("jobsTotal") != 6:
            raise Fail("SEED: not 6/6 recovered")
        if app.get("jpeg") != 3 or app.get("heif") != 3:
            raise Fail("SEED: jpeg/heif not 3/3")
        first = app.get("firstPassCounters", {})
        for k, v in {"cheapInspections": 6, "fastPathHits": 0, "fullVerifierRuns": 6}.items():
            if first.get(k) != v:
                raise Fail("SEED first pass: %s=%r != %d" % (k, first.get(k), v))
        if app.get("policyEnabled") is not True:
            raise Fail("SEED: policyEnabled not true (override must not be responsible)")
        if app.get("testOverride") != "UNSET":
            raise Fail("SEED: testOverride=%r != UNSET" % app.get("testOverride"))
    elif mode == "HIT":
        # Accepted cold hit only: the test must report passed with exactly one recovery.
        # Failed (drifted) attempts carry passed=false and are retained separately, never accepted.
        if app.get("passed") is not True:
            raise Fail("HIT: passed is not true (drifted attempt, not acceptable as the cold hit)")
        if app.get("recoveriesExecuted") != 1:
            raise Fail("HIT: recoveriesExecuted=%r != 1" % app.get("recoveriesExecuted"))
        if app.get("recovered") != 6:
            raise Fail("HIT: recovered=%r != 6" % app.get("recovered"))
        c = app.get("counters", {})
        for k, v in {"cheapInspections": 6, "fastPathHits": 6, "fullVerifierRuns": 0, "fallbacks": 0}.items():
            if c.get(k) != v:
                raise Fail("HIT: counter %s=%r != %d" % (k, c.get(k), v))
        if not app.get("zeroWriteVerified"):
            raise Fail("HIT: zeroWriteVerified not true")
    elif mode == "STABILIZE":
        if app.get("recovered") != 6 or app.get("jobsTotal") != 6:
            raise Fail("STABILIZE: not 6/6 recovered")
    elif mode == "GEN_MISMATCH":
        if app.get("recovered") != 6:
            raise Fail("GEN_MISMATCH: not 6 recovered")
        c = app.get("counters", {})
        if c.get("fullVerifierRuns") != 6:
            raise Fail("GEN_MISMATCH: fullVerifierRuns=%r != 6" % c.get("fullVerifierRuns"))
        if c.get("fallback:VOLUME_GENERATION_MISMATCH") != 6:
            raise Fail("GEN_MISMATCH: VOLUME_GENERATION_MISMATCH=%r != 6" % c.get("fallback:VOLUME_GENERATION_MISMATCH"))
    elif mode in ("SIG_KILL", "FTYP_KILL"):
        if app.get("targetDiagnostic") != "SIGNATURE_INVALID":
            raise Fail("%s: targetDiagnostic=%r != SIGNATURE_INVALID" % (mode, app.get("targetDiagnostic")))
    elif mode == "DELETE":
        if "PUBLIC_RESULT_REMOVED" not in (app.get("targetActions") or ""):
            raise Fail("DELETE: PUBLIC_RESULT_REMOVED not in targetActions=%r" % app.get("targetActions"))
    elif mode == "CLEANUP":
        if not app.get("cleanupVerified"):
            raise Fail("CLEANUP: cleanupVerified not true")
        if app.get("urisAbsent") != 6 or app.get("urisTotal") != 6:
            raise Fail("CLEANUP: urisAbsent/urisTotal != 6/6")
        if app.get("jobDirsRemoved") != 6:
            raise Fail("CLEANUP: jobDirsRemoved != 6")
        if app.get("rootAbsent") is not True or app.get("manifestAbsent") is not True:
            raise Fail("CLEANUP: root/manifest not both absent")
        if app.get("leftoverTestRows") != 0:
            raise Fail("CLEANUP: leftoverTestRows != 0")
    if app.get("policyEnabled") is not True:
        raise Fail("%s: policyEnabled not true" % rec["runId"])
    if app.get("testOverride") != "UNSET":
        raise Fail("%s: testOverride=%r != UNSET" % (rec["runId"], app.get("testOverride")))


def main():
    _force_utf8_stdio()
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--cleanup", action="store_true",
                    help="remove any on-device I3 cohort and exit")
    ap.add_argument("--stayon", default="true", choices=["true", "false"])
    args = ap.parse_args()

    adb = find_adb()
    repo = os.getcwd()
    sdk_dir = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")

    check_online(adb, args.serial)
    if args.cleanup:
        # Cleanup must not require pre-existing packages; if the app is gone there is
        # nothing app-private left to clean.
        try:
            verify_packages(adb, args.serial)
        except Fail:
            print("[cleanup] packages not installed; nothing to clean")
            return
        cleanup_cohort(adb, args.serial)
        return
    facts = device_facts(adb, args.serial)

    # Build/APK provenance: clean worktree, HEAD, build both APKs, SHA-256, install exact.
    require_clean_worktree(repo)
    head = git_head(repo)
    print("[host] serial=%s model=%s release=%s api=%d user=%d git=%s" % (
        args.serial, facts["deviceModel"], facts["androidRelease"], facts["androidApi"],
        facts["androidUser"], head[:12]))
    app_apk, test_apk = build_apks(repo)
    app_sha = sha256_file(app_apk)
    test_sha = sha256_file(test_apk)
    badging = apk_badging(sdk_dir, app_apk)
    version_code = badging.get("versionCode", "")
    version_name = badging.get("versionName", "")
    print("[host] appApk=%s sha256=%s" % (os.path.basename(app_apk), app_sha[:16]))
    print("[host] testApk=%s sha256=%s" % (os.path.basename(test_apk), test_sha[:16]))
    install(adb, args.serial, app_apk, test_only=False)
    install(adb, args.serial, test_apk, test_only=True)
    # Package verification AFTER installation for normal execution (never before it).
    verify_packages(adb, args.serial)

    adb_shell(adb, args.serial, "svc power stayon %s" % args.stayon)
    _, dwo, _ = adb_shell(adb, args.serial, "date -u +'%Y-%m-%dT%H:%M:%SZ'")
    device_wallclock = dwo.strip()

    records = []
    attempts = []
    prev_rc = None
    screen = {"wakefulnessBefore": "UNKNOWN", "wakefulnessAfter": "UNKNOWN", "screenOff": False}
    try:
        # Prefix: SEED (fresh cohort; not drift-sensitive, must pass).
        for method, mode, expected_run_id in RUNS_PREFIX:
            rec, prev_rc = run_must_pass(
                adb, args.serial, facts, head, app_sha, test_sha, version_code, version_name,
                device_wallclock, method, mode, expected_run_id, prev_rc)
            records.append(rec)

        # Bounded cold-hit retry: STABILIZE-N -> force-stop/absent -> COLD-HIT-N (max 3).
        # A drifted cold hit writes a failure handoff (passed=false); it is retained in
        # attempts[] (machine-generated, never overwritten) and retried. PASS requires one
        # exact stabilize -> absent -> fresh cold-hit -> first-recovery 6/6 hit.
        accepted_hit = None
        for attempt in range(1, MAX_COLDHIT_ATTEMPTS + 1):
            print("[host] --- cold-hit attempt %d/%d: stabilize ---" % (attempt, MAX_COLDHIT_ATTEMPTS))
            s_rec, prev_rc = run_must_pass(
                adb, args.serial, facts, head, app_sha, test_sha, version_code, version_name,
                device_wallclock, *STABILIZE, prev_rc)
            records.append(s_rec)
            print("[host] --- cold-hit attempt %d/%d: cold hit ---" % (attempt, MAX_COLDHIT_ATTEMPTS))
            h_rec, h_app, h_inst, prev_rc = do_invocation(
                adb, args.serial, facts, head, app_sha, test_sha, version_code, version_name,
                device_wallclock, *COLDHIT, prev_rc)
            if h_app is None:
                print("[host] instrumentation tail:\n" + "\n".join(h_inst["instrumentationTail"]))
                raise Fail("cold-hit attempt %d: no handoff at all (unexpected crash, not drift)" % attempt)
            if h_inst["instrumentationPassed"] and h_app.get("passed") is True \
                    and h_app.get("recoveriesExecuted") == 1:
                validate_i3(h_rec, "HIT", h_app)
                h_rec["attempt"] = attempt
                records.append(h_rec)
                accepted_hit = h_rec
                print("[host] OK i3ColdHit (attempt %d) absentAfterForceStop=true" % attempt)
                break
            attempts.append({
                "attempt": attempt,
                "coldHitInstrumentationExitStatus": h_inst["instrumentationExitStatus"],
                "coldHitPassed": h_app.get("passed"),
                "recoveriesExecuted": h_app.get("recoveriesExecuted"),
                "failureReason": h_app.get("failureReason"),
                "counters": h_app.get("counters"),
                "totalMs": h_app.get("totalMs"),
            })
            print("[host] cold-hit attempt %d rejected by drift; retained, retrying" % attempt)
        if accepted_hit is None:
            raise Fail("REOPEN: all %d cold-hit attempts hit volume drift; no true-cold first-recovery hit" % MAX_COLDHIT_ATTEMPTS)

        # Suffix: C/D/E/F/Sweep (they create their own drift and are robust; must pass).
        for method, mode, expected_run_id in RUNS_SUFFIX:
            rec, prev_rc = run_must_pass(
                adb, args.serial, facts, head, app_sha, test_sha, version_code, version_name,
                device_wallclock, method, mode, expected_run_id, prev_rc)
            records.append(rec)

        result_file_absent = delete_and_verify_result_absent(adb, args.serial)
        records[-1]["resultFileAbsentAfterPull"] = result_file_absent
        if not result_file_absent:
            raise Fail(" swept result file still present after host pull")
    finally:
        adb_shell(adb, args.serial, "svc power stayon false")
        screen = screen_off(adb, args.serial)
        print("[host] screen: %s -> %s (off=%s)" % (
            screen["wakefulnessBefore"], screen["wakefulnessAfter"], screen["screenOff"]))

    if not screen["screenOff"]:
        raise Fail("final screen state is not OFF (wakefulness=%r)" % screen["wakefulnessAfter"])

    hit = next(r for r in records if r["mode"] == "HIT")
    seed = next(r for r in records if r["mode"] == "SEED")
    classification = "U2.3-I3 PRODUCTION CANARY PASS - VALIDATED TARGET DEFAULT-PATH ACTIVATION PROVEN"

    gen_iso, gen_epoch = host_now()
    doc = {
        "schema": "u23-i3-canary-evidence/v1",
        "machineGenerated": True,
        "generator": "tools/u23_i3_canary_host.py",
        "productionGateDefault": "OFF",
        "canaryScope": {
            "manufacturer": "samsung",
            "model": "SM-S921N",
            "api": 37,
            "platformIncremental": "S921NKSUHZZHL",
        },
        "note": "Validated-target default-path pilot. The test override is never set; every run is gate-ON solely via U23RolloutPolicy. Each invocation is independently true process-cold (proven absent via explicit pidof/ps result types before each fresh instrumentation). The cold hit is the FIRST and ONLY recovery in its fresh process (recoveriesExecuted=1); stabilization is a separate exited process. Timestamps are the live HOST clock. appResult is the verbatim per-invocation handoff, correlated by runId. Drifted cold-hit attempts are retained in attempts[] (machine-generated, never overwritten); this tool aborts invalid runs and does not rewrite the final evidence.",
        "gitHead": head,
        "appApkSha256": app_sha,
        "testApkSha256": test_sha,
        "appApk": os.path.basename(app_apk),
        "testApk": os.path.basename(test_apk),
        "appVersionCode": version_code,
        "appVersionName": version_name,
        "buildType": "debug",
        "adbSerial": args.serial,
        "device": facts,
        "generatedAt": {"hostTimestampUtc": gen_iso, "hostTimestampEpochMs": gen_epoch},
        "sequence": ["SEED", "STABILIZE", "HIT", "GEN_MISMATCH", "SIG_KILL", "FTYP_KILL", "DELETE", "CLEANUP"],
        "runs": records,
        "attempts": attempts,
        "pilot": {
            "seedTotalMs": seed["appResult"].get("totalMs"),
            "hitAttempt": hit.get("attempt"),
            "hitTotalMs": hit["appResult"].get("totalMs"),
            "hitRecoveriesExecuted": hit["appResult"].get("recoveriesExecuted"),
            "hitCounters": hit["appResult"].get("counters"),
            "hitTimingsMs": hit["appResult"].get("timingsMs"),
            "hitZeroWriteVerified": hit["appResult"].get("zeroWriteVerified"),
        },
        "screen": screen,
        "classification": classification,
    }

    out_path = os.path.join(repo, EVIDENCE_REL)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("[host] evidence written to %s" % out_path)
    print("[host] HIT totalMs=%s counters=%s" % (
        hit["appResult"].get("totalMs"), hit["appResult"].get("counters")))
    print("[host] ALL RUNS VALID - " + classification)


if __name__ == "__main__":
    try:
        main()
    except Fail as e:
        sys.stderr.write("\n[FATAL] %s\n" % e)
        sys.exit(1)
