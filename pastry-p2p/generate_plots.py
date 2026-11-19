"""Plots for the Project 3 report (reads results.csv and bonus_results.csv)."""
import csv
import math
from collections import defaultdict
from statistics import mean

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def load(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def success(r):
    return 100.0 * int(r["ok"]) / max(1, int(r["completed"]))


# ---- hops vs network size
rows = load("results.csv")
by_n = defaultdict(list)
for r in rows:
    by_n[int(r["nodes"])].append(float(r["avg_hops"]))
ns = sorted(by_n)
plt.figure(figsize=(6.5, 4))
plt.plot(ns, [mean(by_n[n]) for n in ns], "o-", color="#2a6fdb", label="measured average hops")
plt.plot(ns, [math.log(n, 16) for n in ns], "--", color="#888888", label=r"$\log_{16} N$")
plt.plot(ns, [math.ceil(math.log(n, 16)) for n in ns], ":", color="#c0392b",
         label=r"$\lceil \log_{16} N \rceil$ (paper bound)")
plt.xscale("log")
plt.xlabel("number of nodes N")
plt.ylabel("average hops per request")
plt.title("Pastry routing: average hops vs network size")
plt.grid(alpha=0.3)
plt.legend()
plt.tight_layout()
plt.savefig("hops_vs_nodes.png", dpi=160)

# ---- bonus
b = load("bonus_results.csv")


def pick(**want):
    out = []
    for r in b:
        if all(float(r[k]) == v if k not in ("ft",) else r[k] == v for k, v in want.items()):
            out.append(r)
    return out


def series(param, values, ft, extra):
    ys = []
    for v in values:
        sel = [r for r in pick(**{param: float(v), "ft": ft, **extra})]
        ys.append(mean(success(r) for r in sel) if sel else float("nan"))
    return ys


base = {"replicas": 3.0}
fig, axes = plt.subplots(1, 3, figsize=(13, 3.8), sharey=True)
kills = [0, 5, 10, 20, 30, 40]
noflt = {**base, "link_temp": 0.0, "link_perm": 0.0}
zero = series("kill", [0], "off", noflt)        # failure-free baseline
axes[0].plot(kills, zero + series("kill", kills[1:], "on", noflt), "o-", label="fault tolerance on")
axes[0].plot(kills, zero + series("kill", kills[1:], "off", noflt), "s--", label="fault tolerance off")
axes[0].set_title("node crashes")
axes[0].set_xlabel("% of nodes crashed")
axes[0].set_ylabel("% requests answered correctly")
links = [0, 5, 10, 20, 40]
for ax, col, name in ((axes[1], "link_temp", "temporary link failures"),
                      (axes[2], "link_perm", "permanent link failures")):
    other = "link_perm" if col == "link_temp" else "link_temp"
    ex = {**base, "kill": 0.0, other: 0.0}
    ax.plot(links, zero + series(col, links[1:], "on", ex), "o-", label="fault tolerance on")
    ax.plot(links, zero + series(col, links[1:], "off", ex), "s--", label="fault tolerance off")
    ax.set_title(name)
    ax.set_xlabel("% of nodes with a failed link")
for ax in axes:
    ax.grid(alpha=0.3)
    ax.set_ylim(0, 102)
axes[0].legend(loc="lower left")
plt.tight_layout()
plt.savefig("bonus_success.png", dpi=160)

# replicas
reps = [1, 2, 3, 5]
plt.figure(figsize=(5.5, 3.6))
ys = series("replicas", reps, "on", {"kill": 30.0, "link_temp": 0.0, "link_perm": 0.0})
nf = []
for k in reps:
    sel = pick(replicas=float(k), kill=30.0, ft="on", link_temp=0.0, link_perm=0.0)
    nf.append(mean(100.0 * int(r["not_found"]) / max(1, int(r["completed"])) for r in sel))
plt.bar([str(k) for k in reps], nf, color="#c0392b")
for i, v in enumerate(nf):
    plt.text(i, v + 0.1, f"{v:.2f}%", ha="center", fontsize=8)
plt.xlabel("replication factor K")
plt.ylabel("% requests: object lost")
plt.title("30% of nodes crashed: lost objects vs replicas")
plt.grid(axis="y", alpha=0.3)
plt.tight_layout()
plt.savefig("bonus_replicas.png", dpi=160)
print("plots written")
