package com.autogo.jetbrains;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.LinkedHashSet;
import java.util.Set;

/** 为并行 IDEA 项目分配互不冲突的 loopback 控制与 DAP 端口。 */
final class AutoGoPortAllocator {
    private static final Set<Integer> RESERVED = new LinkedHashSet<>();

    private AutoGoPortAllocator() {
        // 端口分配器只维护进程内预留集合。
    }

    /** 原子分配两个当前可绑定且未被其他 AutoGo 项目预留的端口。 */
    static synchronized PortPair allocate() {
        // 两个端口必须同时成功，否则释放已取得的端口并报告启动错误。
        int control = findAvailablePort();
        RESERVED.add(control);
        try {
            int dap = findAvailablePort();
            RESERVED.add(dap);
            return new PortPair(control, dap);
        } catch (RuntimeException error) {
            // 第二个端口失败时回滚第一项预留。
            RESERVED.remove(control);
            throw error;
        }
    }

    /** 释放项目端口，使后续项目可以再次分配。 */
    static synchronized void release(PortPair pair) {
        // 重复释放保持幂等。
        if (pair == null) {
            return;
        }
        RESERVED.remove(pair.control());
        RESERVED.remove(pair.dap());
    }

    private static int findAvailablePort() {
        // 内核选择 loopback 空闲端口；进程内集合消除并发项目之间的选择竞态。
        for (int attempt = 0; attempt < 32; attempt++) {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                int port = socket.getLocalPort();
                if (!RESERVED.contains(port)) {
                    return port;
                }
            } catch (IOException error) {
                // 临时绑定失败允许重试其他系统分配端口。
                if (attempt == 31) {
                    throw new IllegalStateException("无法为 AutoGo 分配本地端口", error);
                }
            }
        }
        throw new IllegalStateException("无法为 AutoGo 找到未预留的本地端口");
    }

    /** 项目专属的控制面与 DAP 本地端口。 */
    record PortPair(int control, int dap) {
        PortPair {
            // 分配结果必须处于 TCP 有效端口范围且不能相同。
            if (control < 1 || control > 65535 || dap < 1 || dap > 65535 || control == dap) {
                throw new IllegalArgumentException("非法 AutoGo 本地端口对");
            }
        }
    }
}
