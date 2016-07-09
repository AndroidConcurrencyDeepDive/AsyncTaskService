/* $Id: $
   Copyright 2015, 2016 G. Blake Meike

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package net.callmeike.android.asynctaskservice;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * @author <a href="mailto:blake.meike@gmail.com">G. Blake Meike</a>
 * @version $Revision: $
 */
public class TaskService {
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    public static final int POOL_SIZE_CORE = CPU_COUNT + 1;
    public static final int POOL_SIZE_MAX = CPU_COUNT * 2 + 1;
    public static final int KEEP_ALIVE = 1;
    public static final int WORK_QUEUE_MAX = 32;
    public static final int STAY_AWAKE_MS = 30 * 1000;

    public static final int OP_TASK_COMPLETE = -8888;

    private static final int OP_STOP_SERVICE = -1;

    public static abstract class Task {
        public abstract void init();
        public abstract void run();
        public abstract void cancel();
        public abstract void onComplete();
        public abstract void onCancelled();
        public abstract boolean isCancelled();
    }

    private class RunnableTask implements Runnable {
        private Task task;
        private Messenger completionHandler;

        public RunnableTask() { }

        public void setTask(Task task) { this.task = task; }
        public Task getTask() { return task; }

        public void setCompletionHandler(Messenger completionHandler) { this.completionHandler = completionHandler; }
        public Messenger getCompletionHandler() { return completionHandler; }

        @Override
        public void run() {
            startTask();
            task.run();
            endTask(this);
            task = null;
        }
    }


    private final Service svc;
    private final long stayAwakeMs;
    private final Executor executor;
    private final Handler hdlr;
    private final LinkedList<RunnableTask> runnablePool = new LinkedList<>();

    private int running;
    private long lastStart;

    public TaskService(Service svc) {
        this(svc,
            STAY_AWAKE_MS,
            POOL_SIZE_CORE,
            POOL_SIZE_MAX,
            KEEP_ALIVE,
            WORK_QUEUE_MAX);
    }

    public TaskService(
        final Service svc,
        long stayAwakeMs,
        int corePoolSize,
        int maxPoolSize,
        int keepAlive,
        int workQueueMax)
    {
        this(svc,
            stayAwakeMs,
            new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAlive,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(workQueueMax),
                new ThreadFactory() {
                    private final AtomicInteger threads = new AtomicInteger(1);
                    public Thread newThread(@NonNull Runnable r) {
                        return new Thread(r, svc.getClass().getName() + " #" + threads.getAndIncrement());
                    }
                }));
    }

    public TaskService(Service svc, long stayAwakeMs, Executor executor) {
        this.svc = svc;
        this.stayAwakeMs = stayAwakeMs;
        this.executor = executor;
        this.hdlr = new Handler(new Handler.Callback() {
            @Override public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case OP_STOP_SERVICE:
                        stopIfDone();
                        return true;
                    default:
                }
                return false;
            }
        });
    }

    public void submitTask(Task task, Messenger completionHandler) {
        if (lastStart <= 0) {
            svc.startService(new Intent(svc, svc.getClass()));
        }

        lastStart = SystemClock.uptimeMillis();

        task.init();

        executor.execute(getRunnableTask(task, completionHandler));
    }

    void startTask() { synchronized (executor) { ++running; } }

    void endTask(RunnableTask runnableTask) {
        Task task = runnableTask.getTask();
        Messenger completionHandler = runnableTask.getCompletionHandler();
        try { completionHandler.send(Message.obtain(null, OP_TASK_COMPLETE, 0, 0, task)); }
        catch (RemoteException e) {
            //  ???
        }

        releaseRunnableTask(runnableTask);

        // run completion methods on UI thread

        int n;
        synchronized (executor) { n = --running; }

        if (n <= 0) {
            Message msg = hdlr.obtainMessage(OP_STOP_SERVICE);
            long delay = (lastStart + stayAwakeMs) - SystemClock.uptimeMillis();
            hdlr.sendMessageDelayed(msg, (0 > delay) ? 0 : delay);
        }
    }

    void stopIfDone() {
        if (SystemClock.uptimeMillis() - lastStart > stayAwakeMs) {
            svc.stopSelf();
            lastStart = 0;
        }
    }

    private RunnableTask getRunnableTask(Task task, Messenger completionHandler) {
        synchronized (runnablePool) {
            RunnableTask runnableTask = (runnablePool.size() <= 0)
                ? new RunnableTask()
                : runnablePool.removeFirst();
            runnableTask.setTask(task);
            runnableTask.setCompletionHandler(completionHandler);
            return runnableTask;
        }
    }

    private void releaseRunnableTask(RunnableTask runnableTask) {
        synchronized (runnablePool) {
            runnablePool.addLast(runnableTask);
        }
    }
}
