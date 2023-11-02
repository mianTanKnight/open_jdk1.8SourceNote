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

    /**
     * interruptTriggered 变量用来区分 Selector 的 select() 方法是因为有 I/O 事件发生而返回，还是因为其他原因（如调用 wakeup()
     * 方法或其他中断）被唤醒。这个标志对于控制 Selector 的行为和处理中断逻辑是必要的。在处理并发程序时，正确地管理这些状态是确保程序行为可预测和可控的关键
     */
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
            /**
             * poll的唤醒有两种情况
             * 1: 就绪事件 >= 1
             * 2: 中断通知
             */
            pollWrapper.poll(timeout);
        } finally {
            end(); //poll 有结果之后就清除中断
        }
        processDeregisterQueue(); //再次处理注销队列
        int numKeysUpdated = updateSelectedKeys(); //
        if (pollWrapper.interrupted()) { //中断本次epoll 清扫工作
            // Clear the wakeup pipe
            pollWrapper.putEventOps(pollWrapper.interruptedIndex(), 0); //注册关闭中断管道
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
     *
     * 处理epoll 发生的事件 通知selectKey和 add to SelectKeys(如果之前没有的话)
     */
    private int updateSelectedKeys() {
        int entries = pollWrapper.updated; //已经准备好IO事件的数量
        int numKeysUpdated = 0;
        for (int i=0; i<entries; i++) {
            int nextFD = pollWrapper.getDescriptor(i); //获得 fd 这个fd是已经有准备好Io的 fd
            /**
             * 这里我们要弄清一个关系 fd和selectionKey的关系 其实是 channel和 selectionKey .channel 是 JAVA fd的实现
             * fd和对应的 event 在 pollWrapper中维护 但 pollWrapper 并不知道 fd是那个 channel(也就是JAVA的封装)
             * 那么这个对应关系就是  EPollSelectorImpl 在维护的
             * 也就是 fdToKey在维护
             *
             */
            SelectionKeyImpl ski = fdToKey.get(Integer.valueOf(nextFD)); //获得 封装的SelectionKey
            // ski is null in the case of an interrupt
            if (ski != null) {
                //获得fd具体的事件
                int rOps = pollWrapper.getEventOps(i);
                /**
                 * selectedKeys 为已经准备IO事件 Keys集
                 * 如果selectedKeys已经存在 就不需要再重复添加 因为 selectedKeys不会在乎次数 而只是是否存在
                 * 把rops通知channel key 会更新 readySet中
                 * 如果不存在就添加
                 * 重点要理解 selectedKeys的作用
                 *
                 */
                if (selectedKeys.contains(ski)) { //如果已经注册
                    if (ski.channel.translateAndSetReadyOps(rOps, ski)) { // 通知 channel
                        numKeysUpdated++;
                    }
                } else { //如果 没有注册
                    ski.channel.translateAndSetReadyOps(rOps, ski); //通知 channel ??
                    //获取当前准备的事件 是否为感兴趣的事件
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
        if (closed) return;
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
        if (closed) throw new ClosedSelectorException();
        SelChImpl ch = ski.channel; //获得 channel
        int fd = Integer.valueOf(ch.getFDVal());
        fdToKey.put(fd, ski);
        pollWrapper.add(fd);
        keys.add(ski);
    }

    protected void implDereg(SelectionKeyImpl ski) throws IOException {  // SelectorKey 取消的实现
        assert (ski.getIndex() >= 0); //如果不大于0 就证明已经处理过了
        SelChImpl ch = ski.channel;
        int fd = ch.getFDVal(); //获得 fd
        fdToKey.remove(Integer.valueOf(fd)); //移除selectKey
        pollWrapper.remove(fd);
        ski.setIndex(-1);
        keys.remove(ski);
        selectedKeys.remove(ski);
        deregister((AbstractSelectionKey)ski);
        SelectableChannel selch = ski.channel();
        if (!selch.isOpen() && !selch.isRegistered())
            ((SelChImpl)selch).kill();
    }

    public void putEventOps(SelectionKeyImpl ski, int ops) { //把fd和感兴趣的事件注册到epoll
        if (closed)
            throw new ClosedSelectorException();
        SelChImpl ch = ski.channel;
        pollWrapper.setInterest(ch.getFDVal(), ops);
    }

    /**
     * wakeup 是个唤醒动作
     * 也就是通过 pollWrapper 给中断管道发送中断信息
     * @return
     */
    public Selector wakeup() { // epoll wait中断通知 唤醒wait的线程(单一线程)
        synchronized (interruptLock) {
            if (!interruptTriggered) { //如果没有中断过
                pollWrapper.interrupt(); //那么通知中断管道
                interruptTriggered = true; //更新为 中断型唤醒
            }
        }
        return this;
    }
}
