/*
 * Copyright (c) 1997, 2017, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.ref;

import sun.misc.Cleaner;
import sun.misc.JavaLangRefAccess;
import sun.misc.SharedSecrets;

/**
 * Abstract base class for reference objects.  This class defines the
 * operations common to all reference objects.  Because reference objects are
 * implemented in close cooperation with the garbage collector, this class may
 * not be subclassed directly.
 *  抽象基类用于引用对象。此类定义了所有引用对象的通用操作。由于引用对象与垃圾收集器密切合作实现 ,因此不能直接对此类进行子类化
 *
 * @author   Mark Reinhold
 * @since    1.2
 */

public abstract class Reference<T> {

    /* A Reference instance is in one of four possible internal states:
     *
     *     Active: Subject to special treatment by the garbage collector.  Some
     *     time after the collector detects that the reachability of the
     *     referent has changed to the appropriate state, it changes the
     *     instance's state to either Pending or Inactive,
     *     受垃圾收集器的特殊处理。在收集器检测到引用对象可达性改变到适当状态后的某个时刻， 它会根据实例创建时是否注册到队列，将实例状态改变为待处理（Pending）或无效（Inactive）。
     *     depending upon whether or not the instance was registered with a queue when it was
     *     created.  In the former case it also adds the instance to the
     *     pending-Reference list.  Newly-created instances are Active.
     *    前者情况下，还会将实例添加到待处理引用列表中。新创建的实例处于活动状态。
     *
     *     Pending: An element of the pending-Reference list, waiting to be
     *     enqueued by the Reference-handler thread.  Unregistered instances
     *     are never in this state.
     *     待处理状态：处于待处理引用列表中，等待引用处理线程进行入队。未注册的实例永远不会处于此状态

     *     Enqueued: An element of the queue with which the instance was
     *     registered when it was created.  When an instance is removed from
     *     its ReferenceQueue, it is made Inactive.  Unregistered instances are
     *     never in this state.
     *     入队状态：是创建时注册的队列中的一个元素。当实例从其ReferenceQueue中移除时，它变为无效状态。
     *     未注册的实例永远不会处于此状态。
     *

     *     Inactive: Nothing more to do.  Once an instance becomes Inactive its
     *     state will never change again.
     *     无效状态：无需再做任何处理。一旦实例变为无效，其状态永远不会再改变。
     *
     * The state is encoded in the queue and next fields as follows:
     *
     *     Active: queue = ReferenceQueue with which instance is registered, or
     *     ReferenceQueue.NULL if it was not registered with a queue; next =
     *     null.
     *
     *     Pending: queue = ReferenceQueue with which instance is registered;
     *     next = this
     *
     *     Enqueued: queue = ReferenceQueue.ENQUEUED; next = Following instance
     *     in queue, or this if at end of list.
     *
     *     Inactive: queue = ReferenceQueue.NULL; next = this.
     *
     * With this scheme the collector need only examine the next field in order
     * to determine whether a Reference instance requires special treatment: If
     * the next field is null then the instance is active; if it is non-null,
     * then the collector should treat the instance normally.
     *
     * To ensure that a concurrent collector can discover active Reference
     * objects without interfering with application threads that may apply
     * the enqueue() method to those objects, collectors should link
     * discovered objects through the discovered field. The discovered
     * field is also used for linking Reference objects in the pending list.
     */

    /**
     * 关于 Java 中的 Reference 类和 ReferenceQueue 的工作原理和特点：
     *
     * 1. ReferenceQueue 共享性：
     *    - ReferenceQueue 可以被多个 Reference 对象共享。
     *    - 当创建 Reference 对象（如 WeakReference, SoftReference, PhantomReference）时，
     *      可以指定一个 ReferenceQueue。
     *    - 当引用的对象变得不可达时，这些 Reference 对象可以自动加入到这个队列中，
     *      以便进行资源释放等特殊处理。
     *
     * 2. ReferenceQueue 的入队操作（尾推法）：
     *    - Reference 对象被加入到 ReferenceQueue 中时，采用尾部插入。
     *    - 这保证了先进入队列的对象先被处理，遵循队列的基本原则。
     *
     * 3. Reference 对象的无效状态（Inactive）：
     *    - 当 Reference 对象变为无效状态时，表示它不再有任何作用。
     *    - 这通常在引用的对象被垃圾回收器处理后发生，此时相关的清理工作也已完成。
     *    - 此状态的 Reference 对象本身可能很快被回收。
     *
     * 4. Reference 类型与强引用的区别：
     *    - 通过 new 关键字创建的对象引用是强引用（Strong Reference）。
     *    - 强引用意味着只要引用存在，垃圾回收器就不会回收对象。
     *    - WeakReference, SoftReference, PhantomReference 是程序员控制的引用类型，
     *      允许垃圾回收器在满足特定条件时回收其引用的对象。
     *    - 这些引用类型用于更细致地管理对象生命周期和内存，特别是在需要特别管理资源的情况下。
     *
     * 这些机制为高级内存管理和资源清理提供了强大的工具，尤其适用于需要细粒度控制对象生命周期的复杂应用程序。
     */

    /**
     * 关于 Java Reference 类中的 'next' 字段的核心作用和意义：
     *
     * 'next' 字段在 Reference 对象中扮演着关键角色，用于跟踪和管理引用对象在其生命周期中的不同状态。以下是 'next' 字段的主要用途和含义：
     *
     * 1. 活动状态（Active）：
     *    - 在活动状态下，'next' 字段为 null。
     *    - 这表示引用对象当前正受到垃圾收集器的特殊处理。
     *    - 在此状态下，引用对象指向的数据（referent）是可达的，但垃圾收集器会监视其状态。
     *
     * 2. 待处理状态（Pending）：
     *    - 当垃圾收集器确定引用对象所指向的数据不再可达时，引用对象进入待处理状态。
     *    - 在此状态下，'next' 字段指向引用对象自身（next = this）。
     *    - 这意味着引用对象已被垃圾收集器标记为待处理，并将被添加到引用队列中。
     *
     * 3. 入队状态（Enqueued）：
     *    - 在这个状态下，引用对象已被加入到其创建时注册的队列中。
     *    - 'next' 字段指向队列中的下一个引用对象，或在队列末尾时指向它自己（next = following instance or this）。
     *    - 这表明该对象等待应用程序处理，如执行清理操作。
     *
     * 4. 无效状态（Inactive）：
     *    - 一旦引用对象从队列中移除或其所指向的数据被清理，对象进入无效状态。
     *    - 此时，'next' 字段再次指向自身（next = this），且 'queue' 字段被设置为 ReferenceQueue.NULL。
     *    - 这表示引用对象的生命周期已结束，不再有任何状态变化。
     *
     * 通过这种方式，垃圾收集器可以通过检查 'next' 字段来轻松确定引用对象的状态，并在不同状态之间进行高效的转换。这是 Java 中引用对象（软引用、弱引用、虚引用）与垃圾回收机制交互的关键机制之一。
     */

    /**
     * - 关键组件说明：
     *   1. T referent:
     *      - 被引用的对象。
     *      - GC 特殊处理，用于判断对象的可达性和回收。
     *
     *   2. volatile ReferenceQueue<? super T> queue:
     *      - 关联的 ReferenceQueue。
     *      - 当 referent 被 GC 回收时，Reference 对象可能被添加到这个队列。
     *
     *   3. volatile Reference next:
     *      - 用于链接 ReferenceQueue 中的引用。
     *      - 根据对象状态不同（活动、待处理、入队、无效），有不同的值。
     *
     *   4. transient private Reference<T> discovered:
     *      - 由 GC 使用，用于跟踪待处理的 Reference 对象。
     *      - GC 发现只被软/弱/虚引用的对象时，将对应的 Reference 对象添加到内部列表。
     *
     *   5. private static class Lock { }
     *      - 用于与 GC 同步的静态内部类。
     *
     *   6. private static Lock lock:
     *      - 用于保护对 pending 链表的操作，确保线程安全。
     *      - 是一个全局锁，由所有 Reference 对象共享。
     *
     *   7. private static Reference<Object> pending:
     *      - 全局的待处理 Reference 对象列表（链表头部）。
     *      - GC 将待处理的 Reference 对象添加到此列表，ReferenceHandler 线程负责处理。
     *      - 由 lock 锁保护，以防止并发修改带来的线程安全问题。
     *
     * - 线程安全：
     *   全局静态锁（lock）和待处理列表（pending）在多线程环境下可能成为性能瓶颈，尤其是在高并发场景下。
     *   锁定和访问这个全局列表必须高效，以减少线程等待时间。
     */

    private T referent;         /* Treated specially by GC */

    volatile ReferenceQueue<? super T> queue; // share queue

    /* When active:   NULL
     *     pending:   this
     *    Enqueued:   next reference in queue (or this if last)
     *    Inactive:   this
     */
    @SuppressWarnings("rawtypes")
    volatile Reference next;

    /* When active:   next element in a discovered reference list maintained by GC (or this if last)
     *     pending:   next element in the pending list (or null if last)
     *   otherwise:   NULL
     */
    transient private Reference<T> discovered;  /* used by VM */


    /* Object used to synchronize with the garbage collector.  The collector
     * must acquire this lock at the beginning of each collection cycle.  It is
     * therefore critical that any code holding this lock complete as quickly
     * as possible, allocate no new objects, and avoid calling user code.
     */
    static private class Lock { } // 这个Lock被申明静态的 那就证明是公用锁 它是独立于对象 假如有Q1, Q2 ,Q3 它们都公用这把锁
    private static Lock lock = new Lock();


    /* List of References waiting to be enqueued.  The collector adds
     * References to this list, while the Reference-handler thread removes
     * them.  This list is protected by the above lock object. The
     * list uses the discovered field to link its elements.
     * Reference 对象 已经注册 持有待人对的Reference对象引用
     * lock 保护queue
     */
    private static Reference<Object> pending = null;

    /* High-priority thread to enqueue pending References
     * Reference queue 高级别的工作线程
     */
    private static class ReferenceHandler extends Thread {

        private static void ensureClassInitialized(Class<?> clazz) { //确保指定clazz被加载
            try {
                Class.forName(clazz.getName(), true, clazz.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw (Error) new NoClassDefFoundError(e.getMessage()).initCause(e);
            }
        }
        /*
         * 静态初始化块中的代码 在静态初始化块中，方法 ensureClassInitialized 被用于预加载和初始化 InterruptedException 类和 Cleaner 类。这样做有几个重要目的：
         * 避免延迟加载导致的问题：在 ReferenceHandler 的运行循环中，如果需要处理中断异常（InterruptedException）或使用清理器（Cleaner），而这些类还没有被加载和初始化，就可能在低内存条件下遇到问题。在内存紧张时，类的加载和初始化可能会失败，导致系统出现更严重的错误。
         * 提高效率和可靠性： 预加载这些类可以提高 ReferenceHandler 线程处理引用的效率和可靠性，因为在处理引用时可能需要用到这些类。预加载确保了在必要时它们已经可用，无需在关键时刻进行加载和初始化。
         * 防止内存不足情况下的错误： 在内存紧张的情况下，类加载和初始化可能会因为资源不足而失败。通过预先加载这些重要的类，系统能够在内存充足时完成这些操作，从而减少在处理引用时因为内存问题导致的失败风险。
         * 总的来说，这个设计模式是为了确保 ReferenceHandler 线程在运行时能够顺利、高效地执行其任务，特别是在资源受限的情况下。这表明 Java 的设计者在处理系统级别的特性时非常关注稳定性和健壮性。
         */
        static {
            // pre-load and initialize InterruptedException and Cleaner classes
            // so that we don't get into trouble later in the run loop if there's
            // memory shortage while loading/initializing them lazily.
            ensureClassInitialized(InterruptedException.class);
            ensureClassInitialized(Cleaner.class);
        }

        ReferenceHandler(ThreadGroup g, String name) {
            super(g, name);
        }

        public void run() {
            while (true) {
                tryHandlePending(true);
            }
        }
    }

    /**
     * Try handle pending {@link Reference} if there is one.<p>
     * Return {@code true} as a hint that there might be another
     * {@link Reference} pending or {@code false} when there are no more pending
     * {@link Reference}s at the moment and the program can do some other
     * useful work instead of looping.
     *
     * @param waitForNotify if {@code true} and there was no pending
     *                      {@link Reference}, wait until notified from VM
     *                      or interrupted; if {@code false}, return immediately
     *                      when there is no pending {@link Reference}.
     * @return {@code true} if there was a {@link Reference} pending and it
     *         was processed, or we waited for notification and either got it
     *         or thread was interrupted before being notified;
     *         {@code false} otherwise.
     * 尝试处理一个待处理的 {@link Reference}（如果存在）。
     * 如果存在另一个待处理的 {@link Reference}，则返回 {@code true} 作为提示；
     * 当没有更多待处理的 {@link Reference} 时，返回 {@code false}，
     * 这意味着程序可以进行其他有用的工作，而不是继续循环。
     *
     * @param waitForNotify 如果为 {@code true}，且没有待处理的 {@link Reference}，
     *                      则等待来自虚拟机的通知或线程被中断；如果为 {@code false}，
     *                      当没有待处理的 {@link Reference} 时立即返回。
     * @return 如果有一个待处理的 {@link Reference} 并且已经处理，
     *         或者我们等待通知并且收到了通知或线程在收到通知前被中断，
     *         则返回 {@code true}；否则返回 {@code false}。
     */
    static boolean tryHandlePending(boolean waitForNotify) {
        Reference<Object> r;
        Cleaner c;
        try {
            synchronized (lock) {
                if (pending != null) {
                    r = pending; // 如果pending不是null copy其引用  r -> pending
                    // 'instanceof' might throw OutOfMemoryError sometimes
                    // so do this before un-linking 'r' from the 'pending' chain...
                    c = r instanceof Cleaner ? (Cleaner) r : null;  //检查对象 或者说是检查职责 如果是普通的reference 只需要操作连接 但如果是cleaner则证明有清理工作
                    // unlink 'r' from 'pending' chain
                    pending = r.discovered; //  那么 r -> pending.discovered(原来 r 引用 pending的连接 但这个步骤就是断开了 ,会成为 r-> pending.discovered )
                    r.discovered = null; // 删除peding ???
                } else {
                    // The waiting on the lock may cause an OutOfMemoryError
                    // because it may try to allocate exception objects.
                    if (waitForNotify) {
                        lock.wait();
                    }
                    // retry if waited
                    return waitForNotify;
                }
            }
        } catch (OutOfMemoryError x) {
            // Give other threads CPU time so they hopefully drop some live references
            // and GC reclaims some space.
            // Also prevent CPU intensive spinning in case 'r instanceof Cleaner' above
            // persistently throws OOME for some time...
            Thread.yield();
            // retry
            return true;
        } catch (InterruptedException x) {
            // retry
            return true;
        }

        // Fast path for cleaners
        if (c != null) {
            c.clean();
            return true;
        }

        ReferenceQueue<? super Object> q = r.queue;
        if (q != ReferenceQueue.NULL) q.enqueue(r);
        return true;
    }

    static {
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        for (ThreadGroup tgn = tg;
             tgn != null;
             tg = tgn, tgn = tg.getParent());
        Thread handler = new ReferenceHandler(tg, "Reference Handler");
        /* If there were a special system-only priority greater than
         * MAX_PRIORITY, it would be used here
         */
        handler.setPriority(Thread.MAX_PRIORITY);
        handler.setDaemon(true);
        handler.start();

        // provide access in SharedSecrets
        SharedSecrets.setJavaLangRefAccess(new JavaLangRefAccess() {
            @Override
            public boolean tryHandlePendingReference() {
                return tryHandlePending(false);
            }
        });
    }

    /* -- Referent accessor and setters -- */

    /**
     * Returns this reference object's referent.  If this reference object has
     * been cleared, either by the program or by the garbage collector, then
     * this method returns <code>null</code>.
     *
     * @return   The object to which this reference refers, or
     *           <code>null</code> if this reference object has been cleared
     */
    public T get() {
        return this.referent;
    }

    /**
     * Clears this reference object.  Invoking this method will not cause this
     * object to be enqueued.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * clears references it does so directly, without invoking this method.
     */
    public void clear() {
        this.referent = null;
    }


    /* -- Queue operations -- */

    /**
     * Tells whether or not this reference object has been enqueued, either by
     * the program or by the garbage collector.  If this reference object was
     * not registered with a queue when it was created, then this method will
     * always return <code>false</code>.
     *
     * @return   <code>true</code> if and only if this reference object has
     *           been enqueued
     */
    public boolean isEnqueued() {
        return (this.queue == ReferenceQueue.ENQUEUED);
    }

    /**
     * Adds this reference object to the queue with which it is registered,
     * if any.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * enqueues references it does so directly, without invoking this method.
     *
     * @return   <code>true</code> if this reference object was successfully
     *           enqueued; <code>false</code> if it was already enqueued or if
     *           it was not registered with a queue when it was created
     */
    public boolean enqueue() {
        return this.queue.enqueue(this);
    }


    /* -- Constructors -- */

    Reference(T referent) {
        this(referent, null);
    }

    Reference(T referent, ReferenceQueue<? super T> queue) {
        this.referent = referent;
        this.queue = (queue == null) ? ReferenceQueue.NULL : queue;
    }

}
