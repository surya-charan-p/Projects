# Dynamic Programming: Weighted Approximate Substring and Largest Zero Square

Two dynamic programming algorithms in Java, written without recursion and using
flat arrays. Each one finds the optimal answer and also extracts the solution
itself, not just its value. Both are checked against brute force and benchmarked on synthetic
data. Built for the Analysis of Algorithms course at the University of Florida.

1. **Weighted approximate common substring.** Given strings X and Y, find the
   pair of equal-length substrings (no gaps) that maximizes the sum of letter
   weights on matches minus a penalty δ per mismatch.
   Recurrence: `S[i][j] = s(x_i, y_j) + max(0, S[i-1][j-1])`, which is Kadane's
   algorithm run along every diagonal. Θ(nm) time, Θ(m) space in the
   linear-space version.
2. **Largest all-zero k×k square** in an m×n boolean matrix.
   Recurrence: `D[i][j] = 1 + min(D[i-1][j], D[i][j-1], D[i-1][j-1])` on zero
   cells. Θ(mn) time. The table uses one byte per value (read as unsigned) when
   min(m, n) ≤ 255, and widens to short when larger squares are possible.

The full write-up, with Bellman equations, correctness proofs, complexity and
experiment graphs, is in [`report/report.pdf`](report/report.pdf).

## Results at a glance

- Problem 1 runs at about 1.3 to 2.1 ns per DP cell. With English letter
  frequency weights, a planted 200-letter block with 15% mutations is recovered
  for every δ ≥ 1.2.
- Problem 2 solves a 1000×1000 matrix in about 2.5 to 4.3 ms. Measured JVM
  allocation matches the computed table size to within 1 KB.
- Both DPs agree with brute force on 3000 random small instances each.

## Files

| Path | Contents |
|---|---|
| `src/Substring.java` | Problem 1: full-table DP + traceback, linear-space DP, brute force, data generators, experiments |
| `src/ZeroSquare.java` | Problem 2: byte/short DP table, two-row DP, brute force, runtime and memory experiments |
| `run_experiments.py` | Compiles both programs, runs tests and all experiments, writes `results/*.csv`, `figures/*.png` and the LaTeX tables |
| `report/` | `report.tex` and the compiled `report.pdf` |
| `results/`, `figures/` | Experiment data and graphs used in the report |

## Run

Requires a JDK (tested with OpenJDK 25), Python 3 with matplotlib, and pdflatex
for the report.

```bash
python3 run_experiments.py
```

```bash
javac -d build src/Substring.java src/ZeroSquare.java
```

```bash
java -cp build Substring solve ABCAABCAA ABBCAACCBBBBBB s2 3.5
```

```bash
java -cp build ZeroSquare test
```

`java -cp build Substring test` runs the brute-force checks for Problem 1.
`java -cp build ZeroSquare solve file.txt` reads `m n` followed by `m` rows of
0/1 values.

To rebuild the report: `cd report && pdflatex report.tex && pdflatex report.tex`.
