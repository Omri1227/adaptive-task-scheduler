package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class TimeConstraintsTaskCompletedTest {

    private GraphTaskNode node(int id, int dur) {
        Task t = new Task("T"+id, id, dur, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    private void link(GraphTaskNode p, GraphTaskNode c) {
        p.addChildren(c);
        c.addParent(p);
        c.addParentRemain(p);
    }

    private TimeConstraints build(List<GraphTaskNode> t) {
        HashMap<Integer,GraphTaskNode> map = new HashMap<>();
        for (GraphTaskNode x : t) map.put(x.getId(), x);
        return new TimeConstraints(new LinkedList<>(t), map);
    }


    // =======================================================================
    // ===================== TEST #1 — LINEAR CHAIN ==========================
    // =======================================================================

    @Test
    public void taskCompleted_linearChain() {

        GraphTaskNode A = node(0,1);
        GraphTaskNode B = node(1,1);
        GraphTaskNode C = node(2,1);

        link(A,B);
        link(B,C);

        // A is root:
        A.getParentsRemained().clear();

        TimeConstraints tc = build(List.of(A,B,C));

        LinkedList<GraphTaskNode> ready = tc.taskCompleted(A);

        assertTrue(A.isCompleted());
        assertEquals(1, ready.size());
        assertEquals(B, ready.get(0));

        ready = tc.taskCompleted(B);

        assertTrue(B.isCompleted());
        assertEquals(1, ready.size());
        assertEquals(C, ready.get(0));
    }


    // =======================================================================
    // ========== TEST #2 — ONLY ONE OUT OF TWO CHILDREN READY ===============
    // =======================================================================

    /**
     * A→B
     * A→C→D
     * כאשר A מסתיים:
     * B מוכן, C לא (כי יש לו ילד C→D)
     */
    @Test
    public void taskCompleted_partialReady() {

        GraphTaskNode A=node(0,1), B=node(1,1), C=node(2,1), D=node(3,1);

        link(A,B);
        link(A,C);
        link(C,D);

        A.getParentsRemained().clear(); // root

        TimeConstraints tc = build(List.of(A,B,C,D));

        LinkedList<GraphTaskNode> ready = tc.taskCompleted(A);

        assertEquals(2, ready.size());
        assertTrue(ready.contains(B));
        assertTrue(ready.contains(C));
    }


    // =======================================================================
    // ===== TEST #3 — MULTI-PARENTS REQUIRED BOTH TO FINISH ================
    // =======================================================================

    /**
     *  A → C ← B
     * C יהיה מוכן רק אחרי ששני ההורים הסתיימו.
     */
    @Test
    public void taskCompleted_multiParents() {

        GraphTaskNode A=node(0,1), B=node(1,1), C=node(2,1);

        link(A,C);
        link(B,C);

        A.getParentsRemained().clear();
        B.getParentsRemained().clear();

        TimeConstraints tc = build(List.of(A,B,C));

        LinkedList<GraphTaskNode> ready = tc.taskCompleted(A);

        assertEquals(0, ready.size());

        ready = tc.taskCompleted(B);

        assertEquals(1, ready.size());
        assertEquals(C, ready.get(0));
    }


    // =======================================================================
    // ====================== TEST #4 — SINGLE LEAF ==========================
    // =======================================================================

    @Test
    public void taskCompleted_leaf() {

        GraphTaskNode A=node(0,1);

        A.getParentsRemained().clear();

        TimeConstraints tc = build(List.of(A));

        LinkedList<GraphTaskNode> ready = tc.taskCompleted(A);

        assertTrue(A.isCompleted());
        assertTrue(ready.isEmpty());
    }


    // =======================================================================
    // ============= TEST #5 — TWO CHILDREN BOTH READY ======================
    // =======================================================================

    /**
     *     A
     *   /   \
     *  B     C
     *  |     |
     *  D     E
     *
     * כאשר A מסתיים:
     * B,C מוכנים. D,E לא.
     */
    @Test
    public void taskCompleted_twoChildrenReady() {

        GraphTaskNode A=node(0,1), B=node(1,1), C=node(2,1),
                D=node(3,1), E=node(4,1);

        link(A,B);
        link(A,C);
        link(B,D);
        link(C,E);

        A.getParentsRemained().clear();

        TimeConstraints tc = build(List.of(A,B,C,D,E));

        LinkedList<GraphTaskNode> ready = tc.taskCompleted(A);

        assertEquals(2, ready.size());
        assertTrue(ready.contains(B));
        assertTrue(ready.contains(C));

        assertFalse(ready.contains(D));
        assertFalse(ready.contains(E));
    }

}