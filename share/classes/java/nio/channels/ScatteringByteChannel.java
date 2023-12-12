/*
 * Copyright (c) 2000, 2006, Oracle and/or its affiliates. All rights reserved.
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
 * A channel that can read bytes into a sequence of buffers.
 *
 * <p> A <i>scattering</i> read operation reads, in a single invocation, a
 * sequence of bytes into one or more of a given sequence of buffers.
 * Scattering reads are often useful when implementing network protocols or
 * file formats that, for example, group data into segments consisting of one
 * or more fixed-length headers followed by a variable-length body.  Similar
 * <i>gathering</i> write operations are defined in the {@link
 * GatheringByteChannel} interface.  </p>
 * 它继承自 ReadableByteChannel 并添加了将数据读取到多个缓冲区的能力。这里是对其 read 方法的文档注释的翻译和解释：
 *
 * 方法说明
 * ScatteringByteChannel 的 read 方法允许从通道读取字节序列到一个缓冲区数组的子序列中。
 * 读取字节到多个缓冲区:
 * 这个方法尝试从通道读取最多 r 字节，其中 r 是指定缓冲区子序列中所有缓冲区剩余容量的总和。
 * 缓冲区数组的处理:
 * 读取的字节序列首先填充到 dsts[offset] 缓冲区，直到该缓冲区满或字节序列耗尽。随后继续填充到 dsts[offset+1]，以此类推，
 * 直到所有字节序列被传输到给定的缓冲区或所有缓冲区都已满。每个缓冲区尽可能被填满，除了最后一个更新的缓冲区以外，每个缓冲区的最终位置都等于该缓冲区的限制。
 * 阻塞与非阻塞行为:
 * 类似 ReadableByteChannel，如果通道处于阻塞模式，并且至少有一个字节可读，那么这个方法将阻塞，直到读取到数据。在非阻塞模式下，读取的字节数可能少于请求的字节数，甚至可能为零。
 * 处理多个缓冲区:
 * 当您需要从一个通道读取数据到多个缓冲区时，可以使用 ScatteringByteChannel。例如，您可能希望将数据的不同部分读取到不同的缓冲区以便于分别处理。
 * 网络通信:
 * 在处理网络通信时，例如读取一个包含多部分数据的网络消息，您可以将每部分数据直接读取到不同的缓冲区，这样可以更有效地处理和解析消息。
 * 文件 I/O:
 *
 * 从文件通道读取时，如果您希望将文件的不同部分分散到多个缓冲区中，也可以使用这个方法。
 * ScatteringByteChannel 提供了一种高效的方式来将数据分散读取到多个缓冲区，这在需要对数据进行分段处理的场景中非常有用。
 *
 * @author Mark Reinhold
 * @author JSR-51 Expert Group
 * @since 1.4
 */

public interface ScatteringByteChannel
    extends ReadableByteChannel
{

    /**
     * Reads a sequence of bytes from this channel into a subsequence of the
     * given buffers.
     *
     * <p> An invocation of this method attempts to read up to <i>r</i> bytes
     * from this channel, where <i>r</i> is the total number of bytes remaining
     * the specified subsequence of the given buffer array, that is,
     *
     * <blockquote><pre>
     * dsts[offset].remaining()
     *     + dsts[offset+1].remaining()
     *     + ... + dsts[offset+length-1].remaining()</pre></blockquote>
     *
     * at the moment that this method is invoked.
     *
     * <p> Suppose that a byte sequence of length <i>n</i> is read, where
     * <tt>0</tt>&nbsp;<tt>&lt;=</tt>&nbsp;<i>n</i>&nbsp;<tt>&lt;=</tt>&nbsp;<i>r</i>.
     * Up to the first <tt>dsts[offset].remaining()</tt> bytes of this sequence
     * are transferred into buffer <tt>dsts[offset]</tt>, up to the next
     * <tt>dsts[offset+1].remaining()</tt> bytes are transferred into buffer
     * <tt>dsts[offset+1]</tt>, and so forth, until the entire byte sequence
     * is transferred into the given buffers.  As many bytes as possible are
     * transferred into each buffer, hence the final position of each updated
     * buffer, except the last updated buffer, is guaranteed to be equal to
     * that buffer's limit.
     *
     * <p> This method may be invoked at any time.  If another thread has
     * already initiated a read operation upon this channel, however, then an
     * invocation of this method will block until the first operation is
     * complete. </p>
     *
     * @param  dsts
     *         The buffers into which bytes are to be transferred
     *
     * @param  offset
     *         The offset within the buffer array of the first buffer into
     *         which bytes are to be transferred; must be non-negative and no
     *         larger than <tt>dsts.length</tt>
     *
     * @param  length
     *         The maximum number of buffers to be accessed; must be
     *         non-negative and no larger than
     *         <tt>dsts.length</tt>&nbsp;-&nbsp;<tt>offset</tt>
     *
     * @return The number of bytes read, possibly zero,
     *         or <tt>-1</tt> if the channel has reached end-of-stream
     *
     * @throws  IndexOutOfBoundsException
     *          If the preconditions on the <tt>offset</tt> and <tt>length</tt>
     *          parameters do not hold
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
    public long read(ByteBuffer[] dsts, int offset, int length)
        throws IOException;

    /**
     * Reads a sequence of bytes from this channel into the given buffers.
     *
     * <p> An invocation of this method of the form <tt>c.read(dsts)</tt>
     * behaves in exactly the same manner as the invocation
     *
     * <blockquote><pre>
     * c.read(dsts, 0, dsts.length);</pre></blockquote>
     *
     * @param  dsts
     *         The buffers into which bytes are to be transferred
     *
     * @return The number of bytes read, possibly zero,
     *         or <tt>-1</tt> if the channel has reached end-of-stream
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
    public long read(ByteBuffer[] dsts) throws IOException;

}
