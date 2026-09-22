package backend;

import java.util.LinkedList;

public class Task {
    private String name;
    private int id;
    private int duration;
    private LinkedList<Integer> dependsOnIDS;

    public Task(String name, int id, int duration, LinkedList<Integer> dependsOnIDS) {
        this.name = name;
        this.id = id;
        this.duration = duration;
        this.dependsOnIDS = dependsOnIDS;
    }

    public int getId() {
        return id;
    }

    public int getDuration() {
        return duration;
    }

    public String getName() {
        return name;
    }

    public LinkedList<Integer> getDependsOnIDS() {
        return dependsOnIDS;
    }

    public void addDependsOn(int id) {
        this.dependsOnIDS.add(id);
    }

    public void removeDependsOn(int id) {
        this.dependsOnIDS.remove(Integer.valueOf(id));
    }

}