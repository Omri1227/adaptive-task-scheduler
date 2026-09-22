package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.function.BiConsumer;

public class TimeConstraintsBlockPowerTest {

    private GraphTaskNode node(int id, int dur) {
        Task t = new Task("T"+id, id, dur, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    private void link(GraphTaskNode p, GraphTaskNode c) {
        p.addChildren(c);
        c.addParent(p);
    }

    private TimeConstraints build(List<GraphTaskNode> t) {
        HashMap<Integer,GraphTaskNode> map=new HashMap<>();
        for(GraphTaskNode x : t) map.put(x.getId(),x);
        return new TimeConstraints(new LinkedList<>(t),map);
    }

    private double run(TimeConstraints tc){
        HashSet<Integer> v=new HashSet<>();
        return tc.blockPower(v);
    }

    // =============================================================================
    // === TEST 1: LINEAR CHAIN (0→1→2→3) =========================================
    // =============================================================================
    @Test
    public void block_linearChain() {

        GraphTaskNode a=node(0,2);
        GraphTaskNode b=node(1,3);
        GraphTaskNode c=node(2,4);
        GraphTaskNode d=node(3,5);

        link(a,b);
        link(b,c);
        link(c,d);

        TimeConstraints tc = build(List.of(a,b,c,d));
        double max = run(tc);

        // children have 1 parent
        assertEquals(1.0, a.getBlockingPower(),0.0001);
        assertEquals(1.0, b.getBlockingPower(),0.0001);
        assertEquals(1.0, c.getBlockingPower(),0.0001);
        assertEquals(0.0, d.getBlockingPower(),0.0001);

        assertEquals(1.0,max,0.0001);
    }

    // =============================================================================
    // === TEST 2: WIDE GRAPH (root→4 children) ====================================
    // =============================================================================
    @Test
    public void block_wideGraph() {

        GraphTaskNode root=node(0,5);
        GraphTaskNode a=node(1,1);
        GraphTaskNode b=node(2,1);
        GraphTaskNode c=node(3,1);
        GraphTaskNode d=node(4,1);

        link(root,a);
        link(root,b);
        link(root,c);
        link(root,d);

        TimeConstraints tc = build(List.of(root,a,b,c,d));
        double max = run(tc);

        assertEquals(4.0, root.getBlockingPower(),0.0001);
        assertEquals(0.0, a.getBlockingPower(),0.0001);

        assertEquals(4.0,max,0.0001);
    }

    // =============================================================================
    // === TEST 3: ISOLATED NODE ===================================================
    // =============================================================================
    @Test
    public void block_isolated() {

        GraphTaskNode a=node(0,3);
        GraphTaskNode b=node(1,3);
        GraphTaskNode iso=node(2,3);

        link(a,b);

        TimeConstraints tc = build(List.of(a,b,iso));
        double max = run(tc);

        assertEquals(1.0, a.getBlockingPower(),0.0001);
        assertEquals(0.0, b.getBlockingPower(),0.0001);
        assertEquals(0.0, iso.getBlockingPower(),0.0001);

        assertEquals(1.0,max,0.0001);
    }

    // =============================================================================
    // === TEST 4: MULTI CHILDREN ==================================================
    // =============================================================================
    @Test
    public void block_multiChildren() {

        GraphTaskNode root=node(0,2);

        GraphTaskNode a=node(1,3);
        GraphTaskNode b=node(2,3);
        GraphTaskNode c=node(3,3);

        link(root,a);
        link(root,b);
        link(root,c);

        TimeConstraints tc = build(List.of(root,a,b,c));
        double max = run(tc);

        assertEquals(3.0,root.getBlockingPower(),0.0001);
        assertEquals(0.0,a.getBlockingPower(),0.0001);

        assertEquals(3.0,max,0.0001);
    }

    // =============================================================================
    // === TEST 5: MULTIPLE ROOTS ==================================================
    // =============================================================================
    @Test
    public void block_multipleRoots() {

        GraphTaskNode A=node(0,2);
        GraphTaskNode B=node(1,2);

        GraphTaskNode C=node(2,6);
        GraphTaskNode D=node(3,2);

        link(A,C);
        link(B,D);

        TimeConstraints tc = build(List.of(A,B,C,D));
        double max = run(tc);

        assertEquals(1.0,A.getBlockingPower(),0.0001);
        assertEquals(1.0,B.getBlockingPower(),0.0001);

        assertEquals(0.0,C.getBlockingPower(),0.0001);
        assertEquals(0.0,D.getBlockingPower(),0.0001);

        assertEquals(1.0,max,0.0001);
    }

    // =============================================================================
    // === TEST 6: SPLIT–MERGE =====================================================
    //
    //      A
    //    / | \
    //   B  C  D
    //    \ | /
    //      E
    //
    // =============================================================================
    @Test
    public void block_tripleMerge() {

        GraphTaskNode A=node(0,5);
        GraphTaskNode B=node(1,3),C=node(2,3),D=node(3,3);
        GraphTaskNode E=node(4,5);

        link(A,B);
        link(A,C);
        link(A,D);

        link(B,E);
        link(C,E);
        link(D,E);

        TimeConstraints tc = build(List.of(A,B,C,D,E));
        double max = run(tc);

        assertEquals(3.0,A.getBlockingPower(),0.0001);
        assertEquals(1.0/3.0,B.getBlockingPower(),0.0001);
        assertEquals(1.0/3.0,C.getBlockingPower(),0.0001);
        assertEquals(1.0/3.0,D.getBlockingPower(),0.0001);
        assertEquals(0.0,E.getBlockingPower(),0.0001);

        assertEquals(3.0,max,0.0001);
    }

    // =============================================================================
    // === TEST 7: SMALL DEEP TREE =================================================
    // =============================================================================
    @Test
    public void block_deepChain() {

        GraphTaskNode R=node(0,2);
        GraphTaskNode A=node(1,2);
        GraphTaskNode B=node(2,2);
        GraphTaskNode C=node(3,2);

        link(R,A);
        link(A,B);
        link(B,C);

        TimeConstraints tc = build(List.of(R,A,B,C));
        double max = run(tc);

        assertEquals(1.0,R.getBlockingPower(),0.0001);
        assertEquals(1.0,A.getBlockingPower(),0.0001);
        assertEquals(1.0,B.getBlockingPower(),0.0001);
        assertEquals(0.0,C.getBlockingPower(),0.0001);

        assertEquals(1.0,max,0.0001);
    }

    // =============================================================================
    // === TEST 8: BIG CUSTOM 30-NODE GRAPH ========================================
    //    Uses same structure & numbering as our criticalPath & reachable tests
    // =============================================================================
    @Test
    public void block_big30Graph() {

        List<GraphTaskNode> all=new ArrayList<>();
        HashMap<Integer,GraphTaskNode> map=new HashMap<>();

        for(int i=0;i<30;i++){
            GraphTaskNode n=node(i, (i%4)+1);
            all.add(n);
            map.put(i,n);
        }

        BiConsumer<Integer,Integer> L=(a, b)->{
            GraphTaskNode p=all.get(a);
            GraphTaskNode c=all.get(b);
            p.addChildren(c);
            c.addParent(p);
        };

        // SAME GRAPH WE USED PREVIOUSLY
        L.accept(0,4); L.accept(0,5);
        L.accept(1,6); L.accept(1,7);
        L.accept(2,8);
        L.accept(3,9); L.accept(3,10);

        L.accept(4,11); L.accept(4,12);
        L.accept(5,12); L.accept(5,13);
        L.accept(6,13); L.accept(6,14);
        L.accept(7,15);
        L.accept(8,15); L.accept(8,16);
        L.accept(9,17);
        L.accept(10,17); L.accept(10,18);

        TimeConstraints tc = new TimeConstraints(new LinkedList<>(all),map);
        double max = run(tc);

        // Validate known max (root with 2 children → then deep branching)
        assertEquals(true, max >= 2.0);

        // Check first levels
        assertEquals(2.0, all.get(0).getBlockingPower(),0.0001);
        assertEquals(2.0, all.get(1).getBlockingPower(),0.0001);
        assertEquals(1.0, all.get(2).getBlockingPower(),0.0001);
        assertEquals(2.0, all.get(3).getBlockingPower(),0.0001);
    }
}