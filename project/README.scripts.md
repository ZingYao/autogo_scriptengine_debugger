# 项目模板文件说明

这个目录包含了项目初始化时会释放的模板文件。

## 文件说明

### scripts/
包含示例脚本，会在项目初始化时复制到用户项目的 `scripts/` 目录。

**包含的文件：**
- `main.lua` - Lua 示例脚本
- `module.lua` - Lua 模块示例
- `main.js` - JavaScript 示例脚本
- `module.js` - JavaScript 模块示例

### debugger.go.code
项目的主程序模板，会在初始化时重命名为 `main.go` 并释放到项目根目录。

这个文件包含：
- TCP Socket 服务器
- Lua 和 JavaScript 引擎支持
- 多种代码风格支持（AutoGo、LrAppSoft、NodeJS）
- 脚本管理功能
- 信号处理

## 修改模板

如果需要修改这些模板文件：

1. 编辑对应的文件
2. 重新编译 debugger
3. 新初始化的项目会使用更新后的模板

## 注意事项

- 这些文件通过 Go embed 嵌入到编译后的二进制文件中
- 修改模板后需要重新编译才能生效
- `debugger.go.code` 是合法的 Go 代码文件，可以直接用 Go 工具验证语法
