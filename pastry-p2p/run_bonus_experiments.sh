#!/bin/sh
# Failure-model experiments (bonus). N = 1000 peers, 10 requests each,
# 3 trials per configuration, run 4 at a time. Results go to bonus_results.csv.
cd "$(dirname "$0")"
./build.sh >/dev/null
OUT=bonus_results.csv
mkdir -p logs/bonus
echo "nodes,requests,kill,link_temp,link_perm,link_dur,replicas,ft,avg_hops,completed,ok,not_found,failed,wrong_value,correct,misrouted,join_secs,verify_ok" > $OUT

CONFIGS=$(mktemp)
for T in 1 2 3; do
  for K in 0 5 10 20 30 40; do
    echo "--kill $K --seed $T"
    [ $K -gt 0 ] && echo "--kill $K --no-ft --seed $T"
  done
  for R in 1 2 5; do echo "--kill 30 --replicas $R --seed $T"; done
  for P in 5 10 20 40; do
    echo "--link-temp $P --seed $T"
    echo "--link-temp $P --no-ft --seed $T"
    echo "--link-perm $P --seed $T"
    echo "--link-perm $P --no-ft --seed $T"
  done
  echo "--kill 10 --link-temp 10 --link-perm 10 --seed $T"
  echo "--kill 10 --link-temp 10 --link-perm 10 --no-ft --seed $T"
done > "$CONFIGS"

# each line of $CONFIGS becomes one run; logs are named after the options
tr ' ' '_' < "$CONFIGS" | xargs -P 4 -I{} sh -c \
  'args=$(echo "{}" | tr "_" " "); ./project3 1000 10 $args --csv '"$OUT"' > "logs/bonus/run{}.txt" 2>&1; echo "done: $args"'
rm -f "$CONFIGS"
