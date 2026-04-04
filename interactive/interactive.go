package interactive

import (
	"bufio"
	"os"
	"strconv"
	"strings"

	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

// Menu 菜单项
type MenuItem struct {
	Key         string
	Label       string
	Description string
	Action      func() error
}

// Menu 菜单
type Menu struct {
	Title       string
	Items       []MenuItem
	Printer     *printer.Printer
	ClearScreen bool
}

// NewMenu 创建菜单
func NewMenu(title string, p *printer.Printer) *Menu {
	return &Menu{
		Title:       title,
		Items:       []MenuItem{},
		Printer:     p,
		ClearScreen: true,
	}
}

// AddItem 添加菜单项
func (m *Menu) AddItem(key, label, description string, action func() error) {
	m.Items = append(m.Items, MenuItem{
		Key:         key,
		Label:       label,
		Description: description,
		Action:      action,
	})
}

// Show 显示菜单并处理用户选择
func (m *Menu) Show() error {
	reader := bufio.NewReader(os.Stdin)

	for {
		if m.ClearScreen {
			m.printHeader()
		}

		// 显示菜单项
		for _, item := range m.Items {
			m.Printer.Info("  [%s] %s", item.Key, item.Label)
			if item.Description != "" {
				m.Printer.Debug("      %s", item.Description)
			}
		}
		m.Printer.Info("  [q] 退出")
		m.Printer.Println("")

		// 读取用户输入
		m.Printer.Prompt("请选择操作: ")
		input, _ := reader.ReadString('\n')
		input = strings.TrimSpace(strings.ToLower(input))

		// 处理退出
		if input == "q" || input == "quit" || input == "exit" {
			m.Printer.Info("再见!")
			return nil
		}

		// 查找并执行菜单项
		found := false
		for _, item := range m.Items {
			if strings.EqualFold(input, item.Key) {
				found = true
				m.Printer.Println("")
				if item.Action != nil {
					if err := item.Action(); err != nil {
						m.Printer.Error("执行失败: %v", err)
					}
				}
				m.Printer.Println("")
				m.Printer.Prompt("按回车键继续...")
				reader.ReadString('\n')
				break
			}
		}

		if !found {
			m.Printer.Warning("无效的选择: %s", input)
			m.Printer.Prompt("按回车键继续...")
			reader.ReadString('\n')
		}
	}
}

// printHeader 打印菜单头部
func (m *Menu) printHeader() {
	m.Printer.Println("")
	m.Printer.Success("╔══════════════════════════════════════════════╗")
	m.Printer.Success("║         %-36s ║", m.Title)
	m.Printer.Success("╚══════════════════════════════════════════════╝")
	m.Printer.Println("")
}

// InputReader 输入读取器
type InputReader struct {
	Reader  *bufio.Reader
	Printer *printer.Printer
}

// NewInputReader 创建输入读取器
func NewInputReader(p *printer.Printer) *InputReader {
	return &InputReader{
		Reader:  bufio.NewReader(os.Stdin),
		Printer: p,
	}
}

// ReadString 读取字符串
func (r *InputReader) ReadString(prompt, defaultValue string) string {
	if defaultValue != "" {
		r.Printer.Prompt("%s [%s]: ", prompt, defaultValue)
	} else {
		r.Printer.Prompt("%s: ", prompt)
	}

	input, _ := r.Reader.ReadString('\n')
	input = strings.TrimSpace(input)

	if input == "" {
		return defaultValue
	}
	return input
}

// ReadInt 读取整数
func (r *InputReader) ReadInt(prompt string, defaultValue int) int {
	r.Printer.Prompt("%s [%d]: ", prompt, defaultValue)

	input, _ := r.Reader.ReadString('\n')
	input = strings.TrimSpace(input)

	if input == "" {
		return defaultValue
	}

	val, err := strconv.Atoi(input)
	if err != nil {
		r.Printer.Warning("无效的数字，使用默认值: %d", defaultValue)
		return defaultValue
	}
	return val
}

// ReadBool 读取布尔值
func (r *InputReader) ReadBool(prompt string, defaultValue bool) bool {
	defaultStr := "n"
	if defaultValue {
		defaultStr = "y"
	}

	r.Printer.Prompt("%s [y/n, 默认: %s]: ", prompt, defaultStr)

	input, _ := r.Reader.ReadString('\n')
	input = strings.TrimSpace(strings.ToLower(input))

	if input == "" {
		return defaultValue
	}

	return input == "y" || input == "yes"
}

// ReadChoice 读取选择
func (r *InputReader) ReadChoice(prompt string, choices []string, defaultIndex int) int {
	r.Printer.Info("%s:", prompt)
	for i, choice := range choices {
		marker := " "
		if i == defaultIndex {
			marker = "*"
		}
		r.Printer.Info("  [%d] %s %s", i+1, marker, choice)
	}

	for {
		r.Printer.Prompt("请选择 [1-%d, 默认: %d]: ", len(choices), defaultIndex+1)

		input, _ := r.Reader.ReadString('\n')
		input = strings.TrimSpace(input)

		if input == "" {
			return defaultIndex
		}

		val, err := strconv.Atoi(input)
		if err != nil || val < 1 || val > len(choices) {
			r.Printer.Warning("无效的选择，请重新输入")
			continue
		}

		return val - 1
	}
}

// Confirm 确认操作
func (r *InputReader) Confirm(prompt string) bool {
	r.Printer.Prompt("%s [y/n]: ", prompt)

	input, _ := r.Reader.ReadString('\n')
	input = strings.TrimSpace(strings.ToLower(input))

	return input == "y" || input == "yes"
}

// Pause 暂停等待用户输入
func (r *InputReader) Pause() {
	r.Printer.Prompt("按回车键继续...")
	r.Reader.ReadString('\n')
}
