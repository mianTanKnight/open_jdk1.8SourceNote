/*
 * Copyright (c) 2005, 2015, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.util.*;
import sun.misc.*;

/**
 * An implementation of Selector for Linux 2.6+ kernels that uses
 * the epoll event notification facility.
 *
 * 这里我们想要理解中断支持
 *
 * Java线程调用Selector.select()时，它实际上是在底层通过像epoll这样的系统调用被阻塞住的，
 * 这是在操作系统的内核级别上的阻塞。
 * 然而，当我们希望中断这个Java线程时（例如，调用线程的interrupt()方法），
 * 我们不能直接从用户态进程中断内核态的epoll调用。
 * 因此，Java NIO使用了一个技巧：通过一个特殊的管道（又称为中断管道）来模拟I/O事件。
 * 这个管道是Java NIO在初始化Selector时创建的，
 * 它的目的就是为了能够在需要时产生一个可以立即返回的I/O事件。
 * 当你中断一个正在调用Selector.select()的线程时，Java NIO会向这个中断管道的写端发送数据。
 * 这样，epoll（或其他相应的系统调用）会检测到这个管道的读端是可读的，因此它会返回，就好像真正的I/O事件发生了一样。
 * 这导致Java线程从Selector.select()中返回，然后Java NIO可以处理线程的中断状态。
 *
 */
class EPollSelectorImpl extends SelectorImpl
{

    // File descriptors used for interrupt
    protected int fd0;
    protected int fd1;

    // The poll object
    EPollArrayWrapper pollWrapper; // C中 epoll的JAVA封装 详细实现分析 @EPollArrayWrapper

    // Maps from file descriptors to keys
    private Map<Integer,SelectionKeyImpl> fdToKey; // 感兴趣的事件Key 拥有select 和 channel的引用

    // True if this Selector has been closed
    private volatile boolean closed = false;

    // Lock for interrupt triggering and clearing
    private final Object interruptLock = new Object(); //中断锁
    private boolean interruptTriggered = false;

    /**
     * Package private constructor called by factory method in
     * the abstract superclass Selector.
     * 在Java NIO中，Selector是一个对象，它可以监视多个SelectableChannel的I/O状态（例如SocketChannel或ServerSocketChannel）。
     * Selector是对象 但也是一个线程 它更像Boss总线
     *
     */
    EPollSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);
        long pipeFds = IOUtil.makePipe(false); //获得一个无名管道 Linux支持
        /**
         *  IOUtil.makePipe(false)创建了一个无名管道，并返回一个长整数（long），该整数实际上编码了两个文件描述符。
         *  无名管道在UNIX和Linux系统中是一个很基本的IPC（进程间通信）机制，它提供了两个文件描述符：一个用于读，另一个用于写。
         *  fd0通常是读端。
         *  fd1通常是写端。
         */
        fd0 = (int) (pipeFds >>> 32); // 获得高 32位
        fd1 = (int) pipeFds; // 获得低32位
        try {
            pollWrapper = new EPollArrayWrapper(); //构建一个epoll的包装类
            pollWrapper.initInterrupt(fd0, fd1); //注册interrupt 但fd0 和 fd1的区别是什么呢？？？
            fdToKey = new HashMap<>();
        } catch (Throwable t) {
            try {
                FileDispatcherImpl.closeIntFD(fd0);
            } catch (IOException ioe0) {
                t.addSuppressed(ioe0);
            }
            try {
                FileDispatcherImpl.closeIntFD(fd1);
            } catch (IOException ioe1) {
                t.addSuppressed(ioe1);
            }
            throw t;
        }
    }

    protected int doSelect(long timeout) throws IOException {
        if (closed)
            throw new ClosedSelectorException();
        processDeregisterQueue(); //当前线程会先处理注销列队
        try {
            begin(); //注册中断支持
            pollWrapper.poll(timeout); //调用 poll
        } finally {
            end();
        }
        processDeregisterQueue(); //再次处理注销队列
        int numKeysUpdated = updateSelectedKeys(); //
        if (pollWrapper.interrupted()) {
            // Clear the wakeup pipe
            pollWrapper.putEventOps(pollWrapper.interruptedIndex(), 0);
            synchronized (interruptLock) {
                pollWrapper.clearInterrupted();
                IOUtil.drain(fd0);
                interruptTriggered = false;
            }
        }
        return numKeysUpdated;
    }

    /**
     * Update the keys whose fd's have been selected by the epoll.
     * Add the ready keys to the ready queue.
     */
    private int updateSelectedKeys() {
        int entries = pollWrapper.updated;
        int numKeysUpdated = 0;
        for (int i=0; i<entries; i++) {
            int nextFD = pollWrapper.getDescriptor(i);
            SelectionKeyImpl ski = fdToKey.get(Integer.valueOf(nextFD));
            // ski is null in the case of an interrupt
            if (ski != null) {
                int rOps = pollWrapper.getEventOps(i);
                if (selectedKeys.contains(ski)) {
                    if (ski.channel.translateAndSetReadyOps(rOps, ski)) {
                        numKeysUpdated++;
                    }
                } else {
                    ski.channel.translateAndSetReadyOps(rOps, ski);
                    if ((ski.nioReadyOps() & ski.nioInterestOps()) != 0) {
                        selectedKeys.add(ski);
                        numKeysUpdated++;
                    }
                }
            }
        }
        return numKeysUpdated;
    }

    protected void implClose() throws IOException {
        if (closed)
            return;
        closed = true;

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        FileDispatcherImpl.closeIntFD(fd0);
        FileDispatcherImpl.closeIntFD(fd1);

        pollWrapper.closeEPollFD();
        // it is possible
        selectedKeys = null;

        // Deregister channels
        Iterator<SelectionKey> i = keys.iterator();
        while (i.hasNext()) {
            SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
            deregister(ski);
            SelectableChannel selch = ski.channel();
            if (!selch.isOpen() && !selch.isRegistered())
                ((SelChImpl)selch).kill();
            i.remove();
        }

        fd0 = -1;
        fd1 = -1;
    }

    protected void implRegister(SelectionKeyImpl ski) {
        if (closed)
            throw new ClosedSelectorException();
        SelChImpl ch = ski.channel;
        int fd = Integer.valueOf(ch.getFDVal());
        fdToKey.put(fd, ski);
        pollWrapper.add(fd);
        keys.add(ski);
    }

    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert (ski.getIndex() >= 0);
        SelChImpl ch = ski.channel;
        int fd = ch.getFDVal();
        fdToKey.remove(Integer.valueOf(fd));
        pollWrapper.remove(fd);
        ski.setIndex(-1);
        keys.remove(ski);
        selectedKeys.remove(ski);
        deregister((AbstractSelectionKey)ski);
        SelectableChannel selch = ski.channel();
        if (!selch.isOpen() && !selch.isRegistered())
            ((SelChImpl)selch).kill();
    }

    public void putEventOps(SelectionKeyImpl ski, int ops) {
        if (closed)
            throw new ClosedSelectorException();
        SelChImpl ch = ski.channel;
        pollWrapper.setInterest(ch.getFDVal(), ops);
    }

    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                pollWrapper.interrupt();
                interruptTriggered = true;
            }
        }
        return this;
    }
}
