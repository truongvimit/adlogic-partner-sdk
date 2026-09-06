#!/usr/bin/env python3
"""RetentionKit evidence checks. Python standard library; never uses a shell."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import signal
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
PROFILES = ("core", "notifications", "widgets", "feedback", "review")
VENDOR_RULES = {
    "Firebase": r"com[./]google[./]firebase|suite[-/]firebase|io[./]suite[./]firebase",
    "Compose": r"androidx[./]compose|org[./]jetbrains[./]compose",
    "Google ads": r"com[./]google[./]android[./]gms[:/]?(?:play-services-ads|ads/)|com[./]google[./]ads",
    "AdLogic ads": r"(?:project\s+:|[:/])ads(?=[:/\s]|$)|com[./]ads[./]module",
    "Other ad/MMP SDKs": r"com[./](?:applovin|ironsource|unity3d[./]ads|mbridge|bytedance[./]sdk|pangle|adjust)|com[./]facebook[./]ads|audience-network-sdk",
    "Unrelated suite UI/billing": r"(?:project\s+:|[:/])(?:onboardkitorigin|paykit|billingkit)(?=[:/\s]|$)|io[./](?:onboardkit|paykit)|com[./]android[./]billingclient",
}
ALWAYS_FORBIDDEN_PERMISSIONS = {
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.USE_EXACT_ALARM",
    "android.permission.USE_FULL_SCREEN_INTENT",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
}
NO_NOTIFICATION_PERMISSIONS = {
    "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.FOREGROUND_SERVICE",
}
MAX_MEMBER_BYTES = 64 * 1024 * 1024


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def read_xml(data):
    # These are build outputs, not general documents. Reject DTD/entity expansion.
    if re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", data, re.I):
        raise ValueError("DTD/entity declarations are not allowed in evidence XML")
    return ET.fromstring(data)


def fingerprint(path):
    path = Path(path).resolve(strict=True)
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def forbidden_dependencies(values, profile):
    errors = []
    for value in sorted(set(values)):
        for label, pattern in VENDOR_RULES.items():
            if re.search(pattern, value, re.I):
                errors.append(f"{label}: {value}")
        for other in PROFILES:
            if other not in ("core", profile) and re.search(
                rf"retention(?:kit)?[-/]{other}(?=[:/\s]|$)", value, re.I
            ):
                errors.append(f"Unrelated retention module: {value}")
        if re.search(r"(?:project\s+:|:)retentionkit(?=[:\s]|$)", value):
            errors.append(f"Umbrella dependency in selective profile: {value}")
    if profile in ("core", "review", "feedback"):
        errors.extend(f"Unexpected background scheduler: {value}" for value in values
                      if re.search(r"androidx[./]work", value))
    return errors


def parse_dependencies(text, configuration):
    """Parse only the named Gradle dependencies configuration, not the whole log."""
    text = ANSI.sub("", text)
    lines = text.splitlines()
    headers = [i for i, line in enumerate(lines)
               if re.match(rf"^{re.escape(configuration)}(?:\s+-.*)?\s*$", line)]
    if len(headers) != 1:
        raise ValueError(f"Expected exactly one {configuration} section; found {len(headers)}")
    start = headers[0]
    section = []
    for line in lines[start + 1:]:
        if line and not line[0].isspace() and not line.startswith(("+---", "\\---", "|", "No dependencies")):
            break
        if not line.strip():
            if section:
                break
            continue
        section.append(line)
    if not section:
        raise ValueError("Dependency configuration has no evidence lines")
    errors = []
    if "(n)" in lines[start]:
        errors.append("Configuration is not resolvable")
    values = []
    for line in section:
        match = re.search(r"(?:\+---|\\---)\s+(.+)$", line)
        if not match:
            if line.strip() != "No dependencies":
                errors.append(f"Unrecognized dependency line: {line.strip()}")
            continue
        value = match.group(1).strip()
        if re.search(r"\bFAILED\b|\(n\)", value):
            errors.append(f"Unresolved dependency: {value}")
        values.append(value)
    # A successful dependencies task can still contain FAILED dependency nodes.
    if "BUILD SUCCESSFUL" not in text or "BUILD FAILED" in text:
        errors.append("Log does not show a successful Gradle invocation")
    return {"configuration": configuration, "dependencies": values, "errors": errors}


def parse_pom(data):
    root = read_xml(data)
    if local_name(root.tag) != "project":
        raise ValueError("Expected Maven project XML")

    def child_text(node, name):
        return next((c.text.strip() for c in node if local_name(c.tag) == name and c.text), "")

    coordinates = {key: child_text(root, key) for key in ("groupId", "artifactId", "version", "packaging")}
    errors = []
    for key in ("groupId", "artifactId", "version"):
        if not coordinates[key] or "${" in coordinates[key] or coordinates[key] == "unspecified":
            errors.append(f"Missing/unresolved POM {key}")
    if coordinates["packaging"] != "aar":
        errors.append("POM packaging must be aar")
    dependencies = []
    for container in root:
        if local_name(container.tag) == "dependencies":
            for dep in container:
                if local_name(dep.tag) != "dependency":
                    continue
                group, artifact, version = (child_text(dep, key) for key in ("groupId", "artifactId", "version"))
                scope = child_text(dep, "scope") or "compile"
                if not group or not artifact or "${" in f"{group}:{artifact}:{version}":
                    errors.append("Missing/unresolved POM dependency coordinate")
                dependencies.append({"coordinate": f"{group}:{artifact}:{version}", "scope": scope,
                                     "optional": child_text(dep, "optional") == "true"})
    return {"coordinates": coordinates, "dependencies": dependencies, "errors": errors}


def parse_manifest(data):
    root = read_xml(data)
    if local_name(root.tag) != "manifest":
        raise ValueError("Expected decoded/merged Android manifest XML, not binary AXML")
    permissions = sorted({node.get(ANDROID + "name", "") for node in root
                          if local_name(node.tag).startswith("uses-permission")})
    components = []
    for node in root.iter():
        if local_name(node.tag) in ("activity", "activity-alias", "receiver", "service", "provider"):
            components.append({"kind": local_name(node.tag), "name": node.get(ANDROID + "name", ""),
                               "exported": node.get(ANDROID + "exported")})
    return {"package": root.get("package"), "permissions": permissions, "components": components}


def manifest_errors(manifest, profile):
    denied = set(ALWAYS_FORBIDDEN_PERMISSIONS)
    if profile != "notifications":
        denied.update(NO_NOTIFICATION_PERMISSIONS)
    errors = [f"Forbidden permission: {p}" for p in manifest["permissions"] if p in denied]
    for component in manifest["components"]:
        name = component["name"].replace(".", "/")
        errors.extend(forbidden_dependencies([name], profile))
        if component["kind"] == "receiver" and component["exported"] == "true" and re.search(r"debug|test", name, re.I):
            errors.append(f"Exported test/debug receiver: {component['name']}")
    return errors


def read_member(archive, name):
    info = archive.getinfo(name)
    if info.file_size > MAX_MEMBER_BYTES:
        raise ValueError(f"Archive member too large for this checker: {name}")
    return archive.read(name)


def inspect_aar(path, profile=None):
    with zipfile.ZipFile(path) as aar:
        manifest = parse_manifest(read_member(aar, "AndroidManifest.xml"))
        class_names = []
        jars = [name for name in aar.namelist() if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar"))]
        if "classes.jar" not in jars:
            raise ValueError("AAR has no classes.jar")
        for name in jars:
            with zipfile.ZipFile(io.BytesIO(read_member(aar, name))) as jar:
                class_names.extend(entry for entry in jar.namelist() if entry.endswith(".class"))
        if not class_names:
            raise ValueError("AAR contains no class entries")
        errors = (manifest_errors(manifest, profile) + forbidden_dependencies(class_names, profile)) if profile else []
        return {"manifest": manifest, "class_count": len(class_names), "jars": jars,
                "errors": errors, "scope": "Own/bundled classes only; transitive libraries require resolved graph"}


def inspect_tests(paths, allow_skipped=False):
    totals = {"tests": 0, "passed": 0, "failures": 0, "errors": 0, "skipped": 0}
    reports, errors = [], []
    for path in paths:
        root = read_xml(Path(path).read_bytes())
        if local_name(root.tag) not in ("testsuite", "testsuites"):
            raise ValueError(f"Not JUnit XML: {path}")
        cases = [n for n in root.iter() if local_name(n.tag) == "testcase"]
        if not cases:
            errors.append(f"No testcase evidence: {path}")
        for suite in root.iter():
            if local_name(suite.tag) != "testsuite":
                continue
            direct = [n for n in suite if local_name(n.tag) == "testcase"]
            if any(local_name(n.tag) == "testsuite" for n in suite):
                continue  # Container totals would double count children.
            if "tests" in suite.attrib and int(suite.attrib["tests"]) != len(direct):
                errors.append(f"Suite count disagrees with testcase evidence: {path}")
            for attr, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                observed = sum(any(local_name(c.tag) == tag for c in case) for case in direct)
                if attr in suite.attrib and int(suite.attrib[attr]) != observed:
                    errors.append(f"Suite {attr} count disagrees with testcase evidence: {path}")
        failures = []
        for case in cases:
            children = {local_name(c.tag) for c in case}
            kind = "errors" if "error" in children else "failures" if "failure" in children else "skipped" if "skipped" in children else "passed"
            totals["tests"] += 1
            totals[kind] += 1
            if kind in ("errors", "failures"):
                failures.append({"class": case.get("classname"), "name": case.get("name"), "kind": kind})
        reports.append({**fingerprint(path), "cases": len(cases), "failed_cases": failures})
    if totals["failures"] or totals["errors"]:
        errors.append("Test failures/errors exist")
    if not totals["passed"]:
        errors.append("No executed passing testcase evidence")
    if totals["skipped"] and not allow_skipped:
        errors.append("Skipped tests exist (use --allow-skipped only when documented separately)")
    return {"ok": not errors, "totals": totals, "reports": reports, "errors": errors,
            "skipped_are_not_passed": True}


def publication(args):
    pom = parse_pom(args.pom.read_bytes())
    aar = inspect_aar(args.aar)
    errors = pom["errors"] + aar["errors"]
    if pom["coordinates"]["artifactId"] != args.artifact_id:
        errors.append(f"Expected POM artifactId {args.artifact_id}")
    if pom["coordinates"]["version"] != args.version:
        errors.append(f"Expected POM version {args.version}")
    result = {"ok": not errors, "errors": errors, "pom": pom, "aar": aar,
              "evidence": [fingerprint(args.pom), fingerprint(args.aar)],
              "limitations": ["Inspects the supplied local files; does not prove remote publication or consumer API compatibility."]}
    if args.metadata:
        metadata = json.loads(args.metadata.read_text())
        if not isinstance(metadata, dict) or not isinstance(metadata.get("component"), dict):
            raise ValueError("Expected Gradle module metadata object with component coordinates")
        component = metadata["component"]
        expected = {"group": pom["coordinates"]["groupId"], "module": args.artifact_id, "version": args.version}
        if any(component.get(k) != v for k, v in expected.items()):
            errors.append("Gradle module metadata coordinates disagree with POM")
        if not metadata.get("variants"):
            errors.append("Gradle module metadata contains no variants")
        result["metadata_component"] = component
        result["evidence"].append(fingerprint(args.metadata))
    result["ok"] = not errors
    return result


def composition(args):
    if not args.configuration.endswith("RuntimeClasspath"):
        raise ValueError("Composition needs a resolved RuntimeClasspath, not a compile-only graph")
    dependencies = parse_dependencies(args.dependencies.read_text(), args.configuration)
    pom = parse_pom(args.pom.read_bytes())
    aar = inspect_aar(args.aar, args.profile)
    merged = parse_manifest(args.manifest.read_bytes())
    expected_artifact = f"retention-{args.profile}"
    errors = dependencies["errors"] + pom["errors"] + aar["errors"] + manifest_errors(merged, args.profile)
    if pom["coordinates"]["artifactId"] != expected_artifact:
        errors.append(f"Expected POM artifactId {expected_artifact}")
    if args.version and pom["coordinates"]["version"] != args.version:
        errors.append(f"Expected POM version {args.version}")
    graph = dependencies["dependencies"]
    errors.extend(forbidden_dependencies(graph, args.profile))
    errors.extend(forbidden_dependencies([d["coordinate"] for d in pom["dependencies"]], args.profile))
    for coordinate in args.require:
        if not coordinate or not any(re.search(re.escape(coordinate) + r"(?=[:\s]|$)", item) for item in graph):
            errors.append(f"Required dependency not found: {coordinate}")
    if not graph:
        errors.append("Empty graph cannot establish selective composition")
    return {"ok": not errors, "profile": args.profile, "errors": sorted(set(errors)),
            "evidence": [fingerprint(p) for p in (args.dependencies, args.pom, args.aar, args.manifest)],
            "dependencies": dependencies, "pom": pom, "aar": aar, "merged_manifest": merged,
            "limitations": ["Caller must supply the matching release runtime graph and consumer merged manifest.",
                            "A POM alone does not prove absence of transitive dependencies.",
                            "These checks do not prove a remote Maven/JitPack publication, API usability, or device behavior.",
                            "Deny rules cover named packages/coordinates, not arbitrary obfuscated/shaded code."]}


def make_directory(path):
    # Never erase/overwrite earlier evidence. A unique output directory is required.
    path = path.resolve()
    path.mkdir(parents=True, exist_ok=False)
    return path


def run_command(argv, repo, directory, name, timeout=300):
    started = utc_now()
    stdout_path, stderr_path = directory / f"{name}.stdout", directory / f"{name}.stderr"
    timed_out = False
    with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
        process = subprocess.Popen(argv, cwd=repo, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                                   start_new_session=(os.name == "posix"))
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.terminate()
            try:
                code = process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                if os.name == "posix":
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
                code = process.wait()
    return {"argv": argv, "cwd": str(repo), "started_at": started, "finished_at": utc_now(),
            "exit_code": code, "timed_out": timed_out, "stdout": fingerprint(stdout_path), "stderr": fingerprint(stderr_path)}


def repo_context(repo):
    def git(*args):
        result = subprocess.run(["git", *args], cwd=repo, stdin=subprocess.DEVNULL,
                                capture_output=True, text=True, timeout=20, check=False)
        return result.stdout.strip() if result.returncode == 0 else None
    return {"repo": str(repo), "commit": git("rev-parse", "HEAD"),
            "working_tree_status": git("status", "--porcelain")}


def run_gradle(args):
    repo = args.repo.resolve(strict=True)
    wrapper = repo / "gradlew"
    if not wrapper.is_file():
        raise ValueError(f"Missing Gradle wrapper: {wrapper}")
    for task in args.task:
        if not re.fullmatch(r":[A-Za-z0-9_:-]+", task) or task.endswith(":"):
            raise ValueError(f"Expected fully qualified Gradle task, got: {task}")
    if args.configuration and (len(args.task) != 1 or not args.task[0].endswith(":dependencies")):
        raise ValueError("--configuration requires one explicit :module:dependencies task")
    directory = make_directory(args.output)
    argv = [str(wrapper), "--no-daemon", "--console=plain", "--max-workers=2", "--stacktrace", *args.task]
    if args.configuration:
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", args.configuration):
            raise ValueError("Invalid configuration name")
        argv.extend(["--configuration", args.configuration])
    result = run_command(argv, repo, directory, "gradle", args.timeout)
    payload = {"ok": result["exit_code"] == 0 and not result["timed_out"], "kind": "gradle-run",
               **repo_context(repo), "command": result, "recorded_at": utc_now()}
    (directory / "run.json").write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def adb_evidence(args):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        raise ValueError("Invalid Android package name")
    if not args.device or args.device.startswith("-") or re.search(r"[\s\x00]", args.device):
        raise ValueError("Supply one explicit device serial (not an adb option)")
    repo = args.repo.resolve(strict=True)
    directory = make_directory(args.output)
    prefix = [args.adb, "-s", args.device]
    commands = [
        ("state", ["get-state"]),
        ("api", ["shell", "getprop", "ro.build.version.sdk"]),
        ("model", ["shell", "getprop", "ro.product.model"]),
        ("fingerprint", ["shell", "getprop", "ro.build.fingerprint"]),
        ("package", ["shell", "dumpsys", "package", args.package]),
        ("notification-appop", ["shell", "cmd", "appops", "get", args.package, "POST_NOTIFICATION"]),
    ]
    results = []
    for name, command in commands:
        record = run_command(prefix + command, repo, directory, name, 30)
        results.append(record)
        if name == "state" and (record["exit_code"] != 0 or (directory / "state.stdout").read_text().strip() != "device"):
            break
    if args.screenshot and len(results) == len(commands) and results[0]["exit_code"] == 0:
        screen = run_command(prefix + ["exec-out", "screencap", "-p"], repo, directory, "screen", 30)
        (directory / "screen.stdout").rename(directory / "screen.png")
        screen["stdout"] = fingerprint(directory / "screen.png")
        results.append(screen)
    ok = len(results) >= len(commands) and all(r["exit_code"] == 0 and not r["timed_out"] for r in results)
    package_file = directory / "package.stdout"
    package_present = package_file.exists() and f"Package [{args.package}]" in package_file.read_text(errors="replace")
    capture_errors = [] if package_present else ["Target package was not confirmed installed by dumpsys package"]
    if args.screenshot and (directory / "screen.png").exists():
        with (directory / "screen.png").open("rb") as screenshot:
            if screenshot.read(8) != b"\x89PNG\r\n\x1a\n":
                capture_errors.append("Screenshot output is not PNG")
    payload = {"ok": ok and not capture_errors, "errors": capture_errors,
               "package_present": package_present, "kind": "adb-read-only-capture", **repo_context(repo), "device": args.device,
               "package": args.package, "recorded_at": utc_now(), "commands": results,
               "behavioral_result": "not_evaluated", "notes": args.note,
               "limitations": ["Command success is not a notification/pin/review/retention test pass.",
                               "AppOps alone does not establish channel state or notification delivery.",
                               "No force-stop, clearing, permission mutation, install, launch, or global logcat was performed."]}
    (directory / "capture.json").write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    tests = sub.add_parser("tests", help="Inspect existing JUnit XML; never invokes Gradle")
    tests.add_argument("paths", type=Path, nargs="+")
    tests.add_argument("--allow-skipped", action="store_true")
    tests.add_argument("--output", type=Path, help="New JSON file; refuses overwrite")
    pub = sub.add_parser("publication", help="Inspect existing local AAR/POM and optional Gradle module metadata")
    pub.add_argument("--pom", type=Path, required=True)
    pub.add_argument("--aar", type=Path, required=True)
    pub.add_argument("--artifact-id", required=True)
    pub.add_argument("--version", required=True)
    pub.add_argument("--metadata", type=Path)
    pub.add_argument("--output", type=Path, help="New JSON file; refuses overwrite")
    comp = sub.add_parser("composition", help="Check selective AAR/POM/runtime graph/merged manifest")
    comp.add_argument("--profile", choices=PROFILES, required=True)
    for option in ("dependencies", "pom", "aar", "manifest"):
        comp.add_argument(f"--{option}", type=Path, required=True)
    comp.add_argument("--configuration", default="releaseRuntimeClasspath")
    comp.add_argument("--version")
    comp.add_argument("--require", action="append", default=[], help="Literal dependency identity required in graph; repeatable")
    comp.add_argument("--output", type=Path, help="New JSON file; refuses overwrite")
    gradle = sub.add_parser("gradle", help="Explicitly execute real Gradle tasks and capture exit/logs")
    gradle.add_argument("--repo", type=Path, required=True)
    gradle.add_argument("--task", action="append", required=True)
    gradle.add_argument("--configuration")
    gradle.add_argument("--timeout", type=int, default=1800)
    gradle.add_argument("--output", type=Path, required=True, help="New evidence directory")
    adb = sub.add_parser("adb-evidence", help="Opt-in read-only ADB capture; does not evaluate test outcomes")
    adb.add_argument("--repo", type=Path, required=True)
    adb.add_argument("--device", required=True)
    adb.add_argument("--package", required=True)
    adb.add_argument("--adb", default="adb")
    adb.add_argument("--output", type=Path, required=True, help="New evidence directory")
    adb.add_argument("--screenshot", action="store_true", help="Explicitly capture the visible screen; inspect before sharing")
    adb.add_argument("--note", action="append", default=[], help="Operator scenario notes; never interpreted as pass/fail")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "tests":
            paths = set()
            for path in args.paths:
                if path.is_dir():
                    paths.update(path.rglob("TEST-*.xml"))
                elif path.is_file():
                    paths.add(path)
                else:
                    raise ValueError(f"Test evidence path does not exist: {path}")
            payload = inspect_tests(sorted({p.resolve() for p in paths}), args.allow_skipped)
        elif args.command == "publication":
            payload = publication(args)
        elif args.command == "composition":
            payload = composition(args)
        elif args.command == "gradle":
            if args.timeout <= 0:
                raise ValueError("Timeout must be positive")
            payload = run_gradle(args)
        else:
            payload = adb_evidence(args)
        payload.setdefault("recorded_at", utc_now())
        if args.command in ("tests", "composition", "publication") and args.output:
            with args.output.open("x") as stream:
                json.dump(payload, stream, indent=2)
                stream.write("\n")
        print(json.dumps(payload, indent=2))
        return 0 if payload["ok"] else 1
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile, KeyError, subprocess.SubprocessError) as exc:
        print(json.dumps({"ok": False, "errors": [str(exc)], "recorded_at": utc_now()}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
