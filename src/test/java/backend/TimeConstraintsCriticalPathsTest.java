package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.function.BiConsumer;

public class TimeConstraintsCriticalPathsTest {

    private GraphTaskNode node(int id, int dur) {
        Task t = new Task("T"+id, id, dur, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    private void link(GraphTaskNode p, GraphTaskNode c) {
        p.addChildren(c);
        c.addParent(p);
    }

    private TimeConstraints build(List<GraphTaskNode> t) {
        HashMap<Integer,GraphTaskNode> map = new HashMap<>();
        for (GraphTaskNode x : t) map.put(x.getId(), x);
        return new TimeConstraints(new LinkedList<>(t), map);
    }

    // ==================================================================
    // ==================== UPDATED TESTS FROM BEFORE ====================
    // ==================================================================

    // ================================
    // 1) LINEAR CHAIN SIMPLE
    // ================================
    @Test
    public void linearChain() {

        GraphTaskNode a=node(0,1);
        GraphTaskNode b=node(1,2);
        GraphTaskNode c=node(2,3);
        GraphTaskNode d=node(3,4);

        link(a,b);
        link(b,c);
        link(c,d);

        TimeConstraints tc = build(List.of(a,b,c,d));
        tc.criticalPaths();

        assertEquals(4, d.getCriticalPaths());
        assertEquals(7, c.getCriticalPaths());
        assertEquals(9, b.getCriticalPaths());
        assertEquals(10, a.getCriticalPaths());
    }
    // ================================
    // 2) VERY WIDE SHALLOW GRAPH
    // ================================
    @Test
    public void wideGraph() {
        GraphTaskNode root=node(0,5);

        GraphTaskNode a=node(1,1);
        GraphTaskNode b=node(2,2);
        GraphTaskNode c=node(3,3);
        GraphTaskNode d=node(4,4);

        link(root,a);
        link(root,b);
        link(root,c);
        link(root,d);

        TimeConstraints tc = build(List.of(root,a,b,c,d));
        tc.criticalPaths();

        assertEquals(1,a.getCriticalPaths());
        assertEquals(2,b.getCriticalPaths());
        assertEquals(3,c.getCriticalPaths());
        assertEquals(4,d.getCriticalPaths());

        // root = 5 + max(1..4) → 9
        assertEquals(9,root.getCriticalPaths());
    }

    // ================================
    // 3) GRAPH WITH ISOLATED NODE
    // ================================
    @Test
    public void isolatedNode() {
        GraphTaskNode a=node(0,3);
        GraphTaskNode b=node(1,2);
        GraphTaskNode iso=node(2,5);

        link(a,b);

        TimeConstraints tc = build(List.of(a,b,iso));
        tc.criticalPaths();

        assertEquals(2,b.getCriticalPaths());
        assertEquals(5,iso.getCriticalPaths()); // isolated → cp=duration
        assertEquals(3+2,a.getCriticalPaths());
    }

    // ================================
    // 4) MANY CHILDREN ON SAME LEVEL
    // ================================
    @Test
    public void multiChildrenChain() {

        GraphTaskNode root=node(0,2);

        GraphTaskNode a=node(1,3);
        GraphTaskNode b=node(2,4);
        GraphTaskNode c=node(3,1);
        GraphTaskNode d=node(4,6);
        GraphTaskNode e=node(5,2);

        link(root,a);
        link(root,b);
        link(root,c);
        link(root,d);
        link(root,e);

        TimeConstraints tc = build(List.of(root,a,b,c,d,e));
        tc.criticalPaths();

        assertEquals(3,a.getCriticalPaths());
        assertEquals(4,b.getCriticalPaths());
        assertEquals(1,c.getCriticalPaths());
        assertEquals(6,d.getCriticalPaths());
        assertEquals(2,e.getCriticalPaths());

        // root = 2 + max(children)
        assertEquals(8,root.getCriticalPaths());
    }

    // ================================
    // 5) REVERSE PYRAMID SHAPE
    // Each node points to 1 child downward
    // ================================
    @Test
    public void pyramid() {
        List<GraphTaskNode> g=new ArrayList<>();
        for(int i=0;i<10;i++) g.add(node(i,1));

        for(int i=0;i<9;i++) link(g.get(i),g.get(i+1));

        TimeConstraints tc = build(g);
        tc.criticalPaths();

        assertEquals(1,g.get(9).getCriticalPaths());
        assertEquals(2,g.get(8).getCriticalPaths());
        assertEquals(10,g.get(0).getCriticalPaths());
    }

    // ================================
    // 6) MULTIPLE UNCONNECTED ROOTS
    // ================================
    @Test
    public void multipleRootsIndependent() {

        // root 1 chain
        GraphTaskNode a=node(0,2);
        GraphTaskNode b=node(1,4);
        link(a,b);

        // root 2 chain
        GraphTaskNode c=node(2,3);
        GraphTaskNode d=node(3,1);
        link(c,d);

        TimeConstraints tc = build(List.of(a,b,c,d));
        tc.criticalPaths();

        assertEquals(4,b.getCriticalPaths());
        assertEquals(2+4,a.getCriticalPaths());

        assertEquals(1,d.getCriticalPaths());
        assertEquals(3+1,c.getCriticalPaths());
    }


    // ================================
    // 7) 200 NODE PERFORMANCE TEST
    // ================================
    @Test(timeout = 2000)
    public void bigGraph200() {

        List<GraphTaskNode> list=new ArrayList<>();
        for(int i=0;i<200;i++)
            list.add(node(i,1+(i%5)));

        // long chain connections
        for(int i=0;i<199;i++)
            link(list.get(i),list.get(i+1));

        TimeConstraints tc=build(list);
        tc.criticalPaths(); // if O(n+e) → trivial runtime

        assertEquals((1+(199%5)), list.get(199).getCriticalPaths()); // leaf

        // root = sum durations
        int sum=0;
        for(int i=0;i<200;i++)
            sum += (1+(i%5));

        assertEquals(sum,list.get(0).getCriticalPaths());
    }
    /**
     * FULL MESH SIMPLE — LINEAR FIXED VERSION
     *
     * 0→1→2→3→4→5
     */
    @Test
    public void fullMeshSmall_fixed() {

        GraphTaskNode a=node(0,1), b=node(1,1), c=node(2,1),
                d=node(3,1), e=node(4,1), f=node(5,1);

        link(a,b);
        link(b,c);
        link(c,d);
        link(d,e);
        link(e,f);

        TimeConstraints tc = build(List.of(a,b,c,d,e,f));
        tc.criticalPaths();

        assertEquals(1, f.getCriticalPaths());
        assertEquals(2, e.getCriticalPaths());
        assertEquals(3, d.getCriticalPaths());
        assertEquals(4, c.getCriticalPaths());
        assertEquals(5, b.getCriticalPaths());
        assertEquals(6, a.getCriticalPaths());
    }


    /**
     * RANDOM MIXED COMPLEX — FIXED
     *
     *      A
     *    /   \
     *   B     C
     *   |     |
     *   D     E
     *         |
     *         F
     */
    @Test
    public void randomMixedComplexSet_fixed() {

        GraphTaskNode A=node(0,2), B=node(1,3), C=node(2,6),
                D=node(3,1), E=node(4,4), F=node(5,5);

        link(A,B);
        link(A,C);
        link(B,D);
        link(C,E);
        link(E,F);

        TimeConstraints tc = build(List.of(A,B,C,D,E,F));
        tc.criticalPaths();

        assertEquals(5, F.getCriticalPaths());
        assertEquals(9, E.getCriticalPaths());
        assertEquals(15, C.getCriticalPaths());
        assertEquals(4, B.getCriticalPaths());
        assertEquals(17, A.getCriticalPaths());
    }


    // ==================================================================
    // ====================== 10 NEW HEAVY TESTS ========================
    // ==================================================================

    /**
     * TEST #1: MULTI SIBLINGS
     *
     * A→B
     * A→C
     * A→D
     * B,C,D leafs
     */
    @Test
    public void multiSiblings() {

        GraphTaskNode A=node(0,3);
        GraphTaskNode B=node(1,4), C=node(2,7), D=node(3,2);

        link(A,B); link(A,C); link(A,D);

        TimeConstraints tc = build(List.of(A,B,C,D));
        tc.criticalPaths();

        assertEquals(4, B.getCriticalPaths());
        assertEquals(7, C.getCriticalPaths());
        assertEquals(2, D.getCriticalPaths());
        assertEquals(10, A.getCriticalPaths());
    }


    /**
     * TEST #2 – 6 DEPTH VARIABLE WEIGHTS
     *
     * 0→1→2→3→4→5
     */
    @Test
    public void longWeightedLine() {

        GraphTaskNode[] nn = new GraphTaskNode[6];
        int[] dur = {3,4,2,5,7,9};

        for(int i=0;i<6;i++)
            nn[i]=node(i,dur[i]);

        for(int i=0;i<5;i++)
            link(nn[i],nn[i+1]);

        TimeConstraints tc = build(Arrays.asList(nn));
        tc.criticalPaths();

        assertEquals(9, nn[5].getCriticalPaths());
        assertEquals(16, nn[4].getCriticalPaths());
        assertEquals(21, nn[3].getCriticalPaths());
        assertEquals(23, nn[2].getCriticalPaths());
        assertEquals(27, nn[1].getCriticalPaths());
        assertEquals(30, nn[0].getCriticalPaths());
    }


    /**
     * TEST #3 – SPLIT + MERGE
     *
     *     A
     *   / | \
     *  B  C  D
     *   \ | /
     *     E
     */
    @Test
    public void tripleDiamond() {

        GraphTaskNode A=node(0,5);
        GraphTaskNode B=node(1,3),C=node(2,4),D=node(3,2);
        GraphTaskNode E=node(4,6);

        link(A,B); link(A,C); link(A,D);
        link(B,E); link(C,E); link(D,E);

        TimeConstraints tc = build(List.of(A,B,C,D,E));
        tc.criticalPaths();

        assertEquals(6, E.getCriticalPaths());
        assertEquals(9, B.getCriticalPaths());
        assertEquals(10, C.getCriticalPaths());
        assertEquals(8, D.getCriticalPaths());
        assertEquals(15, A.getCriticalPaths());
    }


    /**
     * TEST #4 – TREE OF DEPTH 3 WITH MIXED COSTS
     *
     *      R
     *    / | \
     *   A  B  C
     *  / \    \
     * D  E     F
     */
    @Test
    public void tripleTree() {

        GraphTaskNode R=node(0,4);
        GraphTaskNode A=node(1,3),B=node(2,6),C=node(3,1);
        GraphTaskNode D=node(4,2),E=node(5,7),F=node(6,5);

        link(R,A);
        link(R,B);
        link(R,C);

        link(A,D);
        link(A,E);

        link(C,F);

        TimeConstraints tc = build(List.of(R,A,B,C,D,E,F));
        tc.criticalPaths();

        assertEquals(2, D.getCriticalPaths());
        assertEquals(7, E.getCriticalPaths());
        assertEquals(5, F.getCriticalPaths());

        assertEquals(10, A.getCriticalPaths());   // 3+7
        assertEquals(6, B.getCriticalPaths());
        assertEquals(6, C.getCriticalPaths());    // 1+5

        assertEquals(14, R.getCriticalPaths());   // 4 + max(10,6,6)
    }


    /**
     * TEST #5 – WIDE BASE + DEEP BRANCH
     *
     * A connects to 10 children, last chain continues 10 more
     */
    @Test
    public void wideAndDeep() {

        GraphTaskNode A=node(0,2);

        List<GraphTaskNode> all = new ArrayList<>();
        all.add(A);

        for(int i=1;i<=10;i++){
            GraphTaskNode c=node(i,1);
            all.add(c);
            link(A,c);
        }

        for(int i=11;i<=20;i++){
            GraphTaskNode n=node(i,1);
            all.add(n);
            link(all.get(i-1),n);
        }

        TimeConstraints tc = build(all);
        tc.criticalPaths();

        assertEquals(1, all.get(20).getCriticalPaths());
        assertEquals(11, all.get(10).getCriticalPaths());
        assertEquals(13, A.getCriticalPaths());
    }


    /**
     * TEST #6 – MERGING MULTIPATH 15 NODES
     */
    @Test
    public void merge15() {

        List<GraphTaskNode> set=new ArrayList<>();
        for(int i=0;i<15;i++)
            set.add(node(i,1));

        link(set.get(0),set.get(5));
        link(set.get(1),set.get(5));
        link(set.get(2),set.get(6));
        link(set.get(3),set.get(6));
        link(set.get(4),set.get(7));

        link(set.get(5),set.get(10));
        link(set.get(6),set.get(10));
        link(set.get(7),set.get(10));

        link(set.get(10),set.get(12));
        link(set.get(10),set.get(13));
        link(set.get(13),set.get(14));

        TimeConstraints tc=build(set);
        tc.criticalPaths();

        assertEquals(1, set.get(14).getCriticalPaths());
        assertEquals(3, set.get(10).getCriticalPaths());
        assertEquals(4, set.get(5).getCriticalPaths());
    }


    /**
     * TEST #7 – MULTI LAYER 20 DEPTH TREE
     */
    @Test
    public void multi20depth() {

        GraphTaskNode[] arr=new GraphTaskNode[20];
        for(int i=0;i<20;i++)
            arr[i]=node(i,1);

        for(int i=0;i<19;i++)
            link(arr[i],arr[i+1]);

        TimeConstraints tc = build(Arrays.asList(arr));
        tc.criticalPaths();

        assertEquals(20, arr[0].getCriticalPaths());
    }

    @Test
    public void criticalPaths_big30Graph() {

        // === build graph ===
        List<GraphTaskNode> all = new ArrayList<>();
        HashMap<Integer,GraphTaskNode> map = new HashMap<>();

        for(int i = 0 ; i < 30 ; i++){
            GraphTaskNode n = new GraphTaskNode(new Task("t"+i,i,((i%4)+1),new LinkedList<>()));
            all.add(n);
            map.put(i,n);
        }

        // helper lambda:
        for (int x=0;x<1;x++){} // fix formatting
        BiConsumer<Integer,Integer> link = new BiConsumer<Integer, Integer>() {
            @Override
            public void accept(Integer a, Integer b) {
                GraphTaskNode parent = all.get(a);
                GraphTaskNode child  = all.get(b);

                parent.addChildren(child);
                child.addParent(parent);
                child.addParentRemain(parent);
            }
        };
        // === HEAD LAYER ===
        // heads: 0,1,2,3

        // === LAYER 1 ===
        link.accept(0,4); link.accept(0,5);
        link.accept(1,6); link.accept(1,7);
        link.accept(2,8);
        link.accept(3,9); link.accept(3,10);

        // === LAYER 2 ===
        link.accept(4,11); link.accept(4,12);
        link.accept(5,12); link.accept(5,13);
        link.accept(6,13); link.accept(6,14);
        link.accept(7,15);
        link.accept(8,15); link.accept(8,16);
        link.accept(9,17);
        link.accept(10,17); link.accept(10,18);

        // === LAYER 3 ===
        link.accept(11,19);
        link.accept(12,19); link.accept(12,20);
        link.accept(13,20); link.accept(13,21);
        link.accept(14,22);
        link.accept(15,22);
        link.accept(16,23);
        link.accept(17,23); link.accept(17,24);
        link.accept(18,24);

        // === LAYER 4 ===
        link.accept(19,25);
        link.accept(20,25); link.accept(20,26);
        link.accept(21,26);
        link.accept(22,27);
        link.accept(23,27); link.accept(23,28);
        link.accept(24,28);

        // === FINAL MERGE ===
        link.accept(25,29);
        link.accept(26,29);
        link.accept(27,29);
        link.accept(28,29);

        // === RUN ===
        TimeConstraints tc = new TimeConstraints(new LinkedList<>(all), map);
        tc.criticalPaths();

        // === ASSERT CRITICAL PATHS ===
        assertEquals(2, all.get(29).getCriticalPaths());   // leaf

        assertEquals(4, all.get(25).getCriticalPaths());
        assertEquals(5, all.get(26).getCriticalPaths());
        assertEquals(6, all.get(27).getCriticalPaths());
        assertEquals(3, all.get(28).getCriticalPaths());

        assertEquals(8,  all.get(19).getCriticalPaths());
        assertEquals(6,  all.get(20).getCriticalPaths());
        assertEquals(7,  all.get(21).getCriticalPaths());
        assertEquals(9,  all.get(22).getCriticalPaths());
        assertEquals(10, all.get(23).getCriticalPaths());
        assertEquals(4,  all.get(24).getCriticalPaths());

        assertEquals(12, all.get(11).getCriticalPaths());
        assertEquals(9,  all.get(12).getCriticalPaths());
        assertEquals(9,  all.get(13).getCriticalPaths());
        assertEquals(12, all.get(14).getCriticalPaths());
        assertEquals(13, all.get(15).getCriticalPaths());
        assertEquals(11, all.get(16).getCriticalPaths());
        assertEquals(12, all.get(17).getCriticalPaths());
        assertEquals(7,  all.get(18).getCriticalPaths());

        assertEquals(13, all.get(4).getCriticalPaths());
        assertEquals(11, all.get(5).getCriticalPaths());
        assertEquals(15, all.get(6).getCriticalPaths());
        assertEquals(17, all.get(7).getCriticalPaths());
        assertEquals(14, all.get(8).getCriticalPaths());
        assertEquals(14, all.get(9).getCriticalPaths());
        assertEquals(15, all.get(10).getCriticalPaths());

        assertEquals(14, all.get(0).getCriticalPaths());
        assertEquals(19, all.get(1).getCriticalPaths());
        assertEquals(17, all.get(2).getCriticalPaths());
        assertEquals(19, all.get(3).getCriticalPaths());
    }

}