package com.autogo.jetbrains;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.ide.trustedProjects.TrustedProjectsLocator;
import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;

/**
 * 汇总顶部菜单与 Console 工具栏动作，所有入口都通过统一进程服务执行。
 */
public final class AutoGoMenuActions {
    private AutoGoMenuActions() {
        // 动作容器不允许实例化。
    }

    /** 快速调试当前项目。 */
    public static final class QuickDebugAction extends DumbAwareAction {
        public QuickDebugAction() {
            super("快速调试", "同步当前 Lua/GLua 依赖并连接移动端 DAP", AutoGoIcons.DEBUG);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 根 main.go 是移动端宿主入口；当前 Lua/GLua 文件是本次调试脚本入口。
            if (!validateRootMain(event)) {
                return;
            }
            Project project = requireProject(event);
            VirtualFile file = AutoGoActionFiles.currentFile(event);
            if (project == null || file == null || !isLuaFile(file)) {
                // 快速调试只接受当前 Lua/GLua 文件，不把 Go 或目录误传给脚本引擎。
                Messages.showErrorDialog(project, "请先打开需要调试的 Lua 或 GLua 文件。", "快速调试");
                return;
            }
            FileDocumentManager.getInstance().saveAllDocuments();
            project.getService(AutoGoRemoteEngineService.class).syncAndRun(Path.of(file.getPath()), true);
        }
    }

    /** 运行当前 Lua 或 GLua 文件。 */
    public static final class RunAction extends DumbAwareAction {
        public RunAction() {
            super("运行当前 Lua/GLua 文件", "同步当前文件及 require 依赖并在移动端运行", AutoGoIcons.RUN);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 根 main.go 仅负责常驻移动端宿主；本次执行入口必须来自当前编辑文件。
            if (!validateRootMain(event)) {
                return;
            }
            Project project = requireProject(event);
            VirtualFile file = AutoGoActionFiles.currentFile(event);
            if (project == null || file == null || !isLuaFile(file)) {
                // 与 VSCode 一致，F7 不允许回退为 ag run 或执行项目根 Go 入口。
                Messages.showErrorDialog(project, "请先打开需要运行的 Lua 或 GLua 文件。", "运行当前脚本");
                return;
            }
            FileDocumentManager.getInstance().saveAllDocuments();
            project.getService(AutoGoRemoteEngineService.class).syncAndRun(Path.of(file.getPath()), false);
        }
    }

    /** 停止本地任务并停止设备项目。 */
    public static final class StopAction extends DumbAwareAction {
        public StopAction() {
            super("停止运行", "停止当前 AutoGo 任务", AutoGoIcons.STOP);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 先终止插件创建的长任务，再调用 AG 停止设备端项目。
            Project project = requireProject(event);
            if (project == null) {
                return;
            }
            AutoGoProcessService process = project.getService(AutoGoProcessService.class);
            process.stopActive();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                // 等待本地进程释放独占槽位后补发 stop。
                for (int retry = 0; retry < 20 && process.isRunning(); retry++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        // 中断表示项目正在关闭，停止后续设备操作。
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                process.runAg(deviceArgs(project, "stop", false));
            });
        }
    }

    /** 启动 AG 节点助手。 */
    public static final class NodeServeAction extends DumbAwareAction {
        public NodeServeAction() {
            super("节点助手", "启动 ag nodeserve", AutoGoIcons.NODE);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // nodeserve 是长任务，由统一进程服务提供终止能力。
            runAg(event, List.of("nodeserve"));
        }
    }

    /** 清空 AutoGo Console。 */
    public static final class ClearConsoleAction extends DumbAwareAction {
        public ClearConsoleAction() {
            super("清空日志", "清空 AutoGo Console", AutoGoIcons.CLEAR);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 清空仅影响当前项目控制台文本。
            Project project = requireProject(event);
            if (project != null) {
                project.getService(AutoGoConsoleService.class).clear();
            }
        }
    }

    /** 将设置页中的模块策略和自定义初始化文件应用到现有项目。 */
    public static final class ApplyEngineConfigAction extends DumbAwareAction {
        public ApplyEngineConfigAction() {
            super("应用引擎配置", "保留项目同步与远程配置并重新生成根 main.go", AutoGoIcons.APPLY);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 重生成只修改受管配置与根入口，旧 main.go 会先保存到 .autogo/backups。
            Project project = requireProject(event);
            if (project == null || project.getBasePath() == null) {
                return;
            }
            if (!ensureTrustedForCustomInitializer(project)) {
                // 未信任项目不得把外部 Go 代码复制到根包并参与后续设备构建。
                return;
            }
            try {
                Path backup = AutoGoProjectGenerator.regenerate(Path.of(project.getBasePath()), settings());
                String backupMessage = backup == null ? "原项目没有 main.go。" : "旧入口备份：" + backup;
                project.getService(AutoGoConsoleService.class)
                        .info("已应用 GLua 模块策略并重新生成项目根 main.go。" + backupMessage);
            } catch (IOException | IllegalArgumentException error) {
                // 配置损坏或名单非法时保持现有入口并给出具体原因。
                Messages.showErrorDialog(project, error.getMessage(), "应用引擎配置失败");
                project.getService(AutoGoConsoleService.class).error("应用引擎配置失败：" + error.getMessage());
            }
        }
    }

    /** 编译 arm64-v8a。 */
    public static final class BuildArm64Action extends BuildAction {
        public BuildArm64Action() { super("arm64-v8a", "arm64-v8a"); }
    }

    /** 编译 x86_64。 */
    public static final class BuildX8664Action extends BuildAction {
        public BuildX8664Action() { super("x86_64", "x86_64"); }
    }

    /** 编译 x86。 */
    public static final class BuildX86Action extends BuildAction {
        public BuildX86Action() { super("x86", "x86"); }
    }

    /** 编译包含三种架构的 APK。 */
    public static final class BuildApkAction extends BuildAction {
        public BuildApkAction() { super("apk", "apk[arm64-v8a,x86_64,x86]"); }
    }

    private abstract static class BuildAction extends DumbAwareAction {
        private final String target;

        private BuildAction(String text, String target) {
            super(text, "执行 ag build -t " + target, AutoGoIcons.BUILD);
            this.target = target;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 架构目标直接传递给 AG，不做二次转换。
            runAg(event, List.of("build", "-t", target));
        }
    }

    /** 初始化 Android 项目。 */
    public static final class InitAndroidAction extends InitAction {
        public InitAndroidAction() { super("Android", "android"); }
    }

    /** 初始化 iOS 项目。 */
    public static final class InitIosAction extends InitAction {
        public InitIosAction() { super("iOS", "ios"); }
    }

    private abstract static class InitAction extends DumbAwareAction {
        private final String target;

        private InitAction(String text, String target) {
            super(text, "执行 ag init -t " + target, AutoGoIcons.INIT);
            this.target = target;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // ag init 会清空项目目录，必须在执行前进行破坏性操作二次确认。
            Project project = requireProject(event);
            if (project == null) {
                return;
            }
            if (!ensureTrustedForCustomInitializer(project)) {
                // 破坏性初始化之前先完成信任门禁，避免清空后才拒绝生成。
                return;
            }
            String message = "ag init 将清理项目目录中的全部内容：\n\n"
                    + project.getBasePath()
                    + "\n\n此操作不可撤销。确认继续初始化 " + target + " 项目吗？";
            int answer = Messages.showYesNoDialog(project, message, "确认清空并初始化项目",
                    "清空并继续", "取消", Messages.getWarningIcon());
            if (answer != Messages.YES) {
                // 用户取消时不启动任何 AG 子进程。
                project.getService(AutoGoConsoleService.class).info("已取消项目初始化，目录未发生变化。");
                return;
            }
            AutoGoSettings settings = settings();
            project.getService(AutoGoProcessService.class).runAg(List.of("init", "-t", target), exitCode -> {
                // 只有 ag 初始化成功后才生成脚本引擎配置和宿主代码。
                if (exitCode != 0) {
                    project.getService(AutoGoConsoleService.class)
                            .error("ag init 失败，已跳过 GLua 脚本引擎初始化。");
                    return;
                }
                try {
                    AutoGoProjectGenerator.generate(Path.of(project.getBasePath()), settings, target);
                    Path entry = AutoGoProjectGenerator.ensureDefaultScript(Path.of(project.getBasePath()));
                    ApplicationManager.getApplication().invokeLater(() -> {
                        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry);
                        if (file != null) {
                            // 初始化完成后直接进入默认脚本，缩短首次运行路径。
                            FileEditorManager.getInstance(project).openFile(file, true);
                        }
                    });
                    project.getService(AutoGoConsoleService.class)
                            .info("GLua 脚本引擎入口已生成到项目根目录 main.go。");
                    // 生成本地宿主配置后 Clone 脚本引擎并写入根 go.mod replace。
                    AutoGoScriptEngineDependencyService.initialize(project);
                } catch (IOException error) {
                    // 生成失败保留 ag 结果，并输出可定位的文件错误。
                    project.getService(AutoGoConsoleService.class)
                            .error("GLua 脚本引擎初始化失败：" + error.getMessage());
                }
            });
        }
    }

    private static boolean ensureTrustedForCustomInitializer(Project project) {
        // 没有自定义初始化代码时无需额外信任门禁，保持普通生成流程可用。
        if (settings().getCustomInitializerPath().isBlank()) {
            return true;
        }
        var locatedProject = TrustedProjectsLocator.Companion.locateProject(project);
        if (TrustedProjects.INSTANCE.isProjectTrusted(locatedProject)) {
            // IDEA 已记录用户对当前项目位置的信任，可以安全引入选定源码。
            return true;
        }
        Messages.showErrorDialog(project,
                "当前项目尚未被 IDEA 信任，不能引入或执行自定义初始化代码。请先信任项目后重试。",
                "AutoGo 工作区未信任");
        return false;
    }

    /** 动态列出 adb devices 中处于 device 状态的设备。 */
    public static final class DevicesGroup extends DefaultActionGroup {
        @Override
        public AnAction @NotNull [] getChildren(@Nullable AnActionEvent event) {
            // 动作系统会高频调用该方法；共享刷新器通过短缓存和 single-flight 控制 adb 扫描频率。
            if (event == null || event.getProject() == null) {
                return new AnAction[0];
            }
            var refresh = AutoGoDeviceSupport.refreshDevicesAsync(settings().getAdbPath(), false);
            if (!refresh.isDone()) {
                // 扫描完成后唤醒动作系统，使已展开菜单及时重建设备项。
                refresh.whenComplete((devices, error) -> ApplicationManager.getApplication().invokeLater(
                        () -> ActivityTracker.getInstance().inc()));
            }
            List<String> devices = AutoGoDeviceSupport.cachedDevices();
            if (devices.isEmpty() && !settings().getDefaultDevice().isBlank()) {
                // 首次扫描完成前保留已配置设备，避免菜单短暂显示为未连接。
                devices = List.of(settings().getDefaultDevice());
            }
            List<AnAction> actions = new ArrayList<>();
            if (AutoGoDeviceSupport.isRefreshing()) {
                // 保留已有缓存设备，同时用禁用项明确反馈后台刷新状态。
                actions.add(new DisabledInfoAction("正在刷新设备…"));
            }
            for (String serial : devices) {
                // 每个设备项负责更新全局默认设备。
                actions.add(new SelectDeviceAction(serial));
            }
            if (actions.isEmpty()) {
                // 空列表明确提示用户检查连接，而不是展示空白菜单。
                actions.add(new DisabledInfoAction("未发现在线设备"));
            }
            return actions.toArray(AnAction[]::new);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            // 这里只读取内存快照，可直接在 EDT 构造菜单，避免后台动作更新延迟。
            return ActionUpdateThread.EDT;
        }
    }

    /** 把当前 USB ADB 设备切换为无线 ADB，并在成功后选中无线序列号。 */
    public static final class SwitchToWirelessAction extends DumbAwareAction {
        private static final int DEFAULT_ADB_TCP_PORT = 5555;

        public SwitchToWirelessAction() {
            super("切换为无线连接", "通过当前 ADB 连接启用设备无线调试", AutoGoIcons.DEVICE);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 当前设备必须在线且仍是 USB 序列号，无线设备无需重复切换。
            Project project = requireProject(event);
            String serial = requireDevice(project);
            if (project == null || serial == null) {
                return;
            }
            if (serial.contains(":")) {
                // host:port 形式已经是无线 ADB 连接。
                project.getService(AutoGoConsoleService.class).info("当前设备已经是无线连接：" + serial);
                return;
            }
            AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
            console.info("正在查询设备 Wi-Fi 地址，USB 连接会保留到无线验证成功……");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String ip = AutoGoDeviceSupport.findDeviceIpv4(settings().getAdbPath(), serial);
                if (ip.isBlank()) {
                    // 无 Wi-Fi 默认路由时不可执行 tcpip，继续保留原设备。
                    console.error("无法获取设备 Wi-Fi IPv4 地址，请确认电脑与设备位于同一网络。");
                    return;
                }
                String wirelessSerial = ip + ":" + DEFAULT_ADB_TCP_PORT;
                AutoGoProcessService process = project.getService(AutoGoProcessService.class);
                boolean started = process.runAdb(List.of("-s", serial, "tcpip", String.valueOf(DEFAULT_ADB_TCP_PORT)), tcpExit -> {
                    if (tcpExit != 0) {
                        // tcpip 失败时不尝试 connect，默认设备仍是 USB。
                        console.error("设备拒绝启用无线 ADB，已保留 USB 连接。");
                        return;
                    }
                    process.runAdb(List.of("connect", wirelessSerial), connectExit -> {
                        if (connectExit != 0) {
                            // connect 失败时明确保留 USB 设备选择。
                            console.error("无线 ADB 连接失败，已保留 USB 设备：" + serial);
                            return;
                        }
                        List<String> devices = AutoGoDeviceSupport.listDevices(settings().getAdbPath());
                        if (!devices.contains(wirelessSerial)) {
                            // adb connect 退出成功但设备未进入 device 状态时不得切换选择。
                            console.error("无线设备尚未进入可用状态，已保留 USB 设备：" + serial);
                            return;
                        }
                        selectDevice(project, wirelessSerial);
                        console.info("无线 ADB 已连接并设为默认设备：" + wirelessSerial);
                    });
                });
                if (!started) {
                    // 被其他独占任务阻止时不改变设备状态。
                    console.error("当前有任务运行，暂时无法切换无线 ADB。");
                }
            });
        }
    }

    /** 使用 Android 11+ 无线调试配对码建立 ADB 连接。 */
    public static final class PairWirelessAction extends DumbAwareAction {
        public PairWirelessAction() {
            super("无线调试配对", "使用 Android 无线调试配对地址和配对码连接设备", AutoGoIcons.DEVICE);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 配对地址、配对码和连接地址来自设备“无线调试”页面。
            Project project = requireProject(event);
            if (project == null) {
                return;
            }
            String pairEndpoint = Messages.showInputDialog(project,
                    "请输入设备显示的配对 IP 地址和端口，例如 192.168.1.28:37123：",
                    "Android 无线调试配对", Messages.getQuestionIcon());
            if (pairEndpoint == null) {
                // 用户取消时不运行 adb。
                return;
            }
            pairEndpoint = pairEndpoint.trim();
            if (!AutoGoDeviceSupport.isHostPort(pairEndpoint)) {
                // 端点必须包含有效端口，配对端口不能默认推断。
                Messages.showErrorDialog(project, "配对地址必须是有效的 主机:端口。", "Android 无线调试配对");
                return;
            }
            String pairingCode = Messages.showInputDialog(project,
                    "请输入设备显示的六位无线配对码：", "Android 无线调试配对",
                    Messages.getQuestionIcon());
            if (pairingCode == null) {
                // 用户取消配对码输入时保持现有连接。
                return;
            }
            pairingCode = pairingCode.trim();
            if (!pairingCode.matches("\\d{6}")) {
                // Android 配对码固定为六位十进制数字。
                Messages.showErrorDialog(project, "无线配对码必须是六位数字。", "Android 无线调试配对");
                return;
            }
            String finalPairEndpoint = pairEndpoint;
            String finalPairingCode = pairingCode;
            AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
            AutoGoProcessService process = project.getService(AutoGoProcessService.class);
            process.runAdbPair(finalPairEndpoint, finalPairingCode, pairExit -> {
                if (pairExit != 0) {
                    // 配对失败原因由 adb 输出保留在 Console。
                    console.error("无线调试配对失败，请刷新设备配对码后重试。");
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> requestWirelessConnect(project, process, console));
            });
        }

        private static void requestWirelessConnect(Project project, AutoGoProcessService process,
                                                   AutoGoConsoleService console) {
            // Android 的配对端口和连接端口通常不同，必须再次读取无线调试主页面地址。
            String connectEndpoint = Messages.showInputDialog(project,
                    "配对成功。请输入“无线调试”主页面显示的 IP 地址和端口：",
                    "连接无线 Android 设备", Messages.getQuestionIcon());
            if (connectEndpoint == null) {
                // 已完成配对但用户暂不连接，不改变默认设备。
                console.info("无线调试已配对，尚未连接设备。");
                return;
            }
            connectEndpoint = connectEndpoint.trim();
            if (!AutoGoDeviceSupport.isHostPort(connectEndpoint)) {
                // 连接端口同样不可从配对端口推断。
                Messages.showErrorDialog(project, "连接地址必须是有效的 主机:端口。", "连接无线 Android 设备");
                return;
            }
            String finalEndpoint = connectEndpoint;
            process.runAdb(List.of("connect", finalEndpoint), connectExit -> {
                if (connectExit != 0) {
                    // 失败时保留当前默认设备。
                    console.error("无线设备连接失败：" + finalEndpoint);
                    return;
                }
                List<String> devices = AutoGoDeviceSupport.listDevices(settings().getAdbPath());
                if (!devices.contains(finalEndpoint)) {
                    // adb connect 成功文本不能代替 device 状态验证。
                    console.error("无线设备未进入可用状态：" + finalEndpoint);
                    return;
                }
                selectDevice(project, finalEndpoint);
                console.info("无线设备已连接并设为默认设备：" + finalEndpoint);
            });
        }
    }

    private static final class SelectDeviceAction extends DumbAwareAction {
        private final String serial;

        private SelectDeviceAction(String serial) {
            super(serial, "选择设备 " + serial, AutoGoIcons.DEVICE);
            this.serial = serial;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 选择结果立即持久化并写入控制台。
            Project project = requireProject(event);
            if (project != null) {
                selectDevice(project, serial);
                project.getService(AutoGoConsoleService.class).info("当前设备：" + serial);
            }
        }
    }

    private static final class DisabledInfoAction extends DumbAwareAction {
        private DisabledInfoAction(String text) {
            super(text);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 禁用的信息项不执行任何操作。
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            // 动作实例的禁用状态必须写入本次事件 Presentation，而非模板对象。
            event.getPresentation().setEnabled(false);
        }
    }

    /** 使用系统文件选择器向当前设备推送单个文件。 */
    public static final class PushFileAction extends DumbAwareAction {
        public PushFileAction() {
            super("推送文件", "选择文件并推送到设备临时目录", AutoGoIcons.PUSH);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 文件推送要求项目和默认设备均已就绪。
            Project project = requireProject(event);
            String device = requireDevice(project);
            if (project == null || device == null) {
                return;
            }
            VirtualFile file = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(), project, null);
            if (file == null) {
                // 用户取消选择不视为错误。
                return;
            }
            String target = settings().getRemoteTempDir() + "/";
            project.getService(AutoGoProcessService.class)
                    .runAdb(List.of("-s", device, "push", file.getPath(), target));
        }
    }

    /** 打开 AutoGo 官方文档。 */
    public static final class OfficialDocsAction extends DumbAwareAction {
        public OfficialDocsAction() {
            super("官方文档", "打开 AutoGo Script Engine 官方文档", AutoGoIcons.DOCS);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 官方文档固定在 IDEA 内部 JCEF 工具窗口打开。
            Project project = requireProject(event);
            if (project != null) {
                project.getService(AutoGoDocumentationService.class)
                        .open("https://zingyao.github.io/autogo_scriptengine/");
            }
        }
    }

    /** 打开插件设置页。 */
    public static final class SettingsAction extends DumbAwareAction {
        public SettingsAction() {
            super("其他设置", "打开 AutoGo Script Engine Console 设置", AutoGoIcons.SETTINGS);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // 直接定位 AutoGo 设置，减少用户查找成本。
            Project project = requireProject(event);
            if (project != null) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AutoGoSettingsConfigurable.class);
            }
        }
    }

    private static void runAg(AnActionEvent event, List<String> args) {
        // 所有 AG 动作统一检查项目并转交进程服务。
        Project project = requireProject(event);
        if (project != null) {
            project.getService(AutoGoProcessService.class).runAg(args);
        }
    }

    private static boolean validateRootMain(AnActionEvent event) {
        // AG 固定从项目根目录查找 main.go；其他目录中的同名文件不具备入口作用。
        Project project = requireProject(event);
        if (project == null) {
            return false;
        }
        Path mainFile = Path.of(project.getBasePath(), "main.go");
        try {
            if (!Files.isRegularFile(mainFile)) {
                // 缺少入口时给出可操作提示，禁止启动必然失败的 ag run。
                Messages.showErrorDialog(project,
                        "项目根目录缺少 main.go。请先执行“初始化项目”，或在项目根目录创建入口文件。",
                        "无法运行 AutoGo 项目");
                return false;
            }
            String source = Files.readString(mainFile);
            boolean packageMain = source.matches("(?s).*\\bpackage\\s+main\\b.*");
            boolean functionMain = source.matches("(?s).*\\bfunc\\s+main\\s*\\(\\s*\\).*" );
            if (!packageMain || !functionMain) {
                // 根文件必须同时满足 Go 可执行程序的包名和入口函数约束。
                Messages.showErrorDialog(project,
                        "项目根目录 main.go 必须声明 package main，并包含 func main()。",
                        "无效的 AutoGo 入口");
                return false;
            }
            return true;
        } catch (IOException error) {
            // 入口不可读时禁止运行并保留原始文件系统错误。
            Messages.showErrorDialog(project, "无法读取项目根 main.go：" + error.getMessage(),
                    "无法运行 AutoGo 项目");
            return false;
        }
    }

    private static boolean isLuaFile(VirtualFile file) {
        // Lua 源码和 GLua 源码共用远程脚本同步与调试协议。
        String extension = file.getExtension();
        return extension != null && ("lua".equalsIgnoreCase(extension) || "glua".equalsIgnoreCase(extension));
    }

    static List<String> deviceArgs(String action, boolean debug) {
        // 测试与无项目调用使用应用级默认设备。
        return deviceArgs(null, action, debug);
    }

    static List<String> deviceArgs(@Nullable Project project, String action, boolean debug) {
        // 项目设备覆盖优先；为空时保留 AG 自动选择行为。
        List<String> args = new ArrayList<>();
        args.add(action);
        String device = selectedDevice(project);
        if (!device.isBlank()) {
            args.add("-s");
            args.add(device);
        }
        if (debug) {
            // 快速调试显式启用 AG 调试模式。
            args.add("-d");
        }
        return List.copyOf(args);
    }

    private static @Nullable Project requireProject(AnActionEvent event) {
        // 所有动作都要求有效项目根目录。
        Project project = event.getProject();
        if (project == null || project.getBasePath() == null) {
            // 缺少项目时提前退出，避免命令在 IDE 目录执行。
            Messages.showErrorDialog("请先打开 AutoGo 项目。", "AutoGo Script Engine Console");
            return null;
        }
        return project;
    }

    static @Nullable String requireDevice(@Nullable Project project) {
        // 设备操作必须由用户明确选择在线设备。
        String device = selectedDevice(project);
        if (device.isBlank()) {
            // 未选择设备时给出菜单路径提示。
            Messages.showErrorDialog(project, "请先在“连接设备”菜单选择在线设备。", "AutoGo Script Engine Console");
            return null;
        }
        return device;
    }

    static String selectedDevice(@Nullable Project project) {
        // 项目配置允许多个打开项目分别绑定设备；全局设置仅作为默认值。
        String fallback = settings().getDefaultDevice();
        if (project == null || project.getBasePath() == null) {
            return fallback;
        }
        Path config = Path.of(project.getBasePath(), ".autogo", "engine.json");
        try {
            return AutoGoProjectConfig.selectedDevice(config, fallback);
        } catch (IOException error) {
            // 损坏项目配置不能被猜测，返回空值让调用方停止设备操作。
            project.getService(AutoGoConsoleService.class)
                    .error("无法读取项目设备配置：" + error.getMessage());
            return "";
        }
    }

    private static void selectDevice(Project project, String serial) {
        // 同时更新项目覆盖与全局默认；其他已有项目覆盖不受影响。
        settings().setDefaultDevice(serial);
        Path config = Path.of(project.getBasePath(), ".autogo", "engine.json");
        if (!Files.isRegularFile(config)) {
            return;
        }
        try {
            AutoGoProjectConfig.setSelectedDevice(config, serial);
        } catch (IOException error) {
            // 全局默认仍已更新，但项目持久化失败必须明确提示。
            project.getService(AutoGoConsoleService.class)
                    .error("保存项目设备选择失败：" + error.getMessage());
        }
    }

    static AutoGoSettings settings() {
        // 菜单动作共享应用级配置。
        return ApplicationManager.getApplication().getService(AutoGoSettings.class);
    }
}
