package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class TimeConstraintsLargeGraphTest {

    private GraphTaskNode node(int id) {
        Task t = new Task("T" + id, id, 1, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    private void link(GraphTaskNode parent, GraphTaskNode child) {
        parent.addChildren(child);
        child.addParent(parent);
    }

    @Test
    public void constructor_bigGraph10() {

        LinkedList<GraphTaskNode> tasks = new LinkedList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 10; i++) {
            GraphTaskNode n = node(i);
            tasks.add(n);
            map.put(n.getId(), n);
        }

        link(tasks.get(0), tasks.get(3));
        link(tasks.get(0), tasks.get(4));
        link(tasks.get(3), tasks.get(6));
        link(tasks.get(4), tasks.get(6));
        link(tasks.get(6), tasks.get(9));

        link(tasks.get(1), tasks.get(5));
        link(tasks.get(5), tasks.get(7));
        link(tasks.get(7), tasks.get(8));

        // NEW → connect isolated node2 to node1
        link(tasks.get(1), tasks.get(2));

        TimeConstraints tc = new TimeConstraints(tasks, map);

        assertEquals(2, tc.getHeads().size());
        assertTrue(tc.getHeads().contains(tasks.get(0)));
        assertTrue(tc.getHeads().contains(tasks.get(1)));
        assertEquals(10, tc.getTopologicalSort().size());
    }

    @Test
    public void constructor_bigGraph20() {

        LinkedList<GraphTaskNode> tasks = new LinkedList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 20; i++) {
            GraphTaskNode n = node(i);
            tasks.add(n);
            map.put(n.getId(), n);
        }

        // build:
        // 0→2,3,4
        // 2→5→9→12→18
        // 3→6→10
        // 4→7→11→15→19
        // 1→8→14→17
        // 13 & 16 isolated

        link(tasks.get(0), tasks.get(2));
        link(tasks.get(0), tasks.get(3));
        link(tasks.get(0), tasks.get(4));

        link(tasks.get(2), tasks.get(5));
        link(tasks.get(5), tasks.get(9));
        link(tasks.get(9), tasks.get(12));
        link(tasks.get(12), tasks.get(18));

        link(tasks.get(3), tasks.get(6));
        link(tasks.get(6), tasks.get(10));

        link(tasks.get(4), tasks.get(7));
        link(tasks.get(7), tasks.get(11));
        link(tasks.get(11), tasks.get(15));
        link(tasks.get(15), tasks.get(19));

        link(tasks.get(1), tasks.get(8));
        link(tasks.get(8), tasks.get(14));
        link(tasks.get(14), tasks.get(17));

        // isolated: 13, 16

        TimeConstraints tc = new TimeConstraints(tasks, map);

        // Heads are: 0,1,13,16
        assertEquals(4, tc.getHeads().size());
        assertTrue(tc.getHeads().contains(tasks.get(0)));
        assertTrue(tc.getHeads().contains(tasks.get(1)));
        assertTrue(tc.getHeads().contains(tasks.get(13)));
        assertTrue(tc.getHeads().contains(tasks.get(16)));

        assertEquals(20, tc.getTopologicalSort().size());
    }

    @Test
    public void constructor_bigGraph50() {

        LinkedList<GraphTaskNode> tasks = new LinkedList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 50; i++) {
            GraphTaskNode n = node(i);
            tasks.add(n);
            map.put(n.getId(), n);
        }

        // build layered DAG: 5 layers of 10 items each
        // Each node connects to 2 children in next layer

        for (int i = 0; i < 40; i++) {
            link(tasks.get(i), tasks.get(i + 10));
            if (i % 2 == 0 && i + 11 < 50)
                link(tasks.get(i), tasks.get(i + 11));
        }

        TimeConstraints tc = new TimeConstraints(tasks, map);

        // Heads = first layer = id 0-9
        for (int i = 0; i < 10; i++) {
            assertTrue(tc.getHeads().contains(tasks.get(i)));
        }

        assertEquals(10, tc.getHeads().size());
        assertEquals(50, tc.getTopologicalSort().size());
    }

    @Test
    public void constructor_complexWeb10() {

        LinkedList<GraphTaskNode> tasks = new LinkedList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 10; i++) {
            GraphTaskNode n = node(i);
            tasks.add(n);
            map.put(n.getId(), n);
        }


        // Interconnected random DAG style:
        link(tasks.get(0), tasks.get(3));
        link(tasks.get(0), tasks.get(4));
        link(tasks.get(4), tasks.get(5));
        link(tasks.get(5), tasks.get(6));
        link(tasks.get(3), tasks.get(7));
        link(tasks.get(7), tasks.get(8));
        link(tasks.get(1), tasks.get(5));
        link(tasks.get(1), tasks.get(2));
        link(tasks.get(2), tasks.get(9));
        link(tasks.get(9), tasks.get(8));

        TimeConstraints tc = new TimeConstraints(tasks, map);

        assertEquals(2, tc.getHeads().size());
        assertTrue(tc.getHeads().contains(tasks.get(0)));
        assertTrue(tc.getHeads().contains(tasks.get(1)));

        assertEquals(10, tc.getTopologicalSort().size());
    }

    @Test
    public void constructor_bigGraph50_complexMesh() {

        LinkedList<GraphTaskNode> tasks = new LinkedList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 50; i++) {
            GraphTaskNode n = node(i);
            tasks.add(n);
            map.put(n.getId(), n);
        }

        for (int i = 10; i < 50; i++) {

            link(tasks.get(i - 1), tasks.get(i));
            link(tasks.get(i - 2), tasks.get(i));

            if (i >= 17) {
                link(tasks.get(i - 7), tasks.get(i));
            }

            if (i % 2 == 0) {
                link(tasks.get(0), tasks.get(i));
            }

            if (i % 3 == 0 && i >= 15) {
                link(tasks.get(i - 15), tasks.get(i));
            }
        }

        TimeConstraints tc = new TimeConstraints(tasks, map);

        ArrayList<GraphTaskNode> topo = tc.getTopologicalSort();

        assertEquals(50, topo.size());

        // Build map id -> index in topo
        HashMap<Integer, Integer> pos = new HashMap<>();
        for (int i = 0; i < topo.size(); i++) {
            pos.put(topo.get(i).getId(), i);
        }

        // 1️⃣ topo legality
        for (GraphTaskNode parent : tasks) {
            for (GraphTaskNode child : parent.getChildren()) {
                assertTrue(
                        "parent must appear first",
                        pos.get(parent.getId()) < pos.get(child.getId()));
            }
        }

        // 2️⃣ no duplications
        HashSet<Integer> seen = new HashSet<>();
        for (GraphTaskNode node : topo) {
            assertTrue(seen.add(node.getId()));
        }

        // 3️⃣ HEADS correctness: check rule, not number
        for (GraphTaskNode h : tc.getHeads()) {
            assertTrue(h.getParents().isEmpty());
        }

        // 4️⃣ check that all non-heads indeed have parents
        for (GraphTaskNode n : tasks) {
            if (!tc.getHeads().contains(n)) {
                assertFalse(n.getParents().isEmpty());
            }
        }
    }

    @Test
    public void constructor_bigGraph50_definedStructure() {

        // create 50 nodes
        LinkedList<GraphTaskNode> tasks = new LinkedList<>();
        HashMap<Integer, GraphTaskNode> map = new HashMap<>();

        for (int i = 0; i < 50; i++) {
            GraphTaskNode n = node(i);
            tasks.add(n);
            map.put(n.getId(), n);
        }

        // define 5 heads: 0–4
        // layer 1: nodes 5–14 depend on 0
        // layer 2: nodes 15–34 depend on 5 and 6
        // layer 3: nodes 35–49 depend on 20

        for (int i = 5; i < 15; i++) {
            link(tasks.get(0), tasks.get(i));
        }

        for (int i = 15; i < 35; i++) {
            link(tasks.get(5), tasks.get(i));
            link(tasks.get(6), tasks.get(i));
        }

        for (int i = 35; i < 50; i++) {
            link(tasks.get(20), tasks.get(i));
        }

        // build
        TimeConstraints tc = new TimeConstraints(tasks, map);

        // verify heads:
        assertEquals(5, tc.getHeads().size());
        assertTrue(tc.getHeads().contains(tasks.get(0)));
        assertTrue(tc.getHeads().contains(tasks.get(1)));
        assertTrue(tc.getHeads().contains(tasks.get(2)));
        assertTrue(tc.getHeads().contains(tasks.get(3)));
        assertTrue(tc.getHeads().contains(tasks.get(4)));

        // verify topo sort size:
        assertEquals(50, tc.getTopologicalSort().size());

        // verify ordering
        HashMap<Integer, Integer> pos = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            pos.put(tc.getTopologicalSort().get(i).getId(), i);
        }

        for (int i = 5; i < 15; i++) {
            assertTrue(pos.get(0) < pos.get(i));
        }

        for (int i = 15; i < 35; i++) {
            assertTrue(pos.get(5) < pos.get(i));
            assertTrue(pos.get(6) < pos.get(i));
        }

        for (int i = 35; i < 50; i++) {
            assertTrue(pos.get(20) < pos.get(i));
        }

    }
}