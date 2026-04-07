-- 简单测试模块 - 不使用任何混淆
local M = {}

M.add = function(a, b)
    return a + b
end

M.hello = function(name)
    if name then
        return "Hello, " .. name .. "!"
    else
        return "Hello, World!"
    end
end

return M
