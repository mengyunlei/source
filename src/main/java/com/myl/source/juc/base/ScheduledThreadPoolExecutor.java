/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package com.myl.source.juc.base;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;

/**
 * A {@link ThreadPoolExecutor} that can additionally schedule
 * commands to run after a given delay, or to execute
 * periodically. This class is preferable to {@link Timer}
 * when multiple worker threads are needed, or when the additional
 * flexibility or capabilities of {@link ThreadPoolExecutor} (which
 * this class extends) are required.
 *
 * <p>Delayed tasks execute no sooner than they are enabled, but
 * without any real-time guarantees about when, after they are
 * enabled, they will commence. Tasks scheduled for exactly the same
 * execution time are enabled in first-in-first-out (FIFO) order of
 * submission.
 *
 * <p>When a submitted task is cancelled before it is run, execution
 * is suppressed. By default, such a cancelled task is not
 * automatically removed from the work queue until its delay
 * elapses. While this enables further inspection and monitoring, it
 * may also cause unbounded retention of cancelled tasks. To avoid
 * this, set {@link #setRemoveOnCancelPolicy} to {@code true}, which
 * causes tasks to be immediately removed from the work queue at
 * time of cancellation.
 *
 * <p>Successive executions of a task scheduled via
 * {@code scheduleAtFixedRate} or
 * {@code scheduleWithFixedDelay} do not overlap. While different
 * executions may be performed by different threads, the effects of
 * prior executions <a
 * href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * those of subsequent ones.
 *
 * <p>While this class inherits from {@link ThreadPoolExecutor}, a few
 * of the inherited tuning methods are not useful for it. In
 * particular, because it acts as a fixed-sized pool using
 * {@code corePoolSize} threads and an unbounded queue, adjustments
 * to {@code maximumPoolSize} have no useful effect. Additionally, it
 * is almost never a good idea to set {@code corePoolSize} to zero or
 * use {@code allowCoreThreadTimeOut} because this may leave the pool
 * without threads to handle tasks once they become eligible to run.
 *
 * <p><b>Extension notes:</b> This class overrides the
 * {@link ThreadPoolExecutor#execute(Runnable) execute} and
 * {@link AbstractExecutorService#submit(Runnable) submit}
 * methods to generate internal {@link ScheduledFuture} objects to
 * control per-task delays and scheduling.  To preserve
 * functionality, any further overrides of these methods in
 * subclasses must invoke superclass versions, which effectively
 * disables additional task customization.  However, this class
 * provides alternative protected extension method
 * {@code decorateTask} (one version each for {@code Runnable} and
 * {@code Callable}) that can be used to customize the concrete task
 * types used to execute commands entered via {@code execute},
 * {@code submit}, {@code schedule}, {@code scheduleAtFixedRate},
 * and {@code scheduleWithFixedDelay}.  By default, a
 * {@code ScheduledThreadPoolExecutor} uses a task type extending
 * {@link FutureTask}. However, this may be modified or replaced using
 * subclasses of the form:
 *
 *  <pre> {@code
 * public class CustomScheduledExecutor extends ScheduledThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableScheduledFuture<V> { ... }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Runnable r, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(r, task);
 *   }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Callable<V> c, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(c, task);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public class ScheduledThreadPoolExecutor
        extends ThreadPoolExecutor
        implements ScheduledExecutorService {

    /*
     * This class specializes ThreadPoolExecutor implementation by
     *
     * 1. Using a custom task type, ScheduledFutureTask for
     *    tasks, even those that don't require scheduling (i.e.,
     *    those submitted using ExecutorService execute, not
     *    ScheduledExecutorService methods) which are treated as
     *    delayed tasks with a delay of zero.
     *
     * 2. Using a custom queue (DelayedWorkQueue), a variant of
     *    unbounded DelayQueue. The lack of capacity constraint and
     *    the fact that corePoolSize and maximumPoolSize are
     *    effectively identical simplifies some execution mechanics
     *    (see delayedExecute) compared to ThreadPoolExecutor.
     *
     * 3. Supporting optional run-after-shutdown parameters, which
     *    leads to overrides of shutdown methods to remove and cancel
     *    tasks that should NOT be run after shutdown, as well as
     *    different recheck logic when task (re)submission overlaps
     *    with a shutdown.
     *
     * 4. Task decoration methods to allow interception and
     *    instrumentation, which are needed because subclasses cannot
     *    otherwise override submit methods to get this effect. These
     *    don't have any impact on pool control logic though.
     */

    /**
     * 线程池状态成为 shutdown 时，线程从任务队列内获取到 “周期执行任务” 时，是否进行执行。
     * 默认值 false，不执行。
     * 如果设置为true，线程读取出来 周期执行任务 时 ，还会执行一次..
     *
     * False if should cancel/suppress periodic tasks on shutdown.
     */
    private volatile boolean continueExistingPeriodicTasksAfterShutdown;

    /**
     * 线程池状态成为 shutdown 时，线程从任务队列内获取到 “延迟任务” 时，是否进行执行。
     * 默认值 true，执行。
     *
     * False if should cancel non-periodic tasks on shutdown.
     */
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;


    /**
     * 任务被取消时 是否 需要从“任务队列”内移除，默认false，不移除，等到线程拿到任务之后 抛弃..
     * 设置为 true 则 取消任务时，就主动的从 任务队列 内移除走了..
     *
     * True if ScheduledFutureTask.cancel should remove from queue
     */
    private volatile boolean removeOnCancel = false;

    /**
     * 生成任务序列号的一个字段
     * 为什么需要序列号呢？因为调度线程池 它 有独特的任务队列，该任务队列 是 优先级任务队列，
     * 优先级任务队列 内的 任务 都是需要实现 Compare 接口的，如果两个 任务 他们 的 compare 比较之后
     * 得到的 结果 是 0, 说明 这俩任务的 优先级一致，此时 就需要 再根据 序列号 进行 对比，看到底谁优先。
     *
     * Sequence number to break scheduling ties, and in turn to
     * guarantee FIFO order among tied entries.
     */
    private static final AtomicLong sequencer = new AtomicLong();

    /**
     * Returns current nanosecond time.
     */
    final long now() {
        return System.nanoTime();
    }

    private class ScheduledFutureTask<V>
            extends FutureTask<V> implements RunnableScheduledFuture<V> {

        // 序列号，用于对比任务优先级，当任务的 time 字段 比较不出来 优先级时，就使用该字段进行比较。
        // 该字段一定不会一致。
        /** Sequence number to break ties FIFO */
        private final long sequenceNumber;


        // 任务下一次执行的时间节点，交付时间。当任务执行结束之后，会再次将任务加入到 queue，加入到queue之前
        // 会修改该字段 为 下一次 执行的时间节点。
        /** The time the task is enabled to execute in nanoTime units */
        private long time;



        // 周期时间长度..
        // 1. period > 0 的情况，说明当前任务 是 由 scheduleAtFixedRate(..)接口提交的任务，该任务它不考虑执行耗时。
        // 2. period < 0 的情况，说明当前任务 是 由 scheduleWithFixedDelay(..)接口提交的任务，该任务考虑执行耗时，下次执行节点为 本次结束时间 + period 时间。
        // 3. perod == 0 的情况，说明当前任务 是 由 schedule(..)接口提交的任务，该任务是一个延迟执行的任务，只执行一次
        /**
         * Period in nanoseconds for repeating tasks.  A positive
         * value indicates fixed-rate execution.  A negative value
         * indicates fixed-delay execution.  A value of 0 indicates a
         * non-repeating task.
         */
        private final long period;


        // 指向自身..后面看源码再说..
        /** The actual task to be re-enqueued by reExecutePeriodic */
        RunnableScheduledFuture<V> outerTask = this;



        // 因为DelayedWorkQueue 底层使用的数据结构是 堆（最小堆），记录当前任务 在 堆 中的索引。
        // 其实就是 数组中的索引，堆是一个满二叉树，满二叉树 一般是使用 数组表示，看过 netty 内存管理的同学 应该清楚 满二叉树 数组表示..
        /**
         * Index into delay queue, to support faster cancellation.
         */
        int heapIndex;


        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Runnable r, V result, long ns) {
            super(r, result);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a periodic action with given nano time and period.
         */
        ScheduledFutureTask(Runnable r, V result, long ns, long period) {
            super(r, result);
            this.time = ns;
            this.period = period;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Callable<V> callable, long ns) {
            super(callable);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }



        // 返回任务还差多久该下一次执行
        public long getDelay(TimeUnit unit) {
            // time 交付时间 - 当前时间..
            return unit.convert(time - now(), NANOSECONDS);
        }



        public int compareTo(Delayed other) {

            if (other == this) // compare zero if same object
                // 对象自己 和 自己比较 返回 0，说明相等。
                return 0;



            if (other instanceof ScheduledFutureTask) {
                // 该条件成立，说明 other 对象 最起码也是 ScheduledFutureTask 类型

                // other 转换成 ScheduledFutureTask 类型，x 表示该对象。
                ScheduledFutureTask<?> x = (ScheduledFutureTask<?>)other;

                // this.time - x.time 得到差值
                long diff = time - x.time;

                if (diff < 0)
                    // 该条件成立，说明 this.time < x.time ，说明 this 任务 优先级 要高于 other，返回 -1
                    return -1;
                else if (diff > 0)
                    // 该条件成立，说明 this.time > x.time ，说明 this 任务 优先级 要低于 other，返回 1
                    return 1;

                // 执行到这里，说明 diff == 0 ，两个任务的 time 是一致的了，无法比较出 哪个优先级 更高了..此时选择使用 sequenceNumber 进行比较。

                else if (sequenceNumber < x.sequenceNumber)
                    return -1;
                else
                    return 1;
            }

            // 当 other 不是 ScheduledFutureTask 类型时，才执行下面代码..
            // this 和 other 都是 Delayed 接口的实现，所以 使用 getDelay() 获取任务的 距离下次执行的 时间点的差值，进行比较。
            long diff = getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS);

            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }






        /**
         * period == 0 说明 不是周期任务（延迟任务），当 ！= 0 时，说明 该任务一定是一个 周期任务.
         *
         * Returns {@code true} if this is a periodic (not a one-shot) action.
         *
         * @return {@code true} if periodic
         */
        public boolean isPeriodic() {
            return period != 0;
        }



        /**
         * 周期任务执行完成之后，再次提交到 DelayedWorkQueue 之前调用的。
         * 设置下一次 任务的执行时间节点。
         *
         * Sets the next time to run for a periodic task.
         */
        private void setNextRunTime() {
            // p 表示 period
            long p = period;

            if (p > 0)
                // 该条件成立，说明 任务 是由 scheduleAtFixedRate(..)接口提交的任务，该任务它不考虑执行耗时。
                // 直接拿 上一次 任务的执行节点 + 执行周期 作为下一次 执行节点。
                time += p;
            else
                // 该条件成立，说明 任务 是由 scheduleWithFixedDelay(..)接口提交的任务，该任务考虑执行耗时 (period < 0 ， -period 就是正数了 )
                // 拿系统当前时间 + 执行周期，作为下一次执行 时间节点。
                time = triggerTime(-p);
        }



        /**
         * 取消任务
         * @param mayInterruptIfRunning （当任务运行中时，是否需要先中断它..true 会先去中断任务，false 则直接设置任务状态为 取消状态）
         * @return cancelled bool值，表示是否取消成功
         */
        public boolean cancel(boolean mayInterruptIfRunning) {
            // super -> FutureTask 父类，cancelled 表示任务是否取消成功
            // 取消失败有哪些情况？1. 任务已经执行完成  2. 任务出现异常 3.任务被其它线程取消..
            boolean cancelled = super.cancel(mayInterruptIfRunning);

            // 条件1：cancelled ...
            // 条件2：removeOnCancel  设置为 true 则 取消任务时，就主动的从 任务队列 内移除走了..
            // 条件3：heapIndex > = 0 ，条件成立，说明 任务仍然在 堆 结构中，从 堆 结构中移除的任务 它的 heapIndex 会先设置为 -1
            if (cancelled && removeOnCancel && heapIndex >= 0)
                // remove 是线程池的方法，最终触发的是 延迟任务队列（DelayedWorkQueue.remove(..) 方法）
                remove(this);
            return cancelled;
        }




        /**
         * 线程池中的线程 从 queue 内获取到 task 之后，调用 task.run() 方法 执行任务逻辑。
         * Overrides FutureTask version so as to reset/requeue if periodic.
         */
        public void run() {
            // periodic 表示当前任务是否为 周期执行 的任务
            boolean periodic = isPeriodic();

            // 1. periodic == true 时，当前任务是周期任务，并且 continue...参数值为 true，则会执行当前任务，否则 continue.. 值为 false，则不执行周期任务。
            // 2. periodic == false 时，当前任务是 延迟任务，并且 executeExistingDelayedTasksAfterShutdown 参数值为 true（默认），则执行当前 延迟任务，
            // 否则 executeExistingDelayedTasksAfterShutdown 参数值为 false，则不执行 从queue内取出的 延迟任务。
            if (!canRunInCurrentRunState(periodic))
                // 取消任务
                cancel(false);


            else if (!periodic)// 条件成立，说明任务是 延迟任务，不是 周期任务..
                // 调用父类的run方法，即FutureTask.run() 逻辑，普通线程池没啥区别
                ScheduledFutureTask.super.run();


            // 执行到 else if 条件，就说明当前这个任务 肯定 是一个 “周期执行的任务”，此时调用 父类的 runAndReset() 方法去执行 任务逻辑。
            // 该方法一般情况下 返回 true
            else if (ScheduledFutureTask.super.runAndReset()) {
                // 上面 ScheduledFutureTask.super.runAndReset() 执行，代表 任务 周期性 执行了 一次，执行完一次之后 该干什么呢？
                // 1. 设置当前任务的 下一次执行时间
                // 2. 再加入到 延迟任务队列
                setNextRunTime();
                reExecutePeriodic(outerTask);

            }

        }



    }

    /**
     * Returns true if can run a task given current run state
     * and run-after-shutdown parameters.
     *
     * @param periodic true if this task periodic, false if delayed
     */
    boolean canRunInCurrentRunState(boolean periodic) {
        return isRunningOrShutdown(periodic ?
                                   continueExistingPeriodicTasksAfterShutdown :
                                   executeExistingDelayedTasksAfterShutdown);
    }

    /**
     * Main execution method for delayed or periodic tasks.  If pool
     * is shut down, rejects the task. Otherwise adds task to queue
     * and starts a thread, if necessary, to run it.  (We cannot
     * prestart the thread to run the task because the task (probably)
     * shouldn't be run yet.)  If the pool is shut down while the task
     * is being added, cancel and remove it if required by state and
     * run-after-shutdown parameters.
     *
     * @param task the task
     */
    private void delayedExecute(RunnableScheduledFuture<?> task) {
        if (isShutdown())
            reject(task);
        else {
            super.getQueue().add(task);
            if (isShutdown() &&
                !canRunInCurrentRunState(task.isPeriodic()) &&
                remove(task))
                task.cancel(false);
            else
                ensurePrestart();
        }
    }

    /**
     * Requeues a periodic task unless current run state precludes it.
     * Same idea as delayedExecute except drops task rather than rejecting.
     *
     * @param task the task
     */
    void reExecutePeriodic(RunnableScheduledFuture<?> task) {
        if (canRunInCurrentRunState(true)) {
            // 将任务再次加入到 任务队列
            super.getQueue().add(task);
            // 下面if 条件内的逻辑，是说 如果提交完任务之后，线程池状态变为了 shutdown 状态，并且 continue...参数值是 false的话，直接
            // 将上一步 提交的任务 从 队列 移除走
            if (!canRunInCurrentRunState(true) && remove(task))
                // 将任务设置为 取消状态
                task.cancel(false);
            else
                // 正常走这里..确保提交任务之后，线程池内有线程干活。
                ensurePrestart();
        }
    }

    /**
     * Cancels and clears the queue of all tasks that should not be run
     * due to shutdown policy.  Invoked within super.shutdown.
     */
    @Override void onShutdown() {
        BlockingQueue<Runnable> q = super.getQueue();
        boolean keepDelayed =
            getExecuteExistingDelayedTasksAfterShutdownPolicy();
        boolean keepPeriodic =
            getContinueExistingPeriodicTasksAfterShutdownPolicy();
        if (!keepDelayed && !keepPeriodic) {
            for (Object e : q.toArray())
                if (e instanceof RunnableScheduledFuture<?>)
                    ((RunnableScheduledFuture<?>) e).cancel(false);
            q.clear();
        }
        else {
            // Traverse snapshot to avoid iterator exceptions
            for (Object e : q.toArray()) {
                if (e instanceof RunnableScheduledFuture) {
                    RunnableScheduledFuture<?> t =
                        (RunnableScheduledFuture<?>)e;
                    if ((t.isPeriodic() ? !keepPeriodic : !keepDelayed) ||
                        t.isCancelled()) { // also remove if already cancelled
                        if (q.remove(t))
                            t.cancel(false);
                    }
                }
            }
        }
        tryTerminate();
    }

    /**
     * Modifies or replaces the task used to execute a runnable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param runnable the submitted Runnable
     * @param task the task created to execute the runnable
     * @param <V> the type of the task's result
     * @return a task that can execute the runnable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Modifies or replaces the task used to execute a callable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param callable the submitted Callable
     * @param task the task created to execute the callable
     * @param <V> the type of the task's result
     * @return a task that can execute the callable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given core pool size.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     */
    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue());
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code threadFactory} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), threadFactory);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), handler);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code threadFactory} or
     *         {@code handler} is null
     *
     * corePoolSize 核心线程数
     * threadFactory 线程工厂
     * handler 拒绝策略
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory,
                                       RejectedExecutionHandler handler) {
        // super -> ThreadPoolExecutor 构造方法
        // 参数2：maximumPoolSize，传的 int 最大值，非核心线程 不受限制
        // 参数3：keepAliveTime，0 说明非核心线程 一旦空闲 就立马销毁去..（线程是不分 核心 和 非核心的，只不过线程池会维护 一个 corePoolSize 内的线程数量）
        // 参数5：new DelayedWorkQueue() ，创建了一个 任务队列 ，这是一个 延迟任务队列，它是一个 优先级 任务队列，在优先级 基础之上提供了 hold 线程的事情。
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), threadFactory, handler);

    }

    /**
     * Returns the trigger time of a delayed action.
     */
    private long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    long triggerTime(long delay) {
        return now() +
            ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    /**
     * Constrains the values of all delays in the queue to be within
     * Long.MAX_VALUE of each other, to avoid overflow in compareTo.
     * This may occur if a task is eligible to be dequeued, but has
     * not yet been, while some other task is added with a delay of
     * Long.MAX_VALUE.
     */
    private long overflowFree(long delay) {
        Delayed head = (Delayed) super.getQueue().peek();
        if (head != null) {
            long headDelay = head.getDelay(NANOSECONDS);
            if (headDelay < 0 && (delay - headDelay < 0))
                delay = Long.MAX_VALUE + headDelay;
        }
        return delay;
    }

    /**
     * 提交的任务被称为“延迟任务”，不是周期执行的任务
     * 延迟多久呢？ delay 参数 和 unit 参数控制。
     * 注意：只执行一次
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public ScheduledFuture<?> schedule(Runnable command,
                                       long delay,
                                       TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        RunnableScheduledFuture<?> t = decorateTask(command,
            new ScheduledFutureTask<Void>(command, null,
                                          triggerTime(delay, unit)));
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay,
                                           TimeUnit unit) {
        if (callable == null || unit == null)
            throw new NullPointerException();
        RunnableScheduledFuture<V> t = decorateTask(callable,
            new ScheduledFutureTask<V>(callable,
                                       triggerTime(delay, unit)));
        delayedExecute(t);
        return t;
    }

    /**
     * 提交的任务是“周期任务”，什么是周期任务？按照一个周期去反复执行的任务。
     * initialDelay：任务初次执行时的延迟时间。
     * period：周期时长
     *
     * 特点：该接口提交的任务，它不考虑执行的耗时。
     * 举个例子：有一个任务 10秒钟 执行一次，假设它每次执行耗时2秒。在 03:00:00 执行了一次，耗时了2秒，那它下一次的执行时间节点是 03:00:10.
     * 如果它执行耗时超过了 10 秒，那它下一次的执行时间 就是 立马执行。
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (period <= 0)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
            new ScheduledFutureTask<Void>(command,
                                          null,
                                          triggerTime(initialDelay, unit),
                                          unit.toNanos(period));
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * 提交的任务是“周期任务”，什么是周期任务？按照一个周期去反复执行的任务。
     * initialDelay：任务初次执行时的延迟时间。
     * period：周期时长
     *
     * 特点：它考虑到执行耗时。
     * 例子：有一个任务 每10秒执行一次，假设它本次执行的时间点是 03:00:00 ，耗时了5秒钟，那它下一次的执行时间点 03:00:05 + 10s => 03:00:15 这个时间点。
     *
     * 任务执行【等待10s】任务执行【等待10s】任务执行【等待10s】...
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (delay <= 0)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
            new ScheduledFutureTask<Void>(command,
                                          null,
                                          triggerTime(initialDelay, unit),
                                          unit.toNanos(-delay));
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * Executes {@code command} with zero required delay.
     * This has effect equivalent to
     * {@link #schedule(Runnable,long,TimeUnit) schedule(command, 0, anyUnit)}.
     * Note that inspections of the queue and of the list returned by
     * {@code shutdownNow} will access the zero-delayed
     * {@link ScheduledFuture}, not the {@code command} itself.
     *
     * <p>A consequence of the use of {@code ScheduledFuture} objects is
     * that {@link ThreadPoolExecutor#afterExecute afterExecute} is always
     * called with a null second {@code Throwable} argument, even if the
     * {@code command} terminated abruptly.  Instead, the {@code Throwable}
     * thrown by such a task can be obtained via {@link Future#get}.
     *
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution because the
     *         executor has been shut down
     * @throws NullPointerException {@inheritDoc}
     */
    public void execute(Runnable command) {
        schedule(command, 0, NANOSECONDS);
    }

    // Override AbstractExecutorService methods

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<?> submit(Runnable task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * Sets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @param value if {@code true}, continue after shutdown, else don't
     * @see #getContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @return {@code true} if will continue after shutdown
     * @see #setContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @param value if {@code true}, execute after shutdown, else don't
     * @see #getExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @return {@code true} if will execute after shutdown
     * @see #setExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @param value if {@code true}, remove on cancellation, else don't
     * @see #getRemoveOnCancelPolicy
     * @since 1.7
     */
    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    /**
     * Gets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @return {@code true} if cancelled tasks are immediately removed
     *         from the queue
     * @see #setRemoveOnCancelPolicy
     * @since 1.7
     */
    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * <p>If the {@code ExecuteExistingDelayedTasksAfterShutdownPolicy}
     * has been set {@code false}, existing delayed tasks whose delays
     * have not yet elapsed are cancelled.  And unless the {@code
     * ContinueExistingPeriodicTasksAfterShutdownPolicy} has been set
     * {@code true}, future executions of existing periodic tasks will
     * be cancelled.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        super.shutdown();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution.
     *         Each element of this list is a {@link ScheduledFuture},
     *         including those tasks submitted using {@code execute},
     *         which are for scheduling purposes used as the basis of a
     *         zero-delay {@code ScheduledFuture}.
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    /**
     * Returns the task queue used by this executor.  Each element of
     * this queue is a {@link ScheduledFuture}, including those
     * tasks submitted using {@code execute} which are for scheduling
     * purposes used as the basis of a zero-delay
     * {@code ScheduledFuture}.  Iteration over this queue is
     * <em>not</em> guaranteed to traverse tasks in the order in
     * which they will execute.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    /**
     * 先讲一个数据结构 “堆”，堆分为 最大堆 和 最小堆，堆这个结构是使用 满二叉树 表示的。
     * 满二叉树结构在程序内一般是使用 数组表示的，下面这个最小堆 使用数组表示：[1,3,5,6,7,8,9]
     * 公式：
     * 1. 查询父节点：floor((i-1) / 2)
     * 2. 查询左子节点： left = i * 2 + 1
     * 3. 查询右子节点： right = i * 2 + 2
     *
     * 最小堆：
     *           1 --- 索引 0
     *         /  \
     *索引1----3    5  ----索引 2
     *       / \  / \
     *      6  7 8   9
     *
     * 最大堆：
     *
     *          10
     *         /  \
     *        8    9
     *       / \  / \
     *      4  5 6   7
     *
     * 延迟队列采用的堆 是 最小堆,言外之意 最顶层的 元素 是优先级最高的 任务。
     *
     * 最小堆 插入元素的流程，咱们向下面这颗 最小堆 插入 元素 2 的流程：
     *
     *
     *           1                  |               1                   |               1
     *         /  \                 |              /  \                 |              /  \
     *        3    5               ->             3    5               ->             2    5
     *       / \  / \               |            / \  / \               |            / \  / \
     *      6  7 8   9              |           2  7 8   9              |           3   7 8  9
     *     /                                   /                        |          /
     *    2                                   6                         |         6
     *
     * 规则：
     * 1. 将新元素 放入数组的最后一个位置，[1,3,5,6,7,8,9] -> [1,3,5,6,7,8,9, “2”]
     * 2. 新元素 执行向上冒泡的逻辑，去和它的父节点 比较大小，（最小堆）父节点 值 大于 子节点，则交换它俩位置，此时相当于 向上冒泡了一层，然后持续这个过程
     * 直到 新元素 成为 顶层节点 或者 碰到 某个 父节点 优先级 比他高，则停止。
     *
     *
     * 最小堆 删除顶层元素的流程，假设删除 节点 1：
     *              1               |           9           |           3           |           3
     *             / \              |          / \          |          / \          |          / \
     *            3   5            ->         3   5        ->         9   5        ->         6   5
     *           / \  / \           |        / \  /         |        / \  /         |        / \  /
     *          6   7 8  9          |       6   7 8         |       6   7 8         |       9  7  8
     *
     *
     * 规则：
     * 1. 将数组最后一个节点 覆盖 数组[0] 这个位置，将数组最后一个节点 设置为null，反映到树上 就是 将最后一个 节点 提升到 树顶层位置。
     * 2. 上一步 提升的 这个节点，执行 向下 冒泡的逻辑。怎么向下冒泡？获取左右子节点 中 优先级 高的节点，去和优先级高的子节点比较 看 到底是 向下 冒泡节点的优先级高
     *  还是 子节点优先级高，如果子节点 优先级高，则交换位置，反映到图上 就是向下 坠落了一层。
     *
     * Specialized delay queue. To mesh with TPE declarations, this
     * class must be declared as a BlockingQueue<Runnable> even though
     * it can only hold RunnableScheduledFutures.
     */
    static class DelayedWorkQueue extends AbstractQueue<Runnable>
        implements BlockingQueue<Runnable> {
        /*
         * A DelayedWorkQueue is based on a heap-based data structure
         * like those in DelayQueue and PriorityQueue, except that
         * every ScheduledFutureTask also records its index into the
         * heap array. This eliminates the need to find a task upon
         * cancellation, greatly speeding up removal (down from O(n)
         * to O(log n)), and reducing garbage retention that would
         * otherwise occur by waiting for the element to rise to top
         * before clearing. But because the queue may also hold
         * RunnableScheduledFutures that are not ScheduledFutureTasks,
         * we are not guaranteed to have such indices available, in
         * which case we fall back to linear search. (We expect that
         * most tasks will not be decorated, and that the faster cases
         * will be much more common.)
         *
         * All heap operations must record index changes -- mainly
         * within siftUp and siftDown. Upon removal, a task's
         * heapIndex is set to -1. Note that ScheduledFutureTasks can
         * appear at most once in the queue (this need not be true for
         * other kinds of tasks or work queues), so are uniquely
         * identified by heapIndex.
         */
        // 初始化数组时的大小 16
        private static final int INITIAL_CAPACITY = 16;

        // 这个数组就是 满二叉树 的数组表示，存储提交的 优先级任务
        // 这里其实存储的就是 ScheduledFutureTask 实例
        private RunnableScheduledFuture<?>[] queue =
            new RunnableScheduledFuture<?>[INITIAL_CAPACITY];


        // 可重入锁，添加任务 和 删除任务的时候 都需要加锁操作，确保 满二叉树 线程安全
        private final ReentrantLock lock = new ReentrantLock();

        // 表示延迟队列内存储的任务数量
        private int size = 0;

        /**
         * leader字段？ 一种策略
         * 线程池内的某个线程 去 take() 获取任务时，如果 满二叉树 顶层节点不为null（队列内有任务），但是顶层节点 这个 任务 它还不到 交付时间，
         * 这个怎么办？线程就去检查 leader 字段是否被占用，如果未被占用，则当前线程占用该字段，将线程自己 保存 到 leader字段
         * 再下一步，当前线程 到 available 条件队列 指定超时时间（堆顶任务.time - now()） 的挂起。
         *
         * 如果 满二叉树顶层节点不为null（队列内有任务），但是顶层节点 这个 任务 它还不到 交付时间，
         * 线程检查 leader 字段是否被占用，如果被占用了，则当前线程怎么办？直接到available 条件队列 “不指定”超时时间的 挂起。
         *
         * 不指定超时时间挂起，那什么时候会被唤醒呢？..
         * leader 挂起时指定了 超时时间，其实 leader 在 available 条件队列 内 它是 首元素，它超时之后，会醒过来，
         * 然后再次将 堆顶元素 获取走..
         * 获取走之后，take()结束之前，会释放 lock，释放lock前 会做一件事，就是 available.signal() 唤醒下一个 条件队列 内的 等待者
         * 下一个等待者 收到信号之后，去哪了 ？ 去到 AQS 队列了，做 acquireQueue(node) 逻辑去了。
         */
        private Thread leader = null;

        /**
         * 条件队列，很关键的一个东西..
         *
         * Condition signalled when a newer task becomes available at the
         * head of the queue or a new thread may need to become leader.
         */
        private final Condition available = lock.newCondition();




        /**
         * 设置 调度任务的heapIndex 字段
         * Sets f's heapIndex if it is a ScheduledFutureTask.
         */
        private void setIndex(RunnableScheduledFuture<?> f, int idx) {
            if (f instanceof ScheduledFutureTask)
                ((ScheduledFutureTask)f).heapIndex = idx;
        }

        /**
         * 向上冒泡的逻辑，一会看..
         * Sifts element added at bottom up to its heap-ordered spot.
         * Call only when holding lock.
         *
         * k: 新插元素的下标
         * key: 新插元素
         */
        private void siftUp(int k, RunnableScheduledFuture<?> key) {
            // while 条件：k > 0， k 其实表示的是 新插元素的最终存储的下标，
            // 隐含结束循环的条件是 “k == 0”，因为最顶层元素的下标是0，到0之后没办法再冒泡了..
            while (k > 0) {
                // 获取父节点 下标
                int parent = (k - 1) >>> 1;
                // 获取父节点 任务对象
                RunnableScheduledFuture<?> e = queue[parent];
                if (key.compareTo(e) >= 0)
                    // 条件成立：说明 父节点 优先级 是高于 key 任务的，停止向上冒泡的逻辑。
                    break;

                // 执行到这里，说明 父节点 的优先级 是 低于 key 任务的，需要执行 冒泡逻辑..

                // 父节点 占用 当前key 对象的 位置 即可，即下坠一层。
                queue[k] = e;
                // 设置heapIndex
                setIndex(e, k);
                // k 赋值为 原父节点的 下标位置
                k = parent;
            }
            // 结束循环之后，说明当前key任务 找到 它的应许之地了
            // k 这个下标表示的位置，就是 key 对象的应许之地
            queue[k] = key;
            // 设置任务的heapIndex
            setIndex(key, k);
        }

        /**
         * 向下冒泡的逻辑，一会看..
         * Sifts element added at top down to its heap-ordered spot.
         * Call only when holding lock.
         */
        // k： 堆最后节点 当前开始下坠的位置
        // key： 堆最后节点   （在这个时候，它已经认为自己是 堆顶了..）
        private void siftDown(int k, RunnableScheduledFuture<?> key) {
            // 记住：size 在来到 siftDown 之前 已经-1过了.. k 值，最终要成为 key 对象 应该存储的 slot 下标.
            int half = size >>> 1;

            // half 干什么的呢？做为  while 循环条件的一个判断了..
            // half 表示满二叉树 最底层 ，最左侧的节点 下标值，当 k >= half 时，说明 key 对象已经下落到 最底层了，再往下 没法再下坠了..
            while (k < half) {
                // child 最终表示 key 的左右子节点 中 优先级 高的 子节点 下标
                // child 的初始值 是 key 的左子节点的 下标
                int child = (k << 1) + 1;

                // 获取左子节点任务对象
                RunnableScheduledFuture<?> c = queue[child];

                // 获取key的右子节点 下标
                int right = child + 1;

                // 条件1：right < size，成立 说明 key 存在右子节点
                // 条件2：（条件1成立，key在该层时，存在右子节点），c key的左子节点 比较 右子节点
                if (right < size && c.compareTo(queue[right]) > 0)
                    // 将优先级高的子节点 赋值给 c，优先级高的子节点的下标 赋值给 child
                    c = queue[child = right];


                if (key.compareTo(c) <= 0)
                    // 条件成立，说明 key 的优先级 是高于 下层子节点的，说明已经下坠到 该到的层级了..不再继续下坠了..break 跳出循环。
                    break;


                // 执行到这里，说明 key 优先级 是小于 下层子节点的 某个节点的。
                // 与子节点 交换位置..对应就是 下坠一层..(对应c节点 向上升一层)
                queue[k] = c;
                // 设置 c的heapIndex..
                setIndex(c, k);
                // key 下坠一层，占用child 下标..
                k = child;
            }

            // 执行完 while 循环 key对象 肯定已经找到它 自己的位置了..
            // 进入自己的坐席..
            queue[k] = key;
            // 设置好自己的 heapIndex
            setIndex(key, k);
        }

        /**
         * 数组扩容的方法，每次扩容1.5倍
         * Resizes the heap array.  Call only when holding lock.
         */
        private void grow() {
            int oldCapacity = queue.length;
            int newCapacity = oldCapacity + (oldCapacity >> 1); // grow 50%
            if (newCapacity < 0) // overflow
                newCapacity = Integer.MAX_VALUE;
            queue = Arrays.copyOf(queue, newCapacity);
        }

        /**
         * 查询x是否在 任务队列内，如果在，则返回其在 数组的 下标
         * Finds index of given object, or -1 if absent.
         */
        private int indexOf(Object x) {
            if (x != null) {
                if (x instanceof ScheduledFutureTask) {
                    int i = ((ScheduledFutureTask) x).heapIndex;
                    // Sanity check; x could conceivably be a
                    // ScheduledFutureTask from some other pool.
                    if (i >= 0 && i < size && queue[i] == x)
                        return i;
                } else {
                    for (int i = 0; i < size; i++)
                        if (x.equals(queue[i]))
                            return i;
                }
            }
            return -1;
        }



        /**
         * 检查指定x对象，是否存储在队列内
         */
        public boolean contains(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return indexOf(x) != -1;
            } finally {
                lock.unlock();
            }
        }


        /**
         * 从队列删除指定x对象
         */
        public boolean remove(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = indexOf(x);
                if (i < 0)
                    return false;

                setIndex(queue[i], -1);
                int s = --size;
                RunnableScheduledFuture<?> replacement = queue[s];
                queue[s] = null;
                if (s != i) {
                    siftDown(i, replacement);
                    if (queue[i] == replacement)
                        siftUp(i, replacement);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }



        public int size() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }


        public boolean isEmpty() {
            return size() == 0;
        }

        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }


        /**
         * peek() 和 take() 是有区别的，peek() 只是返回 队列首节点的引用，并不会将 首节点 从队列移除。
         */
        public RunnableScheduledFuture<?> peek() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return queue[0];
            } finally {
                lock.unlock();
            }
        }


        /**
         * 添加任务的入口
         * @param x 任务（ScheduledFutureTask 实例）
         */
        public boolean offer(Runnable x) {

            if (x == null)
                throw new NullPointerException();

            // 转换成RunnableScheduledFuture类型，e表示
            RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>)x;

            // 加锁
            final ReentrantLock lock = this.lock;
            lock.lock();
            // 内部的代码 肯定是 串行的！
            try {
                // i，当前任务队列 任务数量
                int i = size;

                if (i >= queue.length)// 条件成立，说明 任务数组 所有的slot都已经占用完了，需要扩容了..
                    // 扩容..
                    grow();

                // size + 1，因为接下来就是存储任务 到 最小堆 的逻辑了，注意：i 还是原来的 size值。
                size = i + 1;


                if (i == 0) {// 条件成立，说明 当前 任务 是最小堆 的第一个节点，将它存放到 数组[0] 的这个位置，即可，不需要执行 向上冒泡的逻辑。
                    queue[0] = e;
                    setIndex(e, 0);
                } else {
                    // 执行到这里，说明 最小堆 存在其它节点，新节点 需要执行向上冒泡的逻辑..

                    // i : 新插元素的下标
                    // e : 新提交的任务
                    siftUp(i, e);
                }



                if (queue[0] == e) {
                    // 两种情况：
                    // 1. 当前任务是 第一个加入到 queue 内的任务
                    // 2. 当前任务不是第一个加入到 queue 内的任务，但是当前任务 优先级 高啊，它上升成为了 堆顶 节点。

                    // 1. 当前任务是 第一个加入到 queue 内的任务
                    // 第一种情况：在当前任务 加入 到 queue 之前，take()线程会直接到 available 不设置超时的挂起。并不会去占用 leader 字段。
                    //  available.signal() 时，会唤醒一个线程 让它去消费...

                    // 2. 当前任务不是第一个加入到 queue 内的任务，但是当前任务 优先级 高啊，它上升成为了 堆顶 节点。
                    // 这种情况下，leader 可能是 有值的，因为原 堆顶 任务 可能还未到 交付时间，leader线程正在 设置超时的 在 available 挂起呢..
                    // 此时 需要将 leader 线程 唤醒，唤醒之后 它 会检查 堆顶，如果堆顶任务 可以被消费，则直接获取走了..否则 继续 成为 leader 等待 新堆顶..

                    leader = null;
                    available.signal();
                }

            } finally {
                // 释放锁
                lock.unlock();
            }
            // 返回true 表示添加任务成功..
            return true;
        }





        public void put(Runnable e) {
            offer(e);
        }

        public boolean add(Runnable e) {
            return offer(e);
        }

        public boolean offer(Runnable e, long timeout, TimeUnit unit) {
            return offer(e);
        }

        /**
         * Performs common bookkeeping for poll and take: Replaces
         * first element with last and sifts it down.  Call only when
         * holding lock.
         * @param f the task to remove and return
         */
        private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
            // --size ，堆顶任务 肯定是要返回走了，所以 减就行了。
            // 将 减1之后的 size 赋值给 s，s表示 堆结构 最后一个节点的下标
            int s = --size;

            // 获取 堆结构 最后一个节点
            RunnableScheduledFuture<?> x = queue[s];

            // 将堆结构 最后一个节点占用的slot设置为null，因为 该节点 要升级成 堆顶，然后执行 下坠的逻辑了..
            queue[s] = null;


            if (s != 0)// s == 0 说明 当前 堆结构 只有堆顶一个节点，此时不需要做任何的事情..
                // 执行if代码块内，说明 堆结构 的元素数量是 > 1 的。这个时候就需要执行 堆最后节点 下坠的逻辑了..
                // k： 堆最后节点 当前开始下坠的位置
                // x： 堆最后节点   （在这个时候，它已经认为自己是 堆顶了..）
                siftDown(0, x);


            // f 原堆顶，将它的 heapIndex 设置为 -1 ，表示从 堆内出去了..
            setIndex(f, -1);

            // 返回原堆顶任务..
            return f;
        }

        public RunnableScheduledFuture<?> poll() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first = queue[0];
                if (first == null || first.getDelay(NANOSECONDS) > 0)
                    return null;
                else
                    return finishPoll(first);
            } finally {
                lock.unlock();
            }
        }


        // 线程池 线程数在 核心数量以内时（<= corePoolSize） 的时候，线程获取任务使用的 方法 是 take() 方法
        public RunnableScheduledFuture<?> take() throws InterruptedException {
            final ReentrantLock lock = this.lock;
            // 获取锁，保证 队列线程安全
            lock.lockInterruptibly();
            try {
                // 自旋，从该循环返回时，一定是获取到 任务了，或者 线程收到了 中断异常..
                for (;;) {
                    // 获取堆顶任务（优先级最高的任务）
                    RunnableScheduledFuture<?> first = queue[0];

                    if (first == null)
                        // 该条件成立，说明队列是空队列，没办法，线程只能在 条件队列 available 不设置超时的挂起..
                        // 什么时候会唤醒呢？ offer时 添加第一个任务时，会signal..
                        available.await();
                    else {
                        // 执行到该分支，说明 任务队列 内存在其它任务

                        // 获取堆顶任务 距离 执行的 时间长度 ，纳秒
                        long delay = first.getDelay(NANOSECONDS);

                        if (delay <= 0)// 该条件成立，说明 堆顶任务 已经到达 交付时间了，需要立马返回 给 线程 去执行了..

                            return finishPoll(first);



                        // 执行到这里，说明什么？
                        // 说明堆顶任务 还未到 交付时间..


                        first = null; // don't retain ref while waiting

                        if (leader != null)
                            // 当前线程不设置 超时时间的挂起..
                            // 不用担心 醒不来的事，leader 它醒来之后，会获取 堆顶元素，返回之前 会 唤醒下一个 等待者线程的..
                            available.await();
                        else {
                            // 因为持锁线程是 自己，没人能给你 抢 leader，所以 这里没有使用CAS

                            // 获取当前线程
                            Thread thisThread = Thread.currentThread();
                            // leader 设置为当前线程
                            leader = thisThread;
                            try {
                                // 在条件队列available使用 带超时的挂起 （堆顶任务.time - now() 纳秒值..）
                                // 当 到达 阻塞时间时，当前线程会从这里醒过来，或者 offer 了 优先级更高 的任务时，offer线程也会唤醒你..
                                // (能从这里醒来，那肯定已经拿到lock了..)
                                available.awaitNanos(delay);
                            } finally {
                                if (leader == thisThread)
                                    // 释放leader字段
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    // 队列内还有其它 任务，此时 唤醒 条件队列内 下一个 等待者，让它去尝试 获取 新的 堆顶节点。
                    available.signal();

                // 释放锁..
                lock.unlock();
            }
        }


        // 线程池 线程数在 核心数量以内时（> corePoolSize） 的时候，线程获取任务使用的 方法 是 poll(..) 指定获取超时的方法
        // 假设返回null，线程池内的该线程 work 就执行 销毁逻辑了..
        public RunnableScheduledFuture<?> poll(long timeout, TimeUnit unit)
            throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (;;) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null) {
                        if (nanos <= 0)
                            return null;
                        else
                            nanos = available.awaitNanos(nanos);
                    } else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0)
                            return finishPoll(first);
                        if (nanos <= 0)
                            return null;
                        first = null; // don't retain ref while waiting
                        if (nanos < delay || leader != null)
                            nanos = available.awaitNanos(nanos);
                        else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                long timeLeft = available.awaitNanos(delay);
                                nanos -= delay - timeLeft;
                            } finally {
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        public void clear() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                for (int i = 0; i < size; i++) {
                    RunnableScheduledFuture<?> t = queue[i];
                    if (t != null) {
                        queue[i] = null;
                        setIndex(t, -1);
                    }
                }
                size = 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns first element only if it is expired.
         * Used only by drainTo.  Call only when holding lock.
         */
        private RunnableScheduledFuture<?> peekExpired() {
            // assert lock.isHeldByCurrentThread();
            RunnableScheduledFuture<?> first = queue[0];
            return (first == null || first.getDelay(NANOSECONDS) > 0) ?
                null : first;
        }

        public int drainTo(Collection<? super Runnable> c) {
            if (c == null)
                throw new NullPointerException();
            if (c == this)
                throw new IllegalArgumentException();
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while ((first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            if (c == null)
                throw new NullPointerException();
            if (c == this)
                throw new IllegalArgumentException();
            if (maxElements <= 0)
                return 0;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while (n < maxElements && (first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public Object[] toArray() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return Arrays.copyOf(queue, size, Object[].class);
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if (a.length < size)
                    return (T[]) Arrays.copyOf(queue, size, a.getClass());
                System.arraycopy(queue, 0, a, 0, size);
                if (a.length > size)
                    a[size] = null;
                return a;
            } finally {
                lock.unlock();
            }
        }

        public Iterator<Runnable> iterator() {
            return new Itr(Arrays.copyOf(queue, size));
        }

        /**
         * Snapshot iterator that works off copy of underlying q array.
         */
        private class Itr implements Iterator<Runnable> {
            final RunnableScheduledFuture<?>[] array;
            int cursor = 0;     // index of next element to return
            int lastRet = -1;   // index of last element, or -1 if no such

            Itr(RunnableScheduledFuture<?>[] array) {
                this.array = array;
            }

            public boolean hasNext() {
                return cursor < array.length;
            }

            public Runnable next() {
                if (cursor >= array.length)
                    throw new NoSuchElementException();
                lastRet = cursor;
                return array[cursor++];
            }

            public void remove() {
                if (lastRet < 0)
                    throw new IllegalStateException();
                DelayedWorkQueue.this.remove(array[lastRet]);
                lastRet = -1;
            }
        }
    }
}
