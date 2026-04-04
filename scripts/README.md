# 示例脚本

这个目录包含了一些示例脚本，用于演示如何使用 AutoGo ScriptEngine Debugger。

## Lua 示例

### main.lua
```lua
local utils = require('module')
print(utils.add(5,3))
example.hello()
example.hello('zing')
```

### module.lua
```lua
local M = {}

function M.add(a, b)
    return a + b
end

return M
```

## JavaScript 示例

### main.js
```javascript
const utils = require('./module');

console.log('utils.add(1, 2) =', utils.add(1, 2));
example.hello();
example.hello('zing');
```

### module.js
```javascript
module.exports = {
    add: function(a, b) {
        return a + b;
    }
};
```

## 使用方法

1. 初始化项目后，这些示例脚本会自动复制到项目的 `scripts/` 目录
2. 在 TUI 界面中选择"运行管理"
3. 选择"选择脚本文件"
4. 选择要运行的脚本
5. 点击"运行脚本"

## 创建自己的脚本

你可以参考这些示例创建自己的脚本：

- **Lua**: 使用 `require()` 加载模块
- **JavaScript**: 使用 `require()` 或 ES6 import
- 两种语言都支持 `example.hello()` 示例函数

## 注意事项

- 脚本文件应放在项目的 `scripts/` 目录中
- Lua 模块文件需要以 `.lua` 结尾
- JavaScript 模块文件需要以 `.js` 结尾
- 支持 CommonJS 模块系统
