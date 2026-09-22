package backend;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.*;

public class TimeConstraintsTest {

    private GraphTaskNode makeNode(int id, int duration) {
        Task t = new Task("T" + id, id, duration, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    @Test
    public void constructor_singleTask_graphIsOneHeadAndOneElementTopo() {

        GraphTaskNode a = makeNode(1, 10);

        LinkedList<GraphTaskNode> list = new LinkedList<>();
        list.add(a);

        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        map.put(1, a);

        TimeConstraints tc = new TimeConstraints(list, map);

        assertEquals(1, tc.getHeads().size());
        assertEquals(a, tc.getHeads().getFirst());

        assertEquals(1, tc.getTopologicalSort().size());
        assertEquals(a, tc.getTopologicalSort().get(0));
    }

    @Test
    public void constructor_twoIndependentHeads_detectBoth() {

        GraphTaskNode a = makeNode(1, 10);
        GraphTaskNode b = makeNode(2, 20);

        LinkedList<GraphTaskNode> list = new LinkedList<>();
        list.add(a);
        list.add(b);

        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        map.put(a.getId(), a);
        map.put(b.getId(), b);

        TimeConstraints tc = new TimeConstraints(list, map);

        assertEquals(2, tc.getHeads().size());
        assertTrue(tc.getHeads().contains(a));
        assertTrue(tc.getHeads().contains(b));

        assertEquals(2, tc.getTopologicalSort().size());
        assertTrue(tc.getTopologicalSort().contains(a));
        assertTrue(tc.getTopologicalSort().contains(b));
    }

    @Test
    public void constructor_simpleChain_topoRespectsOrder() {

        GraphTaskNode a = makeNode(1, 5);
        GraphTaskNode b = makeNode(2, 5);
        GraphTaskNode c = makeNode(3, 5);

        a.addChildren(b);
        b.addParent(a);

        b.addChildren(c);
        c.addParent(b);

        LinkedList<GraphTaskNode> list = new LinkedList<>();
        list.add(a);
        list.add(b);
        list.add(c);

        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        map.put(a.getId(), a);
        map.put(b.getId(), b);
        map.put(c.getId(), c);

        TimeConstraints tc = new TimeConstraints(list, map);

        ArrayList<GraphTaskNode> topo = tc.getTopologicalSort();

        assertEquals(3, topo.size());
        assertEquals(a, topo.get(0));
        assertEquals(b, topo.get(1));
        assertEquals(c, topo.get(2));
    }

    @Test
    public void constructor_branchingGraph_topoHandlesForks() {

        GraphTaskNode root = makeNode(1, 5);
        GraphTaskNode childA = makeNode(2, 5);
        GraphTaskNode childB = makeNode(3, 5);

        root.addChildren(childA);
        childA.addParent(root);

        root.addChildren(childB);
        childB.addParent(root);

        LinkedList<GraphTaskNode> list = new LinkedList<>();
        list.add(root);
        list.add(childA);
        list.add(childB);

        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        map.put(root.getId(), root);
        map.put(childA.getId(), childA);
        map.put(childB.getId(), childB);

        TimeConstraints tc = new TimeConstraints(list, map);

        assertEquals(1, tc.getHeads().size());
        assertEquals(root, tc.getHeads().get(0));

        assertEquals(3, tc.getTopologicalSort().size());

        ArrayList<GraphTaskNode> topo = tc.getTopologicalSort();
        assertEquals(root, topo.get(0));
        assertTrue(topo.contains(childA));
        assertTrue(topo.contains(childB));
    }

    @Test
    public void constructor_multipleHeads_multiLayerSort() {

        GraphTaskNode a = makeNode(1, 5);
        GraphTaskNode b = makeNode(2, 5);
        GraphTaskNode c = makeNode(3, 5);

        a.addChildren(c);
        c.addParent(a);

        b.addChildren(c);
        c.addParent(b);

        LinkedList<GraphTaskNode> list = new LinkedList<>();
        list.add(a);
        list.add(b);
        list.add(c);

        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        map.put(a.getId(), a);
        map.put(b.getId(), b);
        map.put(c.getId(), c);

        TimeConstraints tc = new TimeConstraints(list, map);

        ArrayList<GraphTaskNode> topo = tc.getTopologicalSort();

        assertEquals(2, tc.getHeads().size());
        assertTrue(tc.getHeads().contains(a));
        assertTrue(tc.getHeads().contains(b));

        assertEquals(3, topo.size());
        assertEquals(c, topo.get(2)); // must end last
    }

    @Test
    public void constructor_isolatedNodesAlsoAppearInHeads() {

        GraphTaskNode a = makeNode(1, 5);
        GraphTaskNode b = makeNode(2, 5);
        GraphTaskNode c = makeNode(3, 5);

        b.addParent(c); // only b has parent, so a & c heads
        c.addChildren(b);

        LinkedList<GraphTaskNode> list = new LinkedList<>();
        list.add(a);
        list.add(b);
        list.add(c);

        HashMap<Integer, GraphTaskNode> map = new HashMap<>();
        map.put(a.getId(), a);
        map.put(b.getId(), b);
        map.put(c.getId(), c);

        TimeConstraints tc = new TimeConstraints(list, map);

        assertEquals(2, tc.getHeads().size());
        assertTrue(tc.getHeads().contains(a));
        assertTrue(tc.getHeads().contains(c));
    }
}