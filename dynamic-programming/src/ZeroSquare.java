// Problem 2: Largest all-zero square sub-matrix of an m x n boolean matrix.
//
// DP (iterative, arrays only):
//     D[i][j] = side of the largest all-zero square whose bottom-right corner
//               is (i, j).
//     D[i][j] = 0                                            if B[i][j] = 1
//             = 1                                            if B[i][j] = 0 and (i = 0 or j = 0)
//             = 1 + min(D[i-1][j], D[i][j-1], D[i-1][j-1])   otherwise
// Answer k = max D[i][j]; with bottom-right (i*, j*) the square occupies rows
// i*-k+1..i* and columns j*-k+1..j*.
//
// Storage: B is one byte per cell. D uses the smallest type that can hold
// min(m, n): byte (read as unsigned, 0..255 -- the hint's "one byte") when
// min(m, n) <= 255, otherwise short (read as unsigned, 0..65535), otherwise int.
//
// Build:  javac ZeroSquare.java
// Usage:  java ZeroSquare test
//         java ZeroSquare bench <out.csv>        runtime vs. size and density
//         java ZeroSquare mem <out.csv>          measured memory per size
//         java ZeroSquare solve <file>           (file: "m n" then m rows of 0/1)

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.Random;
import java.util.Scanner;

public class ZeroSquare {

    static final class Square {
        int k = 0;               // side length (0 if the matrix has no zero)
        int row = -1, col = -1;  // 0-based top-left corner
        long dpBytes = 0;        // bytes of DP arrays allocated
    }

    static Square result(int best, int bi, int bj, long bytes) {
        Square s = new Square();
        s.k = best;
        if (best > 0) { s.row = bi - best + 1; s.col = bj - best + 1; }
        s.dpBytes = bytes;
        return s;
    }

    // Full m x n DP table of unsigned bytes. Valid when min(m, n) <= 255.
    static Square solveFullByte(byte[] B, int m, int n) {
        byte[] D = new byte[m * n];
        int best = 0, bi = -1, bj = -1;
        for (int i = 0; i < m; i++) {
            final int row = i * n, up = row - n;
            for (int j = 0; j < n; j++) {
                int v;
                if (B[row + j] != 0) v = 0;
                else if (i == 0 || j == 0) v = 1;
                else v = 1 + Math.min(D[up + j] & 0xFF,
                                      Math.min(D[row + j - 1] & 0xFF, D[up + j - 1] & 0xFF));
                D[row + j] = (byte) v;
                if (v > best) { best = v; bi = i; bj = j; }
            }
        }
        return result(best, bi, bj, D.length);
    }

    // Same with unsigned shorts. Valid when min(m, n) <= 65535.
    static Square solveFullShort(byte[] B, int m, int n) {
        short[] D = new short[m * n];
        int best = 0, bi = -1, bj = -1;
        for (int i = 0; i < m; i++) {
            final int row = i * n, up = row - n;
            for (int j = 0; j < n; j++) {
                int v;
                if (B[row + j] != 0) v = 0;
                else if (i == 0 || j == 0) v = 1;
                else v = 1 + Math.min(D[up + j] & 0xFFFF,
                                      Math.min(D[row + j - 1] & 0xFFFF, D[up + j - 1] & 0xFFFF));
                D[row + j] = (short) v;
                if (v > best) { best = v; bi = i; bj = j; }
            }
        }
        return result(best, bi, bj, 2L * D.length);
    }

    // Same with ints, for min(m, n) > 65535.
    static Square solveFullInt(byte[] B, int m, int n) {
        int[] D = new int[m * n];
        int best = 0, bi = -1, bj = -1;
        for (int i = 0; i < m; i++) {
            final int row = i * n, up = row - n;
            for (int j = 0; j < n; j++) {
                int v;
                if (B[row + j] != 0) v = 0;
                else if (i == 0 || j == 0) v = 1;
                else v = 1 + Math.min(D[up + j], Math.min(D[row + j - 1], D[up + j - 1]));
                D[row + j] = v;
                if (v > best) { best = v; bi = i; bj = j; }
            }
        }
        return result(best, bi, bj, 4L * D.length);
    }

    // Same recurrence with two rolling rows: O(n) extra memory. Extraction only
    // needs (k, i*, j*), which are tracked while filling, so nothing is lost.
    static Square solveRolling(byte[] B, int m, int n) {
        int[] prev = new int[n], cur = new int[n];
        int best = 0, bi = -1, bj = -1;
        for (int i = 0; i < m; i++) {
            final int row = i * n;
            for (int j = 0; j < n; j++) {
                int v;
                if (B[row + j] != 0) v = 0;
                else if (i == 0 || j == 0) v = 1;
                else v = 1 + Math.min(prev[j], Math.min(cur[j - 1], prev[j - 1]));
                cur[j] = v;
                if (v > best) { best = v; bi = i; bj = j; }
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return result(best, bi, bj, 2L * 4 * n);
    }

    // Picks the narrowest cell type that can hold the largest possible value.
    static Square solve(byte[] B, int m, int n, boolean rolling) {
        if (rolling) return solveRolling(B, m, n);
        int cap = Math.min(m, n);
        if (cap <= 0xFF) return solveFullByte(B, m, n);
        if (cap <= 0xFFFF) return solveFullShort(B, m, n);
        return solveFullInt(B, m, n);
    }

    // Brute force: try every corner and size.
    static int bruteForce(byte[] B, int m, int n) {
        int best = 0;
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                for (int k = 1; i + k <= m && j + k <= n; k++) {
                    boolean ok = true;
                    for (int a = i; a < i + k && ok; a++)
                        for (int b = j; b < j + k; b++)
                            if (B[a * n + b] != 0) { ok = false; break; }
                    if (!ok) break;  // a larger square at the same corner contains this one
                    best = Math.max(best, k);
                }
        return best;
    }

    static boolean isZeroSquare(byte[] B, int n, Square s) {
        for (int a = s.row; a < s.row + s.k; a++)
            for (int b = s.col; b < s.col + s.k; b++)
                if (B[a * n + b] != 0) return false;
        return true;
    }

    // Each cell is 1 with probability p, independently.
    static byte[] randomMatrix(int m, int n, double p, Random rng) {
        byte[] B = new byte[m * n];
        for (int k = 0; k < B.length; k++) B[k] = (byte) (rng.nextDouble() < p ? 1 : 0);
        return B;
    }

    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.printf("[%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (!ok) fails++;
    }

    static int runTests() {
        {
            // 5 x 6 example with a 3 x 3 zero block at rows 1..3, cols 2..4.
            byte[] B = {1, 0, 1, 0, 0, 1,
                        0, 1, 0, 0, 0, 1,
                        1, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 1,
                        1, 1, 0, 1, 0, 0};
            Square s = solve(B, 5, 6, false);
            System.out.printf("5x6 example: k=%d top-left=(%d,%d)%n", s.k, s.row, s.col);
            check(s.k == 3 && s.row == 1 && s.col == 2, "hand example finds the 3x3 block");
        }
        {
            byte[] B = new byte[12];
            java.util.Arrays.fill(B, (byte) 1);
            check(solve(B, 3, 4, false).k == 0, "all-ones matrix -> k = 0");
        }
        {
            // All zeros with min(m,n) = 300 > 255: a byte table would overflow.
            byte[] B = new byte[300 * 400];
            Square a = solve(B, 300, 400, false), b = solve(B, 300, 400, true);
            check(a.k == 300 && b.k == 300, "300x400 all-zero -> k = 300 (no byte overflow)");
            // 200x200 all zeros: k = 200 > 127 checks the unsigned-byte reading.
            byte[] C = new byte[200 * 200];
            check(solve(C, 200, 200, false).k == 200, "200x200 all-zero -> k = 200 (byte read as unsigned)");
        }
        {
            byte[] B = {0, 0, 0};
            check(solve(B, 1, 3, false).k == 1 && solve(B, 3, 1, true).k == 1, "single row / column");
        }
        Random rng = new Random(7);
        int bad = 0, trials = 3000;
        for (int t = 0; t < trials; t++) {
            int m = 1 + rng.nextInt(15), n = 1 + rng.nextInt(15);
            double p = rng.nextInt(100) / 100.0 * 0.6;
            byte[] B = randomMatrix(m, n, p, rng);
            Square a = solve(B, m, n, false), b = solve(B, m, n, true);
            int bf = bruteForce(B, m, n);
            boolean ok = a.k == bf && b.k == bf && a.row == b.row && a.col == b.col
                    && (bf == 0 || (isZeroSquare(B, n, a) && a.row >= 0 && a.row + a.k <= m
                                    && a.col >= 0 && a.col + a.k <= n));
            if (!ok) bad++;
        }
        check(bad == 0, trials + " random matrices: full == rolling == brute force, square is all zero");
        System.out.println(fails > 0 ? "\n" + fails + " test(s) FAILED" : "\nAll tests passed");
        return fails > 0 ? 1 : 0;
    }

    static final int[][] SIZES = {{10, 10}, {10, 100}, {10, 1000}, {100, 1000}, {1000, 1000}};

    // Runtime of both variants on the five required sizes and several
    // densities. Each solve is repeated until at least ~50 ms has elapsed and
    // averaged; the best of 5 such trials is reported.
    static void runBench(String path) throws FileNotFoundException {
        PrintWriter f = new PrintWriter(path);
        f.println("m,n,p,variant,ms,input_bytes,dp_bytes,k,row,col");
        double[] densities = {0.5, 0.1, 0.01};
        Random rng = new Random(2026);

        // JIT warm-up on every code path that is timed.
        for (int[] sz : SIZES) {
            byte[] W = randomMatrix(sz[0], sz[1], 0.1, rng);
            for (int r = 0; r < 200; r++) { solve(W, sz[0], sz[1], false); solve(W, sz[0], sz[1], true); }
        }

        for (double p : densities)
            for (int[] sz : SIZES) {
                int m = sz[0], n = sz[1];
                byte[] B = randomMatrix(m, n, p, rng);
                for (int v = 0; v < 2; v++) {
                    boolean rolling = v == 1;
                    Square s = null;
                    double bestMs = Double.MAX_VALUE;
                    for (int trial = 0; trial < 5; trial++) {
                        int reps = 0;
                        long t0 = System.nanoTime();
                        double el;
                        do {
                            s = solve(B, m, n, rolling);
                            reps++;
                            el = (System.nanoTime() - t0) / 1e6;
                        } while (el < 50);
                        bestMs = Math.min(bestMs, el / reps);
                    }
                    String name = rolling ? "rolling" : "full";
                    f.printf("%d,%d,%.2f,%s,%.6f,%d,%d,%d,%d,%d%n", m, n, p, name, bestMs,
                            B.length, s.dpBytes, s.k, s.row, s.col);
                    System.out.printf("p=%.2f %4d x %4d %-7s %10.5f ms  dp=%8d B  k=%d at (%d,%d)%n",
                            p, m, n, name, bestMs, s.dpBytes, s.k, s.row, s.col);
                }
            }
        f.close();
    }

    // Bytes allocated on the heap by the current thread (HotSpot extension).
    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    // Measured memory: heap bytes allocated while generating B and while
    // solving (includes JVM array headers), next to the computed array sizes.
    static void runMem(String path) throws FileNotFoundException {
        PrintWriter f = new PrintWriter(path);
        f.println("m,n,p,variant,input_bytes,input_alloc,dp_bytes,dp_alloc,k");
        for (int[] sz : SIZES) {
            int m = sz[0], n = sz[1];
            for (String variant : new String[] {"full", "rolling"}) {
                Random rng = new Random(2026);
                // Warm up so class loading / JIT allocations are not counted.
                for (int r = 0; r < 3; r++) solve(randomMatrix(m, n, 0.1, rng), m, n, variant.equals("rolling"));
                rng = new Random(2026);
                long a0 = allocated();
                byte[] B = randomMatrix(m, n, 0.1, rng);
                long a1 = allocated();
                Square s = solve(B, m, n, variant.equals("rolling"));
                long a2 = allocated();
                f.printf("%d,%d,%.2f,%s,%d,%d,%d,%d,%d%n", m, n, 0.1, variant, B.length, a1 - a0,
                        s.dpBytes, a2 - a1, s.k);
                System.out.printf("%4d x %4d %-7s B=%8d (alloc %8d)  dp=%8d (alloc %8d)  k=%d%n",
                        m, n, variant, B.length, a1 - a0, s.dpBytes, a2 - a1, s.k);
            }
        }
        f.close();
    }

    static int runSolveFile(String path) throws FileNotFoundException {
        Scanner in = new Scanner(new File(path));
        int m = in.nextInt(), n = in.nextInt();
        if (m <= 0 || n <= 0) { System.err.println("bad header"); return 2; }
        byte[] B = new byte[m * n];
        for (int k = 0; k < B.length; k++) {
            if (!in.hasNextInt()) { System.err.println("not enough cells"); return 2; }
            B[k] = (byte) (in.nextInt() != 0 ? 1 : 0);
        }
        Square s = solve(B, m, n, false);
        if (s.k == 0) System.out.println("no zero entry");
        else System.out.printf("k=%d rows %d..%d cols %d..%d (0-based)%n",
                s.k, s.row, s.row + s.k - 1, s.col, s.col + s.k - 1);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java ZeroSquare test | bench out.csv | mem out.csv | solve file");
            System.exit(2);
        }
        switch (args[0]) {
            case "test": System.exit(runTests()); break;
            case "bench": runBench(args[1]); break;
            case "mem": runMem(args[1]); break;
            case "solve": System.exit(runSolveFile(args[1])); break;
            default:
                System.err.println("bad arguments");
                System.exit(2);
        }
    }
}
