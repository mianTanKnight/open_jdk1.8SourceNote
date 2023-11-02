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

package java.nio.channels;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.io.IOException;


/**
 * A token representing the registration of a {@link SelectableChannel} with a
 * {@link Selector}.
 *
 * <p> A selection key is created each time a channel is registered with a
 * selector.  A key remains valid until it is <i>cancelled</i> by invoking its
 * {@link #cancel cancel} method, by closing its channel, or by closing its
 * selector.  Cancelling a key does not immediately remove it from its
 * selector; it is instead added to the selector's <a
 * href="Selector.html#ks"><i>cancelled-key set</i></a> for removal during the
 * next selection operation.  The validity of a key may be tested by invoking
 * its {@link #isValid isValid} method.
 *
 * <a name="opsets"></a>
 *
 * <p> A selection key contains two <i>operation sets</i> represented as
 * integer values.  Each bit of an operation set denotes a category of
 * selectable operations that are supported by the key's channel.
 *
 * <ul>
 *
 *   <li><p> The <i>interest set</i> determines which operation categories will
 *   be tested for readiness the next time one of the selector's selection
 *   methods is invoked.  The interest set is initialized with the value given
 *   when the key is created; it may later be changed via the {@link
 *   #interestOps(int)} method. </p></li>
 *
 *   <li><p> The <i>ready set</i> identifies the operation categories for which
 *   the key's channel has been detected to be ready by the key's selector.
 *   The ready set is initialized to zero when the key is created; it may later
 *   be updated by the selector during a selection operation, but it cannot be
 *   updated directly. </p></li>
 *
 * </ul>
 *
 * <p> That a selection key's ready set indicates that its channel is ready for
 * some operation category is a hint, but not a guarantee, that an operation in
 * such a category may be performed by a thread without causing the thread to
 * block.  A ready set is most likely to be accurate immediately after the
 * completion of a selection operation.  It is likely to be made inaccurate by
 * external events and by I/O operations that are invoked upon the
 * corresponding channel.
 *
 * <p> This class defines all known operation-set bits, but precisely which
 * bits are supported by a given channel depends upon the type of the channel.
 * Each subclass of {@link SelectableChannel} defines an {@link
 * SelectableChannel#validOps() validOps()} method which returns a set
 * identifying just those operations that are supported by the channel.  An
 * attempt to set or test an operation-set bit that is not supported by a key's
 * channel will result in an appropriate run-time exception.
 *
 * <p> It is often necessary to associate some application-specific data with a
 * selection key, for example an object that represents the state of a
 * higher-level protocol and handles readiness notifications in order to
 * implement that protocol.  Selection keys therefore support the
 * <i>attachment</i> of a single arbitrary object to a key.  An object can be
 * attached via the {@link #attach attach} method and then later retrieved via
 * the {@link #attachment() attachment} method.
 *
 * <p> Selection keys are safe for use by multiple concurrent threads.  The
 * operations of reading and writing the interest set will, in general, be
 * synchronized with certain operations of the selector.  Exactly how this
 * synchronization is performed is implementation-dependent: In a naive
 * implementation, reading or writing the interest set may block indefinitely
 * if a selection operation is already in progress; in a high-performance
 * implementation, reading or writing the interest set may block briefly, if at
 * all.  In any case, a selection operation will always use the interest-set
 * value that was current at the moment that the operation began.  </p>
 *
 *SelectionKey是一个标识，代表着SelectableChannel（可选择通道）与Selector（选择器）之间的注册关系。
 *
 * 每次通道注册到选择器时，就会创建一个选择键。只有当选择键被取消、其关联的通道被关闭，或者其关联的选择器被关闭时，这个选择键才会失效。
 * 取消一个键并不会立即从选择器中移除它；相反，它会被加入到选择器的“已取消键集”中，等待在下一轮的选择操作中被移除。可以通过调用isValid方法来检测一个键是否仍然有效。
 * 选择键包含两个操作集：
 * “兴趣集”决定了在下一次选择操作时，哪些操作类别会被检测是否准备就绪。兴趣集在创建键时被初始化，并可之后通过interestOps(int)方法进行修改。
 * “就绪集”指出了通道在哪些操作类别上已经准备就绪，可以进行非阻塞操作。就绪集在创建键时被置为零，并可能在选择操作中被更新，但不能直接修改。
 * 选择键的就绪集表示通道已经准备好进行某些操作，但这并不保证执行这些操作时线程不会阻塞。就绪集在选择操作完成后最为准确，但随后可能因为外部事件或对通道的I/O操作而变得不准确。
 * 此类定义了所有已知的操作集位，但特定通道支持哪些位取决于通道的类型。
 * 每个SelectableChannel的子类定义了一个validOps()方法，它返回一个集合，标识了该通道支持的操作。尝试设置或测试一个通道不支持的操作集位将引发运行时异常。
 * 在实际使用中，通常需要将应用程序特定的数据附加到选择键上，
 * 例如，用于表示更高级别协议状态的对象，以响应准备就绪通知来实现该协议。因此，选择键允许将一个任意对象作为附件与键关联。可以通过attach方法附加对象，并通过attachment()方法检索。
 * 选择键支持多线程并发使用。读取和写入兴趣集的操作通常与选择器的特定操作同步。如何进行同步依赖于具体实现：在一些简单的实现中，
 * 读取或写入兴趣集可能会在选择操作进行时无限期地阻塞；而在高性能的实现中，读取或写入兴趣集可能只会短暂阻塞，或者根本不阻塞。无论如何，选择操作都会使用该操作开始时的兴趣集的当前值。
 * @author Mark Reinhold
 * @author JSR-51 Expert Group
 * @since 1.4
 *
 * @see SelectableChannel
 * @see Selector
 */

public abstract class SelectionKey {

    /**
     * Constructs an instance of this class.
     */
    protected SelectionKey() { }


    // -- Channel and selector operations --

    /**
     * Returns the channel for which this key was created.  This method will
     * continue to return the channel even after the key is cancelled.
     *
     * @return  This key's channel
     */
    public abstract SelectableChannel channel();

    /**
     * Returns the selector for which this key was created.  This method will
     * continue to return the selector even after the key is cancelled.
     *
     * @return  This key's selector
     */
    public abstract Selector selector();

    /**
     * Tells whether or not this key is valid.
     * Key是否还有效  channel被关闭或selector被关闭
     * <p> A key is valid upon creation and remains so until it is cancelled,
     * its channel is closed, or its selector is closed.  </p>
     *
     * @return  <tt>true</tt> if, and only if, this key is valid
     */
    public abstract boolean isValid();

    /**
     * Requests that the registration of this key's channel with its selector
     * be cancelled.  Upon return the key will be invalid and will have been
     * added to its selector's cancelled-key set.  The key will be removed from
     * all of the selector's key sets during the next selection operation.
     *
     * <p> If this key has already been cancelled then invoking this method has
     * no effect.  Once cancelled, a key remains forever invalid. </p>
     *
     * <p> This method may be invoked at any time.  It synchronizes on the
     * selector's cancelled-key set, and therefore may block briefly if invoked
     * concurrently with a cancellation or selection operation involving the
     * same selector.  </p>
     * 通知中断与chanel的连接 但不是selectionKey有channel的连接
     * 而是select与channel的连接
     * SelectionKey 更像粘合剂
     */
    public abstract void cancel();


    // -- Operation-set accessors --

    /**
     * Retrieves this key's interest set.
     *
     * <p> It is guaranteed that the returned set will only contain operation
     * bits that are valid for this key's channel.
     *
     * <p> This method may be invoked at any time.  Whether or not it blocks,
     * and for how long, is implementation-dependent.  </p>
     *
     * @return  This key's interest set
     *
     * @throws  CancelledKeyException
     *          If this key has been cancelled
     *
     *
     * 返回的是 int
     * 使用bit位储存ops
     *
     * 例:
     * OP_READ 可能被定义为 1 << 0（即，二进制的 0001）。
     * OP_WRITE 可能被定义为 1 << 2（即，二进制的 0100）。
     *
     * 然后再使用0xff 取出来
     */
    public abstract int interestOps();

    /**
     * Sets this key's interest set to the given value.
     *
     * <p> This method may be invoked at any time.  Whether or not it blocks,
     * and for how long, is implementation-dependent.  </p>
     *
     * @param  ops  The new interest set
     *
     * @return  This selection key
     *
     * @throws  IllegalArgumentException
     *          If a bit in the set does not correspond to an operation that
     *          is supported by this key's channel, that is, if
     *          {@code (ops & ~channel().validOps()) != 0}
     *
     * @throws  CancelledKeyException
     *          If this key has been cancelled
     */
    public abstract SelectionKey interestOps(int ops);

    /**
     * Retrieves this key's ready-operation set.
     * 检查就绪集 此SelectKey
     * <p> It is guaranteed that the returned set will only contain operation
     * bits that are valid for this key's channel.  </p>
     *
     * @return  This key's ready-operation set
     *
     * @throws  CancelledKeyException
     *          If this key has been cancelled
     */
    public abstract int readyOps();


    // -- Operation bits and bit-testing convenience methods --

    /**
     * Operation-set bit for read operations.
     *
     * <p> Suppose that a selection key's interest set contains
     * <tt>OP_READ</tt> at the start of a <a
     * href="Selector.html#selop">selection operation</a>.  If the selector
     * detects that the corresponding channel is ready for reading, has reached
     * end-of-stream, has been remotely shut down for further reading, or has
     * an error pending, then it will add <tt>OP_READ</tt> to the key's
     * ready-operation set and add the key to its selected-key&nbsp;set.  </p>
     */
    public static final int OP_READ = 1 << 0;

    /**
     * Operation-set bit for write operations.
     *
     * <p> Suppose that a selection key's interest set contains
     * <tt>OP_WRITE</tt> at the start of a <a
     * href="Selector.html#selop">selection operation</a>.  If the selector
     * detects that the corresponding channel is ready for writing, has been
     * remotely shut down for further writing, or has an error pending, then it
     * will add <tt>OP_WRITE</tt> to the key's ready set and add the key to its
     * selected-key&nbsp;set.  </p>
     */
    public static final int OP_WRITE = 1 << 2;

    /**
     * Operation-set bit for socket-connect operations.
     *
     * <p> Suppose that a selection key's interest set contains
     * <tt>OP_CONNECT</tt> at the start of a <a
     * href="Selector.html#selop">selection operation</a>.  If the selector
     * detects that the corresponding socket channel is ready to complete its
     * connection sequence, or has an error pending, then it will add
     * <tt>OP_CONNECT</tt> to the key's ready set and add the key to its
     * selected-key&nbsp;set.  </p>
     */
    public static final int OP_CONNECT = 1 << 3;

    /**
     * Operation-set bit for socket-accept operations.
     *
     * <p> Suppose that a selection key's interest set contains
     * <tt>OP_ACCEPT</tt> at the start of a <a
     * href="Selector.html#selop">selection operation</a>.  If the selector
     * detects that the corresponding server-socket channel is ready to accept
     * another connection, or has an error pending, then it will add
     * <tt>OP_ACCEPT</tt> to the key's ready set and add the key to its
     * selected-key&nbsp;set.  </p>
     */
    public static final int OP_ACCEPT = 1 << 4;

    /**
     * Tests whether this key's channel is ready for reading.
     *
     * <p> An invocation of this method of the form <tt>k.isReadable()</tt>
     * behaves in exactly the same way as the expression
     *
     * <blockquote><pre>{@code
     * k.readyOps() & OP_READ != 0
     * }</pre></blockquote>
     *
     * <p> If this key's channel does not support read operations then this
     * method always returns <tt>false</tt>.  </p>
     *
     * @return  <tt>true</tt> if, and only if,
                {@code readyOps() & OP_READ} is nonzero
     *
     * @throws  CancelledKeyException
     *          If this key has been cancelled
     *
     * 是不是读准备好了
     * 这里为什么不用等于号判断? 也就是 readyOps == OP_READ
     * 是因为存在包含的情况 读写事都有 所以使用 & 运算
     * 举个了例子
     * 1001 (读写)
     * 1001 & 0001 = 0001
     * 如果没有
     * 1000 & 0001  = 0000
     */
    public final boolean isReadable() {
        return (readyOps() & OP_READ) != 0;
    }

    /**
     * Tests whether this key's channel is ready for writing.
     *
     * <p> An invocation of this method of the form <tt>k.isWritable()</tt>
     * behaves in exactly the same way as the expression
     *
     * <blockquote><pre>{@code
     * k.readyOps() & OP_WRITE != 0
     * }</pre></blockquote>
     *
     * <p> If this key's channel does not support write operations then this
     * method always returns <tt>false</tt>.  </p>
     *
     * @return  <tt>true</tt> if, and only if,
     *          {@code readyOps() & OP_WRITE} is nonzero
     *
     * @throws  CancelledKeyException
     *          If this key has been cancelled
     * 同上
     */
    public final boolean isWritable() {
        return (readyOps() & OP_WRITE) != 0;
    }

    /**
     * Tests whether this key's channel has either finished, or failed to
     * finish, its socket-connection operation.
     *
     * <p> An invocation of this method of the form <tt>k.isConnectable()</tt>
     * behaves in exactly the same way as the expression
     *
     * <blockquote><pre>{@code
     * k.readyOps() & OP_CONNECT != 0
     * }</pre></blockquote>
     *
     * <p> If this key's channel does not support socket-connect operations
     * then this method always returns <tt>false</tt>.  </p>
     *
     * @return  <tt>true</tt> if, and only if,
     *          {@code readyOps() & OP_CONNECT} is nonzero
     *
     * @throws  CancelledKeyException
     *          If this key has been cancelled
     */
    public final boolean isConnectable() {
        return (readyOps() & OP_CONNECT) != 0;
    }

    /**
     * Tests whether this key's channel is ready to accept a new socket
     * connection.
     *
     * <p> An invocation of this method of the form <tt>k.isAcceptable()</tt>
     * behaves in exactly the same way as the expression
     *
     * <blockquote><pre>{@code
     * k.readyOps() & OP_ACCEPT != 0
     * }</pre></blockquote>
     *
     * <p> If this key's channel does not support socket-accept operations then
     * this method always returns <tt>false</tt>.  </p>
     *
     * @return  <tt>true</tt> if, and only if,
     *          {@code readyOps() & OP_ACCEPT} is nonzero
     *
     * @throws  CancelledKeyException
     *          If this key has been cancelled
     */
    public final boolean isAcceptable() {
        return (readyOps() & OP_ACCEPT) != 0;
    }


    // -- Attachments --

    /**
     * attachment 为什么要用 volatile 修饰
     * 目的当前是为了保障线程安全
     * 原因是 注册attach和消费attach(有可能被其他线程更改) 不在一个线程环境
     */
    private volatile Object attachment = null;

    private static final AtomicReferenceFieldUpdater<SelectionKey,Object> attachmentUpdater = AtomicReferenceFieldUpdater.newUpdater(SelectionKey.class, Object.class, "attachment");

    /**
     * Attaches the given object to this key.
     *
     * <p> An attached object may later be retrieved via the {@link #attachment()
     * attachment} method.  Only one object may be attached at a time; invoking
     * this method causes any previous attachment to be discarded.  The current
     * attachment may be discarded by attaching <tt>null</tt>.  </p>
     *
     * @param  ob
     *         The object to be attached; may be <tt>null</tt>
     *
     * @return  The previously-attached object, if any,
     *          otherwise <tt>null</tt>
     */
    public final Object attach(Object ob) {
        return attachmentUpdater.getAndSet(this, ob);
    }

    /**
     * Retrieves the current attachment.
     *
     * @return  The object currently attached to this key,
     *          or <tt>null</tt> if there is no attachment
     */
    public final Object attachment() {
        return attachment;
    }

}
