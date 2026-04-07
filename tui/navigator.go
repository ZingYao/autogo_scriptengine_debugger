package tui

import (
	"fmt"
	"sync"
	"time"
)

// MenuInfo 菜单信息结构体
// 存储菜单的元数据信息
type MenuInfo struct {
	ID          string // 菜单唯一标识符
	Name        string // 菜单显示名称
	ParentID    string // 父菜单ID（空字符串表示一级菜单）
	Level       int    // 菜单层级（0=主菜单, 1=一级菜单, 2=二级菜单...）
	Description string // 菜单描述
}

// BreadcrumbItem 面包屑项
type BreadcrumbItem struct {
	MenuInfo  MenuInfo // 菜单信息
	Timestamp int64    // 进入时间戳
}

// BreadcrumbManager 面包屑管理器
// 负责管理菜单导航历史和面包屑显示
type BreadcrumbManager struct {
	mu sync.RWMutex

	// 面包屑历史栈
	history []BreadcrumbItem

	// 菜单注册表（ID -> MenuInfo）
	menuRegistry map[string]MenuInfo

	// 最大历史记录数
	maxHistory int

	// 调试模式
	debug bool

	// 日志输出函数
	logFunc func(format string, args ...interface{})
}

// NewBreadcrumbManager 创建新的面包屑管理器
func NewBreadcrumbManager() *BreadcrumbManager {
	bm := &BreadcrumbManager{
		history:      make([]BreadcrumbItem, 0),
		menuRegistry: make(map[string]MenuInfo),
		maxHistory:   50,
		debug:        true,
	}

	// 注册系统内置菜单
	bm.registerBuiltInMenus()

	return bm
}

// SetLogFunc 设置日志输出函数
func (bm *BreadcrumbManager) SetLogFunc(logFunc func(format string, args ...interface{})) {
	bm.logFunc = logFunc
}

// log 输出日志
func (bm *BreadcrumbManager) log(format string, args ...interface{}) {
	if bm.logFunc != nil {
		bm.logFunc(format, args...)
	}
}

// registerBuiltInMenus 注册内置菜单
func (bm *BreadcrumbManager) registerBuiltInMenus() {
	// 一级菜单
	bm.RegisterMenu(MenuInfo{ID: "", Name: "主菜单", ParentID: "", Level: 0})
	bm.RegisterMenu(MenuInfo{ID: "runmgmt", Name: "运行管理", ParentID: "", Level: 1})
	bm.RegisterMenu(MenuInfo{ID: "device", Name: "设备管理", ParentID: "", Level: 1})
	bm.RegisterMenu(MenuInfo{ID: "ag", Name: "AG管理", ParentID: "", Level: 1})
	bm.RegisterMenu(MenuInfo{ID: "config", Name: "配置管理", ParentID: "", Level: 1})
	bm.RegisterMenu(MenuInfo{ID: "build", Name: "生成构建代码", ParentID: "", Level: 1})
	bm.RegisterMenu(MenuInfo{ID: "help", Name: "帮助", ParentID: "", Level: 1})

	// 运行管理子菜单
	bm.RegisterMenu(MenuInfo{ID: "debugSecurity", Name: "调试安全选项", ParentID: "runmgmt", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "selectScript", Name: "选择脚本", ParentID: "runmgmt", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "symlinkRun", Name: "管理软连接", ParentID: "runmgmt", Level: 2})

	// 构建菜单子菜单
	bm.RegisterMenu(MenuInfo{ID: "scriptSecurity", Name: "脚本安全选项", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "scriptLoadMode", Name: "脚本加载模式", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "aesKey", Name: "生成AES密钥", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "rsaKey", Name: "生成RSA密钥", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "downloadExtract", Name: "下载并解压", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "processScripts", Name: "处理脚本文件", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "generateBuild", Name: "生成构建代码", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "scriptZip", Name: "脚本ZIP打包", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "symlinkBuild", Name: "管理软连接", ParentID: "build", Level: 2})
	bm.RegisterMenu(MenuInfo{ID: "viewConfig", Name: "查看当前配置", ParentID: "build", Level: 2})
}

// RegisterMenu 注册菜单
func (bm *BreadcrumbManager) RegisterMenu(info MenuInfo) {
	bm.mu.Lock()
	defer bm.mu.Unlock()
	bm.menuRegistry[info.ID] = info
}

// GetMenuInfo 获取菜单信息
func (bm *BreadcrumbManager) GetMenuInfo(id string) (MenuInfo, bool) {
	bm.mu.RLock()
	defer bm.mu.RUnlock()
	info, ok := bm.menuRegistry[id]
	return info, ok
}

// EnterMenu 进入菜单（自动压入面包屑）
// 返回是否成功压入
func (bm *BreadcrumbManager) EnterMenu(menuID string) bool {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	// 获取菜单信息
	info, ok := bm.menuRegistry[menuID]
	if !ok {
		// 如果菜单未注册，创建一个临时菜单信息
		info = MenuInfo{
			ID:       menuID,
			Name:     menuID,
			ParentID: "",
			Level:    1,
		}
	}

	// 检查是否已经在当前菜单（避免重复压入）
	if len(bm.history) > 0 {
		currentItem := bm.history[len(bm.history)-1]
		if currentItem.MenuInfo.ID == menuID {
			bm.log("[magenta][DEBUG] 面包屑: 已在菜单 %s，跳过压入[white]", menuID)
			return false
		}
	}

	// 压入历史
	item := BreadcrumbItem{
		MenuInfo:  info,
		Timestamp: time.Now().Unix(),
	}
	bm.history = append(bm.history, item)

	// 限制历史记录数
	if len(bm.history) > bm.maxHistory {
		bm.history = bm.history[1:]
	}

	// 调试：输出历史长度和内容（使用名称更易读）
	bm.log("[magenta][DEBUG] 面包屑压入: %s (层级:%d), 历史长度: %d, 历史: %v[white]",
		menuID, info.Level, len(bm.history), bm.getHistoryNamesUnsafe())

	return true
}

// ExitMenu 退出当前菜单（弹出面包屑）
// 返回上一个菜单ID
func (bm *BreadcrumbManager) ExitMenu() string {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	if len(bm.history) == 0 {
		bm.log("[magenta][DEBUG] 面包屑: 历史为空，返回主菜单[white]")
		return ""
	}

	// 弹出最后一个元素
	lastIndex := len(bm.history) - 1
	currentItem := bm.history[lastIndex]
	bm.history = bm.history[:lastIndex]

	// 获取新的当前菜单
	var previousMenuID string
	if len(bm.history) > 0 {
		previousMenuID = bm.history[len(bm.history)-1].MenuInfo.ID
	}

	bm.log("[magenta][DEBUG] 面包屑弹出: %s, 剩余历史长度: %d, 剩余历史: %v[white]",
		currentItem.MenuInfo.Name, len(bm.history), bm.getHistoryNamesUnsafe())

	return previousMenuID
}

// GetCurrentMenu 获取当前菜单ID
func (bm *BreadcrumbManager) GetCurrentMenu() string {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	if len(bm.history) == 0 {
		return ""
	}
	return bm.history[len(bm.history)-1].MenuInfo.ID
}

// GetPreviousMenu 获取上一个菜单ID
func (bm *BreadcrumbManager) GetPreviousMenu() string {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	if len(bm.history) <= 1 {
		return ""
	}
	return bm.history[len(bm.history)-2].MenuInfo.ID
}

// GetHistory 获取面包屑历史（只读）
func (bm *BreadcrumbManager) GetHistory() []BreadcrumbItem {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	history := make([]BreadcrumbItem, len(bm.history))
	copy(history, bm.history)
	return history
}

// GetBreadcrumbPath 获取面包屑路径字符串
func (bm *BreadcrumbManager) GetBreadcrumbPath() string {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	if len(bm.history) == 0 {
		return "主菜单"
	}

	path := ""
	for i, item := range bm.history {
		if i > 0 {
			path += " > "
		}
		path += item.MenuInfo.Name
	}
	return path
}

// ClearHistory 清空历史
func (bm *BreadcrumbManager) ClearHistory() {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	bm.history = make([]BreadcrumbItem, 0)
	bm.log("[magenta][DEBUG] 面包屑已清空[white]")
}

// NavigateTo 导航到指定菜单（清空历史并重新开始）
func (bm *BreadcrumbManager) NavigateTo(menuID string) {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	// 获取菜单信息
	info, ok := bm.menuRegistry[menuID]
	if !ok {
		info = MenuInfo{
			ID:       menuID,
			Name:     menuID,
			ParentID: "",
			Level:    1,
		}
	}

	// 清空历史
	bm.history = make([]BreadcrumbItem, 0)

	// 如果不是主菜单，先压入主菜单
	if menuID != "" {
		mainMenu := bm.menuRegistry[""]
		bm.history = append(bm.history, BreadcrumbItem{
			MenuInfo:  mainMenu,
			Timestamp: time.Now().Unix(),
		})
	}

	// 压入目标菜单
	bm.history = append(bm.history, BreadcrumbItem{
		MenuInfo:  info,
		Timestamp: time.Now().Unix(),
	})

	bm.log("[magenta][DEBUG] 面包屑导航到: %s, 历史: %v[white]",
		menuID, bm.getHistoryIDsUnsafe())
}

// getHistoryIDsUnsafe 获取历史ID列表（内部方法，需要已持有锁）
func (bm *BreadcrumbManager) getHistoryIDsUnsafe() []string {
	ids := make([]string, len(bm.history))
	for i, item := range bm.history {
		ids[i] = item.MenuInfo.ID
	}
	return ids
}

// getHistoryNamesUnsafe 获取历史名称列表（内部方法，需要已持有锁）
func (bm *BreadcrumbManager) getHistoryNamesUnsafe() []string {
	names := make([]string, len(bm.history))
	for i, item := range bm.history {
		names[i] = item.MenuInfo.Name
	}
	return names
}

// GetMenuLevel 获取当前菜单层级
func (bm *BreadcrumbManager) GetMenuLevel() int {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	if len(bm.history) == 0 {
		return 0
	}
	return bm.history[len(bm.history)-1].MenuInfo.Level
}

// IsInMenu 检查是否在指定菜单中
func (bm *BreadcrumbManager) IsInMenu(menuID string) bool {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	if len(bm.history) == 0 {
		return menuID == ""
	}
	return bm.history[len(bm.history)-1].MenuInfo.ID == menuID
}

// String 返回面包屑的字符串表示
func (bm *BreadcrumbManager) String() string {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	current := ""
	if len(bm.history) > 0 {
		current = bm.history[len(bm.history)-1].MenuInfo.ID
	}
	return fmt.Sprintf("BreadcrumbManager{current: %s, history: %v}",
		current, bm.getHistoryIDsUnsafe())
}
