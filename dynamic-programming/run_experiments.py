#!/usr/bin/env python3
"""Builds both programs, runs every experiment and draws the report figures.

Usage:  python3 run_experiments.py            (everything)
        python3 run_experiments.py --plots    (redraw figures from existing CSVs)
"""
import csv
import os
import subprocess
import sys
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
BUILD = os.path.join(HERE, "build")
RES = os.path.join(HERE, "results")
FIG = os.path.join(HERE, "figures")

# Categorical slots in fixed order; text in neutral ink.
C1, C2, C3 = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"

P2_SIZES = [(10, 10), (10, 100), (10, 1000), (100, 1000), (1000, 1000)]


def run(cmd):
    print("$", " ".join(cmd), flush=True)
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def build():
    run(["javac", "-d", BUILD, os.path.join(SRC, "Substring.java"), os.path.join(SRC, "ZeroSquare.java")])


def java(*args):
    return run(["java", "-Xmx2g", "-cp", BUILD] + list(args))


def experiments():
    os.makedirs(RES, exist_ok=True)
    for name in ("Substring", "ZeroSquare"):
        with open(os.path.join(RES, name + "_tests.txt"), "w") as f:
            f.write(java(name, "test"))
    java("Substring", "bench", os.path.join(RES, "p1_runtime.csv"))
    java("Substring", "sweep", os.path.join(RES, "p1_sweep.csv"))
    java("Substring", "sweep1", os.path.join(RES, "p1_sweep_s1.csv"))
    java("ZeroSquare", "bench", os.path.join(RES, "p2_runtime.csv"))
    java("ZeroSquare", "mem", os.path.join(RES, "p2_memory.csv"))


def read(name):
    with open(os.path.join(RES, name)) as f:
        return list(csv.DictReader(f))


def style(ax, xlabel, ylabel, title):
    ax.set_title(title, color=INK, fontsize=11, loc="left")
    ax.set_xlabel(xlabel, color=INK2)
    ax.set_ylabel(ylabel, color=INK2)
    ax.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(GRID)
    ax.tick_params(colors=INK2)


def save(fig, name):
    os.makedirs(FIG, exist_ok=True)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, name), dpi=200)
    plt.close(fig)
    print("wrote figures/" + name)


def line(ax, xs, ys, color, label, marker="o", dashed=False):
    ax.plot(xs, ys, color=color, linewidth=2, marker=marker, markersize=6,
            linestyle="--" if dashed else "-", label=label)


def plot_p1():
    rows = read("p1_runtime.csv")
    sq = [r for r in rows if r["n"] == r["m"]]
    fig, ax = plt.subplots(figsize=(6.4, 4))
    for scen, color in (("1", C1), ("2", C2)):
        for variant, dashed in (("full", False), ("linear", True)):
            pts = sorted((int(r["n"]) * int(r["m"]), float(r["ms"]))
                         for r in sq if r["scenario"] == scen and r["variant"] == variant)
            line(ax, [p[0] / 1e6 for p in pts], [p[1] for p in pts], color,
                 f"Scenario {scen}, {variant} table", "o" if not dashed else "s", dashed)
    style(ax, "n·m (millions of DP cells), n = m", "time (ms)", "Problem 1: running time vs. n·m")
    ax.legend(frameon=False, fontsize=9)
    save(fig, "p1_runtime.png")

    # Time per cell, all shapes, shows Theta(nm) (flat line).
    fig, ax = plt.subplots(figsize=(6.4, 3.6))
    for scen, color in (("1", C1), ("2", C2)):
        pts = sorted((int(r["n"]) * int(r["m"]), float(r["ms"]) * 1e6 / (int(r["n"]) * int(r["m"])))
                     for r in rows if r["scenario"] == scen and r["variant"] == "full")
        line(ax, [p[0] / 1e6 for p in pts], [p[1] for p in pts], color, f"Scenario {scen}")
    ax.set_ylim(bottom=0)
    style(ax, "n·m (millions of DP cells)", "ns per cell", "Problem 1: time / (n·m) is constant")
    ax.legend(frameon=False, fontsize=9)
    save(fig, "p1_time_per_cell.png")

    fig, ax = plt.subplots(figsize=(6.4, 3.6))
    for variant, color, dashed in (("full", C1, False), ("linear", C2, True)):
        pts = sorted((int(r["n"]) * int(r["m"]), int(r["dp_bytes"]))
                     for r in sq if r["scenario"] == "1" and r["variant"] == variant)
        line(ax, [p[0] / 1e6 for p in pts], [p[1] / 2**20 for p in pts], color,
             f"{variant} table", "o" if not dashed else "s", dashed)
    ax.set_yscale("log")
    style(ax, "n·m (millions of DP cells), n = m", "DP memory (MiB, log scale)", "Problem 1: DP memory")
    ax.legend(frameon=False, fontsize=9)
    save(fig, "p1_memory.png")

    sweep = read("p1_sweep.csv")
    agg = defaultdict(lambda: defaultdict(list))
    for r in sweep:
        if r["data"] in ("random", "planted"):
            d = agg[(r["data"], float(r["delta"]))]
            for k in ("score", "len", "mismatches", "overlap_planted"):
                d[k].append(float(r[k]))
    mean = lambda v: sum(v) / len(v)  # noqa: E731
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6))
    for data, color in (("random", C1), ("planted", C2)):
        ds = sorted(d for (dd, d) in agg if dd == data)
        line(axes[0], ds, [mean(agg[(data, d)]["len"]) for d in ds], color, f"{data}: length")
        line(axes[0], ds, [mean(agg[(data, d)]["mismatches"]) for d in ds], color,
             f"{data}: mismatches", "s", True)
        line(axes[1], ds, [mean(agg[(data, d)]["score"]) for d in ds], color, data)
    axes[0].set_yscale("symlog", linthresh=10)
    style(axes[0], "mismatch penalty δ", "letters (mean of 10 seeds)", "Best substring length / mismatches")
    style(axes[1], "mismatch penalty δ", "best score (mean of 10 seeds)", "Best score")
    axes[0].legend(frameon=False, fontsize=8)
    axes[1].legend(frameon=False, fontsize=8)
    save(fig, "p1_sweep.png")

    # Scenario 1 (w = 1) for several penalties.
    agg1 = sweep_means("p1_sweep_s1.csv")
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6))
    for data, color in (("random", C1), ("planted", C2)):
        ds = sorted(d for (dd, d) in agg1 if dd == data)
        line(axes[0], ds, [agg1[(data, d)]["len"] for d in ds], color, f"{data}: length")
        line(axes[0], ds, [agg1[(data, d)]["mismatches"] for d in ds], color, f"{data}: mismatches", "s", True)
        line(axes[1], ds, [agg1[(data, d)]["score"] for d in ds], color, data)
    style(axes[0], "mismatch penalty δ (all weights 1)", "letters (mean of 10 seeds)", "Best substring length / mismatches")
    style(axes[1], "mismatch penalty δ (all weights 1)", "best score (mean of 10 seeds)", "Best score")
    axes[0].legend(frameon=False, fontsize=8)
    axes[1].legend(frameon=False, fontsize=8)
    save(fig, "p1_sweep_s1.png")


def sweep_means(name):
    """{(data, delta): {field: mean over seeds}} for a sweep CSV."""
    acc = defaultdict(lambda: defaultdict(list))
    for r in read(name):
        for k in ("score", "len", "mismatches", "overlap_planted"):
            acc[(r["data"], float(r["delta"]))][k].append(float(r[k]))
    return {key: {k: sum(v) / len(v) for k, v in d.items()} for key, d in acc.items()}


def plot_p2():
    rows = read("p2_runtime.csv")
    labels = {(m, n): f"{m}×{n}" for m, n in P2_SIZES}
    fig, ax = plt.subplots(figsize=(6.4, 4))
    for p, color in (("0.50", C1), ("0.10", C2), ("0.01", C3)):
        pts = sorted((int(r["m"]) * int(r["n"]), float(r["ms"]))
                     for r in rows if r["p"] == p and r["variant"] == "full")
        line(ax, [x for x, _ in pts], [y for _, y in pts], color, f"P(1) = {float(p):g}")
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks([m * n for m, n in P2_SIZES])
    ax.set_xticklabels([labels[s] for s in P2_SIZES], fontsize=8)
    style(ax, "matrix size (log scale, m·n cells)", "time (ms, log scale)",
          "Problem 2: running time (full byte/short table)")
    ax.legend(frameon=False, fontsize=9)
    save(fig, "p2_runtime.png")

    mem = read("p2_memory.csv")
    by = {(int(r["m"]), int(r["n"]), r["variant"]): r for r in mem}
    xs = [m * n for m, n in P2_SIZES]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6))
    line(axes[0], xs, [int(by[(m, n, "full")]["input_bytes"]) for m, n in P2_SIZES], INK2, "input matrix B", "^")
    line(axes[0], xs, [int(by[(m, n, "full")]["dp_bytes"]) for m, n in P2_SIZES], C1, "DP table, full")
    line(axes[0], xs, [int(by[(m, n, "rolling")]["dp_bytes"]) for m, n in P2_SIZES], C2, "DP table, 2 rows", "s", True)
    axes[0].set_yscale("log")
    style(axes[0], "matrix size", "bytes (log scale)", "Computed array sizes")
    line(axes[1], xs, [int(by[(m, n, "full")]["input_alloc"]) for m, n in P2_SIZES], INK2, "input matrix B", "^")
    line(axes[1], xs, [int(by[(m, n, "full")]["dp_alloc"]) for m, n in P2_SIZES], C1, "solve, full table")
    line(axes[1], xs, [int(by[(m, n, "rolling")]["dp_alloc"]) for m, n in P2_SIZES], C2, "solve, 2 rows", "s", True)
    axes[1].set_yscale("log")
    style(axes[1], "matrix size", "bytes (log scale)", "Measured JVM heap allocation")
    for ax in axes:
        ax.set_xscale("log")
        ax.set_xticks(xs)
        ax.set_xticklabels([labels[s] for s in P2_SIZES], fontsize=7, rotation=20)
        ax.legend(frameon=False, fontsize=8)
    save(fig, "p2_memory.png")


def write_tex(name, lines):
    with open(os.path.join(RES, name), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote results/" + name)


def tables():
    """LaTeX table bodies included by report/report.tex."""
    rows = read("p1_runtime.csv")
    key = {(r["scenario"], r["n"], r["m"], r["variant"]): r for r in rows}
    out = []
    for n, m in sorted({(int(r["n"]), int(r["m"])) for r in rows}, key=lambda s: (s[0] * s[1], s[0])):
        f1, l1 = key[("1", str(n), str(m), "full")], key[("1", str(n), str(m), "linear")]
        f2, l2 = key[("2", str(n), str(m), "full")], key[("2", str(n), str(m), "linear")]
        out.append(f"{n}$\\times${m} & {float(f1['ms']):.2f} & {float(l1['ms']):.2f} & "
                   f"{float(f2['ms']):.2f} & {float(l2['ms']):.2f} & "
                   f"{int(f1['dp_bytes']) / 2**20:.2f} & {int(l1['dp_bytes']) / 1024:.1f} \\\\")
    write_tex("p1_runtime_table.tex", out)

    agg = defaultdict(lambda: defaultdict(list))
    for r in read("p1_sweep.csv"):
        d = agg[(r["data"], float(r["delta"]))]
        for k in ("score", "len", "mismatches", "overlap_planted"):
            d[k].append(float(r[k]))
    mean = lambda v: sum(v) / len(v)  # noqa: E731
    out = []
    for d in sorted(x for (data, x) in agg if data == "random"):
        a, b = agg[("random", d)], agg[("planted", d)]
        out.append(f"{d:.3f} & {mean(a['score']):.1f} & {mean(a['len']):.1f} & {mean(a['mismatches']):.1f} & "
                   f"{mean(b['score']):.1f} & {mean(b['len']):.1f} & {mean(b['mismatches']):.1f} & "
                   f"{mean(b['overlap_planted']):.1f} \\\\")
    s1 = next(v for (data, _), v in agg.items() if data == "planted_s1")
    write_tex("p1_sweep_table.tex", out)
    write_tex("p1_s1_planted.tex", [f"\\newcommand{{\\SOneLen}}{{{mean(s1['len']):.1f}}}",
                                    f"\\newcommand{{\\SOneMis}}{{{mean(s1['mismatches']):.1f}}}",
                                    f"\\newcommand{{\\SOneScore}}{{{mean(s1['score']):.1f}}}"])

    agg1 = sweep_means("p1_sweep_s1.csv")
    out = []
    for d in sorted(x for (data, x) in agg1 if data == "random"):
        a, b = agg1[("random", d)], agg1[("planted", d)]
        out.append(f"{d:g} & {a['score']:.1f} & {a['len']:.1f} & {a['mismatches']:.1f} & "
                   f"{b['score']:.1f} & {b['len']:.1f} & {b['mismatches']:.1f} & {b['overlap_planted']:.1f} \\\\")
    write_tex("p1_sweep_s1_table.tex", out)

    rows = read("p2_runtime.csv")
    key = {(r["m"], r["n"], r["p"], r["variant"]): r for r in rows}
    out = []
    for m, n in P2_SIZES:
        cells = []
        for p in ("0.50", "0.10", "0.01"):
            r = key[(str(m), str(n), p, "full")]
            cells.append(f"{float(r['ms']) * 1000:.2f} & {r['k']}")
        rr = key[(str(m), str(n), "0.10", "rolling")]
        out.append(f"{m}$\\times${n} & " + " & ".join(cells) + f" & {float(rr['ms']) * 1000:.2f} \\\\")
    write_tex("p2_runtime_table.tex", out)

    by = {(int(r["m"]), int(r["n"]), r["variant"]): r for r in read("p2_memory.csv")}
    out = []
    for m, n in P2_SIZES:
        f, r = by[(m, n, "full")], by[(m, n, "rolling")]
        out.append(f"{m}$\\times${n} & {int(f['input_bytes']):,} & {int(f['dp_bytes']):,} & {int(r['dp_bytes']):,} & "
                   f"{int(f['input_alloc']):,} & {int(f['dp_alloc']):,} & {int(r['dp_alloc']):,} \\\\"
                   .replace(",", "{,}"))
    write_tex("p2_memory_table.tex", out)


if __name__ == "__main__":
    if "--plots" not in sys.argv:
        build()
        experiments()
    plot_p1()
    plot_p2()
    tables()
