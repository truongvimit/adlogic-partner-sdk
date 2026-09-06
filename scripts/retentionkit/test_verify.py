"""Fixture tests for evidence validation; never invoke Gradle, git, or adb."""
import argparse
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

SPEC = importlib.util.spec_from_file_location("retention_verify", Path(__file__).with_name("verify.py"))
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)

MANIFEST = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.retentionkit.review"><application/></manifest>'
POM = b'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>com.github.truongvimit</groupId><artifactId>retention-review</artifactId>
<version>1.0-test</version><packaging>aar</packaging><dependencies>
<dependency><groupId>com.github.truongvimit</groupId><artifactId>retention-core</artifactId><version>1.0-test</version><scope>runtime</scope></dependency>
</dependencies></project>'''
GRAPH = '''> Task :sample:dependencies
releaseRuntimeClasspath - Runtime classpath of /release.
+--- project :retention-review
|    +--- project :retention-core
|    |    \\--- androidx.core:core:1.15.0
|    \\--- com.google.android.play:review:2.0.2
\\--- org.jetbrains.kotlin:kotlin-stdlib:2.0.0

A web-based, searchable dependency report is available by adding --scan.
BUILD SUCCESSFUL in 1s
'''


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, data):
        path = self.path / name
        path.write_bytes(data.encode() if isinstance(data, str) else data)
        return path

    def aar(self, manifest=MANIFEST, classes=None):
        jar_bytes = io.BytesIO()
        with zipfile.ZipFile(jar_bytes, "w") as jar:
            for name in classes or ["io/retentionkit/review/ReviewController.class"]:
                jar.writestr(name, b'\xca\xfe\xba\xbe')
        path = self.path / "release.aar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", manifest)
            archive.writestr("classes.jar", jar_bytes.getvalue())
        return path

    def args(self):
        return argparse.Namespace(profile="review", dependencies=self.write("graph.txt", GRAPH),
                                  pom=self.write("pom.xml", POM), aar=self.aar(),
                                  manifest=self.write("merged.xml", MANIFEST),
                                  configuration="releaseRuntimeClasspath", version="1.0-test",
                                  require=["project :retention-core"])

    def test_nested_dependency_leak_is_detected_not_just_first_line(self):
        graph = GRAPH.replace("androidx.core:core:1.15.0", "com.google.firebase:firebase-config:23.0.0")
        parsed = verify.parse_dependencies(graph, "releaseRuntimeClasspath")
        self.assertEqual(len(parsed["dependencies"]), 5)
        self.assertTrue(any("Firebase" in e for e in verify.forbidden_dependencies(parsed["dependencies"], "review")))

    def test_configuration_isolated_from_unrelated_debug_graph(self):
        text = "debugRuntimeClasspath - Runtime\n\\--- project :ads\n\n" + GRAPH
        args = self.args()
        args.dependencies.write_text(text)
        self.assertTrue(verify.composition(args)["ok"])

    def test_unresolved_node_fails_even_with_successful_gradle_task(self):
        parsed = verify.parse_dependencies(GRAPH.replace("androidx.core:core:1.15.0", "androidx.core:core:1.15.0 FAILED"), "releaseRuntimeClasspath")
        self.assertTrue(any("Unresolved" in e for e in parsed["errors"]))

    def test_missing_duplicate_or_empty_config_cannot_pass(self):
        for graph in ("BUILD SUCCESSFUL", GRAPH + GRAPH, "releaseRuntimeClasspath - Runtime\n\nBUILD SUCCESSFUL"):
            with self.subTest(graph=graph), self.assertRaises(ValueError):
                verify.parse_dependencies(graph, "releaseRuntimeClasspath")

    def test_missing_success_marker_fails(self):
        self.assertTrue(verify.parse_dependencies(GRAPH.replace("BUILD SUCCESSFUL", "BUILD FAILED"), "releaseRuntimeClasspath")["errors"])

    def test_composition_requires_consistent_artifact_and_no_leaks(self):
        args = self.args()
        self.assertTrue(verify.composition(args)["ok"])
        args.version = "wrong"
        self.assertFalse(verify.composition(args)["ok"])
        args.version = "1.0-test"
        args.pom.write_bytes(POM.replace(b"retention-core", b"retention-notifications"))
        self.assertFalse(verify.composition(args)["ok"])

    def test_review_manifest_rejects_permissions_even_without_class_leak(self):
        args = self.args()
        args.manifest.write_bytes(MANIFEST.replace(b"<application/>", b'<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/><application/>'))
        self.assertFalse(verify.composition(args)["ok"])

    def test_review_aar_detects_bundled_vendor_and_unrelated_module_classes(self):
        path = self.aar(classes=["com/google/firebase/FirebaseApp.class", "io/retentionkit/notifications/Receiver.class"])
        errors = verify.inspect_aar(path, "review")["errors"]
        self.assertTrue(any("Firebase" in error for error in errors))
        self.assertTrue(any("Unrelated retention" in error for error in errors))

    def test_zip_without_classes_or_malformed_manifest_fails(self):
        path = self.path / "empty.aar"
        with zipfile.ZipFile(path, "w") as aar:
            aar.writestr("AndroidManifest.xml", MANIFEST)
        with self.assertRaises(ValueError):
            verify.inspect_aar(path, "review")
        with self.assertRaises(verify.ET.ParseError):
            verify.parse_manifest(b"binary\x00xml")

    def test_publication_metadata_must_match_pom(self):
        args = self.args()
        args.artifact_id = "retention-review"
        args.metadata = self.write("release.module", json.dumps({"component": {"group": "other", "module": "retention-review", "version": "1.0-test"}, "variants": [{"name": "releaseRuntimeElements"}]}))
        self.assertFalse(verify.publication(args)["ok"])

    def test_testcase_counts_failures_not_just_suite_success_label(self):
        path = self.write("TEST-fail.xml", '<testsuite tests="1" failures="0"><testcase name="async"><failure message="bad"/></testcase></testsuite>')
        result = verify.inspect_tests([path])
        self.assertFalse(result["ok"])
        self.assertEqual(result["totals"]["failures"], 1)
        self.assertTrue(any("disagrees" in error for error in result["errors"]))

    def test_aggregate_suite_not_double_counted_and_skips_are_explicit(self):
        path = self.write("TEST-nested.xml", '<testsuites tests="2"><testsuite tests="2" skipped="1"><testcase name="pass"/><testcase name="skip"><skipped/></testcase></testsuite></testsuites>')
        result = verify.inspect_tests([path])
        self.assertEqual(result["totals"]["tests"], 2)
        self.assertFalse(result["ok"])
        self.assertTrue(verify.inspect_tests([path], allow_skipped=True)["ok"])

    def test_all_skipped_empty_and_summary_only_do_not_pass(self):
        for xml in ('<testsuite tests="0"/>', '<testsuite tests="99"/>', '<testsuite tests="1"><testcase><skipped/></testcase></testsuite>'):
            with self.subTest(xml=xml):
                self.assertFalse(verify.inspect_tests([self.write("TEST-empty.xml", xml)], True)["ok"])
        self.assertFalse(verify.inspect_tests([])["ok"])

    def test_dtd_entities_rejected(self):
        with self.assertRaises(ValueError):
            verify.read_xml(b'<!DOCTYPE x [<!ENTITY a "expanded">]><testsuite/>')

    def test_existing_output_never_erased(self):
        existing = self.path / "evidence"
        existing.mkdir()
        marker = existing / "keep"
        marker.write_text("existing evidence")
        with self.assertRaises(FileExistsError):
            verify.make_directory(existing)
        self.assertEqual(marker.read_text(), "existing evidence")

    def test_bad_adb_package_rejected_before_any_command(self):
        args = argparse.Namespace(package="com.example;echo bad", device="pixel", repo=self.path, output=self.path / "output")
        with mock.patch.object(verify.subprocess, "Popen") as popen:
            with self.assertRaises(ValueError):
                verify.adb_evidence(args)
            popen.assert_not_called()

    def test_bad_gradle_task_rejected_before_any_command(self):
        self.write("gradlew", "fixture")
        args = argparse.Namespace(repo=self.path, task=[":app:test; echo bad"], configuration=None, output=self.path / "output")
        with mock.patch.object(verify.subprocess, "Popen") as popen:
            with self.assertRaises(ValueError):
                verify.run_gradle(args)
            popen.assert_not_called()


if __name__ == "__main__":
    unittest.main()
