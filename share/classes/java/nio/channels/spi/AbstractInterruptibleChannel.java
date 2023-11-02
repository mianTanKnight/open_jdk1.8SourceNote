/*
 * Copyright (c) 2000, 2013, Oracle and/or its affiliates. All rights reserved.
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
 */

package java.nio.channels.spi;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import sun.nio.ch.Interruptible;


/**
 * Base implementation class for interruptible channels.
 * 一个可中断的channels的 基本实现规范
 * <p> This class encapsulates the low-level machinery required to implement
 * the asynchronous closing and interruption of channels.  A concrete channel
 * class must invoke the {@link #begin begin} and {@link #end end} methods
 * before and after, respectively, invoking an I/O operation that might block
 * indefinitely.  In order to ensure that the {@link #end end} method is always
 * invoked, these methods should be used within a
 * <tt>try</tt>&nbsp;...&nbsp;<tt>finally</tt> block:
 *
 * <blockquote><pre>
 * boolean completed = false;
 * try {
 *     begin();
 *     completed = ...;    // Perform blocking I/O operation
 *     return ...;         // Return result
 * } finally {
 *     end(completed);
 * }</pre></blockquote>
 *
 * <p> The <tt>completed</tt> argument to the {@link #end end} method tells
 * whether or not the I/O operation actually completed, that is, whether it had
 * any effect that would be visible to the invoker.  In the case of an
 * operation that reads bytes, for example, this argument should be
 * <tt>true</tt> if, and only if, some bytes were actually transferred into the
 * invoker's target buffer.
 *
 * <p> A concrete channel class must also implement the {@link
 * #implCloseChannel implCloseChannel} method in such a way that if it is
 * invoked while another thread is blocked in a native I/O operation upon the
 * channel then that operation will immediately return, either by throwing an
 * exception or by returning normally.  If a thread is interrupted or the
 * channel upon which it is blocked is asynchronously closed then the channel's
 * {@link #end end} method will throw the appropriate exception.
 *
 * <p> This class performs the synchronization required to implement the {@link
 * java.nio.channels.Channel} specification.  Implementations of the {@link
 * #implCloseChannel implCloseChannel} method need not synchronize against
 * other threads that might be attempting to close the channel.  </p>
 *
 *
 * AbstractInterruptibleChannel 是 Java NIO 中的一个抽象类，它提供了实现可中断通道的基础设施。
 * 具体的通道类需要在执行可能无限期阻塞的 I/O 操作之前和之后，
 * 分别调用 begin 和 end 方法。为了确保总是调用 end 方法，这些方法应该在 try...finally 语句块中使用
 *
 * 传递给 end 方法的 completed 参数表示 I/O 操作是否实际完成，即是否有对调用者可见的效果。例如，在读取字节的操作中，只有当实际上有字节被传输到调用者的目标缓冲区时，这个参数才应该是 true。
 * 具体的通道类还必须实现 implCloseChannel 方法，以确保如果在另一个线程阻塞在该通道的本地 I/O 操作时调用该方法，
 * 那么该操作将立即返回，要么通过抛出异常，要么通过正常返回。如果一个线程被中断，或者它阻塞的通道被异步关闭，则该通道的 end 方法将抛出适当的异常。
 * AbstractInterruptibleChannel 类执行了实现 java.nio.channels.Channel 规范所需的同步。implCloseChannel 方法的实现不需要对可能尝试关闭通道的其他线程进行同步。
 *
 *
 * 这里我们要先理解Channel的阻塞是什么概念
 *
 *在 Java NIO 中，“阻塞”和“非阻塞”通常指的是通道对 I/O 操作的响应方式：
 *
 * 阻塞模式（Blocking Mode）:
 *
 * 在阻塞模式下，如果一个线程发起一个 I/O 操作，比如读或写，并且操作不能立即完成，线程会阻塞直到操作完成或者发生错误。
 * 对于 ServerSocketChannel，如果在阻塞模式下调用 accept() 方法，那么线程会阻塞直到一个新的连接到达。
 * 对于 SocketChannel，如果进行读取操作，线程会阻塞直到有数据可读；如果进行写入操作，线程会阻塞直到可以写入数据。
 * 这里的阻塞不是由操作系统的 select() 或 epoll() 调用引起的，而是由于通道在等待完成 I/O 操作。
 * 非阻塞模式（Non-Blocking Mode）:
 *
 * 在非阻塞模式下，I/O 操作会立即返回。如果操作不能立即完成，它不会阻塞线程，而是立即返回一个指示状态的值（通常是 0 或负值）。
 * Selector 用于非阻塞通道，可以让一个线程监控多个通道上的 I/O 事件。在这种模式下，Selector 通过 select() 方法等待任一注册的通道准备好进行 I/O 操作。如果没有通道准备好，select() 方法会阻塞，但这是在 Selector 层面的阻塞，不是通道自身的阻塞。
 * 现在，关于 AbstractInterruptibleChannel 类中的 begin() 和 end() 方法：
 *
 * 这两个方法的用途是确保在阻塞和非阻塞模式下，通道可以安全地关闭，并且可以响应线程中断。
 * 即使在非阻塞模式下，这些方法也很重要，因为它们提供了一种机制来处理异步关闭和中断，这些情况可能发生在执行 I/O 操作的任何时间点。
 * begin() 方法在 I/O 操作开始前被调用，以确保如果线程在执行操作时被中断，那么通道可以安全地关闭，并且线程可以退出 I/O 操作。
 * end() 方法在 I/O 操作完成后被调用，以确保如果发生中断，正确的异常可以被抛出。
 * 总结一下，即使 Channels 在非阻塞模式下不会因为 I/O 操作本身而阻塞线程，它们仍然需要能够安全地处理关闭和中断，这正是 AbstractInterruptibleChannel 设计要解决的问题。
 *
 *
 * @author Mark Reinhold
 * @author JSR-51 Expert Group
 * @since 1.4
 */

public abstract class AbstractInterruptibleChannel
    implements Channel, InterruptibleChannel
{

    private final Object closeLock = new Object();
    private volatile boolean open = true;

    /**
     * Initializes a new instance of this class.
     */
    protected AbstractInterruptibleChannel() { }

    /**
     * Closes this channel.
     *
     * <p> If the channel has already been closed then this method returns
     * immediately.  Otherwise it marks the channel as closed and then invokes
     * the {@link #implCloseChannel implCloseChannel} method in order to
     * complete the close operation.  </p>
     *
     * @throws  IOException
     *          If an I/O error occurs
     */
    public final void close() throws IOException {
        synchronized (closeLock) {
            if (!open)
                return;
            open = false;
            implCloseChannel();
        }
    }

    /**
     * Closes this channel.
     *
     * <p> This method is invoked by the {@link #close close} method in order
     * to perform the actual work of closing the channel.  This method is only
     * invoked if the channel has not yet been closed, and it is never invoked
     * more than once.
     *
     * <p> An implementation of this method must arrange for any other thread
     * that is blocked in an I/O operation upon this channel to return
     * immediately, either by throwing an exception or by returning normally.
     * </p>
     *
     * @throws  IOException
     *          If an I/O error occurs while closing the channel
     */
    protected abstract void implCloseChannel() throws IOException;

    public final boolean isOpen() {
        return open;
    }


    // -- Interruption machinery --

    private Interruptible interruptor;
    private volatile Thread interrupted;

    /**
     * Marks the beginning of an I/O operation that might block indefinitely.
     *
     * <p> This method should be invoked in tandem with the {@link #end end}
     * method, using a <tt>try</tt>&nbsp;...&nbsp;<tt>finally</tt> block as
     * shown <a href="#be">above</a>, in order to implement asynchronous
     * closing and interruption for this channel.  </p>
     */
    protected final void begin() {
        if (interruptor == null) {
            interruptor = new Interruptible() {
                    public void interrupt(Thread target) {
                        /**
                         * closeLock 是对于当前channel实现类而言
                         * 一个impl一把closeLock。排他级别是channel
                         * traget 是调用 begin的线程 是 "预存" 也即使当 A线程调用完 begin之后 再去执行别的动作
                         * 它的引用都会被 预存 并在触发时 作为行参
                         * 关闭此通道
                         */
                        synchronized (closeLock) {
                            if (!open)
                                return;
                            open = false;
                            interrupted = target;
                            try {
                                AbstractInterruptibleChannel.this.implCloseChannel();
                            } catch (IOException x) { }
                        }
                    }};
        }
        blockedOn(interruptor);
        Thread me = Thread.currentThread();
        if (me.isInterrupted())
            interruptor.interrupt(me);
    }

    /**
     * Marks the end of an I/O operation that might block indefinitely.
     *
     * <p> This method should be invoked in tandem with the {@link #begin
     * begin} method, using a <tt>try</tt>&nbsp;...&nbsp;<tt>finally</tt> block
     * as shown <a href="#be">above</a>, in order to implement asynchronous
     * closing and interruption for this channel.  </p>
     *
     * @param  completed
     *         <tt>true</tt> if, and only if, the I/O operation completed
     *         successfully, that is, had some effect that would be visible to
     *         the operation's invoker
     *
     * @throws  AsynchronousCloseException
     *          If the channel was asynchronously closed
     *
     * @throws  ClosedByInterruptException
     *          If the thread blocked in the I/O operation was interrupted
     */
    protected final void end(boolean completed)
        throws AsynchronousCloseException
    {
        blockedOn(null);
        Thread interrupted = this.interrupted;
        if (interrupted != null && interrupted == Thread.currentThread()) {
            interrupted = null;
            throw new ClosedByInterruptException();
        }
        if (!completed && !open)
            throw new AsynchronousCloseException();
    }


    // -- sun.misc.SharedSecrets --
    static void blockedOn(Interruptible intr) {         // package-private
        sun.misc.SharedSecrets.getJavaLangAccess().blockedOn(Thread.currentThread(),
                                                             intr);
    }
}
