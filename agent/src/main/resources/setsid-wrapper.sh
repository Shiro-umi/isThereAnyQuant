#!/bin/sh
# setsid wrapper for macOS (BSD doesn't have setsid command)
# Creates a new session and process group, then exec the command

exec "$@" </dev/null &
