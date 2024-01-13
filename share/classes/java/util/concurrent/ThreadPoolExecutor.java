/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

/**
 * An {@link ExecutorService} that executes each submitted task using
 * one of possibly several pooled threads, normally configured
 * using {@link Executors} factory methods.
 *
 * <p>Thread pools address two different problems: they usually
 * provide improved performance when executing large numbers of
 * asynchronous tasks, due to reduced per-task invocation overhead,
 * and they provide a means of bounding and managing the resources,
 * including threads, consumed when executing a collection of tasks.
 * Each {@code ThreadPoolExecutor} also maintains some basic
 * statistics, such as the number of completed tasks.
 *
 * <p>To be useful across a wide range of contexts, this class
 * provides many adjustable parameters and extensibility
 * hooks. However, programmers are urged to use the more convenient
 * {@link Executors} factory methods {@link
 * Executors#newCachedThreadPool} (unbounded thread pool, with
 * automatic thread reclamation), {@link Executors#newFixedThreadPool}
 * (fixed size thread pool) and {@link
 * Executors#newSingleThreadExecutor} (single background thread), that
 * preconfigure settings for the most common usage
 * scenarios. Otherwise, use the following guide when manually
 * configuring and tuning this class:
 *
 * <dl>
 *
 * <dt>Core and maximum pool sizes</dt>
 *
 * <dd>A {@code ThreadPoolExecutor} will automatically adjust the
 * pool size (see {@link #getPoolSize})
 * according to the bounds set by
 * corePoolSize (see {@link #getCorePoolSize}) and
 * maximumPoolSize (see {@link #getMaximumPoolSize}).
 *
 * When a new task is submitted in method {@link #execute(Runnable)},
 * and fewer than corePoolSize threads are running, a new thread is
 * created to handle the request, even if other worker threads are
 * idle.  If there are more than corePoolSize but less than
 * maximumPoolSize threads running, a new thread will be created only
 * if the queue is full.  By setting corePoolSize and maximumPoolSize
 * the same, you create a fixed-size thread pool. By setting
 * maximumPoolSize to an essentially unbounded value such as {@code
 * Integer.MAX_VALUE}, you allow the pool to accommodate an arbitrary
 * number of concurrent tasks. Most typically, core and maximum pool
 * sizes are set only upon construction, but they may also be changed
 * dynamically using {@link #setCorePoolSize} and {@link
 * #setMaximumPoolSize}. </dd>
 *
 * <dt>On-demand construction</dt>
 *
 * <dd>By default, even core threads are initially created and
 * started only when new tasks arrive, but this can be overridden
 * dynamically using method {@link #prestartCoreThread} or {@link
 * #prestartAllCoreThreads}.  You probably want to prestart threads if
 * you construct the pool with a non-empty queue. </dd>
 *
 * <dt>Creating new threads</dt>
 *
 * <dd>New threads are created using a {@link ThreadFactory}.  If not
 * otherwise specified, a {@link Executors#defaultThreadFactory} is
 * used, that creates threads to all be in the same {@link
 * ThreadGroup} and with the same {@code NORM_PRIORITY} priority and
 * non-daemon status. By supplying a different ThreadFactory, you can
 * alter the thread's name, thread group, priority, daemon status,
 * etc. If a {@code ThreadFactory} fails to create a thread when asked
 * by returning null from {@code newThread}, the executor will
 * continue, but might not be able to execute any tasks. Threads
 * should possess the "modifyThread" {@code RuntimePermission}. If
 * worker threads or other threads using the pool do not possess this
 * permission, service may be degraded: configuration changes may not
 * take effect in a timely manner, and a shutdown pool may remain in a
 * state in which termination is possible but not completed.</dd>
 *
 * <dt>Keep-alive times</dt>
 *
 * <dd>If the pool currently has more than corePoolSize threads,
 * excess threads will be terminated if they have been idle for more
 * than the keepAliveTime (see {@link #getKeepAliveTime(TimeUnit)}).
 * This provides a means of reducing resource consumption when the
 * pool is not being actively used. If the pool becomes more active
 * later, new threads will be constructed. This parameter can also be
 * changed dynamically using method {@link #setKeepAliveTime(long,
 * TimeUnit)}.  Using a value of {@code Long.MAX_VALUE} {@link
 * TimeUnit#NANOSECONDS} effectively disables idle threads from ever
 * terminating prior to shut down. By default, the keep-alive policy
 * applies only when there are more than corePoolSize threads. But
 * method {@link #allowCoreThreadTimeOut(boolean)} can be used to
 * apply this time-out policy to core threads as well, so long as the
 * keepAliveTime value is non-zero. </dd>
 *
 * <dt>Queuing</dt>
 *
 * <dd>Any {@link BlockingQueue} may be used to transfer and hold
 * submitted tasks.  The use of this queue interacts with pool sizing:
 *
 * <ul>
 *
 * <li> If fewer than corePoolSize threads are running, the Executor
 * always prefers adding a new thread
 * rather than queuing.</li>
 *
 * <li> If corePoolSize or more threads are running, the Executor
 * always prefers queuing a request rather than adding a new
 * thread.</li>
 *
 * <li> If a request cannot be queued, a new thread is created unless
 * this would exceed maximumPoolSize, in which case, the task will be
 * rejected.</li>
 *
 * </ul>
 *
 * There are three general strategies for queuing:
 * <ol>
 *
 * <li> <em> Direct handoffs.</em> A good default choice for a work
 * queue is a {@link SynchronousQueue} that hands off tasks to threads
 * without otherwise holding them. Here, an attempt to queue a task
 * will fail if no threads are immediately available to run it, so a
 * new thread will be constructed. This policy avoids lockups when
 * handling sets of requests that might have internal dependencies.
 * Direct handoffs generally require unbounded maximumPoolSizes to
 * avoid rejection of new submitted tasks. This in turn admits the
 * possibility of unbounded thread growth when commands continue to
 * arrive on average faster than they can be processed.  </li>
 *
 * <li><em> Unbounded queues.</em> Using an unbounded queue (for
 * example a {@link LinkedBlockingQueue} without a predefined
 * capacity) will cause new tasks to wait in the queue when all
 * corePoolSize threads are busy. Thus, no more than corePoolSize
 * threads will ever be created. (And the value of the maximumPoolSize
 * therefore doesn't have any effect.)  This may be appropriate when
 * each task is completely independent of others, so tasks cannot
 * affect each others execution; for example, in a web page server.
 * While this style of queuing can be useful in smoothing out
 * transient bursts of requests, it admits the possibility of
 * unbounded work queue growth when commands continue to arrive on
 * average faster than they can be processed.  </li>
 *
 * <li><em>Bounded queues.</em> A bounded queue (for example, an
 * {@link ArrayBlockingQueue}) helps prevent resource exhaustion when
 * used with finite maximumPoolSizes, but can be more difficult to
 * tune and control.  Queue sizes and maximum pool sizes may be traded
 * off for each other: Using large queues and small pools minimizes
 * CPU usage, OS resources, and context-switching overhead, but can
 * lead to artificially low throughput.  If tasks frequently block (for
 * example if they are I/O bound), a system may be able to schedule
 * time for more threads than you otherwise allow. Use of small queues
 * generally requires larger pool sizes, which keeps CPUs busier but
 * may encounter unacceptable scheduling overhead, which also
 * decreases throughput.  </li>
 *
 * </ol>
 *
 * </dd>
 *
 * <dt>Rejected tasks</dt>
 *
 * <dd>New tasks submitted in method {@link #execute(Runnable)} will be
 * <em>rejected</em> when the Executor has been shut down, and also when
 * the Executor uses finite bounds for both maximum threads and work queue
 * capacity, and is saturated.  In either case, the {@code execute} method
 * invokes the {@link
 * RejectedExecutionHandler#rejectedExecution(Runnable, ThreadPoolExecutor)}
 * method of its {@link RejectedExecutionHandler}.  Four predefined handler
 * policies are provided:
 *
 * <ol>
 *
 * <li> In the default {@link ThreadPoolExecutor.AbortPolicy}, the
 * handler throws a runtime {@link RejectedExecutionException} upon
 * rejection. </li>
 *
 * <li> In {@link ThreadPoolExecutor.CallerRunsPolicy}, the thread
 * that invokes {@code execute} itself runs the task. This provides a
 * simple feedback control mechanism that will slow down the rate that
 * new tasks are submitted. </li>
 *
 * <li> In {@link ThreadPoolExecutor.DiscardPolicy}, a task that
 * cannot be executed is simply dropped.  </li>
 *
 * <li>In {@link ThreadPoolExecutor.DiscardOldestPolicy}, if the
 * executor is not shut down, the task at the head of the work queue
 * is dropped, and then execution is retried (which can fail again,
 * causing this to be repeated.) </li>
 *
 * </ol>
 *
 * It is possible to define and use other kinds of {@link
 * RejectedExecutionHandler} classes. Doing so requires some care
 * especially when policies are designed to work only under particular
 * capacity or queuing policies. </dd>
 *
 * <dt>Hook methods</dt>
 *
 * <dd>This class provides {@code protected} overridable
 * {@link #beforeExecute(Thread, Runnable)} and
 * {@link #afterExecute(Runnable, Throwable)} methods that are called
 * before and after execution of each task.  These can be used to
 * manipulate the execution environment; for example, reinitializing
 * ThreadLocals, gathering statistics, or adding log entries.
 * Additionally, method {@link #terminated} can be overridden to perform
 * any special processing that needs to be done once the Executor has
 * fully terminated.
 *
 * <p>If hook or callback methods throw exceptions, internal worker
 * threads may in turn fail and abruptly terminate.</dd>
 *
 * <dt>Queue maintenance</dt>
 *
 * <dd>Method {@link #getQueue()} allows access to the work queue
 * for purposes of monitoring and debugging.  Use of this method for
 * any other purpose is strongly discouraged.  Two supplied methods,
 * {@link #remove(Runnable)} and {@link #purge} are available to
 * assist in storage reclamation when large numbers of queued tasks
 * become cancelled.</dd>
 *
 * <dt>Finalization</dt>
 *
 * <dd>A pool that is no longer referenced in a program <em>AND</em>
 * has no remaining threads will be {@code shutdown} automatically. If
 * you would like to ensure that unreferenced pools are reclaimed even
 * if users forget to call {@link #shutdown}, then you must arrange
 * that unused threads eventually die, by setting appropriate
 * keep-alive times, using a lower bound of zero core threads and/or
 * setting {@link #allowCoreThreadTimeOut(boolean)}.  </dd>
 *
 * </dl>
 *
 * <p><b>Extension example</b>. Most extensions of this class
 * override one or more of the protected hook methods. For example,
 * here is a subclass that adds a simple pause/resume feature:
 *
 *  <pre> {@code
 * class PausableThreadPoolExecutor extends ThreadPoolExecutor {
 *   private boolean isPaused;
 *   private ReentrantLock pauseLock = new ReentrantLock();
 *   private Condition unpaused = pauseLock.newCondition();
 *
 *   public PausableThreadPoolExecutor(...) { super(...); }
 *
 *   protected void beforeExecute(Thread t, Runnable r) {
 *     super.beforeExecute(t, r);
 *     pauseLock.lock();
 *     try {
 *       while (isPaused) unpaused.await();
 *     } catch (InterruptedException ie) {
 *       t.interrupt();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void pause() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = true;
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void resume() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = false;
 *       unpaused.signalAll();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 * }}</pre>
 *
 * /**
 *  * 一个{@link ExecutorService}，它使用可能多个池化线程之一执行每个提交的任务，
 *  * 通常使用{@link Executors}工厂方法进行配置。
 *  *
 *  * <p>线程池解决了两个不同的问题：它们在执行大量异步任务时通常提供改善的性能，
 *  * 这是由于减少了每个任务的调用开销，它们还提供了一种方法来限制和管理执行任务集时消耗的资源，
 *  * 包括线程。每个{@code ThreadPoolExecutor}还维护一些基本统计信息，比如完成的任务数量。
 *  *
 *  * <p>为了在广泛的上下文中有用，这个类提供了许多可调整的参数和可扩展性钩子。
 *  * 然而，程序员被敦促使用更方便的{@link Executors}工厂方法
 *  * {@link Executors#newCachedThreadPool}（无界线程池，具有自动线程回收）、
 *  * {@link Executors#newFixedThreadPool}（固定大小线程池）和
 *  * {@link Executors#newSingleThreadExecutor}（单后台线程），这些方法预先配置了最常见用例的设置。
 *  * 否则，在手动配置和调整此类时，请使用以下指南：
 *  *
 *  * <dl>
 *  *
 *  * <dt>核心和最大池大小</dt>
 *  *
 *  * <dd>一个{@code ThreadPoolExecutor}会根据由
 *  * corePoolSize（参见{@link #getCorePoolSize}）和
 *  * maximumPoolSize（参见{@link #getMaximumPoolSize}）设置的边界自动调整池大小（参见{@link #getPoolSize}）。
 *  *
 *  * 当在方法{@link #execute(Runnable)}中提交一个新任务，并且运行的线程少于corePoolSize时，
 *  * 即使有其他工作线程空闲，也会创建一个新线程来处理请求。如果运行的线程多于corePoolSize但少于maximumPoolSize，
 *  * 只有当队列已满时才会创建新线程。通过设置corePoolSize和maximumPoolSize相同，你创建了一个固定大小的线程池。
 *  * 通过将maximumPoolSize设置为实际上无界的值，比如{@code Integer.MAX_VALUE}，你允许池适应任意数量的并发任务。
 *  * 最典型的是，核心和最大池大小只在构造时设置，但它们也可以使用{@link #setCorePoolSize}和
 *  * {@link #setMaximumPoolSize}动态更改。</dd>
 *  *
 *  * <dt>按需构建</dt>
 *  *
 *  * <dd>默认情况下，即使是核心线程最初也只在新任务到达时创建和启动，但可以使用方法{@link #prestartCoreThread}或
 *  * {@link #prestartAllCoreThreads}动态覆盖这一点。如果你使用非空队列构建池，可能会想要预先启动线程。</dd>
 *  *
 *  * <dt>创建新线程</dt>
 *  *
 *  * <dd>使用{@link ThreadFactory}创建新线程。如果没有另外指定，将使用{@link Executors#defaultThreadFactory}，
 *  * 它创建的线程都在同一个{@link ThreadGroup}中，并具有相同的{@code NORM_PRIORITY}优先级和非守护状态。
 *  * 通过提供不同的ThreadFactory，你可以改变线程的名称、线程组、优先级、守护状态等。
 *  * 如果{@code ThreadFactory}在被要求创建线程时失败，通过从{@code newThread}返回null，
 *  * 执行器将继续，但可能无法执行任何任务。线程应该拥有"modifyThread" {@code RuntimePermission}。
 *  * 如果使用池的工作线程或其他线程不具备这个权限，服务可能会降级：配置更改可能不会及时生效，
 *  * 并且关闭的池可能仍处于可能但未完成终止的状态。</dd>
 *  *
 *  * <dt>保活时间</dt>
 *  *
 *  * <dd>如果池当前拥有超过corePoolSize的线程，如果这些线程空闲时间超过keepAliveTime（参见{@link #getKeepAliveTime(TimeUnit)}），
 *  * 多余的线程将被终止。这提供了一种在池不活跃时减少资源消耗的方法。如果池后来变得更活跃，将构建新线程。
 *  * 这个参数也可以使用方法{@link #setKeepAliveTime(long, TimeUnit)}动态更改。
 *  * 使用{@code Long.MAX_VALUE} {@link TimeUnit#NANOSECONDS}的值实际上禁止空闲线程在关闭之前永远终止。
 *  * 默认情况下，保活策略仅在有超过corePoolSize的线程时应用。但是方法{@link #allowCoreThreadTimeOut(boolean)}
 *  * 可用于将此超时策略应用于核心线程，只要keepAliveTime值不为零。</dd>
 *  *
 *  * <dt>队列</dt>
 *  *
 *  * <dd>任何{@link BlockingQueue}可用于传输和保持提交的任务。此队列的使用与池大小的互动：
 *  *
 *  * <ul>
 *  *
 *  * <li>如果运行的线程少于corePoolSize，执行器总是倾向于添加一个新线程而不是排队。</li>
 *  *
 *  * <li>如果运行的线程等于或多于corePoolSize，执行器总是倾向于排队请求而不是添加一个新线程。</li>
 *  *
 *  * <li>如果请求不能排队，将创建一个新线程，除非这会超过maximumPoolSize，在这种情况下，任务将被拒绝。</li>
 *  *
 *  * </ul>
 *  *
 *  * 队列有三种一般策略：
 *  * <ol>
 *  *
 *  * <li> <em>直接传递。</em> 工作队列的一个好的默认选择是{@link SynchronousQueue}，
 *  * 它将任务传递给线程而不另外保留它们。在这里，如果没有线程立即可用来运行它，则尝试排队任务将失败，
 *  * 因此将构建一个新线程。这种策略避免了在处理可能具有内部依赖的请求集时发生锁定。
 *  * 直接传递通常需要无界的maximumPoolSizes，以避免新提交的任务被拒绝。
 *  * 这反过来又允许在命令到达的平均速度超过它们可以被处理的速度时，线程增长无界。</li>
 *  *
 *  * <li><em>无界队列。</em> 使用无界队列（例如，没有预定义容量的{@link LinkedBlockingQueue}）
 *  * 会导致所有corePoolSize线程都忙时新任务在队列中等待。因此，将永远不会创建超过corePoolSize的线程。
 *  * （因此，maximumPoolSize的值实际上没有任何效果。）当每个任务完全独立于其他任务时，这可能是合适的，
 *  * 因此任务不能影响彼此的执行；例如，在网页服务器中。虽然这种排队风格在平滑瞬时请求突发方面可能有用，
 *  * 但它承认了在命令平均到达速度超过它们可以被处理的速度时，工作队列增长无界的可能性。</li>
 *  *
 *  * <li><em>有界队列。</em> 有界队列（例如，{@link ArrayBlockingQueue}）在与有限的maximumPoolSizes一起使用时，
 *  * 有助于防止资源耗尽，但可能更难调整和控制。队列大小和最大池大小可以互相交换：使用大队列和小池最大限度地减少CPU使用、
 *  * 操作系统资源和上下文切换开销，但可能导致人为降低吞吐量。如果任务经常阻塞（例如，如果它们是I/O绑定的），
 *  * 系统可能能够为您允许的线程安排更多时间。使用小队列通常需要更大的池大小，这使得CPU更忙，
 *  * 但可能遇到不可接受的调度开销，这也减少了吞吐量。</li>
 *  *
 *  * </ol>
 *  *
 *  * </dd>
 *  *
 *  * <dt>拒绝任务</dt>
 *  *
 *  * <dd>在方法{@link #execute(Runnable)}中提交的新任务将在执行器关闭时被<em>拒绝</em>，
 *  * 以及当执行器对最大线程和工作队列容量都使用有限界限，并且饱和时。在任一情况下，
 *  * {@code execute}方法调用其{@link RejectedExecutionHandler}的
 *  * {@link RejectedExecutionHandler#rejectedExecution(Runnable, ThreadPoolExecutor)}方法。
 *  * 提供了四种预定义的处理程序策略：
 *  *
 *  * <ol>
 *  *
 *  * <li>在默认的{@link ThreadPoolExecutor.AbortPolicy}中，处理程序在拒绝时抛出运行时{@link RejectedExecutionException}。</li>
 *  *
 *  * <li>在{@link ThreadPoolExecutor.CallerRunsPolicy}中，调用{@code execute}的线程本身运行任务。
 *  * 这提供了一个简单的反馈控制机制，将减慢新任务提交的速度。</li>
 *  *
 *  * <li>在{@link ThreadPoolExecutor.DiscardPolicy}中，无法执行的任务简单地被丢弃。</li>
 *  *
 *  * <li>在{@link ThreadPoolExecutor.DiscardOldestPolicy}中，如果执行器没有关闭，
 *  * 则丢弃工作队列头部的任务，然后重试执行（这可能再次失败，导致这一过程重复。）</li>
 *  *
 *  * </ol>
 *  *
 *  * 可以定义和使用其他类型的{@link RejectedExecutionHandler}类。这样做需要一些注意，
 *  * 尤其是当策略被设计为仅在特定容量或排队策略下工作时。</dd>
 *  *
 *  * <dt>钩子方法</dt>
 *  *
 *  * <dd>这个类提供了{@code protected}可重写的{@link #beforeExecute(Thread, Runnable)}和
 *  * {@link #afterExecute(Runnable, Throwable)}方法，这些方法在每个任务执行前后调用。
 *  * 这些可以用来操纵执行环境；例如，重新初始化ThreadLocals、收集统计数据或添加日志条目。
 *  * 此外，可以重写方法{@link #terminated}以执行Executor完全终止后需要进行的任何特殊处理。
 *  *
 *  * <p>如果钩子或回调方法抛出异常，内部工作线程可能反过来失败并突然终止。</dd>
 *  *
 *  * <dt>队列维护</dt>
 *  *
 *  * <dd>方法{@link #getQueue()}允许访问工作队列以进行监控和调试。
 *  * 强烈不鼓励将此方法用于任何其他目的。提供了两种方法，{@link #remove(Runnable)}和{@link #purge}，
 *  * 可用于在大量排队任务变得取消时协助存储回收。</dd>
 *  *
 *  * <dt>终结</dt>
 *  *
 *  * <dd>不再在程序中引用并且没有剩余线程的池将自动被{@code shutdown}。
 *  * 如果您希望确保即使用户忘记调用{@link #shutdown}，未引用的池也会被回收，
 *  * 那么您必须安排未使用的线程最终死亡，通过设置适当的保活时间，使用零核心线程的下限和/或设置
 *  * {@link #allowCoreThreadTimeOut(boolean)}。  </dd>
 *  *
 *  * </dl>
 *  *
 *  * <p><b>扩展示例</
 *
 * @since 1.5
 * @author Doug Lea
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * The main pool control state, ctl, is an atomic integer packing
     * two conceptual fields
     *   workerCount, indicating the effective number of threads
     *   runState,    indicating whether running, shutting down etc
     *
     * In order to pack them into one int, we limit workerCount to
     * (2^29)-1 (about 500 million) threads rather than (2^31)-1 (2
     * billion) otherwise representable. If this is ever an issue in
     * the future, the variable can be changed to be an AtomicLong,
     * and the shift/mask constants below adjusted. But until the need
     * arises, this code is a bit faster and simpler using an int.
     *
     * The workerCount is the number of workers that have been
     * permitted to start and not permitted to stop.  The value may be
     * transiently different from the actual number of live threads,
     * for example when a ThreadFactory fails to create a thread when
     * asked, and when exiting threads are still performing
     * bookkeeping before terminating. The user-visible pool size is
     * reported as the current size of the workers set.
     *
     * The runState provides the main lifecycle control, taking on values:
     *
     *   RUNNING:  Accept new tasks and process queued tasks
     *   SHUTDOWN: Don't accept new tasks, but process queued tasks
     *   STOP:     Don't accept new tasks, don't process queued tasks,
     *             and interrupt in-progress tasks
     *   TIDYING:  All tasks have terminated, workerCount is zero,
     *             the thread transitioning to state TIDYING
     *             will run the terminated() hook method
     *   TERMINATED: terminated() has completed
     *
     * The numerical order among these values matters, to allow
     * ordered comparisons. The runState monotonically increases over
     * time, but need not hit each state. The transitions are:
     *
     * RUNNING -> SHUTDOWN
     *    On invocation of shutdown(), perhaps implicitly in finalize()
     * (RUNNING or SHUTDOWN) -> STOP
     *    On invocation of shutdownNow()
     * SHUTDOWN -> TIDYING
     *    When both queue and pool are empty
     * STOP -> TIDYING
     *    When pool is empty
     * TIDYING -> TERMINATED
     *    When the terminated() hook method has completed
     *
     * Threads waiting in awaitTermination() will return when the
     * state reaches TERMINATED.
     *
     * Detecting the transition from SHUTDOWN to TIDYING is less
     * straightforward than you'd like because the queue may become
     * empty after non-empty and vice versa during SHUTDOWN state, but
     * we can only terminate if, after seeing that it is empty, we see
     * that workerCount is 0 (which sometimes entails a recheck -- see
     * below).
     *
     * 主要的池控制状态`ctl`是一个原子整数，封装了两个概念性字段：
     *   `workerCount`，表示有效的线程数量；
     *   `runState`，表示线程池的运行状态，如运行中、正在关闭等。
     *
     * 为了将这两个字段封装到一个整数中，我们将`workerCount`的限制为(2^29)-1（大约5亿）个线程，
     * 而不是(2^31)-1（20亿）个线程，后者在理论上是可表示的。如果将来这成为问题，
     * 可以将这个变量改为`AtomicLong`，并调整下面的位移/掩码常量。但在需要之前，
     * 使用整数会使代码更快一些，也更简单。
     *
     * `workerCount`是被允许启动且未被允许停止的工作线程数量。这个值可能与实际的活跃线程数暂时不同，
     * 例如当`ThreadFactory`在被要求时无法创建线程，以及退出线程在终止前仍在执行簿记工作时。
     * 用户可见的池大小报告为工作线程集的当前大小。
     *
     * `runState`提供主要的生命周期控制，它有以下几种值：
     *
     *   RUNNING：接受新任务并处理队列中的任务
     *   SHUTDOWN：不接受新任务，但处理队列中的任务
     *   STOP：不接受新任务，不处理队列中的任务，并中断进行中的任务
     *   TIDYING：所有任务已终止，workerCount为零，
     *            正在转换到TIDYING状态的线程将运行terminated()钩子方法
     *   TERMINATED：terminated()已完成
     *
     * 这些值之间的数值顺序很重要，以允许有序比较。`runState`随时间单调增加，
     * 但不需要经历每个状态。状态转换包括：
     *
     *   RUNNING -> SHUTDOWN
     *      在调用shutdown()时发生，也可能在finalize()中隐式发生
     *   (RUNNING or SHUTDOWN) -> STOP
     *      在调用shutdownNow()时发生
     *   SHUTDOWN -> TIDYING
     *      当队列和池都为空时发生
     *   STOP -> TIDYING
     *      当池为空时发生
     *   TIDYING -> TERMINATED
     *      当terminated()钩子方法完成时发生
     *
     * 等待在awaitTermination()中的线程将在状态达到TERMINATED时返回。
     *
     * 从SHUTDOWN到TIDYING的转换不如你希望的那样直接，因为在SHUTDOWN状态期间，
     * 队列可能在非空后变为空，反之亦然，但我们只能在看到它为空后，再看到workerCount为0时终止
     * （这有时需要重新检查 —— 见下文）。
     *
     *
     */
// ThreadPoolExecutor 中 ctl 变量的实现
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

    // COUNT_BITS 表示用于 workerCount 的位数
    private static final int COUNT_BITS = Integer.SIZE - 3; // Integer.SIZE 是 32，所以 COUNT_BITS 是 29

    // CAPACITY 是 workerCount 可以使用的最大值，由 COUNT_BITS 确定
    // (1 << COUNT_BITS) 是将 1 左移 29 位，然后减去 1 得到 29 个连续的 1，二进制表示为 0001 1111 1111 1111 1111 1111 1111 1110
    private static final int CAPACITY = (1 << COUNT_BITS) - 1;

    // runState 存储在 ctl 的高位，用不同的值表示不同的状态
    private static final int RUNNING = -1 << COUNT_BITS;  // 所有位都是 1，左移 29 位后，变成 1110 0000 0000 0000 0000 0000 0000 0000
    private static final int SHUTDOWN = 0 << COUNT_BITS;  // 所有位都是 0，结果仍然是 0
    private static final int STOP = 1 << COUNT_BITS;      // 二进制 1，左移 29 位，变成 0010 0000 0000 0000 0000 0000 0000 0000
    private static final int TIDYING = 2 << COUNT_BITS;   // 二进制 10，左移 29 位，变成 0100 0000 0000 0000 0000 0000 0000 0000
    private static final int TERMINATED = 3 << COUNT_BITS; // 二进制 11，左移 29 位，变成 0110 0000 0000 0000 0000 0000 0000 0000

    // Packing and unpacking ctl
    // 从 ctl 提取 runState，通过将 ctl 与 ~CAPACITY 进行与操作，清除低 29 位，保留 runState 高位
    private static int runStateOf(int c) { return c & ~CAPACITY; }

    // 从 ctl 提取 workerCount，通过将 ctl 与 CAPACITY 进行与操作，清除 runState 高位，保留 workerCount 低位
    private static int workerCountOf(int c) { return c & CAPACITY; }

    // 组合 runState 和 workerCount 为 ctl，通过或操作合并
    private static int ctlOf(int rs, int wc) { return rs | wc; }


    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * Attempts to CAS-increment the workerCount field of ctl.
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * Attempts to CAS-decrement the workerCount field of ctl.
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * Decrements the workerCount field of ctl. This is called only on
     * abrupt termination of a thread (see processWorkerExit). Other
     * decrements are performed within getTask.
     */
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * The queue used for holding tasks and handing off to worker
     * threads.  We do not require that workQueue.poll() returning
     * null necessarily means that workQueue.isEmpty(), so rely
     * solely on isEmpty to see if the queue is empty (which we must
     * do for example when deciding whether to transition from
     * SHUTDOWN to TIDYING).  This accommodates special-purpose
     * queues such as DelayQueues for which poll() is allowed to
     * return null even if it may later return non-null when delays
     * expire.
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * Lock held on access to workers set and related bookkeeping.
     * While we could use a concurrent set of some sort, it turns out
     * to be generally preferable to use a lock. Among the reasons is
     * that this serializes interruptIdleWorkers, which avoids
     * unnecessary interrupt storms, especially during shutdown.
     * Otherwise exiting threads would concurrently interrupt those
     * that have not yet interrupted. It also simplifies some of the
     * associated statistics bookkeeping of largestPoolSize etc. We
     * also hold mainLock on shutdown and shutdownNow, for the sake of
     * ensuring workers set is stable while separately checking
     * permission to interrupt and actually interrupting.
     * /**
     *  * 访问工作线程集合及相关记录时持有的锁。
     *  * 虽然我们可以使用某种并发集合，但使用锁通常更可取。
     *  * 其中一个原因是这样可以序列化interruptIdleWorkers，
     *  * 避免在关闭过程中不必要的中断风暴。
     *  * 否则，正在退出的线程会并发地中断那些尚未被中断的线程。
     *  * 这也简化了largestPoolSize等相关统计记录的一些工作。
     *  * 我们还在shutdown和shutdownNow时持有mainLock，
     *  * 以确保在分别检查中断权限和实际进行中断时，工作线程集合是稳定的。
     *  *
     *  mainLock 的作用
     * mainLock 是 ThreadPoolExecutor 中的一个锁，它的作用是保护线程池的整体状态，尤其是工作线程集合（workers set）和相关的统计信息。
     * 这个锁不是用来控制单个工作线程的行为，而是用来确保线程池级别的操作（如关闭线程池、添加或移除工作线程）是线程安全的。下面是 mainLock 的几个主要用途：
     *
     * 保护线程集合：当添加或移除工作线程时，需要确保这些操作不会相互冲突，以保持线程池的一致性和稳定性。
     *
     * 序列化 interruptIdleWorkers：这有助于避免在关闭线程池时产生大量不必要的中断。通过序列化这个过程，可以更加有序地中断空闲线程。
     *
     * 统计信息维护：简化了对 largestPoolSize 等统计数据的跟踪和更新。
     *
     * 在关闭操作中的作用：在执行 shutdown 和 shutdownNow 操作时，mainLock 确保在检查中断权限和实际执行中断之前，工作线程集合是稳定的。
     *
     * 为什么使用重入锁（ReentrantLock）
     * ReentrantLock 是一种灵活的锁机制，它允许锁的重入，即同一个线程可以多次获取同一个锁。在线程池的上下文中，
     * 可能会有一些复杂的锁定方案，其中一个操作可能需要多次获取同一个锁。使用 ReentrantLock 可以简化这种复杂操作的锁管理，同时保持足够的灵活性和性能。
     *
     * 结论
     * 总之，mainLock 用于保护线程池级别的状态和操作，而 Worker 类中的 AQS 锁状态是用于管理单个工作线程的中断和生命周期。
     * 这两种锁机制服务于不同的目的，共同确保了线程池的稳定和高效运行。
     *
     *
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * Set containing all worker threads in pool. Accessed only when
     * holding mainLock.
     */
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * Wait condition to support awaitTermination
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * Tracks largest attained pool size. Accessed only under
     * mainLock.
     */
    private int largestPoolSize;

    /**
     * Counter for completed tasks. Updated only on termination of
     * worker threads. Accessed only under mainLock.
     */
    private long completedTaskCount;

    /*
     * All user control parameters are declared as volatiles so that
     * ongoing actions are based on freshest values, but without need
     * for locking, since no internal invariants depend on them
     * changing synchronously with respect to other actions.
     */

    /**
     * Factory for new threads. All threads are created using this
     * factory (via method addWorker).  All callers must be prepared
     * for addWorker to fail, which may reflect a system or user's
     * policy limiting the number of threads.  Even though it is not
     * treated as an error, failure to create threads may result in
     * new tasks being rejected or existing ones remaining stuck in
     * the queue.
     *
     * We go further and preserve pool invariants even in the face of
     * errors such as OutOfMemoryError, that might be thrown while
     * trying to create threads.  Such errors are rather common due to
     * the need to allocate a native stack in Thread.start, and users
     * will want to perform clean pool shutdown to clean up.  There
     * will likely be enough memory available for the cleanup code to
     * complete without encountering yet another OutOfMemoryError.
     */
    private volatile ThreadFactory threadFactory;

    /**
     * Handler called when saturated or shutdown in execute.
     */
    private volatile RejectedExecutionHandler handler;

    /**
     * Timeout in nanoseconds for idle threads waiting for work.
     * Threads use this timeout when there are more than corePoolSize
     * present or if allowCoreThreadTimeOut. Otherwise they wait
     * forever for new work.
     */
    private volatile long keepAliveTime;

    /**
     * If false (default), core threads stay alive even when idle.
     * If true, core threads use keepAliveTime to time out waiting
     * for work.
     */
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * Core pool size is the minimum number of workers to keep alive
     * (and not allow to time out etc) unless allowCoreThreadTimeOut
     * is set, in which case the minimum is zero.
     */
    private volatile int corePoolSize;

    /**
     * Maximum pool size. Note that the actual maximum is internally
     * bounded by CAPACITY.
     */
    private volatile int maximumPoolSize;

    /**
     * The default rejected execution handler
     */
    private static final RejectedExecutionHandler defaultHandler =
        new AbortPolicy();

    /**
     * Permission required for callers of shutdown and shutdownNow.
     * We additionally require (see checkShutdownAccess) that callers
     * have permission to actually interrupt threads in the worker set
     * (as governed by Thread.interrupt, which relies on
     * ThreadGroup.checkAccess, which in turn relies on
     * SecurityManager.checkAccess). Shutdowns are attempted only if
     * these checks pass.
     *
     * All actual invocations of Thread.interrupt (see
     * interruptIdleWorkers and interruptWorkers) ignore
     * SecurityExceptions, meaning that the attempted interrupts
     * silently fail. In the case of shutdown, they should not fail
     * unless the SecurityManager has inconsistent policies, sometimes
     * allowing access to a thread and sometimes not. In such cases,
     * failure to actually interrupt threads may disable or delay full
     * termination. Other uses of interruptIdleWorkers are advisory,
     * and failure to actually interrupt will merely delay response to
     * configuration changes so is not handled exceptionally.
     */
    private static final RuntimePermission shutdownPerm =
        new RuntimePermission("modifyThread");

    /* The context to be used when executing the finalizer, or null. */
    private final AccessControlContext acc;

    /**
     * Class Worker mainly maintains interrupt control state for
     * threads running tasks, along with other minor bookkeeping.
     * This class opportunistically extends AbstractQueuedSynchronizer
     * to simplify acquiring and releasing a lock surrounding each
     * task execution.  This protects against interrupts that are
     * intended to wake up a worker thread waiting for a task from
     * instead interrupting a task being run.  We implement a simple
     * non-reentrant mutual exclusion lock rather than use
     * ReentrantLock because we do not want worker tasks to be able to
     * reacquire the lock when they invoke pool control methods like
     * setCorePoolSize.  Additionally, to suppress interrupts until
     * the thread actually starts running tasks, we initialize lock
     * state to a negative value, and clear it upon start (in
     * runWorker).
     *  * Worker类主要用于维护运行任务的线程的中断控制状态，以及其他一些次要的记录。
     *  * 这个类巧妙地扩展了AbstractQueuedSynchronizer，
     *  * 以简化在每个任务执行周围获取和释放锁的操作。
     *  * 这样做可以防止原本意图唤醒等待任务的工作线程的中断，
     *  * 而误中断了正在运行的任务。
     *  * 我们实现了一个简单的非重入的互斥锁，
     *  * 而不是使用ReentrantLock，因为我们不希望工作任务在调用池控制方法（如setCorePoolSize）时能够重新获取锁。
     *  * 此外，为了在线程实际开始运行任务之前抑制中断，
     *  * 我们将锁状态初始化为一个负值，并在开始时（在runWorker中）清除它。
     *
     *  Worker 继承 AbstractQueuedSynchronizer (AQS)
     * Worker 类继承自 AQS 主要是为了利用 AQS 提供的同步机制来管理线程的状态，特别是在中断控制方面。这里的关键点是如何处理线程池的关闭和线程中断请求，同时确保正在执行的任务不会受到不适当的中断。
     *
     * 中断管理
     * 在一个线程池中，当发出关闭请求时（比如调用 shutdown() 或 shutdownNow()），通常需要逐步停止线程的执行。
     * 这时，线程池会向空闲线程发送中断信号。但是，对于那些正在执行任务的线程，我们不希望它们立即响应中断，因为这可能会导致正在执行的任务被提前终止。
     * 通过 Worker 类中的锁机制，可以确保：
     * 正在执行任务的线程不会立即中断：
     * 当线程正在执行任务时，它会持有一个锁。如果在这期间线程收到中断信号，这个中断不会立即生效，因为线程正在忙碌状态。这样可以保证任务能够完成。
     * 任务完成后响应中断： 一旦任务执行完成，线程会释放锁。这时，如果有之前的中断信号，线程可以在释放锁后响应这个中断，进行相应的中断处理（比如退出）。
     * 结论
     * 总的来说，您的理解是正确的。Worker 类通过继承 AQS，
     * 使用了一个简单的锁机制来确保线程在处理关闭和中断请求时的状态是安全的。这样，线程池可以更加稳健地管理线程的生命周期，确保即使在关闭的情况下，正在执行的任务也能够正确完成。
     *
     */
    private final class Worker
        extends AbstractQueuedSynchronizer
        implements Runnable
    {
        /**
         * This class will never be serialized, but we provide a
         * serialVersionUID to suppress a javac warning.
         */
        private static final long serialVersionUID = 6138294804551838833L;

        /** Thread this worker is running in.  Null if factory fails. */
        final Thread thread;
        /** Initial task to run.  Possibly null. */
        Runnable firstTask;
        /** Per-thread task counter */
        volatile long completedTasks;

        /**
         * Creates with given first task and thread from ThreadFactory.
         * @param firstTask the first task (null if none)
         */
        Worker(Runnable firstTask) {
            //设成-1 会保证当前任务一定会被执行之后再响应其他命令
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }

        /** Delegates main run loop to outer runWorker  */
        public void run() {
            runWorker(this);
        }

        // Lock methods
        //
        // The value 0 represents the unlocked state.
        // The value 1 represents the locked state.

        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /*
     * Methods for setting control state
     */

    /**
     * Transitions runState to given target, or leaves it alone if
     * already at least the given target.
     *
     * @param targetState the desired state, either SHUTDOWN or STOP
     *        (but not TIDYING or TERMINATED -- use tryTerminate for that)
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) ||
                ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * Transitions to TERMINATED state if either (SHUTDOWN and pool
     * and queue empty) or (STOP and pool empty).  If otherwise
     * eligible to terminate but workerCount is nonzero, interrupts an
     * idle worker to ensure that shutdown signals propagate. This
     * method must be called following any action that might make
     * termination possible -- reducing worker count or removing tasks
     * from the queue during shutdown. The method is non-private to
     * allow access from ScheduledThreadPoolExecutor.
     * 如果满足以下条件之一，则转换到 TERMINATED 状态：(SHUTDOWN 状态且线程池和队列都为空)或者(STOP 状态且线程池为空)。
     * 如果符合终止条件但 workerCount 非零，则中断一个空闲工作线程以确保关闭信号得以传播。
     * 任何可能使终止成为可能的行为之后都必须调用此方法 —— 减少工作线程数或在关闭期间从队列中移除任务。
     * 此方法非私有，以允许 ScheduledThreadPoolExecutor 访问。
     *
     * RUNNING -> SHUTDOWN
     *    |          |
     *    v          v
     *  STOP ----> TIDYING -> TERMINATED
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (isRunning(c) || // 如果正在运行
                runStateAtLeast(c, TIDYING) || // 或者已经完全停止
                (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty())) // 或者已经通知停止但还存在任务没有处理完
                return;
            //如果还存在核心工作线程
            if (workerCountOf(c) != 0) { // Eligible to terminate
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /*
     * Methods for controlling interrupts to worker threads.
     */

    /**
     * If there is a security manager, makes sure caller has
     * permission to shut down threads in general (see shutdownPerm).
     * If this passes, additionally makes sure the caller is allowed
     * to interrupt each worker thread. This might not be true even if
     * first check passed, if the SecurityManager treats some threads
     * specially.
     */
    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * Interrupts all threads, even if active. Ignores SecurityExceptions
     * (in which case some threads may remain uninterrupted).
     */
    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers)
                w.interruptIfStarted();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Interrupts threads that might be waiting for tasks (as
     * indicated by not being locked) so they can check for
     * termination or configuration changes. Ignores
     * SecurityExceptions (in which case some threads may remain
     * uninterrupted).
     *
     * @param onlyOne If true, interrupt at most one worker. This is
     * called only from tryTerminate when termination is otherwise
     * enabled but there are still other workers.  In this case, at
     * most one waiting worker is interrupted to propagate shutdown
     * signals in case all threads are currently waiting.
     * Interrupting any arbitrary thread ensures that newly arriving
     * workers since shutdown began will also eventually exit.
     * To guarantee eventual termination, it suffices to always
     * interrupt only one idle worker, but shutdown() interrupts all
     * idle workers so that redundant workers exit promptly, not
     * waiting for a straggler task to finish.
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Common form of interruptIdleWorkers, to avoid having to
     * remember what the boolean argument means.
     */
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    /*
     * Misc utilities, most of which are also exported to
     * ScheduledThreadPoolExecutor
     */

    /**
     * Invokes the rejected execution handler for the given command.
     * Package-protected for use by ScheduledThreadPoolExecutor.
     */
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    /**
     * Performs any further cleanup following run state transition on
     * invocation of shutdown.  A no-op here, but used by
     * ScheduledThreadPoolExecutor to cancel delayed tasks.
     */
    void onShutdown() {
    }

    /**
     * State check needed by ScheduledThreadPoolExecutor to
     * enable running tasks during shutdown.
     *
     * @param shutdownOK true if should return true if SHUTDOWN
     */
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * Drains the task queue into a new list, normally using
     * drainTo. But if the queue is a DelayQueue or any other kind of
     * queue for which poll or drainTo may fail to remove some
     * elements, it deletes them one by one.
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /*
     * Methods for creating, running and cleaning up after workers
     */

    /**
     * Checks if a new worker can be added with respect to current
     * pool state and the given bound (either core or maximum). If so,
     * the worker count is adjusted accordingly, and, if possible, a
     * new worker is created and started, running firstTask as its
     * first task. This method returns false if the pool is stopped or
     * eligible to shut down. It also returns false if the thread
     * factory fails to create a thread when asked.  If the thread
     * creation fails, either due to the thread factory returning
     * null, or due to an exception (typically OutOfMemoryError in
     * Thread.start()), we roll back cleanly.
     *
     * @param firstTask the task the new thread should run first (or
     * null if none). Workers are created with an initial first task
     * (in method execute()) to bypass queuing when there are fewer
     * than corePoolSize threads (in which case we always start one),
     * or when the queue is full (in which case we must bypass queue).
     * Initially idle threads are usually created via
     * prestartCoreThread or to replace other dying workers.
     *
     * @param core if true use corePoolSize as bound, else
     * maximumPoolSize. (A boolean indicator is used here rather than a
     * value to ensure reads of fresh values after checking other pool
     * state).
     *
     *  * 检查在当前线程池状态和给定的界限（核心线程数或最大线程数）下，
     *  * 是否可以添加一个新的工作线程。如果可以，相应地调整工作线程计数，
     *  * 并且如果可能，创建并启动一个新的工作线程，执行 firstTask 作为其第一个任务。
     *  * 如果线程池已停止或符合关闭条件，此方法返回 false。
     *  * 如果线程工厂在被请求时无法创建线程，也返回 false。
     *  * 如果线程创建失败，无论是因为线程工厂返回 null，
     *  * 还是由于异常（通常是 Thread.start() 中的 OutOfMemoryError），
     *  * 我们会进行干净的回滚。
     *  *
     *  * @param firstTask 新线程应该首先运行的任务（如果没有则为 null）。
     *  * 当线程数量少于 corePoolSize 时（在这种情况下我们总是启动一个线程），
     *  * 或者当队列已满时（在这种情况下我们必须绕过队列），工作线程是通过初始任务（在方法 execute() 中）
     *  * 来创建的。通常通过 prestartCoreThread 创建最初空闲的线程，
     *  * 或者替换其他即将死亡的工作线程。
     *  *
     *  * @param core 如果为 true，则使用 corePoolSize 作为界限，
     *  * 否则使用 maximumPoolSize。（这里使用布尔指标而不是值，
     *  * 是为了确保在检查其他线程池状态后能读取到最新的值）。
     *  * @return 如果成功则返回 true
     *  "即将死亡的线程"（dying worker）这个术语在 Java 线程池的上下文中通常指的是那些即将结束其生命周期的线程。
     *             在 ThreadPoolExecutor 的环境中，这可能发生在几种不同的情况下：
     *
     * 1. 线程执行完任务并达到空闲超时
     * 如果线程池配置了空闲超时时间（keepAliveTime），线程在完成任务后会在一段时间内等待新任务。
     *             如果在这段空闲时间内没有新任务到来，线程将结束其生命周期并从线程池中移除。这种线程在等待期间可以被视为“即将死亡”的，因为它们即将因超时而结束。
     *
     * 2. 线程遇到未捕获的异常
     * 如果一个线程在执行任务时遇到了未被捕获的异常，这可能导致线程终止。
     *             除非线程池的配置能够捕获这些异常并启动新的线程，否则这样的线程可以被认为是“即将死亡”的，因为它们即将因异常而结束。
     *
     * 3. 线程池正在关闭
     * 当线程池执行关闭操作（如调用 shutdown() 或 shutdownNow()）时，
     *             它会开始逐步停止所有活动线程。这些线程在关闭过程中可以被认为是“即将死亡”的，因为它们即将被有序地终止。
     *
     * 结论
     * 在线程池的管理和维护过程中，理解“即将死亡”的线程是重要的，
     *             因为这关系到线程池是否需要创建新的线程来替代这些即将退出的线程，以保持池中的线程数量和处理能力。
     *             在 ThreadPoolExecutor 的实现中，这种替换通常是自动进行的，以确保线程池可以继续有效地处理提交的任务。
     * @return true if successful
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            // 如果池的状态是销毁状态 那么 如果传递进来的firstTask 不为NULL 和 workQueue
            if (rs >= SHUTDOWN && ! (rs == SHUTDOWN && firstTask == null && ! workQueue.isEmpty()))
                return false;

            for (;;) {  // 增加ctl的 wc 使用的是自旋增加
                int wc = workerCountOf(c);
                if (wc >= CAPACITY ||
                    wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                c = ctl.get();  // Re-read ctl
                if (runStateOf(c) != rs) // 如果状态发生了改变 也退出
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            w = new Worker(firstTask);
            final Thread t = w.thread;
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    int rs = runStateOf(ctl.get());

                    if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        workers.add(w);
                        int s = workers.size();
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (! workerStarted)
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    /**
     * Rolls back the worker thread creation.
     * - removes worker from workers, if present
     * - decrements worker count
     * - rechecks for termination, in case the existence of this
     *   worker was holding up termination
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w);
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Performs cleanup and bookkeeping for a dying worker. Called
     * only from worker threads. Unless completedAbruptly is set,
     * assumes that workerCount has already been adjusted to account
     * for exit.  This method removes thread from worker set, and
     * possibly terminates the pool or replaces the worker if either
     * it exited due to user task exception or if fewer than
     * corePoolSize workers are running or queue is non-empty but
     * there are no workers.
     * 对即将死亡的工作线程进行清理和记录。此方法仅从工作线程中调用。除非设置了 completedAbruptly，
     * 否则假定 workerCount 已经被调整以考虑到线程的退出。此方法从工作线程集中移除线程，并且
     * 可能会终止线程池或替换工作线程，这取决于它是由于用户任务异常退出，还是因为运行的工作线程少于
     * corePoolSize 或者队列非空但没有工作线程在运行。
     * @param w the worker
     * @param completedAbruptly if the worker died due to user exception
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completedTaskCount += w.completedTasks; //上传完成数
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        tryTerminate(); //检查线程池状态 如果符合线程池终止条件就终止线程池

        int c = ctl.get();
        if (runStateLessThan(c, STOP)) { // 如果状态是处于runing 或者是通知停止
            /*
             * 当一个工作线程退出时，此方法用于判断是否需要替换该线程。
             * 只有当线程池状态小于 STOP（即处于 RUNNING 或 SHUTDOWN）时，才会考虑替换线程。
             *
             * 1. 如果工作线程是因为异常突然退出（completedAbruptly 为 true），
             *    那么线程池将尝试添加一个新的非核心工作线程。
             *
             * 2. 如果工作线程正常退出（completedAbruptly 为 false）：
             *    - 首先计算线程池应保持的最小线程数。
             *      如果 allowCoreThreadTimeOut 为 true，则最小线程数可以为 0；
             *      否则，最小线程数至少为 corePoolSize。
             *    - 如果最小线程数为 0 但任务队列不为空，最小线程数至少应为 1，
             *      以确保有线程可用于处理这些任务。
             *    - 如果当前活跃的工作线程数大于或等于计算出的最小线程数，则不需要替换线程。
             *
             * 3. 在以上条件下，如果确定需要添加新线程，将会添加一个非核心工作线程（addWorker(null, false)）。
             *    这样做是为了确保线程池能够持续处理队列中的任务，特别是在动态负载或线程池关闭过程中。
             */
            if (!completedAbruptly) { //如果是false 也就是当前线程遇到了中断
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                if (workerCountOf(c) >= min) //无需替换
                    return; // replacement not needed
            }
            //如果不是就新增一个非核心工作者
            addWorker(null, false);
        }
    }

    /**
     * Performs blocking or timed wait for a task, depending on
     * current configuration settings, or returns null if this worker
     * must exit because of any of:
     * 1. There are more than maximumPoolSize workers (due to
     *    a call to setMaximumPoolSize).
     * 2. The pool is stopped.
     * 3. The pool is shutdown and the queue is empty.
     * 4. This worker timed out waiting for a task, and timed-out
     *    workers are subject to termination (that is,
     *    {@code allowCoreThreadTimeOut || workerCount > corePoolSize})
     *    both before and after the timed wait, and if the queue is
     *    non-empty, this worker is not the last thread in the pool.
     *
     * @return task, or null if the worker must exit, in which case
     *         workerCount is decremented
     */
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            int wc = workerCountOf(c);

            // Are workers subject to culling?
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            if ((wc > maximumPoolSize || (timed && timedOut)) && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            try {
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                    workQueue.take();
                if (r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * Main worker run loop.  Repeatedly gets tasks from queue and
     * executes them, while coping with a number of issues:
     *
     * 1. We may start out with an initial task, in which case we
     * don't need to get the first one. Otherwise, as long as pool is
     * running, we get tasks from getTask. If it returns null then the
     * worker exits due to changed pool state or configuration
     * parameters.  Other exits result from exception throws in
     * external code, in which case completedAbruptly holds, which
     * usually leads processWorkerExit to replace this thread.
     *
     * 2. Before running any task, the lock is acquired to prevent
     * other pool interrupts while the task is executing, and then we
     * ensure that unless pool is stopping, this thread does not have
     * its interrupt set.
     *
     * 3. Each task run is preceded by a call to beforeExecute, which
     * might throw an exception, in which case we cause thread to die
     * (breaking loop with completedAbruptly true) without processing
     * the task.
     *
     * 4. Assuming beforeExecute completes normally, we run the task,
     * gathering any of its thrown exceptions to send to afterExecute.
     * We separately handle RuntimeException, Error (both of which the
     * specs guarantee that we trap) and arbitrary Throwables.
     * Because we cannot rethrow Throwables within Runnable.run, we
     * wrap them within Errors on the way out (to the thread's
     * UncaughtExceptionHandler).  Any thrown exception also
     * conservatively causes thread to die.
     *
     * 5. After task.run completes, we call afterExecute, which may
     * also throw an exception, which will also cause thread to
     * die. According to JLS Sec 14.20, this exception is the one that
     * will be in effect even if task.run throws.
     *
     * The net effect of the exception mechanics is that afterExecute
     * and the thread's UncaughtExceptionHandler have as accurate
     * information as we can provide about any problems encountered by
     * user code.
     *
     * * 工作线程的主要运行循环。反复从队列中获取任务并执行它们，同时处理多种问题：
     *  *
     *  * 1. 我们可能会以一个初始任务开始，这种情况下我们不需要获取第一个任务。
     *  *    否则，只要线程池运行中，我们就通过 getTask 获取任务。
     *  *    如果它返回 null，则由于线程池状态或配置参数的变化，工作线程将退出。
     *  *    其他退出原因来自外部代码中抛出的异常，在这种情况下 completedAbruptly 为 true，
     *  *    这通常导致 processWorkerExit 替换此线程。
     *  *
     *  * 2. 在执行任何任务之前，首先获取锁，以防止在任务执行期间发生其他线程池中断，
     *  *    然后我们确保除非线程池正在停止，否则该线程不会设置中断。
     *  *
     *  * 3. 每个任务运行之前都会调用 beforeExecute，它可能抛出异常，
     *  *    这种情况下我们会使线程死亡（以 completedAbruptly 为 true 中断循环）而不处理任务。
     *  *
     *  * 4. 假设 beforeExecute 正常完成，我们执行任务，收集其抛出的任何异常，以发送给 afterExecute。
     *  *    我们分别处理 RuntimeException、Error（规范保证我们能捕获这两种异常）以及任意 Throwables。
     *  *    因为我们不能在 Runnable.run 中重新抛出 Throwables，我们将它们在出口处（到线程的 UncaughtExceptionHandler）封装在 Error 之内。
     *  *    任何抛出的异常也会保守地导致线程死亡。
     *  *
     *  * 5. task.run 完成后，我们调用 afterExecute，它也可能抛出异常，这也会导致线程死亡。
     *  *    根据 JLS 第 14.20 节，如果 task.run 抛出异常，这个异常将是生效的。
     *  *
     *  * 异常机制的总体效果是 afterExecute 和线程的 UncaughtExceptionHandler 尽可能准确地了解用户代码遇到的任何问题。
     *  *
     * runWorker 方法的主要工作流程
     * 处理初始任务：如果有一个初始任务（在创建工作线程时指定），则先执行这个任务。否则，从任务队列中获取任务。
     *
     * 任务获取循环：工作线程反复从任务队列中获取任务并执行。这个循环会一直持续，直到 getTask 方法返回 null（表示没有更多任务或线程池正在关闭）。
     *
     * 锁和线程中断：在执行每个任务前，工作线程会获取一个锁，以确保在任务执行期间不会被其他线程池操作中断。此外，除非线程池正在关闭，否则工作线程不会设置中断标志。
     *
     * 执行任务前后的钩子方法：
     *
     * beforeExecute：在每个任务执行前调用。如果这个方法抛出异常，工作线程将终止，不会执行该任务。
     * afterExecute：在每个任务执行后调用。如果这个方法抛出异常，工作线程也将终止。
     * 异常处理：
     *
     * 在执行任务时，如果任务代码抛出异常（如 RuntimeException 或 Error），则这些异常会被捕获并传递给 afterExecute 方法和线程的 UncaughtExceptionHandler。
     * 如果任务执行过程中抛出异常，或者 beforeExecute/afterExecute 方法抛出异常，工作线程会因此终止。
     * 线程替换：如果工作线程因异常终止（completedAbruptly 为 true），线程池会尝试替换这个线程，以保持线程池中线程的数量。
     *
     *
     *
     * ******************************关于对中断的支持*****************************
     * 在 Java 线程池（ThreadPoolExecutor）中，runWorker 方法的 Worker 类使用锁（基于 AbstractQueuedSynchronizer，AQS）来管理工作线程的中断策略和任务执行的安全性。以下是对其关键方面的专业总结：
     *
     * 锁的使用目的
     * 响应中断：
     *
     * 在工作线程开始执行任务前，通过调用 unlock() 方法，线程被设置为可中断状态。这允许线程在开始执行任务之前响应来自线程池的中断信号，例如在线程池关闭时。
     * 这种设计确保线程在开始执行新任务之前可以安全地检查并响应线程池的状态变化。
     * 保护任务执行：
     *
     * 任务开始执行时，通过 lock() 方法重新加锁，确保任务在执行过程中不会被中断。这保护了正在执行的任务，使其能够不受干扰地完成。
     * 加锁机制避免了正在执行的任务由于外部中断而产生的不一致性或非预期行为。
     * 中断处理流程
     * 中断策略：runWorker 方法在执行任务之前检查线程池的状态和当前线程的中断状态。如果发现线程池正在停止，并且当前线程还未被中断，那么方法会主动中断该线程。
     * 这样做是为了确保线程能够及时响应线程池的停止命令。
     * 异常处理和任务循环
     * 异常管理：任务执行中的异常被捕获并通过 afterExecute 方法进行处理。这提供了一个机会来处理运行时异常和错误，同时保持了工作线程的稳定性。
     * 循环执行：工作线程持续从任务队列中获取并执行任务，直到 getTask() 返回 null（表示没有更多任务或线程池正在关闭）。
     * 结论
     * runWorker 方法的锁机制在工作线程中起着至关重要的作用，确保了在保护任务执行的同时，线程能够及时安全地响应中断信号。
     * 这种设计体现了对并发执行环境中稳定性和安全性的深思熟虑，是 Java 线程池高效、稳健运行的关键。
     *
     * @param w the worker
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread(); //获得当前线程 =  w.thread 只是一种代理保证责任的分明
        Runnable task = w.firstTask; // task
        w.firstTask = null; // 中断w中first task 的连接
        // 这里为什么要释放AQS锁 ?
        w.unlock(); // allow interrupts
        boolean completedAbruptly = true;
        try {
            //如果task 是NULL 就证明此Worker 不是新建的 而是已经再运作当中的 那么尝试从队列中获取任务
            while (task != null || (task = getTask()) != null) {
                w.lock(); // 上执行锁 保证run不被破坏
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted.  This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                /**
                 * 在 ThreadPoolExecutor 的上下文中，这两个方法的配合使用是为了处理线程池关闭时的特定情况：
                 *
                 * 当线程池关闭时（例如调用了 shutdownNow()），所有工作线程会被中断。
                 * 工作线程在执行任务时会周期性地检查自己的中断状态。
                 * 如果 Thread.interrupted() 返回 true，则表明线程被中断了，此时会清除中断状态。
                 * 紧接着，!Thread.currentThread().isInterrupted() 用于再次确认线程的当前中断状态。如果线程在清除中断状态后又被中断（可能是因为其他原因或线程池的进一步操作），则需要采取相应的行动。
                 */
                if ((runStateAtLeast(ctl.get(), STOP) || (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP))) && !wt.isInterrupted())
                    wt.interrupt();
                try {
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        afterExecute(task, thrown);
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            //注意此处执行的环境是queue.isEmpty 或者遇到错误 completedAbruptly 的意义是感知循环作业的完成是正常完成还是异常完成
            processWorkerExit(w, completedAbruptly); //检查是否是中断退出
        }
    }

    // Public constructors and methods

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory and rejected execution handler.
     * It may be more convenient to use one of the {@link Executors} factory
     * methods instead of this general purpose constructor.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default rejected execution handler.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             threadFactory, defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), handler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        if (corePoolSize < 0 ||
            maximumPoolSize <= 0 ||
            maximumPoolSize < corePoolSize ||
            keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.acc = System.getSecurityManager() == null ?
                null :
                AccessController.getContext();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * Executes the given task sometime in the future.  The task
     * may execute in a new thread or in an existing pooled thread.
     *
     * If the task cannot be submitted for execution, either because this
     * executor has been shutdown or because its capacity has been reached,
     * the task is handled by the current {@code RejectedExecutionHandler}.
     *
     * @param command the task to execute
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution
     * @throws NullPointerException if {@code command} is null
     */
    public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        /*
         * Proceed in 3 steps:
         *
         * 1. If fewer than corePoolSize threads are running, try to
         * start a new thread with the given command as its first
         * task.  The call to addWorker atomically checks runState and
         * workerCount, and so prevents false alarms that would add
         * threads when it shouldn't, by returning false.
         *
         * 2. If a task can be successfully queued, then we still need
         * to double-check whether we should have added a thread
         * (because existing ones died since last checking) or that
         * the pool shut down since entry into this method. So we
         * recheck state and if necessary roll back the enqueuing if
         * stopped, or start a new thread if there are none.
         *
         * 3. If we cannot queue task, then we try to add a new
         * thread.  If it fails, we know we are shut down or saturated
         * and so reject the task.
         */
        int c = ctl.get();
        if (workerCountOf(c) < corePoolSize) { //如果当然工作数小于corePoolSize
            if (addWorker(command, true)) //直接尝试新增核心线程工作者
                return;
            c = ctl.get(); //如果添加核心线程工作者失败 那就重新检查ctl
        }
        if (isRunning(c) && workQueue.offer(command)) { //如果池是运行状态 尝试入taskQueue
            int recheck = ctl.get(); // 如果入成功了 重新检查ctl
            if (! isRunning(recheck) && remove(command)) // 如果是非运行状态 并且从taskQueue删除成功了
                reject(command); // 拒绝此任务
            else if (workerCountOf(recheck) == 0) // 是为了解决极端情况下 线程退出的问题 1: keepLive 2: 执行时未捕捉到的异常
                addWorker(null, false);
        }
        else if (!addWorker(command, false)) //添加非核心worker失败
            reject(command);
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
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(SHUTDOWN);
            interruptIdleWorkers();
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution. These tasks are drained (removed)
     * from the task queue upon return from this method.
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
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(STOP);
            interruptWorkers();
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }

    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * Returns true if this executor is in the process of terminating
     * after {@link #shutdown} or {@link #shutdownNow} but has not
     * completely terminated.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, causing this executor not
     * to properly terminate.
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                if (nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer
     * referenced and it has no threads.
     */
    protected void finalize() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || acc == null) {
            shutdown();
        } else {
            PrivilegedAction<Void> pa = () -> { shutdown(); return null; };
            AccessController.doPrivileged(pa, acc);
        }
    }

    /**
     * Sets the thread factory used to create new threads.
     *
     * @param threadFactory the new thread factory
     * @throws NullPointerException if threadFactory is null
     * @see #getThreadFactory
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * Returns the thread factory used to create new threads.
     *
     * @return the current thread factory
     * @see #setThreadFactory(ThreadFactory)
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * Sets a new handler for unexecutable tasks.
     *
     * @param handler the new handler
     * @throws NullPointerException if handler is null
     * @see #getRejectedExecutionHandler
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * Returns the current handler for unexecutable tasks.
     *
     * @return the current handler
     * @see #setRejectedExecutionHandler(RejectedExecutionHandler)
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * Sets the core number of threads.  This overrides any value set
     * in the constructor.  If the new value is smaller than the
     * current value, excess existing threads will be terminated when
     * they next become idle.  If larger, new threads will, if needed,
     * be started to execute any queued tasks.
     *
     * @param corePoolSize the new core size
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @see #getCorePoolSize
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    /**
     * Returns the core number of threads.
     *
     * @return the core number of threads
     * @see #setCorePoolSize
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * Starts a core thread, causing it to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed. This method will return {@code false}
     * if all core threads have already been started.
     *
     * @return {@code true} if a thread was started
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
            addWorker(null, true);
    }

    /**
     * Same as prestartCoreThread except arranges that at least one
     * thread is started even if corePoolSize is 0.
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    /**
     * Starts all core threads, causing them to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed.
     *
     * @return the number of threads started
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Returns true if this pool allows core threads to time out and
     * terminate if no tasks arrive within the keepAlive time, being
     * replaced if needed when new tasks arrive. When true, the same
     * keep-alive policy applying to non-core threads applies also to
     * core threads. When false (the default), core threads are never
     * terminated due to lack of incoming tasks.
     *
     * @return {@code true} if core threads are allowed to time out,
     *         else {@code false}
     *
     * @since 1.6
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * Sets the policy governing whether core threads may time out and
     * terminate if no tasks arrive within the keep-alive time, being
     * replaced if needed when new tasks arrive. When false, core
     * threads are never terminated due to lack of incoming
     * tasks. When true, the same keep-alive policy applying to
     * non-core threads applies also to core threads. To avoid
     * continual thread replacement, the keep-alive time must be
     * greater than zero when setting {@code true}. This method
     * should in general be called before the pool is actively used.
     *
     * @param value {@code true} if should time out, else {@code false}
     * @throws IllegalArgumentException if value is {@code true}
     *         and the current keep-alive time is not greater than zero
     *
     * @since 1.6
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value)
                interruptIdleWorkers();
        }
    }

    /**
     * Sets the maximum allowed number of threads. This overrides any
     * value set in the constructor. If the new value is smaller than
     * the current value, excess existing threads will be
     * terminated when they next become idle.
     *
     * @param maximumPoolSize the new maximum
     * @throws IllegalArgumentException if the new maximum is
     *         less than or equal to zero, or
     *         less than the {@linkplain #getCorePoolSize core pool size}
     * @see #getMaximumPoolSize
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * Returns the maximum allowed number of threads.
     *
     * @return the maximum allowed number of threads
     * @see #setMaximumPoolSize
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Sets the time limit for which threads may remain idle before
     * being terminated.  If there are more than the core number of
     * threads currently in the pool, after waiting this amount of
     * time without processing a task, excess threads will be
     * terminated.  This overrides any value set in the constructor.
     *
     * @param time the time to wait.  A time value of zero will cause
     *        excess threads to terminate immediately after executing tasks.
     * @param unit the time unit of the {@code time} argument
     * @throws IllegalArgumentException if {@code time} less than zero or
     *         if {@code time} is zero and {@code allowsCoreThreadTimeOut}
     * @see #getKeepAliveTime(TimeUnit)
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0)
            interruptIdleWorkers();
    }

    /**
     * Returns the thread keep-alive time, which is the amount of time
     * that threads in excess of the core pool size may remain
     * idle before being terminated.
     *
     * @param unit the desired time unit of the result
     * @return the time limit
     * @see #setKeepAliveTime(long, TimeUnit)
     */
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    /**
     * Returns the task queue used by this executor. Access to the
     * task queue is intended primarily for debugging and monitoring.
     * This queue may be in active use.  Retrieving the task queue
     * does not prevent queued tasks from executing.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * Removes this task from the executor's internal queue if it is
     * present, thus causing it not to be run if it has not already
     * started.
     *
     * <p>This method may be useful as one part of a cancellation
     * scheme.  It may fail to remove tasks that have been converted
     * into other forms before being placed on the internal queue. For
     * example, a task entered using {@code submit} might be
     * converted into a form that maintains {@code Future} status.
     * However, in such cases, method {@link #purge} may be used to
     * remove those Futures that have been cancelled.
     *
     * @param task the task to remove
     * @return {@code true} if the task was removed
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * Tries to remove from the work queue all {@link Future}
     * tasks that have been cancelled. This method can be useful as a
     * storage reclamation operation, that has no other impact on
     * functionality. Cancelled tasks are never executed, but may
     * accumulate in work queues until worker threads can actively
     * remove them. Invoking this method instead tries to remove them now.
     * However, this method may fail to remove tasks in
     * the presence of interference by other threads.
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /* Statistics */

    /**
     * Returns the current number of threads in the pool.
     *
     * @return the number of threads
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate number of threads that are actively
     * executing tasks.
     *
     * @return the number of threads
     */
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the largest number of threads that have ever
     * simultaneously been in the pool.
     *
     * @return the number of threads
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have ever been
     * scheduled for execution. Because the states of tasks and
     * threads may change dynamically during computation, the returned
     * value is only an approximation.
     *
     * @return the number of tasks
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have
     * completed execution. Because the states of tasks and threads
     * may change dynamically during computation, the returned value
     * is only an approximation, but one that does not ever decrease
     * across successive calls.
     *
     * @return the number of tasks
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state and estimated worker and
     * task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                     (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                      "Shutting down"));
        return super.toString() +
            "[" + rs +
            ", pool size = " + nworkers +
            ", active threads = " + nactive +
            ", queued tasks = " + workQueue.size() +
            ", completed tasks = " + ncompleted +
            "]";
    }

    /* Extension hooks */

    /**
     * Method invoked prior to executing the given Runnable in the
     * given thread.  This method is invoked by thread {@code t} that
     * will execute task {@code r}, and may be used to re-initialize
     * ThreadLocals, or to perform logging.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.beforeExecute} at the end of
     * this method.
     *
     * @param t the thread that will run task {@code r}
     * @param r the task that will be executed
     */
    protected void beforeExecute(Thread t, Runnable r) { }

    /**
     * Method invoked upon completion of execution of the given Runnable.
     * This method is invoked by the thread that executed the task. If
     * non-null, the Throwable is the uncaught {@code RuntimeException}
     * or {@code Error} that caused execution to terminate abruptly.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.afterExecute} at the
     * beginning of this method.
     *
     * <p><b>Note:</b> When actions are enclosed in tasks (such as
     * {@link FutureTask}) either explicitly or via methods such as
     * {@code submit}, these task objects catch and maintain
     * computational exceptions, and so they do not cause abrupt
     * termination, and the internal exceptions are <em>not</em>
     * passed to this method. If you would like to trap both kinds of
     * failures in this method, you can further probe for such cases,
     * as in this sample subclass that prints either the direct cause
     * or the underlying exception if a task has been aborted:
     *
     *  <pre> {@code
     * class ExtendedExecutor extends ThreadPoolExecutor {
     *   // ...
     *   protected void afterExecute(Runnable r, Throwable t) {
     *     super.afterExecute(r, t);
     *     if (t == null && r instanceof Future<?>) {
     *       try {
     *         Object result = ((Future<?>) r).get();
     *       } catch (CancellationException ce) {
     *           t = ce;
     *       } catch (ExecutionException ee) {
     *           t = ee.getCause();
     *       } catch (InterruptedException ie) {
     *           Thread.currentThread().interrupt(); // ignore/reset
     *       }
     *     }
     *     if (t != null)
     *       System.out.println(t);
     *   }
     * }}</pre>
     *
     * @param r the runnable that has completed
     * @param t the exception that caused termination, or null if
     * execution completed normally
     */
    protected void afterExecute(Runnable r, Throwable t) { }

    /**
     * Method invoked when the Executor has terminated.  Default
     * implementation does nothing. Note: To properly nest multiple
     * overridings, subclasses should generally invoke
     * {@code super.terminated} within this method.
     */
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    /**
     * A handler for rejected tasks that runs the rejected task
     * directly in the calling thread of the {@code execute} method,
     * unless the executor has been shut down, in which case the task
     * is discarded.
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code CallerRunsPolicy}.
         */
        public CallerRunsPolicy() { }

        /**
         * Executes task r in the caller's thread, unless the executor
         * has been shut down, in which case the task is discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /**
     * A handler for rejected tasks that throws a
     * {@code RejectedExecutionException}.
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        /**
         * Creates an {@code AbortPolicy}.
         */
        public AbortPolicy() { }

        /**
         * Always throws RejectedExecutionException.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         * @throws RejectedExecutionException always
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                                                 " rejected from " +
                                                 e.toString());
        }
    }

    /**
     * A handler for rejected tasks that silently discards the
     * rejected task.
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        /**
         * Does nothing, which has the effect of discarding task r.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /**
     * A handler for rejected tasks that discards the oldest unhandled
     * request and then retries {@code execute}, unless the executor
     * is shut down, in which case the task is discarded.
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardOldestPolicy} for the given executor.
         */
        public DiscardOldestPolicy() { }

        /**
         * Obtains and ignores the next task that the executor
         * would otherwise execute, if one is immediately available,
         * and then retries execution of task r, unless the executor
         * is shut down, in which case task r is instead discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
