package benchmark;

import java.util.Arrays;
import java.util.Random;

/**
 * Deterministic (mu/mu_w, lambda)-CMA-ES with cumulative step-size adaptation,
 * rank-one and rank-mu covariance updates (Hansen, "The CMA Evolution
 * Strategy: A Tutorial", 2016, default parameters).
 *
 * Usage: {@code ask()} returns the population of a generation, {@code tell()}
 * receives its fitness values (lower is better). The sequence of populations is
 * a pure function of (initial mean, sigma, population size, seed) and the
 * fitness values told, so replaying recorded fitness values reproduces a run
 * exactly; this is how interrupted runs are resumed.
 */
public final class CmaEs {

    private final int n;
    private final int lambda;
    private final int mu;
    private final double[] weights;
    private final double mueff;
    private final double cc, cs, c1, cmu, damps, chiN;
    private final Random random;

    private double[] mean;
    private double sigma;
    private final double[][] c;
    private final double[][] b;
    private final double[] d;
    private final double[] pc;
    private final double[] ps;
    private int generation;
    private int evaluations;
    private int eigenGeneration = -1;

    private double[][] population;
    private double[][] steps;   // y = (x - mean) / sigma of the current population
    private double bestFitness = Double.POSITIVE_INFINITY;
    private double[] bestX;

    public CmaEs(double[] initialMean, double initialSigma, int populationSize, long seed) {
        this.n = initialMean.length;
        if (n < 1) throw new IllegalArgumentException("dimension must be positive");
        this.lambda = populationSize;
        this.mu = lambda / 2;
        this.random = new Random(seed);
        double[] w = new double[mu];
        double sum = 0;
        for (int i = 0; i < mu; i++) {
            w[i] = Math.log(mu + 0.5) - Math.log(i + 1);
            sum += w[i];
        }
        double sumSq = 0;
        for (int i = 0; i < mu; i++) {
            w[i] /= sum;
            sumSq += w[i] * w[i];
        }
        this.weights = w;
        this.mueff = 1 / sumSq;
        this.cc = (4 + mueff / n) / (n + 4 + 2 * mueff / n);
        this.cs = (mueff + 2) / (n + mueff + 5);
        this.c1 = 2 / ((n + 1.3) * (n + 1.3) + mueff);
        this.cmu = Math.min(1 - c1, 2 * (mueff - 2 + 1 / mueff) / ((n + 2) * (n + 2) + mueff));
        this.damps = 1 + 2 * Math.max(0, Math.sqrt((mueff - 1) / (n + 1)) - 1) + cs;
        this.chiN = Math.sqrt(n) * (1 - 1.0 / (4 * n) + 1.0 / (21.0 * n * n));
        this.mean = initialMean.clone();
        this.sigma = initialSigma;
        this.c = identity(n);
        this.b = identity(n);
        this.d = new double[n];
        Arrays.fill(d, 1);
        this.pc = new double[n];
        this.ps = new double[n];
    }

    /** The next population (lambda points). Calling ask twice without tell returns the same population. */
    public double[][] ask() {
        if (population != null) return copy(population);
        updateEigensystem();
        population = new double[lambda][n];
        steps = new double[lambda][n];
        for (int k = 0; k < lambda; k++) {
            double[] z = new double[n];
            for (int i = 0; i < n; i++) z[i] = random.nextGaussian();
            for (int i = 0; i < n; i++) {
                double y = 0;
                for (int j = 0; j < n; j++) y += b[i][j] * d[j] * z[j];
                steps[k][i] = y;
                population[k][i] = mean[i] + sigma * y;
            }
        }
        return copy(population);
    }

    /** Fitness of the population returned by the last ask(), in the same order. */
    public void tell(double[] fitness) {
        if (population == null) throw new IllegalStateException("tell() without ask()");
        if (fitness.length != lambda) throw new IllegalArgumentException("expected " + lambda + " fitness values");
        Integer[] order = new Integer[lambda];
        for (int k = 0; k < lambda; k++) order[k] = k;
        // stable sort: equal fitness keeps sampling order (deterministic)
        Arrays.sort(order, (a, bb) -> Double.compare(fitness[a], fitness[bb]));
        if (fitness[order[0]] < bestFitness) {
            bestFitness = fitness[order[0]];
            bestX = population[order[0]].clone();
        }

        double[] yw = new double[n];
        for (int r = 0; r < mu; r++) {
            for (int i = 0; i < n; i++) yw[i] += weights[r] * steps[order[r]][i];
        }
        for (int i = 0; i < n; i++) mean[i] += sigma * yw[i];

        // C^(-1/2) yw = B D^-1 B^T yw
        double[] tmp = new double[n];
        for (int j = 0; j < n; j++) {
            double s = 0;
            for (int i = 0; i < n; i++) s += b[i][j] * yw[i];
            tmp[j] = s / d[j];
        }
        double psNorm = 0;
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += b[i][j] * tmp[j];
            ps[i] = (1 - cs) * ps[i] + Math.sqrt(cs * (2 - cs) * mueff) * s;
            psNorm += ps[i] * ps[i];
        }
        psNorm = Math.sqrt(psNorm);
        boolean hsig = psNorm / Math.sqrt(1 - Math.pow(1 - cs, 2.0 * (generation + 1))) / chiN < 1.4 + 2.0 / (n + 1);
        for (int i = 0; i < n; i++) {
            pc[i] = (1 - cc) * pc[i] + (hsig ? Math.sqrt(cc * (2 - cc) * mueff) * yw[i] : 0);
        }
        double deltaH = hsig ? 0 : cc * (2 - cc);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double rankMu = 0;
                for (int r = 0; r < mu; r++) rankMu += weights[r] * steps[order[r]][i] * steps[order[r]][j];
                double v = (1 - c1 - cmu) * c[i][j] + c1 * (pc[i] * pc[j] + deltaH * c[i][j]) + cmu * rankMu;
                c[i][j] = v;
                c[j][i] = v;
            }
        }
        sigma *= Math.exp((cs / damps) * (psNorm / chiN - 1));
        evaluations += lambda;
        generation++;
        population = null;
        steps = null;
    }

    public int generation() {
        return generation;
    }

    public int evaluations() {
        return evaluations;
    }

    public int populationSize() {
        return lambda;
    }

    public double[] mean() {
        return mean.clone();
    }

    public double sigma() {
        return sigma;
    }

    public double bestFitness() {
        return bestFitness;
    }

    public double[] bestX() {
        return bestX == null ? null : bestX.clone();
    }

    /** Largest standard deviation of the search distribution along a coordinate. */
    public double maxStandardDeviation() {
        double m = 0;
        for (int i = 0; i < n; i++) m = Math.max(m, sigma * Math.sqrt(c[i][i]));
        return m;
    }

    /** Condition number of C (ratio of largest to smallest eigenvalue). */
    public double conditionNumber() {
        updateEigensystem();
        double max = 0, min = Double.POSITIVE_INFINITY;
        for (double x : d) {
            max = Math.max(max, x * x);
            min = Math.min(min, x * x);
        }
        return max / min;
    }

    /** Standard termination tests: TolX, a degenerate distribution, or a numerically ill-conditioned C. */
    public String stopReason(double tolX) {
        if (maxStandardDeviation() < tolX) return "tolX";
        if (!(sigma > 0) || Double.isNaN(sigma)) return "sigma";
        if (conditionNumber() > 1e14) return "conditionCov";
        return null;
    }

    private void updateEigensystem() {
        if (eigenGeneration == generation) return;
        double[][] a = copy(c);
        double[][] v = identity(n);
        jacobi(a, v);
        for (int i = 0; i < n; i++) {
            d[i] = Math.sqrt(Math.max(a[i][i], 1e-300));
            for (int j = 0; j < n; j++) b[j][i] = v[j][i];
        }
        eigenGeneration = generation;
    }

    /** Cyclic Jacobi eigenvalue iteration; a becomes diagonal, columns of v the eigenvectors. */
    static void jacobi(double[][] a, double[][] v) {
        int n = a.length;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) off += a[i][j] * a[i][j];
            if (off < 1e-30) return;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double cos = 1 / Math.sqrt(t * t + 1);
                    double sin = t * cos;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = cos * akp - sin * akq;
                        a[k][q] = sin * akp + cos * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = cos * apk - sin * aqk;
                        a[q][k] = sin * apk + cos * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = cos * vkp - sin * vkq;
                        v[k][q] = sin * vkp + cos * vkq;
                    }
                }
            }
        }
    }

    private static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = 1;
        return m;
    }

    private static double[][] copy(double[][] m) {
        double[][] r = new double[m.length][];
        for (int i = 0; i < m.length; i++) r[i] = m[i].clone();
        return r;
    }
}
