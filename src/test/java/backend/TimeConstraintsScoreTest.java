package backend;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.*;
import java.util.function.BiConsumer;

public class TimeConstraintsScoreTest {

    private GraphTaskNode node(int id, int dur) {
        Task t = new Task("T" + id, id, dur, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    private void link(GraphTaskNode p, GraphTaskNode c) {
        p.addChildren(c);
        c.addParent(p);
    }

    private TimeConstraints build(List<GraphTaskNode> t) {
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        for (GraphTaskNode x : t) map.put(x.getId(), x);
        return new TimeConstraints(new LinkedList<>(t), map);
    }

    private int getMaxCP(List<GraphTaskNode> all) {
        int m = 0;
        for (GraphTaskNode x : all) {
            m = Math.max(m, x.getCriticalPaths());
        }
        return m;
    }

    private double scoreFormula(GraphTaskNode n, int totalTime, double maxCritical, double maxBlock) {
        return 0.5 * (n.getCriticalPaths() / maxCritical) +
                0.25 * (n.getBlockingPower() / maxBlock) +
                0.25 * (n.getReachableDuration() / (double) totalTime);
    }

    // ==========================================================
    // TEST 1 – SMALL STRUCTURED GRAPH
    // ==========================================================

    @Test
    public void score_smallGraph_depthWidthBalance() {

        // מבנה:
        //      A(5)
        //    /     \
        //  B(3)     C(4)
        //   |       |
        //  D(2)     E(6)
        //           |
        //          F(1)

        GraphTaskNode A = node(0, 5);
        GraphTaskNode B = node(1, 3);
        GraphTaskNode C = node(2, 4);
        GraphTaskNode D = node(3, 2);
        GraphTaskNode E = node(4, 6);
        GraphTaskNode F = node(5, 1);

        link(A, B);
        link(A, C);
        link(B, D);
        link(C, E);
        link(E, F);

        List<GraphTaskNode> all = List.of(A, B, C, D, E, F);
        TimeConstraints tc = build(all);

        // מילוי כל השדות הנדרשים:
        tc.criticalPaths();
        tc.reachableDuration();
        HashSet<Integer> visitedBP = new HashSet<>();
        double maxBlock = tc.blockPower(visitedBP);

        int totalTime = 0;
        for (GraphTaskNode n : all) {
            totalTime += n.getDuration();
        }

        int maxCritical = getMaxCP(all);

        HashSet<Integer> visitedScore = new HashSet<>();
        tc.scoreSet(totalTime, maxCritical, maxBlock, visitedScore);

        for (GraphTaskNode n : all) {
            double expected = scoreFormula(n, totalTime, maxCritical, maxBlock);
            assertEquals(expected, n.getScore(), 1e-9);
            assertTrue(n.getScore() >= 0.0);
            assertTrue(n.getScore() <= 1.0);
        }
    }

    // ==========================================================
    // TEST 2 – MID COMPLEX GRAPH (20 NODES)
    // ==========================================================

    @Test
    public void score_midGraph_complexHierarchy() {

        // בניית גרף 20 קודקודים עם היררכיה מורכבת:
        // ROOT1: 0→1→2→3→4→5
        // extra: 2→10→11→12  , 3→13→14
        // ROOT2: 6→7→8
        // deep:  7→15→16→17→18→19
        // 9 isolated

        List<GraphTaskNode> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(node(i, (i % 3) + 1));
        }

        // ROOT1 chain
        link(all.get(0), all.get(1));
        link(all.get(1), all.get(2));
        link(all.get(2), all.get(3));
        link(all.get(3), all.get(4));
        link(all.get(4), all.get(5));

        // extra depth
        link(all.get(2), all.get(10));
        link(all.get(10), all.get(11));
        link(all.get(11), all.get(12));

        link(all.get(3), all.get(13));
        link(all.get(13), all.get(14));

        // ROOT2 area
        link(all.get(6), all.get(7));
        link(all.get(7), all.get(8));

        // deep right branch
        link(all.get(7), all.get(15));
        link(all.get(15), all.get(16));
        link(all.get(16), all.get(17));
        link(all.get(17), all.get(18));
        link(all.get(18), all.get(19));

        TimeConstraints tc = build(all);

        tc.criticalPaths();
        tc.reachableDuration();
        HashSet<Integer> visitedBP = new HashSet<>();
        double maxBlock = tc.blockPower(visitedBP);

        int totalTime = 0;
        for (GraphTaskNode n : all) {
            totalTime += n.getDuration();
        }

        int maxCritical = getMaxCP(all);

        HashSet<Integer> visitedScore = new HashSet<>();
        tc.scoreSet(totalTime, maxCritical, maxBlock, visitedScore);

        // בדיקה מתמטית מדויקת מול הנוסחה + טווח
        for (GraphTaskNode n : all) {
            double expected = scoreFormula(n, totalTime, maxCritical, maxBlock);
            assertEquals(expected, n.getScore(), 1e-8);
            assertTrue(n.getScore() >= 0.0);
            assertTrue(n.getScore() <= 1.0);
        }
    }

    // ==========================================================
    // TEST 3 – BIG 30 NODE GRAPH (הגרף הידני הגדול)
    // ==========================================================

    @Test
    public void score_big30Graph_fullLogic() {

        List<GraphTaskNode> all = new ArrayList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 30; i++) {
            GraphTaskNode n = new GraphTaskNode(
                    new Task("t" + i, i, ((i % 4) + 1), new LinkedList<>())
            );
            all.add(n);
            map.put(i, n);
        }

        BiConsumer<Integer, Integer> L = (a, b) -> {
            GraphTaskNode p = all.get(a);
            GraphTaskNode c = all.get(b);
            p.addChildren(c);
            c.addParent(p);
            c.addParentRemain(p);
        };

        // === HEAD LAYER ===
        // heads: 0,1,2,3

        // === LAYER 1 ===
        L.accept(0, 4);
        L.accept(0, 5);
        L.accept(1, 6);
        L.accept(1, 7);
        L.accept(2, 8);
        L.accept(3, 9);
        L.accept(3, 10);

        // === LAYER 2 ===
        L.accept(4, 11);
        L.accept(4, 12);
        L.accept(5, 12);
        L.accept(5, 13);
        L.accept(6, 13);
        L.accept(6, 14);
        L.accept(7, 15);
        L.accept(8, 15);
        L.accept(8, 16);
        L.accept(9, 17);
        L.accept(10, 17);
        L.accept(10, 18);

        // === LAYER 3 ===
        L.accept(11, 19);
        L.accept(12, 19);
        L.accept(12, 20);
        L.accept(13, 20);
        L.accept(13, 21);
        L.accept(14, 22);
        L.accept(15, 22);
        L.accept(16, 23);
        L.accept(17, 23);
        L.accept(17, 24);
        L.accept(18, 24);

        // === LAYER 4 ===
        L.accept(19, 25);
        L.accept(20, 25);
        L.accept(20, 26);
        L.accept(21, 26);
        L.accept(22, 27);
        L.accept(23, 27);
        L.accept(23, 28);
        L.accept(24, 28);

        // === FINAL MERGE ===
        L.accept(25, 29);
        L.accept(26, 29);
        L.accept(27, 29);
        L.accept(28, 29);

        TimeConstraints tc = new TimeConstraints(new LinkedList<>(all), map);

        tc.criticalPaths();
        tc.reachableDuration();

        HashSet<Integer> visitedBP = new HashSet<>();
        double maxBlock = tc.blockPower(visitedBP);

        int totalTime = 0;
        for (GraphTaskNode n : all) {
            totalTime += n.getDuration();
        }

        int maxCritical = getMaxCP(all);

        HashSet<Integer> visitedScore = new HashSet<>();
        tc.scoreSet(totalTime, maxCritical, maxBlock, visitedScore);

        for (GraphTaskNode n : all) {
            double expected = scoreFormula(n, totalTime, maxCritical, maxBlock);
            assertEquals(expected, n.getScore(), 1e-7);
            assertTrue(n.getScore() >= 0.0);
            assertTrue(n.getScore() <= 1.0);
        }

        tc.criticalPaths();
        tc.reachableDuration();
        tc.blockPower(new HashSet<>());
        tc.initial(map); // ממלא את כל השדות הנדרשים

        System.out.println("===== NODE DATA DUMP =====");
        for (GraphTaskNode n : all) {
            System.out.println(
                    "ID=" + n.getId() +
                            "  DUR=" + n.getDuration() +
                            "  CP=" + n.getCriticalPaths() +
                            "  RD=" + n.getReachableDuration() +
                            "  BP=" + n.getBlockingPower()
            );
        }
        System.out.println("===== END =====");
    }

}