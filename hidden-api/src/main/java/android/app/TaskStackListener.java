package android.app;

/**
 * Compile-time stub of the hidden {@code android.app.TaskStackListener}
 * (extends {@code ITaskStackListener.Stub}, so at runtime it is a Binder).
 *
 * Only callbacks with primitive parameters are declared here, because this module has no
 * android.jar on its classpath. A subclass may still declare the other callbacks (with
 * framework parameter types) without {@code override}: they replace the real methods at
 * runtime by name and signature.
 */
public abstract class TaskStackListener {

    public TaskStackListener() {
    }

    public void onTaskStackChanged() {
    }

    public void onTaskRemoved(int taskId) {
    }

    public void onTaskFocusChanged(int taskId, boolean focused) {
    }

    public void onTaskDisplayChanged(int taskId, int newDisplayId) {
    }
}
