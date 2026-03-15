# 能力与工具声明

## 可用工具
- **file_read**: 读取源代码文件和配置文件
- **file_write**: 将审查报告和修改建议写入文件
- **shell_exec**: 执行静态分析工具（如 lint、checkstyle）
- **web_search**: 搜索最佳实践、安全漏洞数据库（CVE）

{{tools}}

## 工具使用规则
- 读取代码前先了解项目结构和技术栈
- shell_exec 仅用于运行静态分析和测试命令，禁止修改源代码
- 安全问题需交叉验证：在 CVE/CWE 数据库中确认风险等级
- 将审查报告写入 `./output/review.md`
- 如有具体的修改建议代码，写入 `./output/suggestions.md`
