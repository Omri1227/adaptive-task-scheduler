package backend;

import java.util.LinkedList;

public class Worker {
    private int id;
    private String name;

    enum Status {
        EMPLOYMENT,
        UNEMPLOYMENT;
    }
    private Status employmentStatus;

    private int taskStartTime;
    private int taskEstimatedEndTime;
    private int lastFinishTime;
    private long insertionOrder;
    private boolean toRemove;
    private int unemploymentTime;


    private GraphTaskNode desk;
    private LinkedList<GraphTaskNode> drawer;

    public Worker(int id, String name){
        this.id= id;
        this.name = name;
        this.employmentStatus = Status.UNEMPLOYMENT;
        desk=null;
        taskStartTime = 0;
        taskEstimatedEndTime = 0;
        this.drawer = new LinkedList<>();
        this.lastFinishTime = 0;
        this.insertionOrder=0;
        this.toRemove = false;
        this.unemploymentTime=0;
    }

    public Worker(Worker other){
        this.id= other.getId();
        this.name = other.getName();
        this.employmentStatus = other.getEmploymentStatus();
        if(other.getDesk()!=null){
            desk=new GraphTaskNode(other.getDesk());
        }
        else{
            this.desk=null;
        }
        taskStartTime = other.getStartTime();
        taskEstimatedEndTime = other.getTaskEstimatedEndTime();
        this.drawer = new LinkedList<>();
        this.lastFinishTime = other.getLastFinishTime();
        this.insertionOrder=other.getInsertionOrder();
        this.toRemove = other.getToRemove();
        this.unemploymentTime= other.copyUnemployedTime();
    }

    public int getLastFinishTime() {
        return this.lastFinishTime;
    }

    LinkedList<GraphTaskNode> getDrawer() {
        return this.drawer;
    }

    public int getStartTime() {
        return this.taskStartTime;
    }


    public void setDesk(GraphTaskNode graphTaskNode) {
        this.desk = graphTaskNode;
    }

    public boolean getToRemove() {
        return toRemove;
    }

    public void setUnemploymentTime(int startTime){
        this.unemploymentTime=startTime;

    }

    public int getUnemploymentTime(int globalTime){
        return globalTime-this.unemploymentTime;

    }



    public void setTaskEstimatedEndTime(int i){
        taskEstimatedEndTime=i;
    }

    public int getId(){
        return id;
    }

    public GraphTaskNode getDesk(){
        return desk;
    }

    public String getName(){
        return this.name;
    }

    public Status getEmploymentStatus(){
        return employmentStatus;
    }

    public int getTaskEstimatedEndTime(){
        return taskEstimatedEndTime;
    }
    public long getInsertionOrder() { return insertionOrder; }
    public void setInsertionOrder(long order) { this.insertionOrder = order; }

    public boolean giveTask(GraphTaskNode task){
        if (desk==null){
            desk=task;
            this.employmentStatus = Status.EMPLOYMENT;
            this.taskStartTime =lastFinishTime;
            this.taskEstimatedEndTime = lastFinishTime+task.getDuration();
            return true;
        }
        else{
            throw new RuntimeException("this worker is bussy right now");
        }
    }

    public boolean finishTask(int finishTime){
        if (desk==null){
            throw new RuntimeException("this worker is not working now");
        }
        desk.setActualFinishTime(finishTime);
        lastFinishTime=finishTime;
        drawer.addLast(desk);
        desk = null;
        this.employmentStatus = Status.UNEMPLOYMENT;
        return true;
    }

    public void setToRemove(){
        toRemove= true;
    }

    public int copyUnemployedTime(){
        return this.unemploymentTime;
    }

    public void setLastFinishTime(int finishTime){
        this.lastFinishTime = finishTime;

    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Worker worker = (Worker) o;
        return id == worker.id;
    }

    @Override
    public int hashCode() {
        return id;
    }
}
