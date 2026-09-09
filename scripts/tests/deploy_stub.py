#!/usr/bin/env python3
"""Deployment test commands. All server paths are confined to a temporary root."""

import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

root = Path(os.environ["DEPLOY_TEST_ROOT"])
config_path = root / "config.json"
config = json.loads(config_path.read_text())
command = Path(sys.argv[0]).name
args = sys.argv[1:]
with (root / "trace.jsonl").open("a") as trace:
    trace.write(json.dumps([command, *args]) + "\n")


def server_path(value):
    if value.startswith("/opt/"):
        return root / "server" / value.lstrip("/")
    path = Path(value)
    if not path.resolve().is_relative_to(root.resolve()):
        raise RuntimeError(f"Test attempted to access outside its temporary root: {value}")
    return path


def save_config():
    config_path.write_text(json.dumps(config))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def docker(arguments):
    if "build" in arguments:
        if config.get("fail_build"):
            return 1
        config["built_revision"] = next(a.split("=", 1)[1] for a in arguments if a.startswith("SHOP_BUILD_GIT_SHA="))
        config["built_time"] = next(a.split("=", 1)[1] for a in arguments if a.startswith("SHOP_BUILD_TIME="))
        save_config()
    elif arguments[:2] == ["image", "inspect"]:
        print("sha256:" + ("new" if config.get("built_revision") else "old"))
    elif arguments[:2] == ["volume", "inspect"]:
        return 1
    elif "compose" in arguments:
        if "--force-recreate" in arguments:
            config["live_revision"] = config["built_revision"]
            config["live_time"] = config["built_time"]
            config["restarted"] = True
            save_config()
        if "exec" in arguments:
            print(config.get("bootstrap_needed", 0))
    return 0


def sudo(arguments):
    if arguments == ["-n", "true"]:
        return 0
    name, *values = arguments
    if name == "docker":
        return docker(values)
    if name == "test":
        path = server_path(values[1])
        result = {"-f": path.is_file, "-d": path.is_dir, "-e": path.exists, "-L": path.is_symlink}[values[0]]()
        return 0 if result else 1
    if name == "cat":
        sys.stdout.write(server_path(values[0]).read_text())
    elif name == "sha256sum":
        print(digest(server_path(values[0]).read_bytes()), values[0])
    elif name == "install":
        directory = "-d" in values
        mode = int(values[values.index("-m") + 1], 8)
        paths = []
        index = 0
        while index < len(values):
            if values[index] in ("-o", "-g", "-m"):
                index += 2
            elif values[index] == "-d":
                index += 1
            else:
                paths.append(server_path(values[index]))
                index += 1
        if directory:
            for path in paths:
                path.mkdir(parents=True, exist_ok=True)
                path.chmod(mode)
        else:
            shutil.copyfile(*paths)
            paths[-1].chmod(mode)
    elif name == "rm":
        for value in values:
            if value.startswith("-"):
                continue
            path = server_path(value)
            if path.is_symlink() or path.is_file():
                path.unlink()
            elif path.exists():
                shutil.rmtree(path)
    elif name == "mktemp":
        template = server_path(values[0])
        descriptor, path = tempfile.mkstemp(prefix=template.name.replace("XXXXXX", ""), dir=template.parent)
        os.close(descriptor)
        print(path)
    elif name == "tee":
        server_path(values[0]).write_bytes(sys.stdin.buffer.read())
    elif name in ("chown", "chmod"):
        pass
    elif name == "mv":
        paths = [server_path(v) for v in values if not v.startswith("-")]
        os.replace(*paths)
    elif name == "cp":
        shutil.copytree(server_path(values[-2]), server_path(values[-1]), dirs_exist_ok=True)
    elif name == "ln":
        server_path(values[-1]).symlink_to(values[-2])
    elif name == "readlink":
        path = server_path(values[0])
        if not path.is_symlink():
            return 1
        print(os.readlink(path))
    elif name == "find":
        if "rm" in values:
            retained = values[values.index("-name") + 1]
            for path in server_path(values[0]).iterdir():
                if path.is_dir() and not path.is_symlink() and path.name != retained:
                    shutil.rmtree(path)
    else:
        raise RuntimeError(f"Unsupported sudo operation: {arguments}")
    return 0


def curl(arguments):
    url = arguments[-1]
    if "/actuator/health" in url:
        if config.get("restarted") and config.get("fail_backend_health"):
            return 22
        print('{"status":"UP"}', end="")
    elif "/actuator/info" in url:
        print(json.dumps({"gitSha": config["live_revision"], "buildTime": config["live_time"]}, separators=(",", ":")), end="")
    elif "/realtime?" in url:
        print("401", end="")
    elif "/admin/auth/registration" in url:
        print('{"code":200,"msg":"success","data":{"enabled":false}}', end="")
    elif config.get("fail_spa") and "__shop_deploy_spa_probe__" in url:
        print("wrong page", end="")
    else:
        sys.stdout.buffer.write(server_path("/opt/1panel/www/sites/admin.junxiangshiping.cn/index/index.html").read_bytes())
    return 0


def ssh(arguments):
    script = arguments[-1]
    if "snapshot" in script:
        sys.stdin.read()
        if config.get("fail_snapshot"):
            return 255
        sys.stdout.write(config["snapshot"] + "\n")
    elif "finalize-backend" in script:
        sys.stdin.read()
    elif "stage_dir=$(mktemp" in script:
        if config.get("fail_upload_lock"):
            return 75
        data = sys.stdin.buffer.read()
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            (root / "upload.json").write_text(json.dumps(archive.getnames()))
        # Exercise the exact SSH shell text generated by deploy.sh without executing it.
        checked = subprocess.run(["bash", "-n"], input=script, text=True, capture_output=True)
        if checked.returncode:
            sys.stderr.write(checked.stderr)
            return checked.returncode
        (root / "remote-command.sh").write_text(script)
    elif "SELECT COUNT(*)" in script:
        print(config.get("bootstrap_needed", 0))
    elif "command -v docker" in script:
        pass
    elif "sudo test -f" in script:
        return 0 if config.get("runtime_exists", True) else 1
    elif "sudo sha256sum" in script:
        print(config.get("runtime_sha", digest(b"runtime\n")))
    elif "label=com.docker.compose.project=shop" in script:
        return 0 if config.get("residual_state") else 1
    else:
        raise RuntimeError(f"Unexpected SSH operation: {script}")
    return 0


if command == "sudo":
    result = sudo(args)
elif command == "curl":
    result = curl(args)
elif command == "ssh":
    result = ssh(args)
elif command == "pnpm":
    if "build" in args:
        repo = root / "repo"
        (repo / "admin/dist").mkdir(parents=True, exist_ok=True)
        (repo / "admin/dist/index.html").write_text("new admin")
        if config.get("change_head_during_build"):
            subprocess.run(["git", "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-qm", "concurrent change"], cwd=repo, check=True)
    result = 0
elif command in ("openssl", "htpasswd", "docker"):
    result = 0
else:
    raise RuntimeError(f"Unexpected command: {command}")
sys.exit(result)
