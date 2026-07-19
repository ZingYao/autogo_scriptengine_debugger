package com.autogo.jetbrains;

/** 编译当前 Lua/GLua 文件并远程运行生成的 GLuac。 */
public final class AutoGoGluacRemoteRunAction extends AutoGoGluacCompileAction {
    /** 创建 GLuac 远程运行动作。 */
    public AutoGoGluacRemoteRunAction() {
        // 远程运行仍要求用户输入目标运行时版本。
        super("编译并远程运行 GLuac", true, false);
    }
}
