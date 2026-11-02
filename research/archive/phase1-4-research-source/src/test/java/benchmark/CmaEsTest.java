package benchmark;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CmaEsTest {

    static double sphere(double[] x) {
        double s = 0;
        for (double v : x) s += (v - 0.3) * (v - 0.3);
        return s;
    }

    static double rosenbrock(double[] x) {
        double s = 0;
        for (int i = 0; i + 1 < x.length; i++) {
            s += 100 * Math.pow(x[i + 1] - x[i] * x[i], 2) + Math.pow(1 - x[i], 2);
        }
        return s;
    }

    static double[] optimize(java.util.function.ToDoubleFunction<double[]> f, int n, int generations, long seed) {
        CmaEs es = new CmaEs(new double[n], 0.5, 4 + (int) (3 * Math.log(n)), seed);
        for (int g = 0; g < generations && es.stopReason(1e-12) == null; g++) {
            double[][] pop = es.ask();
            double[] fit = new double[pop.length];
            for (int k = 0; k < pop.length; k++) fit[k] = f.applyAsDouble(pop[k]);
            es.tell(fit);
        }
        return es.mean();
    }

    @Test
    public void convergesOnSphere() {
        double[] m = optimize(CmaEsTest::sphere, 9, 400, 1);
        for (double v : m) assertEquals(0.3, v, 1e-4);
    }

    @Test
    public void convergesOnRosenbrock() {
        double[] m = optimize(CmaEsTest::rosenbrock, 5, 3000, 2);
        for (double v : m) assertEquals(1.0, v, 1e-3);
    }

    @Test
    public void isDeterministicPerSeed() {
        assertArrayEquals(optimize(CmaEsTest::rosenbrock, 4, 50, 7), optimize(CmaEsTest::rosenbrock, 4, 50, 7), 0);
        assertFalse(java.util.Arrays.equals(optimize(CmaEsTest::rosenbrock, 4, 50, 7), optimize(CmaEsTest::rosenbrock, 4, 50, 8)));
    }

    @Test
    public void replayingRecordedFitnessReproducesTheTrajectory() {
        // run 30 generations recording every population and fitness
        CmaEs a = new CmaEs(new double[]{0.5, 0.5, 0.5}, 0.3, 10, 42);
        List<double[][]> pops = new ArrayList<>();
        List<double[]> fits = new ArrayList<>();
        for (int g = 0; g < 30; g++) {
            double[][] pop = a.ask();
            double[] fit = new double[pop.length];
            for (int k = 0; k < pop.length; k++) fit[k] = rosenbrock(pop[k]);
            pops.add(pop);
            fits.add(fit);
            a.tell(fit);
        }
        // "resume": a fresh instance with the same seed, told the recorded values for the first 20 generations
        CmaEs b = new CmaEs(new double[]{0.5, 0.5, 0.5}, 0.3, 10, 42);
        for (int g = 0; g < 30; g++) {
            double[][] pop = b.ask();
            for (int k = 0; k < pop.length; k++) assertArrayEquals(pops.get(g)[k], pop[k], 0);
            if (g < 20) {
                b.tell(fits.get(g));
            } else {
                double[] fit = new double[pop.length];
                for (int k = 0; k < pop.length; k++) fit[k] = rosenbrock(pop[k]);
                b.tell(fit);
            }
        }
        assertArrayEquals(a.mean(), b.mean(), 0);
        assertEquals(a.sigma(), b.sigma(), 0);
    }

    @Test
    public void jacobi_diagonalizesSymmetricMatrices() {
        double[][] m = {{4, 1, 2}, {1, 3, 0.5}, {2, 0.5, 5}};
        double[][] a = {m[0].clone(), m[1].clone(), m[2].clone()};
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        CmaEs.jacobi(a, v);
        // m v_i = lambda_i v_i
        for (int i = 0; i < 3; i++) {
            for (int r = 0; r < 3; r++) {
                double mv = 0;
                for (int c = 0; c < 3; c++) mv += m[r][c] * v[c][i];
                assertEquals(a[i][i] * v[r][i], mv, 1e-9);
            }
        }
    }
}
