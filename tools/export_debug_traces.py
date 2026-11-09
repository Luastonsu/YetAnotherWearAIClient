"""Export local YAWAC debug records from a debug APK; never print conversation bodies."""
import argparse
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="ADB executable path")
    parser.add_argument("--serial", help="Connected device serial")
    parser.add_argument("--output", type=Path, default=Path("build/debug-export"))
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def run(*command):
        result = subprocess.run(adb + list(command), capture_output=True, check=False)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip()
                               or result.stdout.decode("utf-8", errors="replace").strip())
        return result.stdout

    try:
        listing = run("exec-out", "run-as", "com.yawac.client", "ls", "no_backup/debug-traces")
        names = [name for name in listing.decode("utf-8").splitlines()
                 if re.fullmatch(r"[a-f0-9-]{36}\.json", name)]
        args.output.mkdir(parents=True, exist_ok=True)
        for name in names:
            data = run("exec-out", "run-as", "com.yawac.client", "cat", "no_backup/debug-traces/" + name)
            document = json.loads(data.decode("utf-8"))
            if document.get("id") != name[:-5]:
                raise RuntimeError("Trace identity mismatch")
            (args.output / name).write_bytes(data)
        print(f"Exported {len(names)} trace(s) to {args.output.resolve()}")
    except (OSError, ValueError, RuntimeError) as error:
        parser.exit(1, f"Export failed: {error}\nEnable debug recording and send a new message first. A debuggable APK and an authorized ADB connection are required.\n")


if __name__ == "__main__":
    main()
