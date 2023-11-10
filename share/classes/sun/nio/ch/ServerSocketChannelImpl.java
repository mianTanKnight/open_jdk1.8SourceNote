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

package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.*;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.util.*;
import sun.net.NetHooks;
import sun.net.ExtendedOptionsHelper;


/**
 * An implementation of ServerSocketChannels
 *
 * ServerSocketChannel 和 Selector 之间的关系以及它们如何配合工作是基于非阻塞 I/O 和多路复用的概念。
 *
 * 让我们分解一下这些组件和它们的关系：
 *
 * ServerSocketChannel:
 *
 * 这是一个可以监听新进的 TCP 连接请求的特殊通道。
 * 它本身不处理数据读写，而是用来接受新的连接。
 * 在非阻塞模式下，其 accept() 方法会立即返回，如果没有新的连接，返回值为 null。
 * Selector:
 *
 * Selector 是一个多路复用器，它可以同时监控多个通道的 I/O 状态，如读、写、连接和接受事件。
 * 它允许单个线程管理多个通道，因此，一条工作线程可以处理多个网络连接的 I/O 事件。
 * 工作关系:
 *
 * ServerSocketChannel 注册到 Selector 并指示它对“接受”事件感兴趣（使用 SelectionKey.OP_ACCEPT）。
 * 一个主工作线程运行一个循环，不断调用 Selector 的 select() 方法来检查是否有事件准备就绪。
 * 当 ServerSocketChannel 有新的连接请求时，Selector 会通知工作线程有一个“接受”事件就绪。
 * 工作线程随后调用 ServerSocketChannel 的 accept() 方法来接受连接，该方法返回一个新的 SocketChannel，用于后续的读写操作。
 * 多线程处理:
 *
 * 虽然 ServerSocketChannel 与 Selector 通常在同一工作线程中使用，但一旦接受了连接，新的 SocketChannel 可以被分配给其他线程进行读写操作，或者继续在同一个工作线程中非阻塞地处理。
 * 在实际应用中，可以根据连接的数量和性能要求，选择在单个线程中处理所有操作，或者使用线程池来并行处理。
 * 因此，ServerSocketChannel 主要负责建立新的连接，而 Selector 负责高效地管理这些连接的 I/O 事件。这种设计允许在高并发环境下使用较少的系统资源（线程）来处理大量的网络连接。
 *
 */

class ServerSocketChannelImpl
    extends ServerSocketChannel
    implements SelChImpl
{

    // Used to make native close and configure calls
    private static NativeDispatcher nd;

    // Our file descriptor
    private final FileDescriptor fd; // socket fd
    private final int fdVal;

    /**
     * 在 Java 中，每个通过 new Thread() 创建的 Java 线程背后通常都有一个对应的本地（操作系统级别的）线程。这种关系是一对一的：每个 Java 线程在底层对应着一个本地线程。
     *
     * 这里的关系和职责如下：
     *
     * Java 线程:
     *
     * 职责: 在 JVM 内执行 Java 代码，包括应用逻辑、管理任务以及调用其他 Java 类和对象。
     * 管理: 被 JVM 管理，其生命周期和状态（如运行、休眠、等待）都由 JVM 控制。
     * 本地线程:
     *
     * 职责: 执行底层系统调用，包括文件和网络 I/O、访问硬件、等待操作系统资源等。
     * 管理: 被操作系统内核管理，JVM 请求操作系统创建和终止本地线程，并通过操作系统接口与它们通信。
     * 当你在 Java 程序中创建一个新的线程并启动它时，JVM 会请求操作系统创建一个新的本地线程。Java 线程的所有操作，如执行代码、同步和等待，实际上都是由这个本地线程在操作系统层面执行的。
     *
     * 例如，当 Java 线程执行阻塞 I/O 操作时：
     *
     * 在 Java 层面，代码会调用如 InputStream.read() 或 Socket.accept() 等方法。
     * 这些方法内部会调用本地方法（使用 JNI），由本地线程执行实际的系统调用。
     * 如果这个系统调用是阻塞的（例如，等待网络数据），那么本地线程会阻塞，直到操作系统提供了所需的数据。
     * 因此，尽管 Java 线程是由 JVM 控制的，它们在执行某些任务时实际上是依赖于操作系统的本地线程的。这个机制允许 Java 程序能够执行系统级的任务，同时保持了跨平台的能力，因为 JVM 为不同操作系统提供了统一的线程模型和 API。
     *
     * 总结来说，Java 线程和本地线程之间是一对一的关系，它们协同工作以执行 Java 程序中的任务。Java 线程负责执行高级的、跨平台的 Java 代码，而本地线程负责执行底层、依赖于特定操作系统的任务。
     */
    // ID of native thread currently blocked in this channel, for signalling
    private volatile long thread = 0;

    // Lock held by thread currently blocked in this channel
    private final Object lock = new Object();

    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    private final Object stateLock = new Object();

    // -- The following fields are protected by stateLock

    /**
     * ST_UNINITIALIZED (-1):
     * 这个状态表示通道尚未初始化。通道在创建后可能会处于这个状态，直到它被显式地设置为其他状态。
     *
     * ST_INUSE (0):
     * 当通道正在被使用时，它会处于这个状态。这意味着通道已经初始化并且准备好进行 I/O 操作。
     *
     * ST_KILLED (1):
     * 通道一旦关闭或被“杀死”就会进入这个状态。一旦进入这个状态，通道就不应再被用于 I/O 操作，任何试图对该通道进行操作的行为都应该被阻止。
     */
    // Channel state, increases monotonically
    private static final int ST_UNINITIALIZED = -1;
    private static final int ST_INUSE = 0;
    private static final int ST_KILLED = 1;
    private int state = ST_UNINITIALIZED;

    // Binding
    private InetSocketAddress localAddress; // null => unbound

    // set true when exclusive binding is on and SO_REUSEADDR is emulated
    /**
     * 多个套接字共享一个端口时，主要是通过设置 SO_REUSEADDR 套接字选项来实现的。这通常用在一些特定的场景中，例如高性能服务器和负载均衡器，它们需要在同一物理机器上的多个进程或线程监听同一个端口。这里有一些可能的影响和考虑因素：
     *
     * 快速重启:
     * 允许服务器应用程序快速重启。如果不设置 SO_REUSEADDR，在服务器关闭后，端口可能会在“TIME_WAIT”状态下保持一段时间，导致无法立即重新绑定。
     * 多实例负载分担:
     * 在一些高性能计算场景中，多个实例可能会共享端口以接受传入的连接，操作系统负责分配请求到不同的实例，从而实现负载均衡。
     * 多播和广播:
     * 多个程序可能需要接收发送到一个多播组或广播地址的数据包。在这种情况下，共享端口是必要的。
     * 端口冲突:
     * 如果不是有意为之，多个应用程序共享端口可能会导致端口冲突，数据可能被错误地发送到不期望接收的应用程序。
     * 安全性:
     * 共享端口可能会带来安全隐患，因为恶意程序可能能够绑定到正在使用的端口上，截获数据或进行中间人攻击。
     * 数据分流:
     * 操作系统必须有一种机制来决定哪个套接字接收哪个数据包。通常，这是基于数据包的目的地址和端口以及其他套接字选项来决定的。
     * 复杂性增加:
     * 程序的网络管理和调试可能会变得更加复杂，因为需要处理和识别由多个套接字共享端口带来的问题。
     * 在实际应用中，除非有明确的需求和设计，否则通常不推荐多个套接字共享同一个端口，因为它增加了编程的复杂性，并且可能会带来安全和可靠性的问题。当确实需要这样做时，开发者需要确保他们的应用程序能够正确地处理共享端口的逻辑，并且要考虑所有可能的安全和性能影响。
     */
    private boolean isReuseAddress;

    // Our socket adaptor, if any
    ServerSocket socket;

    // -- End of fields protected by stateLock


    ServerSocketChannelImpl(SelectorProvider sp) throws IOException {
        super(sp);
        this.fd =  Net.serverSocket(true);
        this.fdVal = IOUtil.fdVal(fd);
        this.state = ST_INUSE;
    }

    ServerSocketChannelImpl(SelectorProvider sp,
                            FileDescriptor fd,
                            boolean bound)
        throws IOException
    {
        super(sp);
        this.fd =  fd;
        this.fdVal = IOUtil.fdVal(fd);
        this.state = ST_INUSE;
        if (bound)
            localAddress = Net.localAddress(fd);
    }

    public ServerSocket socket() {
        synchronized (stateLock) {
            if (socket == null)
                socket = ServerSocketAdaptor.create(this);
            return socket;
        }
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            return localAddress == null ? localAddress
                    : Net.getRevealedLocalAddress(
                          Net.asInetSocketAddress(localAddress));
        }
    }

    @Override
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value)
        throws IOException
    {
        if (name == null)
            throw new NullPointerException();
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();

            if (name == StandardSocketOptions.IP_TOS) {
                ProtocolFamily family = Net.isIPv6Available() ?
                    StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
                Net.setSocketOption(fd, family, name, value);
                return this;
            }

            if (name == StandardSocketOptions.SO_REUSEADDR &&
                    Net.useExclusiveBind())
            {
                // SO_REUSEADDR emulated when using exclusive bind
                isReuseAddress = (Boolean)value;
            } else {
                // no options that require special handling
                Net.setSocketOption(fd, Net.UNSPEC, name, value);
            }
            return this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name)
        throws IOException
    {
        if (name == null)
            throw new NullPointerException();
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");

        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (name == StandardSocketOptions.SO_REUSEADDR &&
                    Net.useExclusiveBind())
            {
                // SO_REUSEADDR emulated when using exclusive bind
                return (T)Boolean.valueOf(isReuseAddress);
            }
            // no options that require special handling
            return (T) Net.getSocketOption(fd, Net.UNSPEC, name);
        }
    }

    private static class DefaultOptionsHolder {
        static final Set<SocketOption<?>> defaultOptions = defaultOptions();

        private static Set<SocketOption<?>> defaultOptions() {
            HashSet<SocketOption<?>> set = new HashSet<SocketOption<?>>(2);
            set.add(StandardSocketOptions.SO_RCVBUF);
            set.add(StandardSocketOptions.SO_REUSEADDR); //StandardSocketOptions.SO_REUSEADDR 是一个配置套接字选项的标准常量，它用于控制套接字在被关闭或无连接状态下是否可以立即被再次使用。
            set.add(StandardSocketOptions.IP_TOS);
            set.addAll(ExtendedOptionsHelper.keepAliveOptions());
            return Collections.unmodifiableSet(set);
        }
    }

    @Override
    public final Set<SocketOption<?>> supportedOptions() {
        return DefaultOptionsHolder.defaultOptions;
    }

    public boolean isBound() { //是否已绑定
        synchronized (stateLock) {
            return localAddress != null;
        }
    }

    public InetSocketAddress localAddress() {
        synchronized (stateLock) {
            return localAddress;
        }
    }

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
        synchronized (lock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (isBound())
                throw new AlreadyBoundException();
            InetSocketAddress isa = (local == null) ? new InetSocketAddress(0) : Net.checkAddress(local); //如果是null 随机给port 如果不是就检查port是否可用
            SecurityManager sm = System.getSecurityManager();
            if (sm != null)
                sm.checkListen(isa.getPort());
            NetHooks.beforeTcpBind(fd, isa.getAddress(), isa.getPort());
            Net.bind(fd, isa.getAddress(), isa.getPort()); //通过Net类的bind方法将服务器套接字的文件描述符fd与本地地址isa绑定。
            Net.listen(fd, backlog < 1 ? 50 : backlog); //设置服务器套接字的监听队列的大小。如果backlog小于1，则使用默认值50。
            synchronized (stateLock) {
                localAddress = Net.localAddress(fd); // bind绑定完之后 更新有效localAddress
            }
        }
        return this;
    }

    public SocketChannel accept() throws IOException {
        synchronized (lock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (!isBound())
                throw new NotYetBoundException();
            SocketChannel sc = null;

            int n = 0;
            FileDescriptor newfd = new FileDescriptor();
            /**
             * 为什么使用InetSocketAddress[] isaa = new InetSocketAddress[1];：
             * 在Java中，方法参数传递是按值传递的，但对象引用是按共享传递的。这意味着方法不能修改传递给它的参数本身的值（如重新分配一个新的地址给一个对象引用），
             * 但可以修改该引用所指向的对象的状态（如修改数组中的元素）。因此，当你想要一个方法返回多个值时，你可以传递一个对象（如数组或可变对象）作为参数，然后在方法内部修改它。
             */
            InetSocketAddress[] isaa = new InetSocketAddress[1];

            try {
                begin();  // 注册中断支持
                if (!isOpen()) //已关闭
                    return null;
                /**
                 * 为什么使用thread = NativeThread.current();：
                 * NativeThread.current()调用通常用于获取当前Java线程的本地（native）表示，这在涉及阻塞操作和中断时特别有用。
                 * 在这段代码中，获取当前线程的本地表示可能是用于在需要时能够中断阻塞的accept操作。
                 * 如果另一个线程希望关闭ServerSocketChannel，它可能会需要这个引用来确保能够安全地中断正在accept调用中的线程。
                 */
                thread = NativeThread.current();
                for (;;) {
                    n = accept(this.fd, newfd, isaa); //返回接受状态 -1是异常
                    /**
                     * EOF (-1):
                     * 表示文件结束（End Of File）。在读取操作中，如果没有更多的数据可读，通常返回EOF。
                     * UNAVAILABLE (-2):
                     * 表示请求的操作当前不可用。这可能是由于资源暂时不可用，例如非阻塞I/O操作中没有数据可读。
                     * INTERRUPTED (-3):
                     * 表示线程在I/O操作中被中断。当一个线程在等待I/O完成时被另一个线程中断，操作可能会以这个状态结束。
                     * UNSUPPORTED (-4):
                     * 表示请求的操作不被当前的环境或上下文支持。例如，某个特定的I/O功能在当前平台上不可用。
                     * THROWN (-5):
                     * 表示I/O操作因为一个异常被抛出而没有成功完成。这通常用于内部错误处理，以区分正常的操作返回值和异常情况。
                     * UNSUPPORTED_CASE (-6):
                     * 表示遇到了一个特定的情况，该情况没有被当前的操作所支持。这与UNSUPPORTED略有不同，可能用于更具体的不支持的情况。
                     *
                     * 这段代码检查accept方法是否因为线程中断而返回，并且ServerSocketChannel是否仍然打开。如果两者都是真，那么它会继续在循环中尝试accept调用。
                     * 这种方式称为中断重试模式，它确保即使在异步操作被中断的情况下，操作也能继续进行，
                     * 只要通道仍然是打开的。这种设计通常用于实现健壮的I/O操作，即使面临中断也能保证服务的持续。
                     *
                     */
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    break;
                }
            } finally {
                thread = 0;
                end(n > 0);
                assert IOStatus.check(n);
            }

            if (n < 1)
                return null;

            /**
             * 新的socket会被设置成阻塞的 也就是说 任何读写或者请求在 没有准备好之前都是阻塞的
             * 但注意通常在NIO中 新的socket 会被注册到selector中
             * 也就是说当此阻塞socket 被使用时 已经有了IO
             * 在注册到选择器后，为了兼容选择器的工作方式，必须将其切换到非阻塞模式。
             * 如果在注册到选择器之后仍然保持阻塞模式，那么选择器在尝试非阻塞地检查其状态时将不能正常工作。
             */
            IOUtil.configureBlocking(newfd, true);
            InetSocketAddress isa = isaa[0];
            sc = new SocketChannelImpl(provider(), newfd, isa);
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                try {
                    sm.checkAccept(isa.getAddress().getHostAddress(),
                                   isa.getPort());
                } catch (SecurityException x) {
                    sc.close();
                    throw x;
                }
            }
            return sc;

        }
    }

    protected void implConfigureBlocking(boolean block) throws IOException {
        IOUtil.configureBlocking(fd, block);
    }

    /**
     * 调用 implCloseSelectableChannel() 方法。
     * 调用 nd.preClose(fd) 准备关闭通道。
     * 如果通道在关闭前有线程正在阻塞在一个 accept() 调用上，那么这个线程会被中断。
     * 检查通道是否已经注册到选择器上：
     * 如果已注册，通道将继续其关闭流程，这可能涉及取消注册和清理选择键。
     * 如果未注册，调用 kill() 方法直接关闭通道，并释放所有相关资源。
     * @throws IOException
     */
    protected void implCloseSelectableChannel() throws IOException {
        synchronized (stateLock) {
            if (state != ST_KILLED)// 如果当前通道为正常
                nd.preClose(fd); //先清扫资源
            long th = thread;
            if (th != 0) //检查是否正在进行 accept
                NativeThread.signal(th);
            /**
             * isRegistered() 方法检查通道是否已经注册到任何选择器（Selector）上。
             * 如果通道已经注册到选择器，那么就意味着可能有一个或多个选择键（SelectionKey）与之关联，这些选择键需要被取消并且相应的资源需要被清理。
             */
            if (!isRegistered()) //如果此service通道没有被注册过？？
                kill();
        }
    }

    public void kill() throws IOException {
        synchronized (stateLock) {
            if (state == ST_KILLED)
                return;
            if (state == ST_UNINITIALIZED) {
                state = ST_KILLED;
                return;
            }
            assert !isOpen() && !isRegistered();
            nd.close(fd);
            state = ST_KILLED;
        }
    }

    /**
     * Translates native poll revent set into a ready operation set
     */
    public boolean translateReadyOps(int ops, int initialOps,
                                     SelectionKeyImpl sk) {
        /**
         * 获得当前sk的兴趣集
         * interestOps volatile 修饰
         * int intOps = sk.nioInterestOps();
         * 这一行获取了与 SelectionKey 关联的通道的兴趣操作集。interestOps 是通过 SelectionKey 设置的，
         * 表示应用程序对哪些 I/O 事件感兴趣，如读（OP_READ）、写（OP_WRITE）、接受新连接（OP_ACCEPT）等。
         * volatile 修饰符表示 interestOps 可以由多个线程访问和修改，保证了操作的可见性和顺序。
         * int oldOps = sk.nioReadyOps();:
         * 这代表了选择键上一次查询选择器（Selector）后报告的就绪操作集。readyOps 表示通道已经准备好进行的操作，比如可以读取数据或者可以写入数据。
         * int newOps = initialOps;:
         * initialOps 通常是上一次调用 select 方法后，通道已确定准备好的操作集。这可能是之前的 readyOps，或者在某些情况下可能是 0，表示没有任何操作就绪或通道刚刚被注册。
         * newOps 开始时被设置为 initialOps，在接下来的代码中可能会根据新的 I/O 事件（由 ops 参数表示）来修改。
         */
        int intOps = sk.nioInterestOps(); // Do this just once, it synchronizes
        int oldOps = sk.nioReadyOps();
        int newOps = initialOps;

        /**
         * if ((ops & Net.POLLNVAL) != 0) 这行代码检查 ops 参数（这个参数包含了 poll 调用返回的事件标志）中是否设置了 POLLNVAL 标志。
         * ops & Net.POLLNVAL 是一个位运算，& 是按位与操作符，用于确定 POLLNVAL 标志是否被设置。如果 POLLNVAL 被设置，按位与操作的结果不会是 0，这表明文件描述符无效。
         */
        if ((ops & Net.POLLNVAL) != 0) {
            // This should only happen if this channel is pre-closed while a
            // selection operation is in progress
            // ## Throw an error if this channel has not been pre-closed
            return false;
        }

        /**
         * 下列代码为了解决的问题：
         * 为了保证在发生网络错误或连接挂起时，应用程序能够接收到通知并对此做出响应。
         *
         * 解决的问题：
         * 如何确保错误和挂起事件不会被忽略？
         * 错误和挂起事件可能需要立即的响应，因为它们可能影响到应用程序的整体状态和性能。
         * 如何简化应用程序处理多种事件的逻辑？
         * 将错误和挂起视为通道上的“就绪”操作，即使这些操作不直接对应于常规的I/O操作，也可以在应用程序的常规事件处理逻辑中处理这些事件。
         *
         * 解决核心思路：
         * 统一处理机制：
         * 通过将错误和挂起状态处理为一个通道上的就绪操作，应用程序可以在其事件处理循环中以一种统一的方式处理所有事件，包括正常的I/O操作和异常情况。
         * 保守的方法：
         * 当不确定具体发生了什么错误时，最保守的做法是假设所有感兴趣的操作都可能受到影响，因此将所有感兴趣的操作标记为就绪。
         * 避免丢失事件：
         *
         * 通过将所有感兴趣的操作设置为就绪，确保了选择器在下一次选择操作时一定会返回这些键，这样应用程序就不会错过处理这些紧急事件的机会。
         * 总的来说，这种设计模式体现了在面对不确定性和潜在的失败时，采取保守的策略来保证应用程序的鲁棒性和可靠性。
         * 这是一种经典的错误处理机制，确保了在复杂的异步I/O环境中，错误和异常情况得到了适当的处理。
         *
         */
        if ((ops & (Net.POLLERR | Net.POLLHUP)) != 0) { // 可以翻译成如下易为理解的 ops == net.pollerr ||  ops == pollhup
            newOps = intOps; //
            sk.nioReadyOps(newOps);
            return (newOps & ~oldOps) != 0; //检查newOps是否更新
        }

        /**
         * 判断是否是正常io时间 并且是accept
         * 如果是 newOps add accept(使用位运算)
         */
        if (((ops & Net.POLLIN) != 0) &&
            ((intOps & SelectionKey.OP_ACCEPT) != 0))
                newOps |= SelectionKey.OP_ACCEPT;

        sk.nioReadyOps(newOps);
        return (newOps & ~oldOps) != 0;  //检查newOps是否更新
    }

    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, sk.nioReadyOps(), sk);
    }

    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, 0, sk);
    }

    // package-private
    int poll(int events, long timeout) throws IOException {
        assert Thread.holdsLock(blockingLock()) && !isBlocking();

        /**
         * lock 为channel锁 也就是进行IO 排他
         */
        synchronized (lock) {
            int n = 0;
            try {
                begin(); //中断支持
                //channel的状态更改锁 也就是说channel 在关键状态的变化 目的是安全的更改占用线程？？？？
                synchronized (stateLock) {
                    if (!isOpen()) return 0; //如果已经关闭了
                    thread = NativeThread.current(); //好的占坑线程
                }
                n = Net.poll(fd, events, timeout); //这里是直接调用操作系统底层的poll
            } finally {
                thread = 0;
                end(n > 0);
            }
            return n;
        }
    }

    /**
     * Translates an interest operation set into a native poll event set
     */
    public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
        int newOps = 0;

        /**
         * POLLIN:
         *
         * 表示对应的文件描述符可以进行读操作而不会阻塞。对于网络编程来说，如果是流协议（如TCP），这意味着有数据可读；如果是数据报协议（如UDP），这意味着有数据报可读；对于一个监听socket，它意味着有新的连接请求。
         * POLLOUT:
         * 表示对应的文件描述符可以进行写操作而不会阻塞。对于大多数类型的文件描述符，输出缓冲区的空闲空间足够，可以接受新的写入数据。
         * POLLERR:
         * 表示对应的文件描述符发生错误。此事件总是被poll操作监视，无需在请求的事件中明确设置。
         * POLLHUP:
         * 表示对应的文件描述符挂起，例如当socket的一端已经关闭连接时，另一端会收到POLLHUP事件。
         * POLLNVAL:
         * 表示对应的文件描述符不是一个打开的文件描述符。如果请求poll操作的文件描述符没有被打开，或者是非法的，那么poll操作会返回此事件。
         * POLLCONN:
         *
         * 这个标志不是POSIX标准的一部分，不过它通常被用来指示一个非阻塞的连接操作已经完成，或者一个监听socket准备好接受新的连接了。这个标志可能是特定系统或库为了方便起见添加的。
         */
        // Translate ops
        if ((ops & SelectionKey.OP_ACCEPT) != 0)// ops 包含 selectionKey.OP_ACCEPT 连接事件
            newOps |= Net.POLLIN; // 为什么会翻译成pollIn 因为serverSocket 只会有Op_accept 特性保证的 但在操作系统的视觉来说 就是 IN 有读事件发生了
        // Place ops into pollfd array
        sk.selector.putEventOps(sk, newOps); //sk 可以取出channel 的fd 注册fd 和指定的newOps 到 epoll
    }

    public FileDescriptor getFD() {
        return fd;
    }

    public int getFDVal() {
        return fdVal;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(this.getClass().getName());
        sb.append('[');
        if (!isOpen()) {
            sb.append("closed");
        } else {
            synchronized (stateLock) {
                InetSocketAddress addr = localAddress();
                if (addr == null) {
                    sb.append("unbound");
                } else {
                    sb.append(Net.getRevealedLocalAddressAsString(addr));
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Accept a connection on a socket.
     *
     * @implNote Wrap native call to allow instrumentation.
     */
    private int accept(FileDescriptor ssfd, FileDescriptor newfd,
                       InetSocketAddress[] isaa)
        throws IOException
    {
        return accept0(ssfd, newfd, isaa);
    }

    // -- Native methods --

    // Accepts a new connection, setting the given file descriptor to refer to
    // the new socket and setting isaa[0] to the socket's remote address.
    // Returns 1 on success, or IOStatus.UNAVAILABLE (if non-blocking and no
    // connections are pending) or IOStatus.INTERRUPTED.
    //
    private native int accept0(FileDescriptor ssfd, FileDescriptor newfd,
                               InetSocketAddress[] isaa)
        throws IOException;

    private static native void initIDs();

    static {
        IOUtil.load();
        initIDs();
        nd = new SocketDispatcher();
    }

}
