/*
 * Copyright (c) 2000, 2005, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * A channel that can write bytes.
 *
 * <p> Only one write operation upon a writable channel may be in progress at
 * any given time.  If one thread initiates a write operation upon a channel
 * then any other thread that attempts to initiate another write operation will
 * block until the first operation is complete.  Whether or not other kinds of
 * I/O operations may proceed concurrently with a write operation depends upon
 * the type of the channel. </p>
 *
 * channel 也是串行的
 *
 *
 * @author Mark Reinhold
 * @author JSR-51 Expert Group
 * @since 1.4
 */

public interface WritableByteChannel
    extends Channel
{

    /**
     * Writes a sequence of bytes to this channel from the given buffer.
     *
     * <p> An attempt is made to write up to <i>r</i> bytes to the channel,
     * where <i>r</i> is the number of bytes remaining in the buffer, that is,
     * <tt>src.remaining()</tt>, at the moment this method is invoked.
     *
     * <p> Suppose that a byte sequence of length <i>n</i> is written, where
     * <tt>0</tt>&nbsp;<tt>&lt;=</tt>&nbsp;<i>n</i>&nbsp;<tt>&lt;=</tt>&nbsp;<i>r</i>.
     * This byte sequence will be transferred from the buffer starting at index
     * <i>p</i>, where <i>p</i> is the buffer's position at the moment this
     * method is invoked; the index of the last byte written will be
     * <i>p</i>&nbsp;<tt>+</tt>&nbsp;<i>n</i>&nbsp;<tt>-</tt>&nbsp;<tt>1</tt>.
     * Upon return the buffer's position will be equal to
     * <i>p</i>&nbsp;<tt>+</tt>&nbsp;<i>n</i>; its limit will not have changed.
     *
     * <p> Unless otherwise specified, a write operation will return only after
     * writing all of the <i>r</i> requested bytes.  Some types of channels,
     * depending upon their state, may write only some of the bytes or possibly
     * none at all.  A socket channel in non-blocking mode, for example, cannot
     * write any more bytes than are free in the socket's output buffer.
     *
     * <p> This method may be invoked at any time.  If another thread has
     * already initiated a write operation upon this channel, however, then an
     * invocation of this method will block until the first operation is
     * complete. </p>
     *
     * @param  src
     *         The buffer from which bytes are to be retrieved
     *
     * @return The number of bytes written, possibly zero
     *
     * @throws  NonWritableChannelException
     *          If this channel was not opened for writing
     *
     * @throws  ClosedChannelException
     *          If this channel is closed
     *
     * @throws  AsynchronousCloseException
     *          If another thread closes this channel
     *          while the write operation is in progress
     *
     * @throws  ClosedByInterruptException
     *          If another thread interrupts the current thread
     *          while the write operation is in progress, thereby
     *          closing the channel and setting the current thread's
     *          interrupt status
     *
     * @throws  IOException
     *          If some other I/O error occurs
     *
     * write 方法将从给定的缓冲区 src 写入字节序列到这个通道中。
     *
     * 写入操作的尝试:
     *
     * 尝试将最多 r 字节写入通道，其中 r 是调用此方法时缓冲区中剩余的字节数，即 src.remaining()。
     * 写入的字节序列:
     *
     * 假设写入长度为 n 的字节序列，其中 0 <= n <= r。这个字节序列将从缓冲区的索引 p 处开始传输，p 是调用此方法时缓冲区的位置。写入的最后一个字节的索引将是 p + n - 1。返回时，缓冲区的位置将等于 p + n；其限制不会改变。
     * 写入的完成情况:
     *
     * 除非另有说明，写操作将只在写完所有请求的 r 字节后返回。某些类型的通道，根据它们的状态，可能只写入部分字节，甚至可能根本不写入任何字节。例如，处于非阻塞模式的套接字通道不能写入超出套接字输出缓冲区可用空间的字节。
     * 方法调用的时机:
     *
     * 此方法可以随时调用。如果另一个线程已经在此通道上启动了写操作，则对此方法的调用将阻塞，直到第一个操作完成。
     * 应用场景
     * 非阻塞模式下的网络写入:
     *
     * 当通道（如 SocketChannel）处于非阻塞模式时，如果套接字的输出缓冲区已满，write 方法可能不会写入所有的数据。它可能只写入部分数据或甚至不写入任何数据，并立即返回。
     * 阻塞模式下的文件写入:
     *
     * 当你的应用程序向文件通道（如 FileChannel）写入数据，并且通道是阻塞模式，那么 write 方法将尝试写入所有的数据，并在完成后返回。如果不能立即写入所有数据，方法会阻塞直到完成。
     * 这些行为使得 NIO 在处理 I/O 操作时更加灵活，尤其是在处理网络 I/O 时。它允许程序根据网络或文件系统的当前状态灵活地写入数据。
     *
     */
    public int write(ByteBuffer src) throws IOException;

}
