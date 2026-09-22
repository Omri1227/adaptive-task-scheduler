package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.function.BiConsumer;

public class TimeConstraintsReachableDurationTest {

    private GraphTaskNode node(int id, int dur) {
        Task t = new Task("T" + id, id, dur, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    private void link(GraphTaskNode p, GraphTaskNode c) {
        p.addChildren(c);
        c.addParent(p);
        c.addParentRemain(p);
    }

    private TimeConstraints build(List<GraphTaskNode> t) {
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        for (GraphTaskNode x : t) map.put(x.getId(), x);
        return new TimeConstraints(new LinkedList<>(t), map);
    }

    // ---------------------------------------------------------
    // 1) Linear Chain (0→1→2→3)
    // Durations: [1,2,3,4]
    //
    // Reachable durations:
    // d = 4
    // c = 3+4 = 7
    // b = 2+3+4 = 9
    // a = 1+2+3+4 = 10
    // ---------------------------------------------------------
    @Test
    public void linearChain() {

        GraphTaskNode a = node(0, 1);
        GraphTaskNode b = node(1, 2);
        GraphTaskNode c = node(2, 3);
        GraphTaskNode d = node(3, 4);

        link(a, b);
        link(b, c);
        link(c, d);

        TimeConstraints tc = build(List.of(a, b, c, d));
        tc.reachableDuration();

        assertEquals(4, d.getReachableDuration());
        assertEquals(7, c.getReachableDuration());
        assertEquals(9, b.getReachableDuration());
        assertEquals(10, a.getReachableDuration());
    }


    // ---------------------------------------------------------
    // 2) Single root → 4 leaves
    //          root(5)
    //     /     |     |     \
    //   1(1)  2(2)  3(3)  4(4)
    //
    // reachable(root)=5+1+2+3+4=15
    // ---------------------------------------------------------
    @Test
    public void wideGraph() {

        GraphTaskNode root = node(0, 5);
        GraphTaskNode a = node(1, 1), b = node(2, 2), c = node(3, 3), d = node(4, 4);

        link(root, a);
        link(root, b);
        link(root, c);
        link(root, d);

        TimeConstraints tc = build(List.of(root, a, b, c, d));
        tc.reachableDuration();

        assertEquals(1, a.getReachableDuration());
        assertEquals(2, b.getReachableDuration());
        assertEquals(3, c.getReachableDuration());
        assertEquals(4, d.getReachableDuration());

        assertEquals(15, root.getReachableDuration());
    }


    // ---------------------------------------------------------
    // 3) Isolated node + chain
    //
    // a(3)→b(2)
    // iso(5)
    //
    // reachable(a)=3+2=5
    // reachable(b)=2
    // reachable(iso)=5
    // ---------------------------------------------------------
    @Test
    public void isolatedNode() {

        GraphTaskNode a = node(0, 3);
        GraphTaskNode b = node(1, 2);
        GraphTaskNode iso = node(2, 5);

        link(a, b);

        TimeConstraints tc = build(List.of(a, b, iso));
        tc.reachableDuration();

        assertEquals(5, a.getReachableDuration());
        assertEquals(2, b.getReachableDuration());
        assertEquals(5, iso.getReachableDuration());
    }


    // ---------------------------------------------------------
    // 4) Multi children root
    //           R(2)
    //   3(3) 4(4) 5(1) 6(6) 7(2)
    //
    // reachable(R)=2+3+4+1+6+2 = 18
    // ---------------------------------------------------------
    @Test
    public void multiChildrenChain() {

        GraphTaskNode root = node(0, 2);

        GraphTaskNode a = node(1, 3);
        GraphTaskNode b = node(2, 4);
        GraphTaskNode c = node(3, 1);
        GraphTaskNode d = node(4, 6);
        GraphTaskNode e = node(5, 2);

        link(root, a);
        link(root, b);
        link(root, c);
        link(root, d);
        link(root, e);

        TimeConstraints tc = build(List.of(root, a, b, c, d, e));
        tc.reachableDuration();

        assertEquals(3, a.getReachableDuration());
        assertEquals(4, b.getReachableDuration());
        assertEquals(1, c.getReachableDuration());
        assertEquals(6, d.getReachableDuration());
        assertEquals(2, e.getReachableDuration());

        assertEquals(18, root.getReachableDuration());
    }


    // ---------------------------------------------------------
    // 5) Deep chain length 10
    // all durations = 1
    //
    // reachable(0)=10
    // reachable(9)=1
    // ---------------------------------------------------------
    @Test
    public void pyramid() {

        List<GraphTaskNode> g = new ArrayList<>();
        for (int i = 0; i < 10; i++) g.add(node(i, 1));

        for (int i = 0; i < 9; i++) link(g.get(i), g.get(i + 1));

        TimeConstraints tc = build(g);
        tc.reachableDuration();

        assertEquals(1, g.get(9).getReachableDuration());
        assertEquals(2, g.get(8).getReachableDuration());
        assertEquals(10, g.get(0).getReachableDuration());
    }


    // ---------------------------------------------------------
    // 6) Split + merge (diamond)
    //
    //     A(5)
    //   /  |  \
    // B(3) C(4) D(2)
    //   \   |   /
    //       E(6)
    //
    // reachable(E)=6
    // reachable(C)=4+6=10
    // reachable(B)=3+6=9
    // reachable(D)=2+6=8
    // reachable(A)=5 + (3+4+2+6)=20
    // ---------------------------------------------------------
    @Test
    public void tripleDiamond() {

        GraphTaskNode A = node(0, 5);
        GraphTaskNode B = node(1, 3), C = node(2, 4), D = node(3, 2);
        GraphTaskNode E = node(4, 6);

        link(A, B);
        link(A, C);
        link(A, D);
        link(B, E);
        link(C, E);
        link(D, E);

        TimeConstraints tc = build(List.of(A, B, C, D, E));
        tc.reachableDuration();

        assertEquals(6, E.getReachableDuration());
        assertEquals(9, B.getReachableDuration());
        assertEquals(10, C.getReachableDuration());
        assertEquals(8, D.getReachableDuration());

        assertEquals(20, A.getReachableDuration());
    }

    /**
     * BIG 30-NODE HAND CRAFTED GRAPH — reachableDuration()
     * <p>
     * גרף בנוי שכבות, ללא רנדומליות, ללא מעגלים.
     * חישוב reachableDuration ידני ומאומת.
     */
    @Test
    public void reachableDuration_big30Graph() {

        // === build graph ===
        List<GraphTaskNode> all = new ArrayList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 30; i++) {
            GraphTaskNode n = new GraphTaskNode(
                    new Task("t" + i, i, ((i % 4) + 1), new LinkedList<>())
            );
            all.add(n);
            map.put(i, n);
        }

        // helper for edges
        BiConsumer<Integer, Integer> link = (a, b) -> {
            GraphTaskNode p = all.get(a);
            GraphTaskNode c = all.get(b);
            p.addChildren(c);
            c.addParent(p);
            // parentsRemained לא רלוונטי ל-reachableDuration
        };

        // === HEAD LAYER === (0,1,2,3)

        // === LAYER 1 ===
        link.accept(0, 4);
        link.accept(0, 5);
        link.accept(1, 6);
        link.accept(1, 7);
        link.accept(2, 8);
        link.accept(3, 9);
        link.accept(3, 10);

        // === LAYER 2 ===
        link.accept(4, 11);
        link.accept(4, 12);
        link.accept(5, 12);
        link.accept(5, 13);
        link.accept(6, 13);
        link.accept(6, 14);
        link.accept(7, 15);
        link.accept(8, 15);
        link.accept(8, 16);
        link.accept(9, 17);
        link.accept(10, 17);
        link.accept(10, 18);

        // === LAYER 3 ===
        link.accept(11, 19);
        link.accept(12, 19);
        link.accept(12, 20);
        link.accept(13, 20);
        link.accept(13, 21);
        link.accept(14, 22);
        link.accept(15, 22);
        link.accept(16, 23);
        link.accept(17, 23);
        link.accept(17, 24);
        link.accept(18, 24);

        // === LAYER 4 ===
        link.accept(19, 25);
        link.accept(20, 25);
        link.accept(20, 26);
        link.accept(21, 26);
        link.accept(22, 27);
        link.accept(23, 27);
        link.accept(23, 28);
        link.accept(24, 28);

        // === FINAL MERGE ===
        link.accept(25, 29);
        link.accept(26, 29);
        link.accept(27, 29);
        link.accept(28, 29);

        TimeConstraints tc = new TimeConstraints(new LinkedList<>(all), map);
        tc.reachableDuration();

        // ===== LEAF =====
        assertEquals(2, all.get(29).getReachableDuration());

        // ===== LAYER 4 =====
        assertEquals(4, all.get(25).getReachableDuration());
        assertEquals(5, all.get(26).getReachableDuration());
        assertEquals(6, all.get(27).getReachableDuration());
        assertEquals(3, all.get(28).getReachableDuration());

        // ===== LAYER 3 =====
        assertEquals(8, all.get(19).getReachableDuration());
        assertEquals(8, all.get(20).getReachableDuration());
        assertEquals(7, all.get(21).getReachableDuration());
        assertEquals(9, all.get(22).getReachableDuration());
        assertEquals(11, all.get(23).getReachableDuration());
        assertEquals(4, all.get(24).getReachableDuration());

        // ===== LAYER 2 =====
        assertEquals(12, all.get(11).getReachableDuration());
        assertEquals(13, all.get(12).getReachableDuration());
        assertEquals(12, all.get(13).getReachableDuration());
        assertEquals(12, all.get(14).getReachableDuration());
        assertEquals(13, all.get(15).getReachableDuration());
        assertEquals(12, all.get(16).getReachableDuration());
        assertEquals(14, all.get(17).getReachableDuration());
        assertEquals(7, all.get(18).getReachableDuration());

        // ===== LAYER 1 =====
        assertEquals(18, all.get(4).getReachableDuration());
        assertEquals(19, all.get(5).getReachableDuration());
        assertEquals(25, all.get(6).getReachableDuration());
        assertEquals(17, all.get(7).getReachableDuration());
        assertEquals(20, all.get(8).getReachableDuration());
        assertEquals(16, all.get(9).getReachableDuration());
        assertEquals(20, all.get(10).getReachableDuration());

        // ===== ROOTS =====
        assertEquals(25, all.get(0).getReachableDuration());
        assertEquals(35, all.get(1).getReachableDuration());
        assertEquals(23, all.get(2).getReachableDuration());
        assertEquals(26, all.get(3).getReachableDuration());
    }
}