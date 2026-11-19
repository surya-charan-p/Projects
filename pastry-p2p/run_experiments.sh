#!/bin/sh
# Average hop count versus network size (no failures). 3 trials per size.
# Results are appended to results.csv.
cd "$(dirname "$0")"
./build.sh >/dev/null
OUT=results.csv
mkdir -p logs
echo "nodes,requests,kill,link_temp,link_perm,link_dur,replicas,ft,avg_hops,completed,ok,not_found,failed,wrong_value,correct,misrouted,join_secs,verify_ok" > $OUT
for N in 10 50 100 500 1000 2000 5000 10000 20000; do
  for T in 1 2 3; do
    echo "N=$N trial=$T"
    ./project3 $N 10 --verify --seed $T --csv $OUT > logs/hops_${N}_$T.txt 2>&1
    grep "Average number of hops" logs/hops_${N}_$T.txt
  done
done
