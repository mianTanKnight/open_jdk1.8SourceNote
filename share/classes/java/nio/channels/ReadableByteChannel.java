/*
 * Copyright (c) 2000, 2001, Oracle and/or its affiliates. All rights reserved.
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
 * A channel that can read bytes.
 *
 * <p> Only one read operation upon a readable channel may be in progress at
 * any given time.  If one thread initiates a read operation upon a channel
 * then any other thread that attempts to initiate another read operation will
 * block until the first operation is complete.  Whether or not other kinds of
 * I/O operations may proceed concurrently with a read operation depends upon
 * the type of the channel. </p>
 *
 * channel 的读操作是阻塞的 也就是串行化的
 *
 * @author Mark Reinhold
 * @author JSR-51 Expert Group
 * @since 1.4
 */

public interface ReadableByteChannel extends Channel {

    /**
     * Reads a sequence of bytes from this channel into the given buffer.
     * 顺序读取 bytes 写入指定的 buffer
     *
     * <p> An attempt is made to read up to <i>r</i> bytes from the channel,
     * where <i>r</i> is the number of bytes remaining in the buffer, that is,
     * <tt>dst.remaining()</tt>, at the moment this method is invoked.
     *
     * <p> Suppose that a byte sequence of length <i>n</i> is read, where
     * <tt>0</tt>&nbsp;<tt>&lt;=</tt>&nbsp;<i>n</i>&nbsp;<tt>&lt;=</tt>&nbsp;<i>r</i>.
     * This byte sequence will be transferred into the buffer so that the first
     * byte in the sequence is at index <i>p</i> and the last byte is at index
     * <i>p</i>&nbsp;<tt>+</tt>&nbsp;<i>n</i>&nbsp;<tt>-</tt>&nbsp;<tt>1</tt>,
     * where <i>p</i> is the buffer's position at the moment this method is
     * invoked.  Upon return the buffer's position will be equal to
     * <i>p</i>&nbsp;<tt>+</tt>&nbsp;<i>n</i>; its limit will not have changed.
     *
     * <p> A read operation might not fill the buffer, and in fact it might not
     * read any bytes at all.  Whether or not it does so depends upon the
     * nature and state of the channel.  A socket channel in non-blocking mode,
     * for example, cannot read any more bytes than are immediately available
     * from the socket's input buffer; similarly, a file channel cannot read
     * any more bytes than remain in the file.  It is guaranteed, however, that
     * if a channel is in blocking mode and there is at least one byte
     * remaining in the buffer then this method will block until at least one
     * byte is read.
     *
     * <p> This method may be invoked at any time.  If another thread has
     * already initiated a read operation upon this channel, however, then an
     * invocation of this method will block until the first operation is
     * complete. </p>
     *
     * @param  dst
     *         The buffer into which bytes are to be transferred
     *
     * @return  The number of bytes read, possibly zero, or <tt>-1</tt> if the
     *          channel has reached end-of-stream
     *
     * @throws  NonReadableChannelException
     *          If this channel was not opened for reading
     *
     * @throws  ClosedChannelException
     *          If this channel is closed
     *
     * @throws  AsynchronousCloseException
     *          If another thread closes this channel
     *          while the read operation is in progress
     *
     * @throws  ClosedByInterruptException
     *          If another thread interrupts the current thread
     *          while the read operation is in progress, thereby
     *          closing the channel and setting the current thread's
     *          interrupt status
     *
     * @throws  IOException
     *          If some other I/O error occurs
     */
    /**
     * 从该通道中读取一系列字节到给定的缓冲区中。
     *
     * 尝试从通道中读取多达 r 字节，其中 r 是调用此方法时缓冲区中剩余的字节数，
     * 即 dst.remaining()。
     *
     * 假设读取了长度为 n 的字节序列，其中 0 <= n <= r。
     * 这个字节序列将被传输到缓冲区中，使得序列中的第一个字节位于索引 p，
     * 最后一个字节位于索引 p + n - 1，这里的 p 是调用此方法时缓冲区的位置。
     * 返回时缓冲区的位置将等于 p + n；其限制将不会改变。
     *
     * 读操作可能不会填满缓冲区，实际上可能根本不读取任何字节。
     * 它是否这样做取决于通道的性质和状态。例如，处于非阻塞模式的套接字通道，
     * 不能读取比套接字的输入缓冲区中立即可用的字节更多的字节；类似地，文件通道不能读取
     * 比文件中剩余的字节更多的字节。但是，保证如果通道处于阻塞模式并且缓冲区中至少有一个字节剩余，
     * 则此方法将阻塞直到至少读取一个字节。
     *
     * 此方法可以随时调用。如果另一个线程已经在此通道上启动了读操作，
     * 则对此方法的调用将阻塞，直到第一个操作完成。
     *
     * @param dst
     *        要传输字节的目标缓冲区
     *
     * @return 读取的字节数，可能为零，如果通道已达到流的末尾，则为 -1
     *
     * @throws NonReadableChannelException
     *         如果此通道未打开进行读取
     *
     * @throws ClosedChannelException
     *         如果此通道已关闭
     *
     * @throws AsynchronousCloseException
     *         如果在读操作进行中另一个线程关闭了这个通道
     *
     * @throws ClosedByInterruptException
     *         如果在读操作进行中另一个线程中断了当前线程，
     *         从而关闭通道并设置当前线程的中断状态
     *
     * @throws IOException
     *         如果发生其他 I/O 错误
     *
     *
     *
     * 不保证一定读取到数据:
     *
     * 当您调用 read 方法时，它并不保证一定能读取到数据。这取决于当时通道中是否有可用数据。
     * 受限于缓冲区大小:
     *
     * 如果通道中有数据可读，read 方法也只会读取到您提供的输入缓冲区（ByteBuffer）所能容纳的数据量。换句话说，如果您的缓冲区大小为 100 字节，但通道中有 200 字节可读，那么 read 方法在这次调用中最多只能读取 100 字节。
     * 非阻塞模式特性:
     *
     * 特别在非阻塞模式下，如果通道中没有足够的数据可供读取，read 方法可能一次不读取任何数据，并立即返回。
     *
     */
    public int read(ByteBuffer dst) throws IOException;

}
