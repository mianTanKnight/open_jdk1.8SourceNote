/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
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
import java.security.AccessController;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import sun.security.action.GetIntegerAction;
/**
 * Manipulates a native array of epoll_event structs on Linux:
 *
 * typedef union epoll_data {
 *     void *ptr;
 *     int fd;
 *     __uint32_t u32;
 *     __uint64_t u64;
 *  } epoll_data_t;
 *
 * struct epoll_event {
 *     __uint32_t events;
 *     epoll_data_t data;
 * };
 *
 * The system call to wait for I/O events is epoll_wait(2). It populates an
 * array of epoll_event structures that are passed to the call. The data
 * member of the epoll_event structure contains the same data as was set
 * when the file descriptor was registered to epoll via epoll_ctl(2). In
 * this implementation we set data.fd to be the file descriptor that we
 * register. That way, we have the file descriptor available when we
 * process the events.
 */

class EPollArrayWrapper {
    // EPOLL_EVENTS
    private static final int EPOLLIN      = 0x001; // 000000000.....000000001(32位)

    // opcodes
    private static final int EPOLL_CTL_ADD      = 1;
    private static final int EPOLL_CTL_DEL      = 2;
    private static final int EPOLL_CTL_MOD      = 3;

    // Miscellaneous constants
    private static final int SIZE_EPOLLEVENT  = sizeofEPollEvent(); // sizeof(struct event)
    private static final int EVENT_OFFSET     = 0; // offset  应该event是在结构的位置  使用结构体指针- offset = event的指针
    private static final int DATA_OFFSET      = offsetofData(); // 同上
    private static final int FD_OFFSET        = DATA_OFFSET; //
    private static final int OPEN_MAX         = IOUtil.fdLimit(); //linux的设置的最大描述符数量
    private static final int NUM_EPOLLEVENTS  = Math.min(OPEN_MAX, 8192);

    // Special value to indicate that an update should be ignored
    private static final byte  KILLED = (byte)-1; //  -1 转成byte 11111111? 通常表示指定fd close了

    // Initial size of arrays for fd registration changes
    private static final int INITIAL_PENDING_UPDATE_SIZE = 64;

    private static final int MAX_UPDATE_ARRAY_SIZE = AccessController.doPrivileged( //// maximum size of updatesLow 这段代码的目的是根据系统属性来限制updatesLow数组的最大大小
        new GetIntegerAction("sun.nio.ch.maxUpdateArraySize", Math.min(OPEN_MAX, 64*1024)));

    // The fd of the epoll driver  epoll 的文件描述符
    private final int epfd;

     // The epoll_event array for results from epoll_wait
    private final AllocatedNativeObject pollArray; //AllocatedNativeObject是Java对本地内存块（在C语言中可以视为指针或数组）的对象表示。 它提供了一种方式，使Java能够安全地与在本地分配的内存块交互，而不直接暴露低级的内存操作。

    // Base address of the epoll_event array
    private final long pollArrayAddress; //pollArray 数组地址

    // The fd of the interrupt line going out
    private int outgoingInterruptFD; // interrup out fd

    // The fd of the interrupt line coming in
    private int incomingInterruptFD;

    // The index of the interrupt FD
    private int interruptedIndex;

    // Number of updated pollfd entries
    int updated; //  当调用epoll_wait后，某些文件描述符的状态可能发生了变化（例如，它们已经准备好进行读取或写入）。updated可能用于跟踪这些已经发生状态变化的文件描述符的数量。

    // object to synchronize fd registration changes
    private final Object updateLock = new Object();

    // number of file descriptors with registration changes pending
    private int updateCount; // 当调用epoll_wait后，某些文件描述符的状态可能发生了变化（例如，它们已经准备好进行读取或写入）。updated可能用于跟踪这些已经发生状态变化的文件描述符的数量。

    // file descriptors with registration changes pending
    private int[] updateDescriptors = new int[INITIAL_PENDING_UPDATE_SIZE]; // 当我们有一个或多个文件描述符需要更新时，我们可以将这些描述符的标识存放在这个数组中，然后在适当的时候批量处理它们。

    /**
     * eventsLow (不考虑eventsHigh，eventsHigh只是另一种存储方式，但设计目的与eventsLow相同)
     * 保存了所有的事件类型。
     * 例如: 如果 fd[1] = 100;
     * 那么 eventsLow[100] 就代表 fd1
     * 其Byte的二进制表示了其感兴趣的事件类型。
     * 0b00000001 代表 Read
     * 0b00000010 代表 Write
     * 因此，0b00000011 就表示对Read和Write都感兴趣。
     */
    private final byte[] eventsLow = new byte[MAX_UPDATE_ARRAY_SIZE];

    /**
     * 对于文件描述符值大于MAX_UPDATE_ARRAY_SIZE的情况，其相关的事件类型将被存储在这个映射中。
     * 映射的键是文件描述符的值，而映射的值是该文件描述符的事件。
     */
    private Map<Integer,Byte> eventsHigh;

    // Used by release and updateRegistrations to track whether a file
    // descriptor is registered with epoll.
    private final BitSet registered = new BitSet();


    EPollArrayWrapper() throws IOException {
        // creates the epoll file descriptor
        epfd = epollCreate(); //创建epoll native方法

        // the epoll_event array passed to epoll_wait
        int allocationSize = NUM_EPOLLEVENTS * SIZE_EPOLLEVENT; //malloc的sizeof()
        pollArray = new AllocatedNativeObject(allocationSize, true); // events array
        pollArrayAddress = pollArray.address(); // array point

        // eventHigh needed when using file descriptors > 64k
        if (OPEN_MAX > MAX_UPDATE_ARRAY_SIZE)
            eventsHigh = new HashMap<>();
    }

    void initInterrupt(int fd0, int fd1) { // fd0,和fd1 是中断管道 在unix的视角中也是一种io
        outgoingInterruptFD = fd1;
        incomingInterruptFD = fd0;
        epollCtl(epfd, EPOLL_CTL_ADD, fd0, EPOLLIN); //注册fd0到epoll epoll会监控此fd0发生的IO事件 。它和普通的socket在操作系统眼中是没有区别的 这里监控的是输入事件
    }

    void putEventOps(int i, int event) {
        int offset = SIZE_EPOLLEVENT * i + EVENT_OFFSET; //使用array去装event 这里的i是array的index  offset是内存偏移量
        pollArray.putInt(offset, event);
    }

    void putDescriptor(int i, int fd) {
        int offset = SIZE_EPOLLEVENT * i + FD_OFFSET;
        pollArray.putInt(offset, fd);
    }

    int getEventOps(int i) {
        int offset = SIZE_EPOLLEVENT * i + EVENT_OFFSET;
        return pollArray.getInt(offset);
    }

    int getDescriptor(int i) {
        int offset = SIZE_EPOLLEVENT * i + FD_OFFSET;
        return pollArray.getInt(offset);
    }

    /**
     * Returns {@code true} if updates for the given key (file
     * descriptor) are killed.
     */
    private boolean isEventsHighKilled(Integer key) { //在高位集合中判断指定fd 是否已经close
        assert key >= MAX_UPDATE_ARRAY_SIZE;
        Byte value = eventsHigh.get(key);
        return (value != null && value == KILLED);
    }

    /**
     * Sets the pending update events for the given file descriptor. This
     * method has no effect if the update events is already set to KILLED,
     * unless {@code force} is {@code true}.
     */
    private void setUpdateEvents(int fd, byte events, boolean force) {
        if (fd < MAX_UPDATE_ARRAY_SIZE) {
            if ((eventsLow[fd] != KILLED) || force) { // force 无视KILLED
                eventsLow[fd] = events; //这里是直接= ? 没有做运算
            }
        } else {
            Integer key = Integer.valueOf(fd);
            if (!isEventsHighKilled(key) || force) {
                eventsHigh.put(key, Byte.valueOf(events));
            }
        }
    }

    /**
     * Returns the pending update events for the given file descriptor.
     */
    private byte getUpdateEvents(int fd) {
        if (fd < MAX_UPDATE_ARRAY_SIZE) {
            return eventsLow[fd];
        } else {
            Byte result = eventsHigh.get(Integer.valueOf(fd));
            // result should never be null
            return result.byteValue();
        }
    }

    /**
     * Update the events for a given file descriptor
     */
    void setInterest(int fd, int mask) {  //mask 感兴趣的事件类型
        synchronized (updateLock) {
            // record the file descriptor and events
            int oldCapacity = updateDescriptors.length; // 需要修改事件的fd(也就是已注册的)
            if (updateCount == oldCapacity) {  // 已经发生了事件的fd的数量  检查是否需要扩容
                int newCapacity = oldCapacity + INITIAL_PENDING_UPDATE_SIZE;
                int[] newDescriptors = new int[newCapacity];
                System.arraycopy(updateDescriptors, 0, newDescriptors, 0, oldCapacity);
                updateDescriptors = newDescriptors;
            }
            updateDescriptors[updateCount++] = fd;

            // events are stored as bytes for efficiency reasons
            byte b = (byte)mask; //强转成byte 丢掉高24位
            assert (b == mask) && (b != KILLED);  // 为什么要检查 b == mask??
            setUpdateEvents(fd, b, false);
        }
    }

    /**
     * Add a file descriptor
     */
    void add(int fd) {
        // force the initial update events to 0 as it may be KILLED by a
        // previous registration.
        synchronized (updateLock) {
            assert !registered.get(fd);
            setUpdateEvents(fd, (byte)0, true);
        }
    }

    /**
     * Remove a file descriptor
     */
    void remove(int fd) {
        synchronized (updateLock) {
            // kill pending and future update for this file descriptor
            setUpdateEvents(fd, KILLED, false);

            // remove from epoll
            if (registered.get(fd)) {
                epollCtl(epfd, EPOLL_CTL_DEL, fd, 0);
                registered.clear(fd);
            }
        }
    }

    /**
     * Close epoll file descriptor and free poll array
     */
    void closeEPollFD() throws IOException {
        FileDispatcherImpl.closeIntFD(epfd);
        pollArray.free();
    }

    int poll(long timeout) throws IOException {
        updateRegistrations();
        updated = epollWait(pollArrayAddress, NUM_EPOLLEVENTS, timeout, epfd);
        for (int i=0; i<updated; i++) {
            if (getDescriptor(i) == incomingInterruptFD) {
                interruptedIndex = i;
                interrupted = true;
                break;
            }
        }
        return updated;
    }

    /**
     * Update the pending registrations.
     */
    private void updateRegistrations() {
        synchronized (updateLock) {
            int j = 0;
            while (j < updateCount) {
                int fd = updateDescriptors[j];
                short events = getUpdateEvents(fd);
                boolean isRegistered = registered.get(fd);
                int opcode = 0;

                if (events != KILLED) {
                    if (isRegistered) {
                        opcode = (events != 0) ? EPOLL_CTL_MOD : EPOLL_CTL_DEL;
                    } else {
                        opcode = (events != 0) ? EPOLL_CTL_ADD : 0;
                    }
                    if (opcode != 0) {
                        epollCtl(epfd, opcode, fd, events);
                        if (opcode == EPOLL_CTL_ADD) {
                            registered.set(fd);
                        } else if (opcode == EPOLL_CTL_DEL) {
                            registered.clear(fd);
                        }
                    }
                }
                j++;
            }
            updateCount = 0;
        }
    }

    // interrupt support
    private boolean interrupted = false;

    public void interrupt() {
        interrupt(outgoingInterruptFD);
    }

    public int interruptedIndex() {
        return interruptedIndex;
    }

    boolean interrupted() {
        return interrupted;
    }

    void clearInterrupted() {
        interrupted = false;
    }

    static {
        IOUtil.load();
        init();
    }

    private native int epollCreate();
    private native void epollCtl(int epfd, int opcode, int fd, int events);
    private native int epollWait(long pollAddress, int numfds, long timeout,
                                 int epfd) throws IOException;
    private static native int sizeofEPollEvent();
    private static native int offsetofData();
    private static native void interrupt(int fd);
    private static native void init();
}
