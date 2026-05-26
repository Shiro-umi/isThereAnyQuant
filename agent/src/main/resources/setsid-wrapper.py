#!/usr/bin/env python3
"""
setsid wrapper for macOS/Linux
Creates a new session and process group, then exec the command
"""
import os
import sys

if len(sys.argv) < 2:
    print("Usage: setsid-wrapper.py <command> [args...]", file=sys.stderr)
    sys.exit(1)

# Create new session (becomes session leader and process group leader)
os.setsid()

# Execute the command
os.execvp(sys.argv[1], sys.argv[1:])
