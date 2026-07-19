package com.autogo.jetbrains;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.glua.jetbrains.GluaSettings;
import com.glua.jetbrains.GluaCompilerExecutable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.awt.BorderLayout;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 提供工具自动发现、文件选择、ADB 设备选择和结构化代理设置界面。
 */
public final class AutoGoSettingsConfigurable implements Configurable {
    private final TextFieldWithBrowseButton agPath = executableField("选择 AG 可执行文件");
    private final TextFieldWithBrowseButton adbPath = executableField("选择 ADB 可执行文件");
    private final TextFieldWithBrowseButton goPath = executableField("选择 Go 可执行文件");
    private final TextFieldWithBrowseButton gluacPath = executableField("选择 GLuac 可执行文件");
    private final DefaultComboBoxModel<String> deviceModel = new DefaultComboBoxModel<>();
    private final ComboBox<String> defaultDevice = new ComboBox<>(deviceModel);
    private final JBTextField remoteTempDir = new JBTextField();
    private final JBPasswordField remoteControlToken = new JBPasswordField();
    private final ComboBox<String> modulePolicy = new ComboBox<>(new String[]{"全部模块", "仅白名单", "排除黑名单"});
    private final CheckBoxList<String> moduleEntries = new CheckBoxList<>();
    private final JBTextField moduleSearch = new JBTextField();
    private final JBLabel moduleRegenerationPreference = new JBLabel("每次询问");
    private final JButton clearModuleRegenerationPreference = new JButton("清除记住选项");
    private final Set<String> selectedModules = new LinkedHashSet<>();
    private final TextFieldWithBrowseButton customInitializerPath = sourceFileField("选择自定义 Go 引擎初始化文件");
    private final JBCheckBox proxyEnabled = new JBCheckBox("启用网络代理");
    private final ComboBox<String> proxyType = new ComboBox<>(new String[]{"HTTP", "SOCKS5"});
    private final JBTextField proxyHost = new JBTextField();
    private final JBTextField proxyPort = new JBTextField();
    private final JBCheckBox proxyAuthEnabled = new JBCheckBox("需要认证");
    private final JBTextField proxyUsername = new JBTextField();
    private final JBPasswordField proxyPassword = new JBPasswordField();
    private final JBTextField proxyTestUrl = new JBTextField();
    private final JBLabel proxyTestResult = new JBLabel(" ");
    private final JButton discoverButton = new JButton("重新自动发现");
    private final JButton refreshDevicesButton = new JButton("刷新设备");
    private final JBLabel deviceRefreshStatus = new JBLabel(" ");
    private final JButton proxyTestButton = new JButton("测试代理");
    private JPanel panel;
    private final AtomicInteger deviceRefreshGeneration = new AtomicInteger();
    private volatile String loadedProxyPassword = "";
    private volatile String loadedRemoteControlToken = "";

    /** 返回设置页显示名称。 */
    @Override
    public @Nls String getDisplayName() {
        // 名称与顶部菜单及工具窗口保持一致。
        return "AutoGo Script Engine Console";
    }

    /** 创建设置表单并绑定控件联动。 */
    @Override
    public @Nullable JComponent createComponent() {
        // 工具路径均使用文件选择框，设备使用可刷新的下拉框。
        JPanel discoveryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        discoveryRow.add(discoverButton);
        JPanel deviceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        deviceRow.add(defaultDevice);
        deviceRow.add(refreshDevicesButton);
        deviceRow.add(deviceRefreshStatus);
        JPanel proxyServerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        proxyHost.setColumns(20);
        proxyPort.setColumns(6);
        proxyServerRow.add(new JBLabel("类型"));
        proxyServerRow.add(proxyType);
        proxyServerRow.add(new JBLabel("IP/主机"));
        proxyServerRow.add(proxyHost);
        proxyServerRow.add(new JBLabel("端口"));
        proxyServerRow.add(proxyPort);
        JPanel proxyAuthRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        proxyUsername.setColumns(16);
        proxyPassword.setColumns(16);
        proxyAuthRow.add(proxyAuthEnabled);
        proxyAuthRow.add(new JBLabel("用户名"));
        proxyAuthRow.add(proxyUsername);
        proxyAuthRow.add(new JBLabel("密码"));
        proxyAuthRow.add(proxyPassword);
        JPanel proxyTestRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        proxyTestUrl.setColumns(38);
        proxyTestRow.add(proxyTestUrl);
        proxyTestRow.add(proxyTestButton);
        proxyTestRow.add(proxyTestResult);
        JPanel modulePanel = new JPanel(new BorderLayout(0, 6));
        moduleSearch.getEmptyText().setText("搜索模块");
        moduleEntries.setVisibleRowCount(8);
        modulePanel.add(moduleSearch, BorderLayout.NORTH);
        modulePanel.add(new JBScrollPane(moduleEntries), BorderLayout.CENTER);
        JPanel modulePreferenceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modulePreferenceRow.add(moduleRegenerationPreference);
        modulePreferenceRow.add(clearModuleRegenerationPreference);

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("AG 可执行文件路径", agPath)
                .addLabeledComponent("ADB 可执行文件路径", adbPath)
                .addLabeledComponent("Go 可执行文件路径", goPath)
                .addLabeledComponent("GLuac 可执行文件路径", gluacPath)
                .addLabeledComponent("工具路径", discoveryRow)
                .addLabeledComponent("默认设备序列号", deviceRow)
                .addLabeledComponent("设备临时目录", remoteTempDir)
                .addLabeledComponent("直接远程控制令牌", remoteControlToken)
                .addSeparator()
                .addLabeledComponent("AutoGo 模块策略", modulePolicy)
                .addLabeledComponent("模块选择", modulePanel)
                .addLabeledComponent("模块引入代码生成偏好", modulePreferenceRow)
                .addLabeledComponent("自定义初始化代码", customInitializerPath)
                .addSeparator()
                .addComponent(proxyEnabled)
                .addLabeledComponent("代理服务器", proxyServerRow)
                .addLabeledComponent("代理认证", proxyAuthRow)
                .addLabeledComponent("代理测试地址", proxyTestRow)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        discoverButton.addActionListener(event -> discoverTools(true));
        refreshDevicesButton.addActionListener(event -> refreshDevices());
        proxyEnabled.addActionListener(event -> updateProxyFields());
        proxyAuthEnabled.addActionListener(event -> updateProxyFields());
        proxyTestButton.addActionListener(event -> testProxy());
        modulePolicy.addActionListener(event -> updateModuleFields());
        clearModuleRegenerationPreference.addActionListener(event -> {
            // 清除后下一次模块策略变化会重新询问用户。
            settings().setModuleRegenerationPreference("ASK");
            updateModuleRegenerationPreferenceLabel();
        });
        moduleEntries.setCheckBoxListListener((index, selected) -> {
            // 用户勾选变化立即写入完整选择集合，过滤列表不会丢失隐藏项。
            String module = moduleEntries.getItemAt(index);
            if (selected) {
                selectedModules.add(module);
            } else {
                selectedModules.remove(module);
            }
        });
        moduleSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                // 搜索文本变化后保留勾选状态并过滤展示。
                rebuildModuleList();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                // 删除搜索条件时恢复完整模块列表。
                rebuildModuleList();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                // PlainDocument 通常不触发属性变化，仍保持完整监听语义。
                rebuildModuleList();
            }
        });
        reset();
        return panel;
    }

    /** 判断表单内容是否发生变化。 */
    @Override
    public boolean isModified() {
        // 密码也参与修改判断，但不会写入普通配置文件。
        AutoGoSettings settings = settings();
        return !Objects.equals(text(agPath), settings.getAgPath())
                || !Objects.equals(text(adbPath), settings.getAdbPath())
                || !Objects.equals(text(goPath), settings.getGoPath())
                || !Objects.equals(text(gluacPath), gluaSettings().gluacExecutable())
                || !Objects.equals(selectedDevice(), settings.getDefaultDevice())
                || !Objects.equals(remoteTempDir.getText().trim(), settings.getRemoteTempDir())
                || !Objects.equals(new String(remoteControlToken.getPassword()), loadedRemoteControlToken)
                || !Objects.equals(modulePolicyValue(), settings.getModulePolicy())
                || !Objects.equals(moduleEntriesText(), normalizeStoredModules(settings.getModuleEntries()))
                || !Objects.equals(text(customInitializerPath), settings.getCustomInitializerPath())
                || proxyEnabled.isSelected() != settings.isProxyEnabled()
                || !Objects.equals(proxyType.getItem(), settings.getProxyType())
                || !Objects.equals(proxyHost.getText().trim(), settings.getProxyHost())
                || parsePort(proxyPort.getText()) != settings.getProxyPort()
                || proxyAuthEnabled.isSelected() != settings.isProxyAuthEnabled()
                || !Objects.equals(proxyUsername.getText().trim(), settings.getProxyUsername())
                || !Objects.equals(new String(proxyPassword.getPassword()), loadedProxyPassword)
                || !Objects.equals(proxyTestUrl.getText().trim(), settings.getProxyTestUrl());
    }

    /** 校验并保存表单内容。 */
    @Override
    public void apply() throws ConfigurationException {
        // 已填写的工具路径必须指向真实可执行文件。
        validateExecutable("AG", text(agPath));
        validateExecutable("ADB", text(adbPath));
        validateExecutable("Go", text(goPath));
        validateExecutable("GLuac", text(gluacPath));
        validateOptionalFile("自定义初始化代码", text(customInitializerPath));
        int port = parsePort(proxyPort.getText());
        if (proxyEnabled.isSelected() && (proxyHost.getText().isBlank() || port <= 0)) {
            // 代理开启时主机和端口为必填项。
            throw new ConfigurationException("启用代理后必须填写有效的 IP/主机和 1-65535 端口");
        }
        if (proxyAuthEnabled.isSelected() && proxyUsername.getText().isBlank()) {
            // 认证开启时用户名不能为空。
            throw new ConfigurationException("启用代理认证后必须填写用户名");
        }
        AutoGoSettings settings = settings();
        String previousModulePolicy = settings.getModulePolicy();
        String previousModuleEntries = normalizeStoredModules(settings.getModuleEntries());
        settings.setAgPath(text(agPath));
        settings.setAdbPath(text(adbPath));
        settings.setGoPath(text(goPath));
        gluaSettings().setGluacExecutable(text(gluacPath));
        settings.setDefaultDevice(selectedDevice());
        settings.setRemoteTempDir(remoteTempDir.getText());
        loadedRemoteControlToken = new String(remoteControlToken.getPassword()).trim();
        settings.setRemoteControlToken(loadedRemoteControlToken);
        settings.setModulePolicy(modulePolicyValue());
        settings.setModuleEntries(moduleEntriesText());
        settings.setCustomInitializerPath(text(customInitializerPath));
        settings.setProxyEnabled(proxyEnabled.isSelected());
        settings.setProxyType((String) proxyType.getItem());
        settings.setProxyHost(proxyHost.getText());
        settings.setProxyPort(port);
        settings.setProxyAuthEnabled(proxyAuthEnabled.isSelected());
        settings.setProxyUsername(proxyUsername.getText());
        loadedProxyPassword = new String(proxyPassword.getPassword());
        settings.setProxyPassword(loadedProxyPassword);
        settings.setProxyTestUrl(proxyTestUrl.getText());
        boolean modulesChanged = !Objects.equals(previousModulePolicy, settings.getModulePolicy())
                || !Objects.equals(previousModuleEntries, normalizeStoredModules(settings.getModuleEntries()));
        if (modulesChanged) {
            // 保存完成后按记住的偏好询问并生成当前项目入口。
            handleModuleConfigurationChanged(settings);
        }
    }

    /** 从已保存配置恢复表单，并自动发现空工具路径。 */
    @Override
    public void reset() {
        // 自动发现只填充空配置，尊重用户已经保存的自定义路径。
        discoverTools(false);
        AutoGoSettings settings = settings();
        agPath.setText(settings.getAgPath());
        adbPath.setText(settings.getAdbPath());
        goPath.setText(settings.getGoPath());
        gluacPath.setText(gluaSettings().gluacExecutable());
        remoteTempDir.setText(settings.getRemoteTempDir());
        remoteControlToken.setText("");
        loadedRemoteControlToken = "";
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String token = settings.getRemoteControlToken();
            ApplicationManager.getApplication().invokeLater(() -> {
                // 用户尚未输入新 token 时才回填 PasswordSafe 的后台读取结果。
                if (remoteControlToken.getPassword().length == 0) {
                    loadedRemoteControlToken = token;
                    remoteControlToken.setText(token);
                }
            });
        });
        modulePolicy.setItem(modulePolicyLabel(settings.getModulePolicy()));
        selectedModules.clear();
        selectedModules.addAll(parseStoredModules(settings.getModuleEntries()));
        moduleSearch.setText("");
        rebuildModuleList();
        customInitializerPath.setText(settings.getCustomInitializerPath());
        proxyEnabled.setSelected(settings.isProxyEnabled());
        proxyType.setItem(settings.getProxyType());
        proxyHost.setText(settings.getProxyHost());
        proxyPort.setText(settings.getProxyPort() == 0 ? "" : String.valueOf(settings.getProxyPort()));
        proxyAuthEnabled.setSelected(settings.isProxyAuthEnabled());
        proxyUsername.setText(settings.getProxyUsername());
        // PasswordSafe 读取在后台执行，避免设置页创建时触发 EDT 慢操作。
        proxyPassword.setText("");
        loadedProxyPassword = "";
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String password = settings.getProxyPassword();
            ApplicationManager.getApplication().invokeLater(() -> {
                // 用户尚未输入新密码时才回填后台读取结果。
                if (proxyPassword.getPassword().length == 0) {
                    loadedProxyPassword = password;
                    proxyPassword.setText(password);
                }
            });
        });
        proxyTestUrl.setText(settings.getProxyTestUrl());
        proxyTestResult.setText(" ");
        updateProxyFields();
        updateModuleFields();
        updateModuleRegenerationPreferenceLabel();
        refreshDevices();
    }

    private void handleModuleConfigurationChanged(AutoGoSettings settings) {
        // IDEA 设置为应用级页面，优先处理当前唯一打开且已初始化的 AutoGo 项目。
        Project project = activeAutoGoProject();
        if (project == null) {
            return;
        }
        String preference = settings.getModuleRegenerationPreference();
        if ("NEVER".equals(preference)) {
            // 用户已选择始终不生成，保留设置并结束。
            return;
        }
        boolean regenerate = "ALWAYS".equals(preference);
        if ("ASK".equals(preference)) {
            // “记住此选择”由 IDEA 原生 DoNotAsk 复选框承载。
            DialogWrapper.DoNotAskOption rememberOption = new DialogWrapper.DoNotAskOption() {
                @Override
                public boolean isToBeShown() {
                    return true;
                }

                @Override
                public void setToBeShown(boolean toBeShown, int exitCode) {
                    // 取消勾选“继续显示”代表记住本次是/否选择。
                    if (!toBeShown) {
                        settings.setModuleRegenerationPreference(exitCode == Messages.YES ? "ALWAYS" : "NEVER");
                        updateModuleRegenerationPreferenceLabel();
                    }
                }

                @Override
                public boolean canBeHidden() {
                    return true;
                }

                @Override
                public boolean shouldSaveOptionsOnCancel() {
                    return false;
                }

                @Override
                public String getDoNotShowMessage() {
                    return "记住此选择";
                }
            };
            regenerate = Messages.showYesNoDialog(project,
                    "检测到 AutoGo 模块策略或模块选择发生变化。是否立即重新生成模块引入代码？\n"
                            + "生成过程会保留原入口备份，并包含完整 Debug 能力。",
                    "重新生成模块引入代码？", "重新生成", "暂不生成",
                    Messages.getQuestionIcon(), rememberOption) == Messages.YES;
        }
        if (!regenerate) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // 文件生成在后台执行，结果统一输出到扩展日志。
            try {
                Path backup = AutoGoProjectGenerator.regenerate(Path.of(project.getBasePath()), settings);
                String backupMessage = backup == null ? "" : " 备份：" + backup;
                project.getService(AutoGoConsoleService.class)
                        .info("已应用 AutoGo 模块策略并重新生成项目根 main.go。" + backupMessage);
            } catch (IOException error) {
                project.getService(AutoGoConsoleService.class)
                        .error("重新生成模块引入代码失败：" + error.getMessage());
            }
        });
    }

    private static Project activeAutoGoProject() {
        // 仅选择具备初始化标记且未释放的项目，未初始化工作区不得触发生成。
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed() && project.getBasePath() != null
                    && Files.isRegularFile(Path.of(project.getBasePath(), ".autogo", "engine.json"))) {
                return project;
            }
        }
        return null;
    }

    private void updateModuleRegenerationPreferenceLabel() {
        // 设置页始终回显当前记住状态，并允许一键恢复询问。
        String preference = settings().getModuleRegenerationPreference();
        moduleRegenerationPreference.setText(switch (preference) {
            case "ALWAYS" -> "始终重新生成";
            case "NEVER" -> "始终不生成";
            default -> "每次询问";
        });
        clearModuleRegenerationPreference.setEnabled(!"ASK".equals(preference));
    }

    private void discoverTools(boolean replaceExisting) {
        // 扫描 PATH 和常见目录；找到后立即写入持久化配置。
        AutoGoSettings settings = settings();
        if (replaceExisting || settings.getAgPath().isBlank()) {
            String found = AutoGoToolPathResolver.findAg(System.getenv());
            if (!found.isBlank()) {
                settings.setAgPath(found);
                agPath.setText(found);
            }
        }
        if (replaceExisting || settings.getAdbPath().isBlank()) {
            String found = AutoGoToolPathResolver.findAdb(System.getenv());
            if (!found.isBlank()) {
                settings.setAdbPath(found);
                adbPath.setText(found);
            }
        }
        if (replaceExisting || settings.getGoPath().isBlank()) {
            String found = AutoGoToolPathResolver.findGo(System.getenv());
            if (!found.isBlank()) {
                settings.setGoPath(found);
                goPath.setText(found);
            }
        }
        if (replaceExisting || gluaSettings().gluacExecutable().isBlank()) {
            String found = AutoGoToolPathResolver.findGluac(System.getenv());
            if (found.isBlank()) {
                try {
                    // PATH 未安装时使用插件随附的当前平台 GLuac，并在设置页回显实际路径。
                    found = GluaCompilerExecutable.resolve("").toString();
                } catch (IOException ignored) {
                    // 不支持的平台保持为空，交由用户手动选择兼容编译器。
                }
            }
            if (!found.isBlank()) {
                gluaSettings().setGluacExecutable(found);
                gluacPath.setText(found);
            }
        }
    }

    private void refreshDevices() {
        // ADB 查询在后台执行；刷新序号保证连续点击时只应用最新结果。
        int generation = deviceRefreshGeneration.incrementAndGet();
        refreshDevicesButton.setText("刷新中…");
        refreshDevicesButton.setEnabled(false);
        deviceRefreshStatus.setText("正在扫描");
        String adb = text(adbPath);
        String saved = selectedDevice().isBlank() ? settings().getDefaultDevice() : selectedDevice();
        AutoGoDeviceSupport.refreshDevicesAsync(adb, true).whenComplete((devices, error) ->
            ApplicationManager.getApplication().invokeLater(() -> {
                // 旧扫描结果不得覆盖用户后续触发的新扫描。
                if (generation != deviceRefreshGeneration.get()) {
                    return;
                }
                List<String> refreshedDevices = error == null && devices != null ? devices : List.of();
                deviceModel.removeAllElements();
                for (String device : refreshedDevices) {
                    deviceModel.addElement(device);
                }
                if (!saved.isBlank() && !refreshedDevices.contains(saved)) {
                    // 保存的离线设备仍保留，并明确标记状态。
                    deviceModel.addElement(saved + "（离线）");
                }
                if (!saved.isBlank()) {
                    deviceModel.setSelectedItem(refreshedDevices.contains(saved) ? saved : saved + "（离线）");
                }
                // 成功、空列表和命令失败均恢复按钮，允许无限次刷新。
                refreshDevicesButton.setText("刷新设备");
                refreshDevicesButton.setEnabled(true);
                if (error != null) {
                    // 未预期异常必须结束“正在扫描”状态并给出可见失败原因。
                    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    deviceRefreshStatus.setText("刷新失败：" + message);
                    return;
                }
                deviceRefreshStatus.setText("发现 " + refreshedDevices.size() + " 台在线设备");
            }));
    }

    private void updateProxyFields() {
        // 总开关控制代理服务器与测试控件，认证开关进一步控制凭据字段。
        boolean enabled = proxyEnabled.isSelected();
        proxyType.setEnabled(enabled);
        proxyHost.setEnabled(enabled);
        proxyPort.setEnabled(enabled);
        proxyAuthEnabled.setEnabled(enabled);
        proxyUsername.setEnabled(enabled && proxyAuthEnabled.isSelected());
        proxyPassword.setEnabled(enabled && proxyAuthEnabled.isSelected());
        proxyTestUrl.setEnabled(enabled);
        proxyTestButton.setEnabled(enabled);
    }

    private void updateModuleFields() {
        // 全部模块模式不需要名单；白名单和黑名单模式允许多选。
        boolean selectable = !"ALL".equals(modulePolicyValue());
        moduleSearch.setEnabled(selectable);
        moduleEntries.setEnabled(selectable);
    }

    private void rebuildModuleList() {
        // 合并内置 catalog 与旧配置/远程配置中的模块，避免升级后静默丢项。
        String query = moduleSearch.getText().trim().toLowerCase();
        Set<String> available = new LinkedHashSet<>(AutoGoModuleCatalog.defaultModules());
        available.addAll(selectedModules);
        moduleEntries.clear();
        for (String module : available) {
            if (!query.isEmpty() && !module.toLowerCase().contains(query)) {
                // 不匹配搜索条件的模块仅隐藏，选择状态保存在 selectedModules。
                continue;
            }
            moduleEntries.addItem(module, module, selectedModules.contains(module));
        }
    }

    private String moduleEntriesText() {
        // 按 catalog 稳定顺序保存，再追加远程 catalog 中的额外选项。
        Set<String> ordered = new LinkedHashSet<>();
        for (String module : AutoGoModuleCatalog.defaultModules()) {
            if (selectedModules.contains(module)) {
                ordered.add(module);
            }
        }
        ordered.addAll(selectedModules);
        return String.join("\n", ordered);
    }

    private static List<String> parseStoredModules(String value) {
        // 兼容旧版本按行保存的名单，并过滤空行和注释。
        List<String> modules = new java.util.ArrayList<>();
        for (String line : (value == null ? "" : value).split("\\R")) {
            String module = line.trim();
            if (!module.isEmpty() && !module.startsWith("#") && !modules.contains(module)) {
                modules.add(module);
            }
        }
        return modules;
    }

    private static String normalizeStoredModules(String value) {
        // 修改判断使用与设置保存一致的规范化表示。
        Set<String> selected = new LinkedHashSet<>(parseStoredModules(value));
        Set<String> ordered = new LinkedHashSet<>();
        for (String module : AutoGoModuleCatalog.defaultModules()) {
            if (selected.contains(module)) {
                ordered.add(module);
            }
        }
        ordered.addAll(selected);
        return String.join("\n", ordered);
    }

    private void testProxy() {
        // 测试直接使用当前表单值，无需先点击 Apply。
        String host = proxyHost.getText().trim();
        int port = parsePort(proxyPort.getText());
        String address = proxyTestUrl.getText().trim();
        if (host.isBlank() || port <= 0 || address.isBlank()) {
            proxyTestResult.setText("请填写代理主机、端口和测试地址");
            return;
        }
        proxyTestButton.setEnabled(false);
        proxyTestResult.setText("测试中……");
        String selectedProxyType = (String) proxyType.getItem();
        boolean authenticationEnabled = proxyAuthEnabled.isSelected();
        String username = proxyUsername.getText().trim();
        String password = new String(proxyPassword.getPassword());
        long startedAt = System.nanoTime();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String result;
            HttpURLConnection connection = null;
            try {
                Proxy.Type type = "SOCKS5".equals(selectedProxyType) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                Proxy proxy = new Proxy(type, new InetSocketAddress(host, port));
                connection = (HttpURLConnection) URI.create(address).toURL().openConnection(proxy);
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);
                connection.setInstanceFollowRedirects(true);
                if (authenticationEnabled && "HTTP".equals(selectedProxyType)) {
                    // HTTP 代理认证仅附加到本次测试请求。
                    String credentials = username + ":" + password;
                    String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    connection.setRequestProperty("Proxy-Authorization", "Basic " + token);
                }
                int status = connection.getResponseCode();
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                result = "成功：HTTP " + status + "，" + elapsedMs + " ms";
            } catch (IOException | RuntimeException error) {
                // 网络和配置错误均以简短文本反馈，不弹出阻塞窗口。
                result = "失败：" + error.getMessage();
            } finally {
                // 显式断开连接释放 socket。
                if (connection != null) {
                    connection.disconnect();
                }
            }
            String finalResult = result;
            ApplicationManager.getApplication().invokeLater(() -> {
                proxyTestResult.setText(finalResult);
                proxyTestButton.setEnabled(proxyEnabled.isSelected());
            });
        });
    }

    private static TextFieldWithBrowseButton executableField(String title) {
        // 每个工具路径使用只选单文件的系统文件选择框。
        TextFieldWithBrowseButton field = new TextFieldWithBrowseButton();
        field.addBrowseFolderListener(null,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withTitle(title).withDescription("选择可执行文件"));
        return field;
    }

    private static TextFieldWithBrowseButton sourceFileField(String title) {
        // 自定义初始化代码同样必须通过系统单文件选择框指定。
        TextFieldWithBrowseButton field = new TextFieldWithBrowseButton();
        field.addBrowseFolderListener(null,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withTitle(title).withDescription("选择 Go 源文件"));
        return field;
    }

    private static void validateOptionalFile(String name, String value) throws ConfigurationException {
        // 未配置时使用插件生成的初始化代码。
        if (value.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(value);
            if (!Files.isRegularFile(path)) {
                // 自定义初始化入口必须是现有文件，目录不允许保存。
                throw new ConfigurationException(name + "不是有效文件：" + value);
            }
        } catch (RuntimeException error) {
            // 非法路径给出字段级错误。
            throw new ConfigurationException(name + "路径无效：" + value);
        }
    }

    private String modulePolicyValue() {
        // 中文界面值映射为跨 IDE 共享的稳定枚举。
        return switch (String.valueOf(modulePolicy.getItem())) {
            case "仅白名单" -> "ALLOWLIST";
            case "排除黑名单" -> "DENYLIST";
            default -> "ALL";
        };
    }

    private static String modulePolicyLabel(String value) {
        // 持久化枚举映射为中文设置项。
        return switch (value) {
            case "ALLOWLIST" -> "仅白名单";
            case "DENYLIST" -> "排除黑名单";
            default -> "全部模块";
        };
    }

    private static void validateExecutable(String name, String value) throws ConfigurationException {
        // 空路径允许保存，表示自动发现失败或用户主动清空。
        if (value.isBlank()) {
            return;
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException error) {
            // 非法路径直接指出对应工具。
            throw new ConfigurationException(name + " 路径无效：" + value);
        }
        if (!Files.isRegularFile(path) || (!isWindows() && !Files.isExecutable(path))) {
            // 必须选择真实可执行文件，目录或普通文件均不接受。
            throw new ConfigurationException(name + " 路径不是可执行文件：" + value);
        }
    }

    static int parsePort(String value) {
        // 空值或非数字端口统一返回 0，由应用校验决定是否允许。
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String text(TextFieldWithBrowseButton field) {
        // 路径字段统一去除首尾空格。
        return field.getText().trim();
    }

    private String selectedDevice() {
        // 离线标记只用于界面展示，持久化时恢复真实序列号。
        Object selected = defaultDevice.getSelectedItem();
        return selected == null ? "" : selected.toString().replace("（离线）", "").trim();
    }

    private static boolean isWindows() {
        // Windows 不依赖 POSIX executable 位。
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static AutoGoSettings settings() {
        // 应用级服务确保所有项目共享工具链配置。
        return ApplicationManager.getApplication().getService(AutoGoSettings.class);
    }

    private static GluaSettings gluaSettings() {
        // GLuac 与语言服务设置共享同一应用级 GLua 配置对象。
        return ApplicationManager.getApplication().getService(GluaSettings.class);
    }
}
