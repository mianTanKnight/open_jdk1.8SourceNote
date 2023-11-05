/*
 * Copyright (c) 2020, Red Hat, Inc.
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
package sun.net;

import java.net.SocketOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static jdk.net.ExtendedSocketOptions.TCP_KEEPCOUNT;
import static jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE;
import static jdk.net.ExtendedSocketOptions.TCP_KEEPINTERVAL;

public class ExtendedOptionsHelper {

    private static final boolean keepAliveOptSupported =
            ExtendedOptionsImpl.keepAliveOptionsSupported();
    private static final Set<SocketOption<?>> extendedOptions = options();

    /**
     * 这些TCP keepalive选项的目的和作用是为了确保TCP连接在没有数据交换的情况下依然保持活跃状态，并且能够检测到当网络连接或对方主机不再可用时的情况。这些选项通常用于需要长时间保持连接的应用程序，例如数据库连接或者持久的网络通信通道。下面是每个选项的具体作用：
     *
     * TCP_KEEPCOUNT:
     *
     * 目的: 定义在认定连接失效之前，允许发送多少个keepalive探测包。
     * 作用: 如果在发送了指定数量的keepalive包之后仍然没有收到响应，系统会认为连接已经失效，并将其关闭。这有助于应用程序能够及时地检测到潜在的死链接并做出相应的处理。
     * TCP_KEEPIDLE:
     *
     * 目的: 设置在发送第一个keepalive探测包之前，TCP连接必须处于空闲状态的时间。
     * 作用: 这个间隔定义了一个连接在被认为是空闲之前必须要经过多久的时间。如果在这个时间内没有数据交换，TCP将发送keepalive探测包以检查连接是否仍然活跃。
     * TCP_KEEPINTERVAL:
     *
     * 目的: 设置两个连续的keepalive探测包之间的间隔时间。
     * 作用: 如果第一个keepalive探测包没有得到响应，这个间隔定义了系统在发送下一个探测包之前应该等待多久。这有助于在不过度占用网络资源的情况下，定期检查连接的状态。
     * @return
     */
    private static Set<SocketOption<?>> options() {
        Set<SocketOption<?>> options = new HashSet<>();
        if (keepAliveOptSupported) {
            options.add(TCP_KEEPCOUNT);
            options.add(TCP_KEEPIDLE);
            options.add(TCP_KEEPINTERVAL);
        }
        return Collections.unmodifiableSet(options);
    }

    public static Set<SocketOption<?>> keepAliveOptions() {
        return extendedOptions;
    }
}
