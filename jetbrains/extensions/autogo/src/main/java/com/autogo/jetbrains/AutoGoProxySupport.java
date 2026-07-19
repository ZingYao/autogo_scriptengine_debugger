package com.autogo.jetbrains;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 将插件代理设置转换为网络连接和子进程可复用的统一语义。
 */
public final class AutoGoProxySupport {
    private AutoGoProxySupport() {
        // 工具类禁止实例化。
    }

    /** 创建使用当前代理设置的 HTTP 连接。 */
    public static HttpURLConnection openHttpConnection(String address, AutoGoSettings settings) throws IOException {
        // 代理关闭时使用直接连接。
        URI uri = URI.create(address);
        Proxy proxy = createProxy(settings);
        HttpURLConnection connection = (HttpURLConnection) (proxy == Proxy.NO_PROXY
                ? uri.toURL().openConnection() : uri.toURL().openConnection(proxy));
        if (settings.isProxyEnabled() && settings.isProxyAuthEnabled()
                && !settings.getProxyUsername().isBlank() && "HTTP".equals(settings.getProxyType())) {
            // HTTP Basic 代理认证只写入本次请求，不修改 JVM 全局 Authenticator。
            String credentials = settings.getProxyUsername() + ":" + settings.getProxyPassword();
            String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Proxy-Authorization", "Basic " + token);
        }
        return connection;
    }

    /** 创建 Java 网络代理；配置不完整时抛出明确错误。 */
    public static Proxy createProxy(AutoGoSettings settings) {
        // 总开关关闭时直接连接。
        if (!settings.isProxyEnabled()) {
            return Proxy.NO_PROXY;
        }
        String host = settings.getProxyHost();
        int port = settings.getProxyPort();
        if (host.isBlank() || port < 1 || port > 65535) {
            // 禁止用不完整代理发起难以诊断的网络请求。
            throw new IllegalArgumentException("请填写有效的代理主机和端口");
        }
        Proxy.Type type = "SOCKS5".equals(settings.getProxyType()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, new InetSocketAddress(host, port));
    }
}
