package backend;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.*;

public class TimeConstraintsInitialTest {

    // יצירת קודקוד גרף מתוך Task
    private GraphTaskNode node(int id, int dur) {
        Task t = new Task("T" + id, id, dur, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    // חיבור קשת p -> c
    private void link(GraphTaskNode p, GraphTaskNode c) {
        p.addChildren(c);
        c.addParent(p);
    }

    // בניית אובייקט TimeConstraints לפי ה-API האמיתי שלך
    private TimeConstraints build(List<GraphTaskNode> nodes) {
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        for (GraphTaskNode n : nodes) {
            map.put(n.getId(), n);
        }
        return new TimeConstraints(new LinkedList<>(nodes), map);
    }

    // פונקציה עזר: בדיקה שטופולוגיקל סורט באמת מכבד את כל הקשתות
    private void assertTopoOrderRespectsEdges(List<GraphTaskNode> topo) {
        Map<GraphTaskNode, Integer> index = new HashMap<>();
        for (int i = 0; i < topo.size(); i++) {
            index.put(topo.get(i), i);
        }
        for (GraphTaskNode u : topo) {
            int iu = index.get(u);
            for (GraphTaskNode v : u.getChildren()) {
                int iv = index.get(v);
                assertTrue("Topo order invalid: parent after child",
                        iu < iv);
            }
        }
    }

    // ======================================================
    // = TEST 1 – 5 נודים, גרף קטן עם דיאמונד + סינק יחיד =
    // ======================================================
    /**
     * גרף:
     *
     *      0
     *    /   \
     *   1     2
     *    \   /
     *      3
     *      |
     *      4
     *
     * 0 – שורש יחיד
     * 4 – עלה יחיד (סינק)
     */
    @Test
    public void initial_smallFiveGraph() {
        GraphTaskNode n0 = node(0, 4);
        GraphTaskNode n1 = node(1, 2);
        GraphTaskNode n2 = node(2, 3);
        GraphTaskNode n3 = node(3, 2);
        GraphTaskNode n4 = node(4, 1);

        link(n0, n1);
        link(n0, n2);
        link(n1, n3);
        link(n2, n3);
        link(n3, n4);

        List<GraphTaskNode> all = List.of(n0, n1, n2, n3, n4);

        TimeConstraints tc = build(all);

        // בדיקת ה-constructor: ראשי הגרף והטופולוגיה
        LinkedList<GraphTaskNode> heads = tc.getHeads();
        assertEquals(1, heads.size());
        assertTrue(heads.contains(n0));

        List<GraphTaskNode> topo = tc.getTopologicalSort();
        assertEquals(5, topo.size());
        assertTopoOrderRespectsEdges(topo);

        // עכשיו initial – צריך לדרוס CP, RD, BP, score
        HashMap<Integer, GraphTaskNode> idMap = new HashMap<>();
        for (GraphTaskNode n : all) idMap.put(n.getId(), n);

        tc.initial(idMap);

        // כל צומת צריך לקבל score בטווח [0,1]
        for (GraphTaskNode n : all) {
            double s = n.getScore();
            assertTrue("Score negative", s >= 0.0);
            assertTrue("Score > 1", s <= 1.0);
        }

        // במקרה של הגרף הזה – השורש 0 חזק משמעותית מהילדים
        double s0 = n0.getScore();
        assertTrue(s0 > n1.getScore());
        assertTrue(s0 > n2.getScore());
        assertTrue(s0 > n3.getScore());
        assertTrue(s0 > n4.getScore());
    }

    // ==========================================================
    // = TEST 2 – 10 נודים, 2 שורשים, מיזוגים, סינק יחיד      =
    // ==========================================================
    /**
     * גרף:
     *
     *  0       1
     *  | \   /  \
     *  2  3     4
     *   \  \   /
     *     5   6
     *     |   |
     *     7   8
     *      \ /
     *       9    (סינק)
     *
     * שורשים: 0,1
     * סינק יחיד: 9
     */
    @Test
    public void initial_mediumTenGraph() {
        GraphTaskNode n0 = node(0, 4);
        GraphTaskNode n1 = node(1, 3);
        GraphTaskNode n2 = node(2, 2);
        GraphTaskNode n3 = node(3, 3);
        GraphTaskNode n4 = node(4, 2);
        GraphTaskNode n5 = node(5, 3);
        GraphTaskNode n6 = node(6, 3);
        GraphTaskNode n7 = node(7, 1);
        GraphTaskNode n8 = node(8, 2);
        GraphTaskNode n9 = node(9, 4); // leaf

        link(n0, n2);
        link(n0, n3);
        link(n1, n3);
        link(n1, n4);
        link(n2, n5);
        link(n3, n5);
        link(n4, n6);
        link(n5, n7);
        link(n6, n8);
        link(n7, n9);
        link(n8, n9);

        List<GraphTaskNode> all = List.of(n0,n1,n2,n3,n4,n5,n6,n7,n8,n9);

        TimeConstraints tc = build(all);

        // בדיקה: שורשים
        LinkedList<GraphTaskNode> heads = tc.getHeads();
        assertEquals(2, heads.size());
        assertTrue(heads.contains(n0));
        assertTrue(heads.contains(n1));

        // בדיקת טופולוגיקל סורט
        List<GraphTaskNode> topo = tc.getTopologicalSort();
        assertEquals(10, topo.size());
        assertTopoOrderRespectsEdges(topo);

        HashMap<Integer, GraphTaskNode> idMap = new HashMap<>();
        for (GraphTaskNode n : all) idMap.put(n.getId(), n);

        tc.initial(idMap);

        // כל צומת – score ∈ [0,1]
        for (GraphTaskNode n : all) {
            double s = n.getScore();
            assertTrue(s >= 0.0);
            assertTrue(s <= 1.0);
        }

        // סינק יחיד 9 – אמור להיות בעל הסקור הנמוך ביותר בגרף
        double sLeaf = n9.getScore();
        for (GraphTaskNode n : all) {
            if (n != n9) {
                assertTrue("Sink 9 must have smallest score", sLeaf <= n.getScore());
            }
        }
    }

    // ==========================================================
    // = TEST 3 – 15 נודים, 3 שורשים, עומק + מיזוגים מורכבים  =
    // ==========================================================
    /**
     * שורשים: 0,1,2
     * סינק יחיד: 14
     *
     * 0 → 3 → 6 → 9  → 12 → 14
     *      ↘     ↘
     *        7     13 → 14
     *
     * 1 → 4 → 7 → 10 → 13
     *      ↘         ↗
     *        8 → 11 ↗
     *
     * 2 → 5 → 8
     */
    @Test
    public void initial_bigFifteenGraph() {
        GraphTaskNode[] n = new GraphTaskNode[15];
        int[] dur = {4,5,3,2,3,1,2,2,3,1,3,2,2,4,5};
        for (int i = 0; i < 15; i++) {
            n[i] = node(i, dur[i]);
        }

        // קשתות לפי התרשים
        link(n[0], n[3]);
        link(n[3], n[6]);
        link(n[6], n[9]);
        link(n[9], n[12]);
        link(n[12], n[14]);

        link(n[3], n[7]);
        link(n[7], n[10]);
        link(n[10], n[13]);
        link(n[13], n[14]);

        link(n[1], n[4]);
        link(n[4], n[7]);
        link(n[4], n[8]);
        link(n[8], n[11]);
        link(n[11], n[13]);

        link(n[2], n[5]);
        link(n[5], n[8]);

        List<GraphTaskNode> all = Arrays.asList(n);

        TimeConstraints tc = build(all);

        // שורשים אמורים להיות 0,1,2 (אין להם הורים)
        LinkedList<GraphTaskNode> heads = tc.getHeads();
        assertEquals(3, heads.size());
        assertTrue(heads.contains(n[0]));
        assertTrue(heads.contains(n[1]));
        assertTrue(heads.contains(n[2]));

        // טופולוגיקל סורט
        List<GraphTaskNode> topo = tc.getTopologicalSort();
        assertEquals(15, topo.size());
        assertTopoOrderRespectsEdges(topo);

        HashMap<Integer, GraphTaskNode> idMap = new HashMap<>();
        for (GraphTaskNode g : all) idMap.put(g.getId(), g);

        tc.initial(idMap);

        // כל score ∈ [0,1]
        for (GraphTaskNode g : all) {
            double s = g.getScore();
            assertTrue(s >= 0.0);
            assertTrue(s <= 1.0);
        }

        // 14 הוא הסינק היחיד – מצופה שיהיה בעל הסקור הנמוך ביותר
        double s14 = n[14].getScore();
        for (GraphTaskNode g : all) {
            if (g != n[14]) {
                assertTrue("Sink 14 must have smallest score", s14 <= g.getScore());
            }
        }
    }

}