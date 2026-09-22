package backend;

import java.util.HashSet;
import java.util.LinkedList;

public class GraphTaskNode {
    private int id;
    private String name;
    private int duration;
    private Double score;
    private boolean isCompleted;

    private LinkedList<GraphTaskNode> children;
    private LinkedList<GraphTaskNode> parents;
    private LinkedList<GraphTaskNode> parentsRemained;
    private HashSet<GraphTaskNode> reachableSet;
    private int simulateParentsRemainedNum;

    private int criticalPaths;
    private int reachableDuration;
    private double blockingPower;
    private int actualFinishTime;

    public GraphTaskNode(Task task) {
        this.id = task.getId();
        this.name = task.getName();
        this.duration = task.getDuration();
        this.score = 0.0;
        this.isCompleted = false;

        this.children = new LinkedList<>();
        this.parents = new LinkedList<>();
        this.parentsRemained = new LinkedList<>();
        this.reachableSet = new HashSet<>();
        reachableSet.add(this);
        simulateParentsRemainedNum = 0;

        this.criticalPaths = 0;
        this.reachableDuration = 0;
        this.blockingPower = 0;
        this.actualFinishTime = 0;
    }

    public GraphTaskNode(GraphTaskNode other) {
        this.id = other.getId();
        this.name = other.getName();
        this.duration = other.getDuration();
        this.score = other.getScore();
        this.isCompleted = false;

        this.children = new LinkedList<>(other.getChildren());
        this.parents = new LinkedList<>(other.getParents());
        this.parentsRemained = new LinkedList<>(other.getParentsRemained());
        this.reachableSet = new HashSet<>();
        reachableSet.add(this);
        simulateParentsRemainedNum = other.simulateParentsRemainedNum;

        this.criticalPaths = 0;
        this.reachableDuration = 0;
        this.blockingPower = 0;
        this.actualFinishTime = 0;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return this.name;
    }


    public int getDuration() {
        return this.duration;
    }

    public void setDuration(int toAdd) {
        this.duration = this.duration + toAdd;
    }

    public Double getScore() {
        return this.score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public void setCompleted() {
        isCompleted = true;
    }

    public LinkedList<GraphTaskNode> getChildren() {
        return this.children;
    }

    public void addChildren(GraphTaskNode child) {
        this.children.add(child);
    }

    public boolean removeChildren(GraphTaskNode child) {
        return this.children.remove(child);
    }

    public LinkedList<GraphTaskNode> getParents() {
        return this.parents;
    }

    public void setParents(LinkedList<GraphTaskNode> newParents) {
        this.parents = newParents;
    }

    public void addParent(GraphTaskNode parent) {
        this.parents.add(parent);
    }

    public boolean removeParent(GraphTaskNode parent) {
        return this.parents.remove(parent);
    }

    public LinkedList<GraphTaskNode> getParentsRemained() {
        return this.parentsRemained;
    }

    public void addParentRemain(GraphTaskNode parent) {
        this.parentsRemained.add(parent);
    }

    public boolean removeRemain(GraphTaskNode parent) {
        return this.parentsRemained.remove(parent);
    }

    public HashSet<GraphTaskNode> getReachableSet() {
        return this.reachableSet;
    }

    public void setReachableSet(HashSet<GraphTaskNode> other) {
        this.reachableSet = other;
    }

    public void addAllToReachableSet(GraphTaskNode task) {
        this.reachableSet.addAll(task.getReachableSet());
    }

    public int getCriticalPaths() {
        return this.criticalPaths;
    }


    public boolean setMaxDescendantEndTime(int time) {
        this.criticalPaths = time;
        return true;
    }


    public int getReachableDuration() {
        return reachableDuration;
    }

    public boolean setReachableDuration(int i) {
        reachableDuration = i;
        return true;
    }

    public Double getBlockingPower() {
        return this.blockingPower;
    }

    public void setBlockingPower(double ratio) {
        blockingPower = ratio;
    }

    public int getActualFinishTime() {
        return this.actualFinishTime;
    }

    public void setActualFinishTime(int actualFinishTime) {
        this.actualFinishTime = actualFinishTime;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public void removeReachable(GraphTaskNode toRemove) {
        this.reachableSet.remove(toRemove);
    }


    public void setSimulateParentsRemainedNum(int toSet) {
        simulateParentsRemainedNum = toSet;
    }

    public int getSimulateParentsRemainedNum() {
        return simulateParentsRemainedNum;
    }

}
