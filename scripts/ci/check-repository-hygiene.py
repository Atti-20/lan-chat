#!/usr/bin/env python3
"""Enforce ignore rules even when local/generated files were force-added."""
import subprocess
import sys

rejected = subprocess.check_output([
    "git", "ls-files", "--cached", "--ignored", "--exclude-standard", "-z",
]).decode().split("\0")
rejected = sorted(filter(None, rejected))
if rejected:
    print("Remove ignored local/generated/sensitive files from Git:", *rejected, sep="\n")
    sys.exit(1)
print("Tracked file policy passed")
