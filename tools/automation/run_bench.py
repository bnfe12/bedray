"""Analyze an existing engine capture. Does not launch or stop processes or modify game logs."""
import argparse
import csv
import json
from pathlib import Path
import sys

from capture_analysis import InvalidCapture, analyze, profile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, help="directory containing manifest.json and frames.csv")
    parser.add_argument("--target-fps", type=float, default=60)
    parser.add_argument("--profile", action="store_true", help="analyze the recorded workload without native-1080p acceptance")
    args = parser.parse_args()
    try:
        report = profile(args.capture) if args.profile else analyze(args.capture, args.target_fps)
    except (InvalidCapture, OSError, ValueError, OverflowError, csv.Error) as error:
        print(json.dumps({"valid_native_capture": False, "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False))
    return 0 if args.profile or report["target_met_in_capture"] else 1


if __name__ == "__main__":
    sys.exit(main())
