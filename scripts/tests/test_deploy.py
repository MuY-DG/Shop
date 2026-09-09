"""Run real Bash deployment branches against local command doubles, never a server."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[2]
OLD_REVISION = "1" * 40
NEW_REVISION = "2" * 40
OLD_TIME = "2026-09-01T00:00:00Z"
NEW_TIME = "2026-09-09T00:00:00Z"
OLD_FP = "a" * 64
NEW_ADMIN_FP = "b" * 64
NEW_BACKEND_FP = "c" * 64


def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()


def record(fingerprint, revision=OLD_REVISION, timestamp=OLD_TIME, artifact=None):
    return f"v1|{fingerprint}|{revision}|{timestamp}|{artifact or sha('old')}"


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shop-deploy-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        for relative in ("deploy.sh", "scripts/deploy/common.sh", "scripts/deploy/remote.sh"):
            destination = self.repo / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(PROJECT / relative, destination)
        fixtures = {
            ".gitignore": "dist/\nbackend/shop-server/config/runtime/\n.env.production.local\n",
            ".nvmrc": "22\n",
            "admin/src/index.ts": "old admin source\n",
            "admin/pnpm-lock.yaml": "lockfile\n",
            "backend/shop-server/Dockerfile": "FROM fixture\n",
            "backend/shop-server/.dockerignore": "config/runtime\n",
            "backend/shop-server/pom.xml": "pom\n",
            "backend/shop-server/src/main/resources/application.yaml": "app\n",
            "backend/shop-server/compose.prod.yaml": "compose\n",
            "docs/guide.md": "guide\n",
            "miniprogram/app.ts": "mini program\n",
        }
        for relative, content in fixtures.items():
            self.write(relative, content)
        for name in ("init-runtime-env.sh", "validate-runtime-env.sh", "bootstrap-admin.sh"):
            self.write(f"backend/shop-server/scripts/config/{name}", "#!/usr/bin/env bash\nexit 0\n").chmod(0o755)
        self.runtime = self.write("backend/shop-server/config/runtime/shop.env", "runtime\n")
        self.git("init", "-q")
        self.git("config", "user.name", "Deployment Test")
        self.git("config", "user.email", "deployment-test@example.invalid")
        self.commit()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        stub = self.bin / "stub.py"
        shutil.copyfile(PROJECT / "scripts/tests/deploy_stub.py", stub)
        stub.chmod(0o755)
        for command in ("sudo", "curl", "ssh", "pnpm", "openssl", "htpasswd", "docker"):
            (self.bin / command).symlink_to(stub)
        self.env = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}", "DEPLOY_TEST_ROOT": str(self.root)}
        # A developer's Vite overrides must not affect the tests unless explicitly requested.
        self.env = {k: v for k, v in self.env.items() if not k.startswith("VITE_")}
        self.config = {
            "snapshot": "admin=missing\nbackend=missing",
            "live_revision": OLD_REVISION[:12],
            "live_time": OLD_TIME,
        }
        self.save()

    def write(self, relative, content):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, text=True, capture_output=True, check=True).stdout.strip()

    def commit(self):
        self.git("add", "-A")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "fixture change")

    def save(self):
        (self.root / "config.json").write_text(json.dumps(self.config))

    def run_bash(self, script, *args):
        return subprocess.run(["bash", str(script), *args], cwd=self.repo, env=self.env, text=True, capture_output=True)

    def common(self, expression, *args):
        code = 'set -Eeuo pipefail; repository_dir="$PWD"; source scripts/deploy/common.sh; ' + expression
        return subprocess.run(["bash", "-c", code, "test", *args], cwd=self.repo, env=self.env, text=True, capture_output=True)

    def fingerprints(self):
        result = self.common("component_fingerprint admin; component_fingerprint backend")
        self.assert_success(result)
        return result.stdout.splitlines()

    def trace(self):
        path = self.root / "trace.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def main(self, *args):
        self.save()
        return self.run_bash(self.repo / "deploy.sh", *args)

    def uploaded(self):
        return json.loads((self.root / "upload.json").read_text())

    def server(self, path, content=None):
        destination = self.root / "server" / path.lstrip("/")
        if content is not None:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content)
        return destination

    def prepare_remote(self):
        self.stage = self.root / "shop-deploy.stage123"
        shutil.copytree(self.repo, self.stage, ignore=shutil.ignore_patterns(".git"))
        (self.stage / "admin/dist").mkdir(parents=True, exist_ok=True)
        (self.stage / "admin/dist/index.html").write_text("new admin")
        self.server("/opt/shop/shop-server/config/runtime/runtime.env", "runtime\n")
        self.server("/opt/shop/shop-server/compose.prod.yaml", "compose\n")
        self.server("/opt/1panel/www/sites/admin.junxiangshiping.cn/index/index.html", "old")
        self.server("/opt/1panel/www/sites/api.junxiangshiping.cn").mkdir(parents=True)
        self.old_admin_record = record(OLD_FP)
        self.old_backend_record = record(OLD_FP, artifact=sha("compose\n"))
        self.server("/opt/shop/.deploy-state/admin.state", self.old_admin_record + "\n")
        self.server("/opt/shop/.deploy-state/backend.state", self.old_backend_record + "\n")
        self.snapshot = f"admin={self.old_admin_record}\nbackend={self.old_backend_record}"

    def remote(self, scope, snapshot=None, admin_fp=NEW_ADMIN_FP, backend_fp=NEW_BACKEND_FP):
        self.save()
        return self.run_bash(self.stage / "scripts/deploy/remote.sh", str(self.stage), "shop", scope,
                             NEW_REVISION, NEW_TIME, admin_fp, backend_fp,
                             sha(snapshot if snapshot is not None else self.snapshot), sha("new admin"))

    def test_shell_syntax_and_help_do_not_contact_server(self):
        for relative in ("deploy.sh", "scripts/deploy/common.sh", "scripts/deploy/remote.sh"):
            result = subprocess.run(["bash", "-n", str(self.repo / relative)], capture_output=True, text=True)
            self.assert_success(result)
        self.assert_success(self.main("--help"))
        for args in ((), ("other",), ("shop", "invalid"), ("shop", "all", "oops")):
            self.assertEqual(self.main(*args).returncode, 2)
        self.assertEqual(self.trace(), [])

    def test_auto_selection_uses_separate_component_records(self):
        cases = [
            (record(NEW_ADMIN_FP), record(NEW_BACKEND_FP), "false false"),
            (record(OLD_FP), record(NEW_BACKEND_FP), "true false"),
            (record(NEW_ADMIN_FP), record(OLD_FP), "false true"),
            ("missing", "missing", "true true"),
            (record(NEW_ADMIN_FP), "missing", "false true"),
        ]
        for admin, backend, expected in cases:
            with self.subTest(expected=expected):
                result = self.common('parse_snapshot "$1"; select_components auto "$2" "$3"; printf "%s %s" "$deploy_admin" "$deploy_backend"',
                                     f"admin={admin}\nbackend={backend}", NEW_ADMIN_FP, NEW_BACKEND_FP)
                self.assert_success(result)
                self.assertEqual(result.stdout, expected)

    def test_snapshot_rejects_noise_and_shell_content(self):
        for snapshot in ("", "admin=missing", "notice\nadmin=missing\nbackend=missing",
                         "admin=$(touch injected)\nbackend=missing"):
            self.assertNotEqual(self.common('parse_snapshot "$1"', snapshot).returncode, 0)
        self.assertFalse((self.repo / "injected").exists())

    def test_fingerprints_ignore_unrelated_commits_and_detect_component_inputs(self):
        initial = self.fingerprints()
        self.write("docs/guide.md", "updated docs")
        self.write("miniprogram/app.ts", "updated mini program")
        self.commit()
        self.assertEqual(self.fingerprints(), initial)
        self.write("admin/pnpm-lock.yaml", "new dependency")
        self.commit()
        admin_changed = self.fingerprints()
        self.assertNotEqual(admin_changed[0], initial[0])
        self.assertEqual(admin_changed[1], initial[1])
        self.write("backend/shop-server/src/main/resources/db/migration/V22.sql", "migration")
        self.commit()
        backend_changed = self.fingerprints()
        self.assertEqual(backend_changed[0], admin_changed[0])
        self.assertNotEqual(backend_changed[1], admin_changed[1])
        self.write("scripts/deploy/common.sh", (self.repo / "scripts/deploy/common.sh").read_text() + "\n# shared change\n")
        self.commit()
        both_changed = self.fingerprints()
        self.assertNotEqual(both_changed[0], backend_changed[0])
        self.assertNotEqual(both_changed[1], backend_changed[1])

    def test_plan_only_needs_no_runtime_and_does_not_build_or_upload(self):
        self.runtime.unlink()
        self.assert_success(self.main("shop", "auto", "--plan"))
        self.assertEqual([row[0] for row in self.trace()], ["ssh"])
        self.assertFalse((self.root / "upload.json").exists())

    def test_admin_only_does_not_require_runtime_or_run_backend_steps(self):
        self.runtime.unlink()
        self.assert_success(self.main("shop", "admin"))
        uploaded = self.uploaded()
        self.assertIn("admin/dist/index.html", uploaded)
        self.assertFalse(any(path.startswith("backend/") for path in uploaded))
        ssh_calls = [row[-1] for row in self.trace() if row[0] == "ssh"]
        self.assertEqual(len(ssh_calls), 2)
        self.assertFalse(any("docker" in call or "finalize-backend" in call for call in ssh_calls))

    def test_backend_only_ignores_admin_environment_and_does_not_build_admin(self):
        self.write("admin/.env.production.local", "VITE_API_URL=fixture")
        self.env["VITE_EXAMPLE"] = "allowed for backend"
        self.assert_success(self.main("shop", "backend"))
        self.assertNotIn("pnpm", [row[0] for row in self.trace()])
        self.assertFalse(any(path.startswith("admin/") for path in self.uploaded()))
        self.assertIn("backend/shop-server/config/runtime/shop.env", self.uploaded())

    def test_default_scope_keeps_full_deployment(self):
        self.assert_success(self.main("shop"))
        self.assertIn("admin/dist/index.html", self.uploaded())
        self.assertIn("backend/shop-server/Dockerfile", self.uploaded())

    def test_auto_skips_build_and_upload_when_both_inputs_match(self):
        admin_fp, backend_fp = self.fingerprints()
        self.config["snapshot"] = f"admin={record(admin_fp)}\nbackend={record(backend_fp)}"
        self.runtime.unlink()
        self.assert_success(self.main("shop", "auto"))
        self.assertEqual([row[0] for row in self.trace()], ["ssh"])

    def test_auto_uploads_only_changed_component(self):
        admin_fp, backend_fp = self.fingerprints()
        self.config["snapshot"] = f"admin={record(OLD_FP)}\nbackend={record(backend_fp)}"
        self.runtime.unlink()
        self.assert_success(self.main("shop", "auto"))
        self.assertFalse(any(path.startswith("backend/") for path in self.uploaded()))

    def test_auto_backend_update_does_not_build_admin(self):
        admin_fp, _ = self.fingerprints()
        self.config["snapshot"] = f"admin={record(admin_fp)}\nbackend={record(OLD_FP)}"
        self.assert_success(self.main("shop", "auto"))
        self.assertFalse(any(path.startswith("admin/") for path in self.uploaded()))
        self.assertNotIn("pnpm", [row[0] for row in self.trace()])

    def test_upload_lock_rejection_stops_bootstrap_and_finalization(self):
        self.config["fail_upload_lock"] = True
        self.assertNotEqual(self.main("shop", "all").returncode, 0)
        ssh_calls = [row[-1] for row in self.trace() if row[0] == "ssh"]
        self.assertFalse(any("SELECT COUNT(*)" in call or "finalize-backend" in call for call in ssh_calls))

    def test_runtime_mismatch_stops_before_build_and_upload(self):
        self.config["runtime_sha"] = "0" * 64
        result = self.main("shop", "backend")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("运行密钥不一致", result.stdout + result.stderr)
        self.assertFalse((self.root / "upload.json").exists())

    def test_pending_bootstrap_blocks_backend(self):
        self.write("backend/shop-server/config/runtime/bootstrap-admin.shop.pending.fixture.txt", "pending")
        result = self.main("shop", "backend")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("结果未确认", result.stdout + result.stderr)

    def test_git_change_during_build_stops_upload(self):
        self.config["change_head_during_build"] = True
        result = self.main("shop", "admin")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Git 版本已变化", result.stdout + result.stderr)
        self.assertFalse((self.root / "upload.json").exists())

    def test_snapshot_ssh_failure_is_not_treated_as_first_deployment(self):
        self.config["fail_snapshot"] = True
        result = self.main("shop", "auto")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "upload.json").exists())

    def test_remote_admin_preserves_backend_version_and_never_calls_docker(self):
        self.prepare_remote()
        self.assert_success(self.remote("admin"))
        self.assertEqual(self.server("/opt/shop/.deploy-state/backend.state").read_text().strip(), self.old_backend_record)
        self.assertEqual(self.server("/opt/shop/.deploy-state/admin.state").read_text().strip(),
                         record(NEW_ADMIN_FP, NEW_REVISION, NEW_TIME, sha("new admin")))
        self.assertFalse(any(row[:2] == ["sudo", "docker"] for row in self.trace()))
        self.assertEqual(self.server("/opt/1panel/www/sites/admin.junxiangshiping.cn/index/index.html").read_text(), "new admin")

    def test_remote_backend_preserves_admin_artifacts_and_record(self):
        self.prepare_remote()
        self.assert_success(self.remote("backend"))
        self.assertEqual(self.server("/opt/shop/.deploy-state/admin.state").read_text().strip(), self.old_admin_record)
        self.assertEqual(self.server("/opt/1panel/www/sites/admin.junxiangshiping.cn/index/index.html").read_text(), "old")
        self.assertEqual(self.server("/opt/shop/.deploy-state/backend.state").read_text().strip(),
                         record(NEW_BACKEND_FP, NEW_REVISION, NEW_TIME, sha("compose\n")))

    def test_remote_all_records_successful_backend_even_when_admin_validation_fails(self):
        self.prepare_remote()
        self.config["fail_spa"] = True
        result = self.remote("all")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.server("/opt/shop/.deploy-state/backend.state").read_text().strip(),
                         record(NEW_BACKEND_FP, NEW_REVISION, NEW_TIME, sha("compose\n")))
        self.assertFalse(self.server("/opt/shop/.deploy-state/admin.state").exists())
        # Failure cleanup must never delete the release currently served by the live symlink.
        self.assertEqual(self.server("/opt/1panel/www/sites/admin.junxiangshiping.cn/index/index.html").read_text(), "new admin")

    def test_failed_backend_build_preserves_previous_success_record(self):
        self.prepare_remote()
        self.config["fail_build"] = True
        self.assertNotEqual(self.remote("backend").returncode, 0)
        self.assertEqual(self.server("/opt/shop/.deploy-state/backend.state").read_text().strip(), self.old_backend_record)

    def test_failed_backend_restart_invalidates_success_record(self):
        self.prepare_remote()
        self.config["fail_backend_health"] = True
        self.assertNotEqual(self.remote("backend").returncode, 0)
        self.assertFalse(self.server("/opt/shop/.deploy-state/backend.state").exists())

    def test_remote_rechecks_runtime_before_build(self):
        self.prepare_remote()
        self.server("/opt/shop/shop-server/config/runtime/runtime.env", "different runtime\n")
        result = self.remote("backend")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(row[:3] == ["sudo", "docker", "build"] for row in self.trace()))

    def test_remote_stale_plan_aborts_before_mutation(self):
        self.prepare_remote()
        result = self.remote("all", snapshot="admin=missing\nbackend=missing")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("构建期间发生变化", result.stderr)
        self.assertFalse(any(row[:2] == ["sudo", "docker"] for row in self.trace()))
        self.assertEqual(self.server("/opt/shop/.deploy-state/admin.state").read_text().strip(), self.old_admin_record)

    def test_remote_auto_no_changes_has_no_mutations(self):
        self.prepare_remote()
        self.assert_success(self.remote("auto", admin_fp=OLD_FP, backend_fp=OLD_FP))
        operations = [row[1] for row in self.trace() if row[0] == "sudo"]
        self.assertTrue(set(operations).issubset({"-n", "test", "cat", "sha256sum"}))

    def test_remote_first_auto_deploy_establishes_both_records(self):
        self.prepare_remote()
        self.server("/opt/shop/.deploy-state/admin.state").unlink()
        self.server("/opt/shop/.deploy-state/backend.state").unlink()
        self.assert_success(self.remote("auto", snapshot="admin=missing\nbackend=missing"))
        self.assertEqual(self.server("/opt/shop/.deploy-state/admin.state").read_text().strip(),
                         record(NEW_ADMIN_FP, NEW_REVISION, NEW_TIME, sha("new admin")))
        self.assertEqual(self.server("/opt/shop/.deploy-state/backend.state").read_text().strip(),
                         record(NEW_BACKEND_FP, NEW_REVISION, NEW_TIME, sha("compose\n")))

    def test_first_backend_bootstrap_does_not_record_success_prematurely(self):
        self.prepare_remote()
        self.config["bootstrap_needed"] = 1
        self.assert_success(self.remote("backend"))
        self.assertFalse(self.server("/opt/shop/.deploy-state/backend.state").exists())

    def test_backend_finalization_waits_for_bootstrap_and_rechecks_actual_version(self):
        self.prepare_remote()
        self.config["bootstrap_needed"] = 1
        self.assert_success(self.remote("backend"))
        expression = 'configure_target shop; finalize_backend_record "$1" "$2" "$3" "$4"'
        arguments = (NEW_BACKEND_FP, NEW_REVISION, NEW_TIME, sha("compose\n"))
        self.assertNotEqual(self.common(expression, *arguments).returncode, 0)
        self.assertFalse(self.server("/opt/shop/.deploy-state/backend.state").exists())
        self.config = json.loads((self.root / "config.json").read_text())
        self.config["bootstrap_needed"] = 0
        self.save()
        self.assert_success(self.common(expression, *arguments))
        expected = record(NEW_BACKEND_FP, NEW_REVISION, NEW_TIME, sha("compose\n"))
        self.assertEqual(self.server("/opt/shop/.deploy-state/backend.state").read_text().strip(), expected)
        self.config["live_revision"] = "3" * 12
        self.save()
        self.assertNotEqual(self.common(expression, *arguments).returncode, 0)
        self.assertEqual(self.server("/opt/shop/.deploy-state/backend.state").read_text().strip(), expected)

    def test_common_script_stdin_entrypoint_validates_target(self):
        with (self.repo / "scripts/deploy/common.sh").open() as source:
            result = subprocess.run(["bash", "-s", "--", "invalid-target", "snapshot"],
                                    stdin=source, env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("部署目标只能", result.stderr)

    def test_live_artifact_mismatch_invalidates_snapshot(self):
        self.prepare_remote()
        self.server("/opt/1panel/www/sites/admin.junxiangshiping.cn/index/index.html", "out of band")
        result = self.common("configure_target shop; deployment_snapshot")
        self.assert_success(result)
        self.assertEqual(result.stdout.strip(), f"admin=missing\nbackend={self.old_backend_record}")


if __name__ == "__main__":
    unittest.main()
