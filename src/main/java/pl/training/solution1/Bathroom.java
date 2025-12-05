package pl.training.solution1;

public class Bathroom {

    private enum State {

        WOMAN, MEN, NONE

    }

    private State inUseBy = State.NONE;
    private int employeesInBathroom = 0;

    public void maleUseBathroom(String name) throws InterruptedException {
        synchronized (this) {
            while (inUseBy == State.WOMAN || employeesInBathroom == 3) {
                this.wait();
            }
            employeesInBathroom++;
            inUseBy = State.MEN;
        }
        enterBathroom(name);
        synchronized (this) {
            employeesInBathroom--;
            if (employeesInBathroom == 0) {
                inUseBy = State.NONE;
            }
            this.notifyAll();
        }
    }

    public void femaleUseBathroom(String name) throws InterruptedException {
        synchronized (this) {
            while (inUseBy == State.MEN || employeesInBathroom == 3) {
                this.wait();
            }
            employeesInBathroom++;
            inUseBy = State.WOMAN;
        }
        enterBathroom(name);
        synchronized (this) {
            employeesInBathroom--;
            if (employeesInBathroom == 0) {
                inUseBy = State.NONE;
            }
            this.notifyAll();
        }
    }

    private void enterBathroom(String name) throws InterruptedException {
        System.out.println(name + " is using bathroom");
        Thread.sleep(1_000);
        System.out.println(name + " is leaving bathroom");
    }

}
