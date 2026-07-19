package com.glua.jetbrains;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// GluaPluginRegistrationTest 验证导航相关实现已注册到 JetBrains 扩展点。
final class GluaPluginRegistrationTest {
    // rejectsStandaloneGluaPlugin 验证 AutoGo 不会与重复注册 GLua 语言的独立插件同时启用。
    @Test
    void rejectsStandaloneGluaPlugin() throws Exception {
        // 解析插件描述并确认仅声明一次独立 GLua 插件互斥关系。
        Document document = pluginDocument();

        NodeList incompatiblePlugins = document.getElementsByTagName("incompatible-with");
        int matches = 0;
        for (int index = 0; index < incompatiblePlugins.getLength(); index++) {
            if ("com.glua.jetbrains".equals(incompatiblePlugins.item(index).getTextContent().trim())) {
                // 当前节点命中会重复注册 GLua 语言的旧插件，累计一次以检测缺失或重复配置。
                matches++;
            }
        }
        assertEquals(1, matches);
    }

    // registersBuiltinNavigationExtensions 验证内置声明跳转处理器与引用贡献器各注册一次。
    @Test
    void registersBuiltinNavigationExtensions() throws Exception {
        // 解析插件描述文件并按扩展点与实现类精确计数。
        Document document = pluginDocument();

        assertEquals(1, extensionCount(document, "gotoDeclarationHandler", "com.glua.jetbrains.GluaGotoDeclarationHandler"));
        assertEquals(1, extensionCount(document, "psi.referenceContributor", "com.glua.jetbrains.GluaBuiltinReferenceContributor"));
        assertEquals(1, extensionCount(document, "platform.lsp.serverSupportProvider",
                "com.glua.jetbrains.GluaLspServerSupportProvider"));
    }

    // pluginDocument 读取 AutoGo 的 JetBrains 插件描述文件。
    private static Document pluginDocument() throws Exception {
        // 使用测试模块工作目录定位描述文件，供所有注册约束测试复用。
        return DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("src", "main", "resources", "META-INF", "plugin.xml").toFile());
    }

    // extensionCount 统计指定扩展点下匹配实现类的注册数量。
    private static int extensionCount(Document document, String extensionPoint, String implementation) {
        // 遍历同名扩展节点并仅累计实现类完全匹配的注册。
        NodeList extensions = document.getElementsByTagName(extensionPoint);
        int matches = 0;
        for (int index = 0; index < extensions.getLength(); index++) {
            Element extension = (Element) extensions.item(index);
            if (implementation.equals(extension.getAttribute("implementation"))) {
                // 当前注册命中目标实现类，累计一次以检测缺失或重复配置。
                matches++;
            }
        }
        return matches;
    }
}
