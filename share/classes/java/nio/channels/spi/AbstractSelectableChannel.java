/*
 * Copyright (c) 2000, 2018, Oracle and/or its affiliates. All rights reserved.
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

package java.nio.channels.spi;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;


/**
 * Base implementation class for selectable channels.
 *
 * <p> This class defines methods that handle the mechanics of channel
 * registration, deregistration, and closing.  It maintains the current
 * blocking mode of this channel as well as its current set of selection keys.
 * It performs all of the synchronization required to implement the {@link
 * java.nio.channels.SelectableChannel} specification.  Implementations of the
 * abstract protected methods defined in this class need not synchronize
 * against other threads that might be engaged in the same operations.  </p>
 *
 * 提供线程安全的保证 基础父类
 *
 * @author Mark Reinhold
 * @author Mike McCloskey
 * @author JSR-51 Expert Group
 * @since 1.4
 */

public abstract class AbstractSelectableChannel extends SelectableChannel
{

    // The provider that created this channel
    private final SelectorProvider provider;  // 工厂车间

    // Keys that have been created by registering this channel with selectors.
    // They are saved because if this channel is closed the keys must be
    // deregistered.  Protected by keyLock.
    //
    private SelectionKey[] keys = null; //与selectors绑定的SelectionKey 已经创建
    private int keyCount = 0;

    // Lock for key set and count
    private final Object keyLock = new Object();

    // Lock for registration and configureBlocking operations
    private final Object regLock = new Object();

    // True when non-blocking, need regLock to change;
    private volatile boolean nonBlocking;

    /**
     * Initializes a new instance of this class.
     *
     * @param  provider
     *         The provider that created this channel
     */
    protected AbstractSelectableChannel(SelectorProvider provider) {
        this.provider = provider;
    }

    /**
     * Returns the provider that created this channel.
     *
     * @return  The provider that created this channel
     */
    public final SelectorProvider provider() {
        return provider;
    }


    // -- Utility methods for the key set --

    private void addKey(SelectionKey k) {
        assert Thread.holdsLock(keyLock); // 锁检查
        int i = 0;
        if ((keys != null) && (keyCount < keys.length)) {
            // Find empty element of key array
            for (i = 0; i < keys.length; i++)
                if (keys[i] == null)
                    break;
        } else if (keys == null) {
            keys =  new SelectionKey[3];
        } else {
            // Grow key array
            int n = keys.length * 2;
            SelectionKey[] ks =  new SelectionKey[n];
            for (i = 0; i < keys.length; i++)
                ks[i] = keys[i];
            keys = ks;
            i = keyCount;
        }
        keys[i] = k;
        keyCount++;
    }

    private SelectionKey findKey(Selector sel) {
        synchronized (keyLock) {
            if (keys == null)
                return null;
            for (int i = 0; i < keys.length; i++)
                if ((keys[i] != null) && (keys[i].selector() == sel))
                    return keys[i];
            return null;
        }
    }

    void removeKey(SelectionKey k) {                    // package-private
        synchronized (keyLock) {
            for (int i = 0; i < keys.length; i++)
                if (keys[i] == k) {
                    keys[i] = null;
                    keyCount--;
                }
            ((AbstractSelectionKey)k).invalidate();  // 使selectionKey失效
        }
    }

    private boolean haveValidKeys() {
        synchronized (keyLock) {
            if (keyCount == 0)
                return false;
            for (int i = 0; i < keys.length; i++) {
                if ((keys[i] != null) && keys[i].isValid())
                    return true;
            }
            return false;
        }
    }


    // -- Registration --

    public final boolean isRegistered() {
        synchronized (keyLock) {
            return keyCount != 0;
        }
    }

    public final SelectionKey keyFor(Selector sel) {
        return findKey(sel);
    }

    /**
     * Registers this channel with the given selector, returning a selection key.
     *
     * <p>  This method first verifies that this channel is open and that the
     * given initial interest set is valid.
     *
     * <p> If this channel is already registered with the given selector then
     * the selection key representing that registration is returned after
     * setting its interest set to the given value.
     *
     * <p> Otherwise this channel has not yet been registered with the given
     * selector, so the {@link AbstractSelector#register register} method of
     * the selector is invoked while holding the appropriate locks.  The
     * resulting key is added to this channel's key set before being returned.
     * </p>
     *
     * @throws  ClosedSelectorException {@inheritDoc}
     *
     * @throws  IllegalBlockingModeException {@inheritDoc}
     *
     * @throws  IllegalSelectorException {@inheritDoc}
     *
     * @throws  CancelledKeyException {@inheritDoc}
     *
     * @throws  IllegalArgumentException {@inheritDoc}
     */
    public final SelectionKey register(Selector sel, int ops,
                                       Object att)
        throws ClosedChannelException
    {
        synchronized (regLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if ((ops & ~validOps()) != 0)
                throw new IllegalArgumentException();
            if (isBlocking())
                throw new IllegalBlockingModeException();
            SelectionKey k = findKey(sel);
            if (k != null) {
                k.interestOps(ops); // 注册兴趣事件到selectKey
                k.attach(att); // 注册回调对象
            }
            if (k == null) { //channel 还没有绑定到 selector
                // New registration
                synchronized (keyLock) {
                    if (!isOpen())
                        throw new ClosedChannelException();
                    /**
                     * channel 会主动注册到 sel
                     * selector  其实也有个注册动作
                     * 是绑定 channel ops 和 att
                     */
                    k = ((AbstractSelector)sel).register(this, ops, att);
                    addKey(k);
                }
            }
            return k;
        }
    }


    // -- Closing --

    /**
     * 在Java NIO中，implCloseChannel和implCloseSelectableChannel方法都是关于关闭通道（Channel）的，但它们位于不同的层级并有不同的职责：
     * implCloseChannel 方法是AbstractSelectableChannel类中的一个方法，它是从AbstractInterruptibleChannel类继承来的。这个方法的职责是关闭通道，
     * 并取消所有与该通道相关联的SelectionKey对象。取消SelectionKey意味着告诉Selector这个通道不再参与任何选择操作。这个方法是Channel接口中close方法的具体实现。它处理与所有选择键的解耦，并确保资源被适当地清理。
     * implCloseSelectableChannel 是一个抽象方法，它在AbstractSelectableChannel类中定义，
     * 必须由具体的SelectableChannel子类（如SocketChannel或ServerSocketChannel）来实现。这个方法的职责是执行关闭通道的具体操作，例如释放文件描述符或套接字。它是在implCloseChannel中被调用的，用于处理通道关闭时的资源释放和清理工作。
     *
     * 设计目的：
     * 分层责任：implCloseChannel处理的是所有可选择通道共有的关闭操作，如取消注册的键。而implCloseSelectableChannel由具体的通道实现，处理特定于该类型通道的关闭逻辑。
     * 资源清理：两者都关注于资源的释放，但implCloseSelectableChannel更专注于单个通道类型的资源，如特定的套接字资源。
     * 抽象和具体实现分离：通过分离抽象方法和具体实现，可以在不同层次上更灵活地管理代码，允许更易于维护和扩展的设计
     */

    /**
     * Closes this channel.
     *
     * <p> This method, which is specified in the {@link
     * AbstractInterruptibleChannel} class and is invoked by the {@link
     * java.nio.channels.Channel#close close} method, in turn invokes the
     * {@link #implCloseSelectableChannel implCloseSelectableChannel} method in
     * order to perform the actual work of closing this channel.  It then
     * cancels all of this channel's keys.  </p>
     */
    protected final void implCloseChannel() throws IOException {
        implCloseSelectableChannel();
        synchronized (keyLock) {
            int count = (keys == null) ? 0 : keys.length;
            for (int i = 0; i < count; i++) {
                SelectionKey k = keys[i];
                if (k != null)
                    k.cancel(); // 使用selecotionKey 取消 seletor会感知到
            }
        }
    }

    /**
     * Closes this selectable channel.
     * selectable channel 是个能注册到selector的 channel
     *
     * <p> This method is invoked by the {@link java.nio.channels.Channel#close
     * close} method in order to perform the actual work of closing the
     * channel.  This method is only invoked if the channel has not yet been
     * closed, and it is never invoked more than once.
     *
     * <p> An implementation of this method must arrange for any other thread
     * that is blocked in an I/O operation upon this channel to return
     * immediately, either by throwing an exception or by returning normally.
     * </p>
     *
     * @throws  IOException
     *          If an I/O error occurs
     */
    protected abstract void implCloseSelectableChannel() throws IOException;


    // -- Blocking --

    /**
     * 当通道处于非阻塞模式时（即nonBlocking标志被设置为true），
     * I/O操作会立即返回。如果在非阻塞模式下执行read操作且当前没有数据可读，
     * 这个操作将会立即返回，而不是挂起等待数据到达。
     * 同样地，如果执行write操作且不能立即写入所有数据，操作将会返回写入的数据量，即使这个数量小于请求写入的数据量。
     */

    public final boolean isBlocking() {  //是否是阻塞模式
        return !nonBlocking;
    }

    public final Object blockingLock() {   //那么阻塞模式只是在注册范围？？
        return regLock;
    }

    /**
     * Adjusts this channel's blocking mode.
     *
     * <p> If the given blocking mode is different from the current blocking
     * mode then this method invokes the {@link #implConfigureBlocking
     * implConfigureBlocking} method, while holding the appropriate locks, in
     * order to change the mode.  </p>
     */
    public final SelectableChannel configureBlocking(boolean block)
        throws IOException
    {
        synchronized (regLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            boolean blocking = !nonBlocking; //默认非阻塞
            if (block != blocking) {
                if (block && haveValidKeys())
                    throw new IllegalBlockingModeException();
                implConfigureBlocking(block);
                nonBlocking = !block;
            }
        }
        return this;
    }

    /**
     * Adjusts this channel's blocking mode.
     *
     * <p> This method is invoked by the {@link #configureBlocking
     * configureBlocking} method in order to perform the actual work of
     * changing the blocking mode.  This method is only invoked if the new mode
     * is different from the current mode.  </p>
     *
     * @param  block  If <tt>true</tt> then this channel will be placed in
     *                blocking mode; if <tt>false</tt> then it will be placed
     *                non-blocking mode
     *
     * @throws IOException
     *         If an I/O error occurs
     */
    protected abstract void implConfigureBlocking(boolean block)
        throws IOException;

}
