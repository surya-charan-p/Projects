#!/bin/sh
# Compile every Erlang module into ebin/
set -e
cd "$(dirname "$0")"
mkdir -p ebin
erlc -Wall -o ebin src/*.erl
echo "Built: $(ls ebin)"
