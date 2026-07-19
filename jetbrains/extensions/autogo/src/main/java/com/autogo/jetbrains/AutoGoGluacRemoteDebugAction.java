package com.autogo.jetbrains;

/** 编译当前 Lua/GLua 文件并使用保留行信息的 GLuac 创建远程调试会话。 */
public final class AutoGoGluacRemoteDebugAction extends AutoGoGluacCompileAction {
    /** 创建 GLuac 远程调试动作。 */
    public AutoGoGluacRemoteDebugAction() {
        // 编译命令不使用 -s；若运行时拒绝字节码调试，服务端错误会原样输出。
        super("编译并远程调试 GLuac", true, true);
    }
}
