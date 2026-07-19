package com.autogo.jetbrains;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提供轻量 ADB 设备发现，用于动态菜单；耗时操作由正式进程服务执行。
 */
public final class AutoGoDeviceSupport {
    private static final long DEVICE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long DEVICE_SCAN_TIMEOUT_SECONDS = 8;
    private static final ExecutorService DEVICE_SCAN_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        // 独立守护线程避免 IDE 公共线程池繁忙时设备刷新永久停留在“正在扫描”。
        Thread thread = new Thread(task, "autogo-adb-device-scan");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern ROUTE_SOURCE = Pattern.compile("(?:^|\\s)src\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})(?:\\s|$)");
    private static volatile List<String> cachedDevices = List.of();
    private static volatile long lastRefreshNanos;
    private static volatile CompletableFuture<List<String>> activeRefresh;
    private AutoGoDeviceSupport() {
        // 工具类禁止实例化。
    }

    /** 查询处于 device 状态的设备序列号，失败或超时返回空列表。 */
    public static List<String> listDevices(String configuredAdbPath) {
        // 配置为空时通过系统 PATH 查找 adb。
        long scanDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEVICE_SCAN_TIMEOUT_SECONDS - 1);
        String executable = configuredAdbPath == null || configuredAdbPath.isBlank()
                ? "adb" : configuredAdbPath.trim();
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "devices", "-l")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                // 菜单查询超过预算时终止进程，避免长期占用后台线程。
                process.destroyForcibly();
                cachedDevices = List.of();
                return List.of();
            }
            List<String> devices = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 只接受状态严格为 device 的记录，排除 offline 和 unauthorized。
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 2 && "device".equals(fields[1])) {
                        devices.add(fields[0]);
                    }
                }
            }
            // ADB server 会串行处理部分 shell 请求；顺序读取可避免公共线程池饥饿和并发请求互相阻塞。
            Map<String, String> identities = new LinkedHashMap<>();
            for (String serial : devices) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(scanDeadlineNanos - System.nanoTime());
                if (remainingMillis <= 0) {
                    // 整轮刷新达到预算后停止身份补充，已有连接仍可直接展示和选择。
                    identities.put(serial, "");
                    continue;
                }
                identities.put(serial, readPhysicalSerial(executable, serial, Math.min(2_000, remainingMillis)));
            }
            List<String> selectedDevices = preferRemoteEndpoints(devices, identities);
            cachedDevices = selectedDevices;
            return selectedDevices;
        } catch (IOException | InterruptedException | RuntimeException error) {
            // 设备发现失败由菜单显示为空；中断状态需要保留。
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            cachedDevices = List.of();
            return List.of();
        } finally {
            // 异常路径确保遗留查询进程被清理。
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 返回最近一次显式 ADB 扫描结果，工具栏更新不得在后台反复启动 adb。 */
    public static List<String> cachedDevices() {
        // 返回不可变快照，避免动作菜单遍历期间被另一轮设备刷新修改。
        return cachedDevices;
    }

    /** 异步刷新设备缓存；并发调用复用同一轮 ADB 扫描。 */
    public static synchronized CompletableFuture<List<String>> refreshDevicesAsync(String configuredAdbPath, boolean force) {
        // 非强制调用在短缓存窗口内直接复用结果，避免动作系统高频更新反复执行 adb。
        long now = System.nanoTime();
        if (!force && lastRefreshNanos > 0 && now - lastRefreshNanos < DEVICE_CACHE_NANOS) {
            return CompletableFuture.completedFuture(cachedDevices);
        }
        CompletableFuture<List<String>> running = activeRefresh;
        if (running != null && !running.isDone()) {
            // 上一轮尚未结束时直接复用，保证任意时刻最多一个设备扫描进程。
            return running;
        }
        CompletableFuture<List<String>> created = CompletableFuture
                .supplyAsync(() -> listDevices(configuredAdbPath), DEVICE_SCAN_EXECUTOR)
                .orTimeout(DEVICE_SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        activeRefresh = created;
        created.whenComplete((devices, error) -> {
            // 无论成功或失败都结束单飞状态，并记录刷新时间供菜单短缓存使用。
            synchronized (AutoGoDeviceSupport.class) {
                lastRefreshNanos = System.nanoTime();
                if (activeRefresh == created) {
                    // 只清理当前任务，避免未来刷新被旧回调意外覆盖。
                    activeRefresh = null;
                }
            }
        });
        return created;
    }

    /** 返回设备扫描是否仍在执行。 */
    public static boolean isRefreshing() {
        // 只读取原子任务引用，不阻塞 EDT。
        CompletableFuture<List<String>> running = activeRefresh;
        return running != null && !running.isDone();
    }

    private static String readPhysicalSerial(String adb, String connectionSerial, long timeoutMillis) {
        // ro.serialno 在 USB、tcpip 和 TLS mDNS 连接之间保持一致，可用于物理设备去重。
        Process process = null;
        try {
            process = new ProcessBuilder(adb, "-s", connectionSerial, "shell", "getprop", "ro.serialno")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS)) {
                // 单台设备身份查询超时不阻塞整个刷新，回退到连接序列号。
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                // 离线或权限错误时不参与跨连接去重。
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException error) {
            // 中断时保留线程状态，其他错误回退为不去重。
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        } finally {
            // 异常路径清理身份查询子进程。
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 按物理序列号去重，并优先选择可直接访问的无线 ADB 端点。 */
    static List<String> preferRemoteEndpoints(List<String> connections, Map<String, String> identities) {
        // 先稳定排序再按身份保留第一条，未知身份使用连接序列号避免误合并。
        List<String> sorted = connections.stream()
                .distinct()
                .sorted(Comparator.comparingInt(AutoGoDeviceSupport::connectionPriority)
                        .thenComparing(String::compareTo))
                .toList();
        Map<String, String> selectedByPhysicalDevice = new LinkedHashMap<>();
        for (String connection : sorted) {
            String identity = identities.getOrDefault(connection, "").trim();
            String key = identity.isBlank() ? "connection:" + connection : "device:" + identity;
            selectedByPhysicalDevice.putIfAbsent(key, connection);
        }
        return List.copyOf(selectedByPhysicalDevice.values());
    }

    private static int connectionPriority(String serial) {
        // 固定 tcpip 地址最适合自动恢复，其次为 Android 11+ TLS mDNS，USB 作为回退。
        if (serial.matches("\\d{1,3}(?:\\.\\d{1,3}){3}:\\d+")) {
            return 0;
        }
        if (serial.contains("._adb-tls-connect._tcp")) {
            return 1;
        }
        if (isHostPort(serial)) {
            return 2;
        }
        return 3;
    }

    /** 查询指定 USB 设备在当前 Wi-Fi 网络中的 IPv4 地址。 */
    public static String findDeviceIpv4(String configuredAdbPath, String serial) {
        // ip route 的 src 字段比解析网卡名称更稳定，兼容 wlan0 名称变化。
        if (serial == null || serial.isBlank()) {
            return "";
        }
        String executable = configuredAdbPath == null || configuredAdbPath.isBlank()
                ? "adb" : configuredAdbPath.trim();
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "-s", serial, "shell", "ip", "route")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                // 设备无响应时终止查询并保留当前 USB 选择。
                process.destroyForcibly();
                return "";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseRouteIpv4(output);
        } catch (IOException | InterruptedException error) {
            // 查询失败由动作层输出用户可见原因。
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        } finally {
            // 所有异常路径都清理遗留 adb 查询进程。
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 从 Android `ip route` 输出提取合法 src IPv4。 */
    static String parseRouteIpv4(String output) {
        // 优先读取默认路由所在行，避免 VPN 或虚拟网卡 src 被误选。
        String fallback = "";
        for (String line : output.split("\\R")) {
            Matcher matcher = ROUTE_SOURCE.matcher(line);
            if (!matcher.find() || !isIpv4(matcher.group(1))) {
                // 不含合法 src 地址的路由行直接忽略。
                continue;
            }
            if (line.trim().startsWith("default ")) {
                // 默认路由代表设备当前对外连接使用的 Wi-Fi 地址。
                return matcher.group(1);
            }
            fallback = matcher.group(1);
        }
        return fallback;
    }

    private static boolean isIpv4(String value) {
        // 正则只限制形状，这里继续校验每个十进制分段范围。
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException error) {
                // 非数字分段不是合法地址。
                return false;
            }
            if (number < 0 || number > 255) {
                // IPv4 每个分段只能是 0-255。
                return false;
            }
        }
        return true;
    }

    /** 校验 ADB 无线配对或连接使用的 host:port。 */
    static boolean isHostPort(String value) {
        // 当前设置页优先支持 IPv4/主机名；IPv6 可在后续用 URI 模型扩展。
        if (value == null || value.isBlank()) {
            return false;
        }
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            // 主机或端口缺失均不是合法端点。
            return false;
        }
        String host = value.substring(0, separator).trim();
        if (host.isEmpty() || host.contains(" ")) {
            // ADB 主机名不允许空白字符。
            return false;
        }
        try {
            int port = Integer.parseInt(value.substring(separator + 1).trim());
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException error) {
            // 非数字端口直接拒绝。
            return false;
        }
    }
}
