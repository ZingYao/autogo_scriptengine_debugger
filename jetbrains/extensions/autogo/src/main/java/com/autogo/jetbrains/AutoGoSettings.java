package com.autogo.jetbrains;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保存 AutoGo Script Engine Console 的全局工具链、设备和网络配置。
 */
@Service(Service.Level.APP)
@State(name = "AutoGoSettings", storages = @Storage("autogo.xml"))
public final class AutoGoSettings implements PersistentStateComponent<AutoGoSettings.StateData> {
    private static final CredentialAttributes PROXY_PASSWORD_ATTRIBUTES =
            new CredentialAttributes("AutoGo Script Engine Console Proxy Password");
    private static final CredentialAttributes REMOTE_TOKEN_ATTRIBUTES =
            new CredentialAttributes("AutoGo Script Engine Console Remote Control Token");

    /** 配置持久化数据。 */
    public static final class StateData {
        /** AG 可执行文件路径；为空时自动检测。 */
        public String agPath = "";
        /** ADB 可执行文件路径；为空时从 PATH 查找。 */
        public String adbPath = "";
        /** Go 可执行文件路径；为空时从 PATH 查找。 */
        public String goPath = "";
        /** 当前选中的 ADB 设备序列号。 */
        public String defaultDevice = "";
        /** 文件与动态库推送目标目录。 */
        public String remoteTempDir = "/data/local/tmp";
        /** GLua 模块加载策略：ALL、ALLOWLIST 或 DENYLIST。 */
        public String modulePolicy = "ALL";
        /** 模块名单，每行一个模块名。 */
        public String moduleEntries = "";
        /** 模块策略变化后的代码生成偏好：ASK、ALWAYS 或 NEVER。 */
        public String moduleRegenerationPreference = "ASK";
        /** 用户自定义的 Go 引擎初始化代码文件。 */
        public String customInitializerPath = "";
        /** 是否启用插件统一网络代理。 */
        public boolean proxyEnabled;
        /** 代理类型：HTTP 或 SOCKS5。 */
        public String proxyType = "HTTP";
        /** 代理服务器 IP 或主机名。 */
        public String proxyHost = "";
        /** 代理服务器端口，0 表示未配置。 */
        public int proxyPort;
        /** 是否启用代理认证。 */
        public boolean proxyAuthEnabled;
        /** 代理认证用户名；密码存储在 PasswordSafe。 */
        public String proxyUsername = "";
        /** 代理测试地址。 */
        public String proxyTestUrl = "https://www.google.com/generate_204";
    }

    private StateData state = new StateData();
    private volatile String proxyPasswordCache;
    private final AtomicBoolean proxyPasswordLoading = new AtomicBoolean();
    private volatile String remoteControlTokenCache;

    /** 获取当前配置快照。 */
    @Override
    public @NotNull StateData getState() {
        // 返回由平台序列化的当前配置对象。
        return state;
    }

    /** 加载平台持久化的配置。 */
    @Override
    public void loadState(@NotNull StateData state) {
        // 完整替换状态，避免旧配置字段残留。
        this.state = state;
    }

    /** 获取 AG 路径。 */
    public String getAgPath() {
        // 对 UI 和运行入口提供只读访问。
        return normalize(state.agPath);
    }

    /** 更新 AG 路径；空值表示恢复自动检测。 */
    public void setAgPath(String agPath) {
        // 将空引用规范为空字符串，方便持久化和比较。
        state.agPath = agPath == null ? "" : agPath.trim();
    }

    /** 获取 ADB 可执行文件路径。 */
    public String getAdbPath() {
        // 对设备相关服务提供只读访问。
        return normalize(state.adbPath);
    }

    /** 更新 ADB 可执行文件路径。 */
    public void setAdbPath(String adbPath) {
        // 空值表示恢复 PATH 自动检测。
        state.adbPath = normalize(adbPath);
    }

    /** 获取 Go 可执行文件路径。 */
    public String getGoPath() {
        // Go 路径用于扩充 AG 子进程的 PATH。
        return normalize(state.goPath);
    }

    /** 更新 Go 可执行文件路径。 */
    public void setGoPath(String goPath) {
        // 只保存可执行文件路径，不修改系统 GOROOT 或 GOPATH。
        state.goPath = normalize(goPath);
    }

    /** 获取默认设备序列号。 */
    public String getDefaultDevice() {
        // 所有需要设备的动作共享同一选择。
        return normalize(state.defaultDevice);
    }

    /** 更新默认设备序列号。 */
    public void setDefaultDevice(String defaultDevice) {
        // 设备离线后允许清空选择。
        state.defaultDevice = normalize(defaultDevice);
    }

    /** 获取远端临时目录。 */
    public String getRemoteTempDir() {
        // 默认目录始终提供非空回退值。
        return state.remoteTempDir == null || state.remoteTempDir.isBlank()
                ? "/data/local/tmp" : state.remoteTempDir.trim();
    }

    /** 更新远端临时目录。 */
    public void setRemoteTempDir(String remoteTempDir) {
        // 空值恢复为 Android 标准临时目录。
        state.remoteTempDir = remoteTempDir == null || remoteTempDir.isBlank()
                ? "/data/local/tmp" : remoteTempDir.trim();
    }

    /** 获取模块加载策略。 */
    public String getModulePolicy() {
        // 旧配置或非法值统一回退到加载全部模块。
        return switch (normalize(state.modulePolicy).toUpperCase()) {
            case "ALLOWLIST" -> "ALLOWLIST";
            case "DENYLIST" -> "DENYLIST";
            default -> "ALL";
        };
    }

    /** 更新模块加载策略。 */
    public void setModulePolicy(String policy) {
        // 只持久化稳定枚举，避免界面文案进入项目配置。
        state.modulePolicy = switch (normalize(policy).toUpperCase()) {
            case "ALLOWLIST" -> "ALLOWLIST";
            case "DENYLIST" -> "DENYLIST";
            default -> "ALL";
        };
    }

    /** 获取按行保存的模块名单。 */
    public String getModuleEntries() {
        // 名单原样保留换行，生成阶段再进行去重和校验。
        return state.moduleEntries == null ? "" : state.moduleEntries;
    }

    /** 更新模块名单。 */
    public void setModuleEntries(String entries) {
        // 空引用规范为空名单。
        state.moduleEntries = entries == null ? "" : entries;
    }

    /** 获取模块策略变化后的代码生成偏好。 */
    public String getModuleRegenerationPreference() {
        // 非法或旧版本配置统一恢复为每次询问。
        return switch (normalize(state.moduleRegenerationPreference).toUpperCase()) {
            case "ALWAYS" -> "ALWAYS";
            case "NEVER" -> "NEVER";
            default -> "ASK";
        };
    }

    /** 更新模块策略变化后的代码生成偏好。 */
    public void setModuleRegenerationPreference(String preference) {
        // 只持久化稳定枚举，避免界面文案进入配置文件。
        state.moduleRegenerationPreference = switch (normalize(preference).toUpperCase()) {
            case "ALWAYS" -> "ALWAYS";
            case "NEVER" -> "NEVER";
            default -> "ASK";
        };
    }

    /** 获取用户自定义初始化代码文件。 */
    public String getCustomInitializerPath() {
        // 空值表示使用插件生成的默认初始化实现。
        return normalize(state.customInitializerPath);
    }

    /** 更新用户自定义初始化代码文件。 */
    public void setCustomInitializerPath(String path) {
        // 仅保存文件路径，插件不得覆盖用户选择的文件。
        state.customInitializerPath = normalize(path);
    }

    /** 获取统一网络代理。 */
    public String getNetworkProxy() {
        // 未启用或参数不完整时返回空字符串，表示直接连接。
        if (!isProxyEnabled() || getProxyHost().isBlank() || getProxyPort() <= 0) {
            return "";
        }
        String scheme = "SOCKS5".equals(getProxyType()) ? "socks5" : "http";
        String userInfo = null;
        if (isProxyAuthEnabled() && !getProxyUsername().isBlank()) {
            // 认证开启时把 PasswordSafe 中的凭据组装到子进程代理 URL。
            String password = getProxyPassword();
            userInfo = getProxyUsername() + ":" + password;
        }
        try {
            return new URI(scheme, userInfo, getProxyHost(), getProxyPort(), null, null, null).toString();
        } catch (URISyntaxException error) {
            // 主机或用户名包含非法字符时返回空值，由设置校验提示用户。
            return "";
        }
    }

    /** 判断是否启用代理。 */
    public boolean isProxyEnabled() {
        // 开关关闭时所有下载和子进程直接连接。
        return state.proxyEnabled;
    }

    /** 更新代理启用状态。 */
    public void setProxyEnabled(boolean enabled) {
        // 仅更新插件配置，不修改系统代理。
        state.proxyEnabled = enabled;
    }

    /** 获取代理类型。 */
    public String getProxyType() {
        // 非法旧配置回退为 HTTP。
        return "SOCKS5".equalsIgnoreCase(state.proxyType) ? "SOCKS5" : "HTTP";
    }

    /** 更新代理类型。 */
    public void setProxyType(String proxyType) {
        // 持久化为稳定的大写枚举值。
        state.proxyType = "SOCKS5".equalsIgnoreCase(proxyType) ? "SOCKS5" : "HTTP";
    }

    /** 获取代理主机。 */
    public String getProxyHost() {
        // 主机允许域名或 IP，由测试阶段验证连通性。
        return normalize(state.proxyHost);
    }

    /** 更新代理主机。 */
    public void setProxyHost(String proxyHost) {
        // 去除首尾空格，禁止保存完整 URL。
        state.proxyHost = normalize(proxyHost);
    }

    /** 获取代理端口。 */
    public int getProxyPort() {
        // 端口范围由设置页校验。
        return state.proxyPort;
    }

    /** 更新代理端口。 */
    public void setProxyPort(int proxyPort) {
        // 无效值归零，防止子进程收到错误代理 URL。
        state.proxyPort = proxyPort >= 1 && proxyPort <= 65535 ? proxyPort : 0;
    }

    /** 判断代理是否需要认证。 */
    public boolean isProxyAuthEnabled() {
        // 总代理关闭时认证设置不会生效。
        return state.proxyAuthEnabled;
    }

    /** 更新代理认证开关。 */
    public void setProxyAuthEnabled(boolean enabled) {
        // 认证关闭时保留用户名和 PasswordSafe 密码，便于再次启用。
        state.proxyAuthEnabled = enabled;
    }

    /** 获取代理用户名。 */
    public String getProxyUsername() {
        // 用户名持久化在普通配置中，密码单独存储。
        return normalize(state.proxyUsername);
    }

    /** 更新代理用户名。 */
    public void setProxyUsername(String username) {
        // 去除无意义的首尾空格。
        state.proxyUsername = normalize(username);
    }

    /** 获取代理密码。 */
    public String getProxyPassword() {
        // 已加载的密码直接从内存返回，避免每次访问 PasswordSafe。
        String cached = proxyPasswordCache;
        if (cached != null) {
            return cached;
        }
        if (ApplicationManager.getApplication().isDispatchThread()) {
            // EDT 上只触发后台加载并返回空值，禁止阻塞 UI。
            loadProxyPasswordAsync();
            return "";
        }
        String password = PasswordSafe.getInstance().getPassword(PROXY_PASSWORD_ATTRIBUTES);
        proxyPasswordCache = password == null ? "" : password;
        return proxyPasswordCache;
    }

    /** 更新代理密码。 */
    public void setProxyPassword(String password) {
        // 先更新内存缓存，再在后台写 PasswordSafe，避免设置页 Apply 阻塞 EDT。
        String normalized = password == null ? "" : password;
        proxyPasswordCache = normalized;
        ApplicationManager.getApplication().executeOnPooledThread(() ->
                PasswordSafe.getInstance().setPassword(PROXY_PASSWORD_ATTRIBUTES,
                        normalized.isEmpty() ? null : normalized));
    }

    /** 在后台预加载代理密码，供设置页和子进程后续读取。 */
    public void loadProxyPasswordAsync() {
        // 同一时间只允许一个 PasswordSafe 读取任务。
        if (proxyPasswordCache != null || !proxyPasswordLoading.compareAndSet(false, true)) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String password = PasswordSafe.getInstance().getPassword(PROXY_PASSWORD_ATTRIBUTES);
                proxyPasswordCache = password == null ? "" : password;
            } finally {
                // 无论读取是否成功都释放加载标记，允许后续重试。
                proxyPasswordLoading.set(false);
            }
        });
    }

    /** 获取直接远程控制服务的 bearer token。 */
    public String getRemoteControlToken() {
        // token 仅存储在 PasswordSafe，不进入 autogo.xml 或项目配置。
        String cached = remoteControlTokenCache;
        if (cached != null) {
            return cached;
        }
        if (ApplicationManager.getApplication().isDispatchThread()) {
            // 远程请求只在后台线程执行，EDT 不允许同步读取密钥存储。
            return "";
        }
        String token = PasswordSafe.getInstance().getPassword(REMOTE_TOKEN_ATTRIBUTES);
        remoteControlTokenCache = token == null ? "" : token;
        return remoteControlTokenCache;
    }

    /** 更新直接远程控制服务的 bearer token。 */
    public void setRemoteControlToken(String token) {
        // 先更新内存快照，再异步写入 IDE 密钥存储。
        String normalized = token == null ? "" : token.trim();
        remoteControlTokenCache = normalized;
        ApplicationManager.getApplication().executeOnPooledThread(() ->
                PasswordSafe.getInstance().setPassword(REMOTE_TOKEN_ATTRIBUTES,
                        normalized.isEmpty() ? null : normalized));
    }

    /** 获取代理测试地址。 */
    public String getProxyTestUrl() {
        // 空配置回退到 Google 204 探测地址。
        return state.proxyTestUrl == null || state.proxyTestUrl.isBlank()
                ? "https://www.google.com/generate_204" : state.proxyTestUrl.trim();
    }

    /** 更新代理测试地址。 */
    public void setProxyTestUrl(String testUrl) {
        // 空值恢复默认地址。
        state.proxyTestUrl = testUrl == null || testUrl.isBlank()
                ? "https://www.google.com/generate_204" : testUrl.trim();
    }

    private static String normalize(String value) {
        // 统一消除空引用和首尾空格。
        return value == null ? "" : value.trim();
    }
}
