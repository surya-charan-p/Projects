// Problem 1: Weighted approximate common substring (no gaps).
//
// Given strings X (length n) and Y (length m) over [A-Z], a weight w[c] for
// each letter and a mismatch penalty delta >= 0, find i, j, L maximising
//     score(i, j, L) = sum_{k=0}^{L-1} s(X[i+k], Y[j+k]),
//     s(a, b) = w[a] if a == b, else -delta.
//
// DP (iterative, arrays only):
//     S[i][j] = best score of a pair of equal-length substrings that END at
//               X[i-1] and Y[j-1] (1-based table, row/col 0 are boundaries).
//     S[i][j] = s(X[i-1], Y[j-1]) + max(0, S[i-1][j-1]),   S[0][*] = S[*][0] = 0
// Answer = max_{i,j} S[i][j]. Extraction walks back along the diagonal while
// the predecessor was used (S[i-1][j-1] > 0).
//
// Build:  javac Substring.java
// Usage:  java Substring test                       correctness tests
//         java Substring solve X Y [s1|s2] [delta]   solve one instance
//         java Substring bench <out.csv>             runtime/memory vs. size
//         java Substring sweep <out.csv>             scenario-2 delta sweep
//         java Substring sweep1 <out.csv>            scenario-1 delta sweep

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Random;

public class Substring {

    // English letter frequencies in percent (Lewand, "Cryptological
    // Mathematics", 2000; as tabulated on Wikipedia "Letter frequency").
    // Index 0 = 'A'.
    static final double[] ENGLISH_FREQ = {
        8.167, 1.492, 2.782, 4.253, 12.702, 2.228, 2.015, 6.094, 6.966, 0.153,
        0.772, 4.025, 2.406, 6.749, 7.507,  1.929, 0.095, 5.987, 6.327, 9.056,
        2.758, 0.978, 2.360, 0.150, 1.974,  0.074};

    static final class Weights {
        final double[] w = new double[26];
        double delta;  // mismatch penalty (positive number, subtracted)
    }

    static final class Match {
        double score = 0;
        int startX = -1, startY = -1;  // 0-based start positions
        int len = 0;
        int mismatches = 0;
        long dpBytes = 0;              // bytes of DP arrays allocated
    }

    static double sc(char a, char b, Weights W) {
        return a == b ? W.w[a - 'A'] : -W.delta;
    }

    static boolean validInput(String s) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    // Full-table DP with traceback. Memory: (n+1)(m+1) doubles, stored in one
    // flat array. Ties are broken by the first (smallest i, then j) end cell.
    static Match solveFull(String Xs, String Ys, Weights W) {
        final char[] X = Xs.toCharArray(), Y = Ys.toCharArray();
        final int n = X.length, m = Y.length;
        Match best = new Match();
        if (n == 0 || m == 0) return best;
        final int cols = m + 1;
        double[] S = new double[(n + 1) * cols];
        best.dpBytes = 8L * S.length;

        double bestScore = Double.NEGATIVE_INFINITY;
        int bi = 0, bj = 0;
        for (int i = 1; i <= n; i++) {
            final int row = i * cols, prev = (i - 1) * cols;
            final char xi = X[i - 1];
            for (int j = 1; j <= m; j++) {
                double p = S[prev + j - 1];
                double v = sc(xi, Y[j - 1], W) + (p > 0 ? p : 0);
                S[row + j] = v;
                if (v > bestScore) { bestScore = v; bi = i; bj = j; }
            }
        }

        // Traceback: extend to the upper-left while the predecessor was used.
        int i = bi, j = bj;
        while (i > 1 && j > 1 && S[(i - 1) * cols + (j - 1)] > 0) { i--; j--; }
        best.score = bestScore;
        best.startX = i - 1;
        best.startY = j - 1;
        best.len = bi - i + 1;
        for (int k = 0; k < best.len; k++)
            if (X[best.startX + k] != Y[best.startY + k]) best.mismatches++;
        return best;
    }

    // Space-optimised variant: two rolling rows of scores plus the run length
    // of each cell, so the start is known without the full table. O(m) memory.
    static Match solveLinear(String Xs, String Ys, Weights W) {
        final char[] X = Xs.toCharArray(), Y = Ys.toCharArray();
        final int n = X.length, m = Y.length;
        Match best = new Match();
        if (n == 0 || m == 0) return best;
        double[] prevS = new double[m + 1], curS = new double[m + 1];
        int[] prevL = new int[m + 1], curL = new int[m + 1];
        best.dpBytes = 2L * (m + 1) * (8 + 4);

        double bestScore = Double.NEGATIVE_INFINITY;
        int bi = 0, bj = 0, bl = 0;
        for (int i = 1; i <= n; i++) {
            final char xi = X[i - 1];
            for (int j = 1; j <= m; j++) {
                double p = prevS[j - 1];
                double v;
                int L;
                if (p > 0) { v = sc(xi, Y[j - 1], W) + p; L = prevL[j - 1] + 1; }
                else       { v = sc(xi, Y[j - 1], W);     L = 1; }
                curS[j] = v;
                curL[j] = L;
                if (v > bestScore) { bestScore = v; bi = i; bj = j; bl = L; }
            }
            double[] ts = prevS; prevS = curS; curS = ts;
            int[] tl = prevL; prevL = curL; curL = tl;
        }
        best.score = bestScore;
        best.len = bl;
        best.startX = bi - bl;
        best.startY = bj - bl;
        for (int k = 0; k < best.len; k++)
            if (X[best.startX + k] != Y[best.startY + k]) best.mismatches++;
        return best;
    }

    // O(n * m * min(n, m)) brute force over every (i, j, L), for verification.
    static double bruteForce(String X, String Y, Weights W) {
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < X.length(); i++)
            for (int j = 0; j < Y.length(); j++) {
                double s = 0;
                for (int L = 0; i + L < X.length() && j + L < Y.length(); L++) {
                    s += sc(X.charAt(i + L), Y.charAt(j + L), W);
                    best = Math.max(best, s);
                }
            }
        return best;
    }

    static double scoreOf(String X, String Y, Match r, Weights W) {
        double s = 0;
        for (int k = 0; k < r.len; k++) s += sc(X.charAt(r.startX + k), Y.charAt(r.startY + k), W);
        return s;
    }

    // ---------- Scenarios ----------
    // Scenario 1 (assignment: w_A = 1, delta = 10): every letter has weight 1.
    static Weights scenario1(double delta) {
        Weights W = new Weights();
        for (int c = 0; c < 26; c++) W.w[c] = 1.0;
        W.delta = delta;
        return W;
    }

    static Weights scenario1() { return scenario1(10.0); }

    // Scenario 2: weight proportional to English frequency (w = percentage).
    static Weights scenario2(double delta) {
        Weights W = new Weights();
        System.arraycopy(ENGLISH_FREQ, 0, W.w, 0, 26);
        W.delta = delta;
        return W;
    }

    static double minWeight() {
        double lo = Double.MAX_VALUE;
        for (double f : ENGLISH_FREQ) lo = Math.min(lo, f);
        return lo;
    }

    static double maxWeight() {
        double hi = -Double.MAX_VALUE;
        for (double f : ENGLISH_FREQ) hi = Math.max(hi, f);
        return hi;
    }

    // The 10 penalties strictly between min and max weight, evenly spaced.
    static double[] scenario2Deltas() {
        double lo = minWeight(), hi = maxWeight();
        double[] d = new double[10];
        for (int k = 1; k <= 10; k++) d[k - 1] = lo + k * (hi - lo) / 11.0;
        return d;
    }

    // ---------- Synthetic data ----------
    static String randomUniform(int len, Random rng) {
        char[] s = new char[len];
        for (int k = 0; k < len; k++) s[k] = (char) ('A' + rng.nextInt(26));
        return new String(s);
    }

    // Letters drawn from the English frequency distribution.
    static String randomEnglish(int len, Random rng) {
        double[] cdf = new double[26];
        double total = 0;
        for (int c = 0; c < 26; c++) { total += ENGLISH_FREQ[c]; cdf[c] = total; }
        char[] s = new char[len];
        for (int k = 0; k < len; k++) {
            double u = rng.nextDouble() * total;
            int c = 0;
            while (c < 25 && cdf[c] <= u) c++;
            s[k] = (char) ('A' + c);
        }
        return new String(s);
    }

    // Copy a random block of X (length plantLen) into Y at a random position,
    // mutating each copied letter with probability mutRate. Returns {posX, posY}.
    static int[] plant(String X, char[] Y, int plantLen, double mutRate, Random rng) {
        int px = rng.nextInt(X.length() - plantLen + 1);
        int py = rng.nextInt(Y.length - plantLen + 1);
        for (int k = 0; k < plantLen; k++) {
            char c = X.charAt(px + k);
            if (rng.nextDouble() < mutRate) {
                char d;
                do d = (char) ('A' + rng.nextInt(26)); while (d == c);
                c = d;
            }
            Y[py + k] = c;
        }
        return new int[] {px, py};
    }

    // ---------- Modes ----------
    static void printMatch(String X, String Y, Match r) {
        if (r.len == 0) { System.out.println("  (empty input)"); return; }
        System.out.printf("  score=%.4f  len=%d  mismatches=%d%n", r.score, r.len, r.mismatches);
        System.out.printf("  X[%d..%d] = %s%n", r.startX + 1, r.startX + r.len,
                X.substring(r.startX, r.startX + r.len));
        System.out.printf("  Y[%d..%d] = %s%n", r.startY + 1, r.startY + r.len,
                Y.substring(r.startY, r.startY + r.len));
    }

    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.printf("[%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (!ok) fails++;
    }

    static int runTests() {
        String X = "ABCAABCAA", Y = "ABBCAACCBBBBBB";
        // Assignment example: CAABC vs CAACC must score wC+wA+wA-delta+wC.
        {
            Weights W = scenario1(0.5);  // small penalty so the mismatch pays off
            Match r = solveFull(X, Y, W);
            System.out.println("Example (w=1, delta=0.5):");
            printMatch(X, Y, r);
            Match ex = new Match();
            ex.startX = 2; ex.startY = 3; ex.len = 5;
            check(scoreOf(X, Y, ex, W) == 4 - 0.5, "example CAABC/CAACC scores 4 - delta");
            check(r.score >= 3.5, "optimum at least as good as the example");
            check(r.score == bruteForce(X, Y, W), "example matches brute force");
        }
        {
            Match r = solveFull(X, Y, scenario1());
            System.out.println("Example (scenario 1, w=1, delta=10):");
            printMatch(X, Y, r);
            check(r.score == 4 && r.mismatches == 0, "scenario 1 example: best is exact BCAA (score 4)");
        }
        {
            Weights W = scenario2(1.0);
            Match r = solveFull(X, Y, W);
            System.out.println("Example (scenario 2, delta=1.0):");
            printMatch(X, Y, r);
            check(r.score == bruteForce(X, Y, W), "scenario 2 example matches brute force");
        }
        // No common letter at all: best is a single mismatch (-delta).
        {
            Match r = solveFull("AAAA", "BBB", scenario1(10));
            check(r.score == -10 && r.len == 1, "disjoint alphabets -> single mismatch, score -delta");
        }
        // Identical strings: whole string, score n.
        {
            Match r = solveFull("HELLOWORLD", "HELLOWORLD", scenario1(10));
            check(r.score == 10 && r.len == 10, "identical strings -> full match");
        }
        check(!validInput("abc") && validInput("ABC"), "input validation rejects lowercase");

        // Randomised cross-check against brute force and the linear-space variant.
        Random rng = new Random(12345);
        int randFails = 0, trials = 3000;
        for (int t = 0; t < trials; t++) {
            int n = 1 + rng.nextInt(30), m = 1 + rng.nextInt(30);
            int alpha = 2 + rng.nextInt(5);  // small alphabet => many matches
            char[] a = new char[n], b = new char[m];
            for (int k = 0; k < n; k++) a[k] = (char) ('A' + rng.nextInt(alpha));
            for (int k = 0; k < m; k++) b[k] = (char) ('A' + rng.nextInt(alpha));
            String A = new String(a), B = new String(b);
            Weights W = (t % 2 == 1) ? scenario1(rng.nextInt(40) / 4.0)
                                     : scenario2(0.07 + rng.nextInt(1300) / 100.0);
            Match f = solveFull(A, B, W), l = solveLinear(A, B, W);
            double bf = bruteForce(A, B, W);
            boolean ok = Math.abs(f.score - bf) < 1e-9 && Math.abs(l.score - bf) < 1e-9
                    && Math.abs(scoreOf(A, B, f, W) - f.score) < 1e-9
                    && Math.abs(scoreOf(A, B, l, W) - l.score) < 1e-9
                    && f.startX == l.startX && f.startY == l.startY && f.len == l.len;
            if (!ok) randFails++;
        }
        check(randFails == 0, trials + " random instances: full DP == linear DP == brute force, extraction re-scores");

        System.out.println(fails > 0 ? "\n" + fails + " test(s) FAILED" : "\nAll tests passed");
        return fails > 0 ? 1 : 0;
    }

    interface Job { Match run(); }

    // Best of `reps` timed runs, in milliseconds; result stored in out[0].
    static double timeIt(Job job, int reps, Match[] out) {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            out[0] = job.run();
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        return best;
    }

    // Runtime and DP memory vs. input size, for both scenarios and both variants.
    static void runBench(String path) throws FileNotFoundException {
        PrintWriter f = new PrintWriter(path);
        f.println("scenario,n,m,variant,ms,dp_bytes,score,len,mismatches");
        Random rng = new Random(2026);

        // JIT warm-up so the first measured size is not interpreted code.
        String wa = randomEnglish(1500, rng), wb = randomEnglish(1500, rng);
        for (int k = 0; k < 5; k++) {
            solveFull(wa, wb, scenario2(5));
            solveLinear(wa, wb, scenario2(5));
        }

        int[][] sizes = {{250, 250}, {500, 500}, {1000, 1000}, {1500, 1500}, {2000, 2000},
                         {3000, 3000}, {4000, 4000}, {1000, 4000}, {500, 8000}};
        for (int[] sz : sizes) {
            int n = sz[0], m = sz[1];
            for (int scen = 1; scen <= 2; scen++) {
                String X = scen == 1 ? randomUniform(n, rng) : randomEnglish(n, rng);
                String Y = scen == 1 ? randomUniform(m, rng) : randomEnglish(m, rng);
                Weights W = scen == 1 ? scenario1() : scenario2(scenario2Deltas()[4]);
                int reps = (long) n * m > 4_000_000 ? 3 : 5;
                for (int v = 0; v < 2; v++) {
                    final boolean full = v == 0;
                    Match[] out = new Match[1];
                    double ms = timeIt(() -> full ? solveFull(X, Y, W) : solveLinear(X, Y, W), reps, out);
                    Match r = out[0];
                    String name = full ? "full" : "linear";
                    f.printf("%d,%d,%d,%s,%.4f,%d,%.4f,%d,%d%n", scen, n, m, name, ms,
                            r.dpBytes, r.score, r.len, r.mismatches);
                    System.out.printf("scen %d  %5d x %5d  %-6s %9.2f ms  %10d B  score %.3f len %d mm %d%n",
                            scen, n, m, name, ms, r.dpBytes, r.score, r.len, r.mismatches);
                }
            }
        }
        f.close();
    }

    static int overlap(Match r, int px, int py, int plantLen) {
        if (r.startX - r.startY != px - py) return 0;
        int lo = Math.max(r.startX, px), hi = Math.min(r.startX + r.len, px + plantLen);
        return Math.max(0, hi - lo);
    }

    // Scenario 2: 10 penalties between min and max weight (plus the two
    // endpoints), averaged over seeds. Two data sets: purely random
    // English-like text, and text with a planted 200-letter block copied with
    // 15% mutations (a "true" approximate match).
    static void runSweep(String path) throws FileNotFoundException {
        PrintWriter f = new PrintWriter(path);
        f.println("data,delta,seed,score,len,mismatches,ms,overlap_planted");
        final int n = 2000, m = 2000, plantLen = 200, seeds = 10;
        double[] mid = scenario2Deltas();
        double[] deltas = new double[12];
        deltas[0] = minWeight();
        System.arraycopy(mid, 0, deltas, 1, 10);
        deltas[11] = maxWeight();
        for (int data = 0; data < 2; data++) {
            for (int s = 0; s < seeds; s++) {
                Random rng = new Random(100 + s);
                String X = randomEnglish(n, rng);
                char[] y = randomEnglish(m, rng).toCharArray();
                int[] pos = {-1, -1};
                if (data == 1) pos = plant(X, y, plantLen, 0.15, rng);
                String Y = new String(y);
                for (double d : deltas) {
                    Weights W = scenario2(d);
                    Match[] out = new Match[1];
                    double ms = timeIt(() -> solveFull(X, Y, W), 1, out);
                    Match r = out[0];
                    int ov = data == 1 ? overlap(r, pos[0], pos[1], plantLen) : 0;
                    f.printf("%s,%.4f,%d,%.4f,%d,%d,%.3f,%d%n", data == 1 ? "planted" : "random",
                            d, s, r.score, r.len, r.mismatches, ms, ov);
                }
            }
        }
        // Scenario 1 on the same planted data for comparison.
        for (int s = 0; s < seeds; s++) {
            Random rng = new Random(100 + s);
            String X = randomEnglish(n, rng);
            char[] y = randomEnglish(m, rng).toCharArray();
            int[] pos = plant(X, y, plantLen, 0.15, rng);
            Match r = solveFull(X, new String(y), scenario1());
            f.printf("planted_s1,%.4f,%d,%.4f,%d,%d,0,%d%n", 10.0, s, r.score, r.len, r.mismatches,
                    overlap(r, pos[0], pos[1], plantLen));
        }
        f.close();
        System.out.println("wrote " + path);
    }

    // Scenario 1 (w = 1) for several mismatch penalties besides the
    // assignment's delta = 10, to show how the penalty changes the result.
    // Same set-up as runSweep but with uniformly random letters.
    static final double[] S1_DELTAS = {1, 2, 3, 4, 5, 6, 8, 10};

    static void runSweep1(String path) throws FileNotFoundException {
        PrintWriter f = new PrintWriter(path);
        f.println("data,delta,seed,score,len,mismatches,ms,overlap_planted");
        final int n = 2000, m = 2000, plantLen = 200, seeds = 10;
        for (int data = 0; data < 2; data++) {
            for (int s = 0; s < seeds; s++) {
                Random rng = new Random(300 + s);
                String X = randomUniform(n, rng);
                char[] y = randomUniform(m, rng).toCharArray();
                int[] pos = {-1, -1};
                if (data == 1) pos = plant(X, y, plantLen, 0.15, rng);
                String Y = new String(y);
                for (double d : S1_DELTAS) {
                    Weights W = scenario1(d);
                    Match[] out = new Match[1];
                    double ms = timeIt(() -> solveFull(X, Y, W), 1, out);
                    Match r = out[0];
                    int ov = data == 1 ? overlap(r, pos[0], pos[1], plantLen) : 0;
                    f.printf("%s,%.4f,%d,%.4f,%d,%d,%.3f,%d%n", data == 1 ? "planted" : "random",
                            d, s, r.score, r.len, r.mismatches, ms, ov);
                }
            }
        }
        f.close();
        System.out.println("wrote " + path);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java Substring test | solve X Y [s1|s2] [delta] | bench out.csv | sweep out.csv | sweep1 out.csv");
            System.exit(2);
        }
        switch (args[0]) {
            case "test":
                System.exit(runTests());
                break;
            case "bench":
                runBench(args[1]);
                break;
            case "sweep":
                runSweep(args[1]);
                break;
            case "sweep1":
                runSweep1(args[1]);
                break;
            case "solve": {
                String X = args[1], Y = args[2];
                if (!validInput(X) || !validInput(Y)) {
                    System.err.println("inputs must contain only the letters A-Z");
                    System.exit(2);
                }
                String scen = args.length >= 4 ? args[3] : "s1";
                double delta = args.length >= 5 ? Double.parseDouble(args[4])
                        : (scen.equals("s1") ? 10.0 : scenario2Deltas()[4]);
                Weights W = scen.equals("s1") ? scenario1(delta) : scenario2(delta);
                System.out.printf("scenario %s, delta=%.4f%n", scen, delta);
                printMatch(X, Y, solveFull(X, Y, W));
                break;
            }
            default:
                System.err.println("bad arguments");
                System.exit(2);
        }
    }
}
