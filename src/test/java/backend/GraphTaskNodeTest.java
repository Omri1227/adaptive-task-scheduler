package backend;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.LinkedList;

public class GraphTaskNodeTest {

    private GraphTaskNode buildBasicNode(int id){
        Task t = new Task("T"+id, id, id*10, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    // ===== CONSTRUCTOR BASIC =====
    @Test
    public void constructor_initializesValuesCorrectly() {
        GraphTaskNode node = buildBasicNode(1);

        assertEquals(1, node.getId());
        assertEquals("T1", node.getName());
        assertEquals(10, node.getDuration());
        assertEquals(Double.valueOf(0.0), node.getScore());
        assertFalse(node.isCompleted());
        assertEquals(1, node.getReachableSet().size());
        assertTrue(node.getReachableSet().contains(node));
        assertEquals(0, node.getParents().size());
        assertEquals(0, node.getChildren().size());
        assertEquals(0, node.getParentsRemained().size());
    }

    // ===== COPY CONSTRUCTOR =====
    @Test
    public void copyConstructor_copiesDataCorrectly() {
        GraphTaskNode original = buildBasicNode(5);
        original.setScore(7.7);
        original.addChildren(buildBasicNode(10));
        original.addParent(buildBasicNode(8));
        original.addParentRemain(buildBasicNode(3));
        original.setSimulateParentsRemainedNum(11);

        GraphTaskNode copy = new GraphTaskNode(original);

        assertEquals(original.getId(), copy.getId());
        assertEquals(original.getName(), copy.getName());
        assertEquals(original.getDuration(), copy.getDuration());

        assertEquals(original.getChildren().size(), copy.getChildren().size());
        assertEquals(original.getParents().size(), copy.getParents().size());
        assertEquals(original.getParentsRemained().size(), copy.getParentsRemained().size());

        assertEquals(1, copy.getReachableSet().size());
        assertTrue(copy.getReachableSet().contains(copy));

        assertFalse(copy.isCompleted());
    }

    // ===== CHILDREN HANDLING =====
    @Test
    public void addChildren_shouldIncreaseList() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        a.addChildren(b);

        assertEquals(1, a.getChildren().size());
        assertEquals(b, a.getChildren().getFirst());
    }

    @Test
    public void removeChildren_shouldRemoveChild() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        a.addChildren(b);

        assertTrue(a.removeChildren(b));
        assertEquals(0, a.getChildren().size());
    }

    @Test
    public void removeChildren_nonExisting_shouldReturnFalse() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        assertFalse(a.removeChildren(b));
    }

    // ===== PARENTS HANDLING =====
    @Test
    public void addParent_shouldIncreaseParents() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        a.addParent(b);

        assertEquals(1, a.getParents().size());
        assertEquals(b, a.getParents().getFirst());
    }

    @Test
    public void removeParent_shouldRemoveParent() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        a.addParent(b);
        assertTrue(a.removeParent(b));
        assertEquals(0, a.getParents().size());
    }

    @Test
    public void removeParent_nonExisting_returnsFalse() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        assertFalse(a.removeParent(b));
    }

    // ===== REMAINING PARENTS =====
    @Test
    public void addParentRemain_shouldAdd() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        a.addParentRemain(b);

        assertEquals(1, a.getParentsRemained().size());
    }

    @Test
    public void removeParentRemain_shouldRemove() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        a.addParentRemain(b);

        assertTrue(a.removeRemain(b));
        assertEquals(0, a.getParentsRemained().size());
    }

    @Test
    public void removeParentRemain_nonExisting_returnsFalse() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        assertFalse(a.removeRemain(b));
    }

    // ===== SCORE =====
    @Test
    public void setScore_changesValue() {
        GraphTaskNode a = buildBasicNode(1);

        a.setScore(99.9);

        assertEquals(Double.valueOf(99.9), a.getScore());
    }

    // ===== DURATION MODIFICATION =====
    @Test
    public void setDuration_addsValues() {
        GraphTaskNode a = buildBasicNode(3);

        a.setDuration(5);

        assertEquals(35, a.getDuration());  // 3*10 + 5
    }

    // ===== COMPLETION FLAG =====
    @Test
    public void setCompleted_setsState() {
        GraphTaskNode a = buildBasicNode(1);

        a.setCompleted();

        assertTrue(a.isCompleted());
    }

    // ===== REACHABLE SET TEST =====
    @Test
    public void reachableSet_addAll_shouldExpand() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);
        GraphTaskNode c = buildBasicNode(3);

        b.addAllToReachableSet(c);
        a.addAllToReachableSet(b);

        assertTrue(a.getReachableSet().contains(a));
        assertTrue(a.getReachableSet().contains(b));
        assertTrue(a.getReachableSet().contains(c));
    }

    @Test
    public void removeReachable_shouldDeleteFromSet() {
        GraphTaskNode a = buildBasicNode(1);
        GraphTaskNode b = buildBasicNode(2);

        a.addAllToReachableSet(b);
        a.removeReachable(b);

        assertFalse(a.getReachableSet().contains(b));
    }

    // ===== SIMULATION NUMBER =====
    @Test
    public void simulateParentSetterGetter_works() {
        GraphTaskNode a = buildBasicNode(1);

        a.setSimulateParentsRemainedNum(9);
        assertEquals(9, a.getSimulateParentsRemainedNum());
    }
}