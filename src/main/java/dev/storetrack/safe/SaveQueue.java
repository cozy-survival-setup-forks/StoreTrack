package dev.storetrack.safe;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One background thread that runs saves in order. The queue has a size limit, so a slow or failing disk cannot make
 * memory grow without end:
 * <ul>
 * <li>{@link #ordered} tasks always run in the order they were added and are never merged or dropped. When the queue
 * is full the caller waits (up to 10 seconds) for room, and gets an exception if there is none, so it can stop
 * before it does anything it could not record.</li>
 * <li>{@link #latest} tasks that share a key replace each other: only the newest one runs. For settings that are
 * saved again and again.</li>
 * </ul>
 */
public final class SaveQueue {

    private static final class Task {
        Runnable run;
        final String key;

        Task(Runnable run, String key) {
            this.run = run;
            this.key = key;
        }
    }

    private final String name;
    private final int capacity;
    private final Logger log;
    private final LinkedList<Task> queue = new LinkedList<>();
    private final Map<String, Task> byKey = new HashMap<>();
    private final Thread worker;
    private boolean closed;
    private boolean running;
    private long failures;

    public SaveQueue(String name, int capacity, Logger log) {
        this.name = name;
        this.capacity = Math.max(8, capacity);
        this.log = log;
        worker = new Thread(this::loop, name + "-saves");
        worker.setDaemon(true);
        worker.start();
    }

    public void ordered(Runnable task) {
        synchronized (this) {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (!closed && queue.size() >= capacity) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    Health.failure(name + ": the save queue is full and is not draining");
                    throw new IllegalStateException("The save queue of " + name + " is full; the disk is too slow or failing.");
                }
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the save queue");
                }
            }
            if (closed)
                throw new IllegalStateException("The save queue of " + name + " is closed.");
            queue.add(new Task(task, null));
            notifyAll();
        }
    }

    public void latest(String key, Runnable task) {
        synchronized (this) {
            if (closed)
                throw new IllegalStateException("The save queue of " + name + " is closed.");
            Task existing = byKey.get(key);
            if (existing != null) {
                existing.run = task;
                return;
            }
            Task t = new Task(task, key);
            byKey.put(key, t);
            queue.add(t);
            notifyAll();
        }
    }

    public synchronized int pending() {
        return queue.size() + (running ? 1 : 0);
    }

    public synchronized long failedSaves() {
        return failures;
    }

    /** Waits for everything queued to be written. Returns false when the time ran out first. */
    public boolean shutdown(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (this) {
            closed = true;
            notifyAll();
            while ((!queue.isEmpty() || running) && System.currentTimeMillis() < deadline) {
                try {
                    wait(Math.max(1, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean drained = queue.isEmpty() && !running;
            if (!drained)
                log.warning(name + ": " + (queue.size() + (running ? 1 : 0)) + " save(s) were still waiting after "
                    + timeoutMillis + " ms and may not have been written.");
            return drained;
        }
    }

    private void loop() {
        while (true) {
            Task task;
            synchronized (this) {
                while (queue.isEmpty()) {
                    if (closed)
                        return;
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                task = queue.removeFirst();
                if (task.key != null)
                    byKey.remove(task.key);
                running = true;
                notifyAll();
            }
            try {
                task.run.run();
            } catch (Throwable t) {
                synchronized (this) {
                    failures++;
                }
                Health.failure(name + ": a save failed: " + t);
                log.warning(name + ": a save failed: " + t);
            } finally {
                synchronized (this) {
                    running = false;
                    notifyAll();
                }
            }
        }
    }
}
