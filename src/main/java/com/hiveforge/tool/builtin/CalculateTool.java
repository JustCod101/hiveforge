package com.hiveforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiveforge.tool.Tool;
import com.hiveforge.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CalculateTool — 安全的数学表达式计算器。
 *
 * 使用纯 Java 实现的表达式解析器（无外部依赖），支持：
 * - 基本运算：+ - * / % ^（幂）
 * - 括号：( )
 * - 常用函数：sqrt, abs, ceil, floor, round, log, log10, sin, cos, tan
 * - 常量：PI, E
 *
 * 安全措施：
 * - 不使用 eval() 或 ScriptEngine（防注入）
 * - 纯数学表达式解析，不支持变量赋值或函数定义
 * - 表达式长度限制
 */
public class CalculateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CalculateTool.class);

    /** 表达式最大长度 */
    private static final int MAX_EXPRESSION_LENGTH = 500;

    /** 允许的字符白名单 */
    private static final Pattern SAFE_PATTERN = Pattern.compile(
            "^[0-9+\\-*/%.()^, eEpiPIsqrtabsceilflooroundlgintanh]+$"
    );

    @Override
    public String getName() { return "calculate"; }

    @Override
    public String getDescription() {
        return "执行数学表达式计算。支持 +, -, *, /, %, ^（幂），括号，"
                + "以及函数 sqrt, abs, ceil, floor, round, log, log10, sin, cos, tan。"
                + "常量: PI, E。示例: '(100 - 80) / 80 * 100' 或 'sqrt(144) + log10(1000)'";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "expression": {
                  "type": "string",
                  "description": "数学表达式，如 '(100 - 80) / 80 * 100' 或 'sqrt(144)'"
                }
              },
              "required": ["expression"]
            }
            """;
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.SAFE; }

    @Override
    public ToolResult execute(JsonNode args, String workingDir) {
        String expression = args.path("expression").asText("").trim();

        if (expression.isEmpty()) {
            return new ToolResult(false, "Missing required parameter: expression");
        }

        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            return new ToolResult(false, "Expression too long (max " + MAX_EXPRESSION_LENGTH + " chars)");
        }

        try {
            // 预处理：替换常量
            String processed = expression
                    .replaceAll("\\bPI\\b", String.valueOf(Math.PI))
                    .replaceAll("\\bpi\\b", String.valueOf(Math.PI))
                    .replaceAll("(?<![0-9.])\\bE\\b(?![0-9+-])", String.valueOf(Math.E));

            double result = evaluate(processed);

            // 格式化结果：整数则不显示小数点
            String formatted;
            if (result == Math.floor(result) && !Double.isInfinite(result)
                    && Math.abs(result) < 1e15) {
                formatted = String.valueOf((long) result);
            } else {
                formatted = String.valueOf(result);
            }

            log.info("[Calculate] {} = {}", expression, formatted);
            return new ToolResult(true, expression + " = " + formatted);

        } catch (Exception e) {
            log.info("[Calculate] Error: {} — {}", expression, e.getMessage());
            return new ToolResult(false, "Calculation error: " + e.getMessage());
        }
    }

    // ============================================================
    // 递归下降表达式解析器（安全，无 eval）
    // ============================================================

    /**
     * 入口：解析并计算表达式。
     */
    private double evaluate(String expr) {
        ExpressionParser parser = new ExpressionParser(expr.trim());
        double result = parser.parseExpression();
        if (parser.pos < parser.chars.length) {
            throw new RuntimeException("Unexpected character: '" + parser.chars[parser.pos] + "'");
        }
        return result;
    }

    /**
     * 递归下降解析器 — 支持 +-^、函数调用、括号。
     *
     * 优先级（低→高）：
     * 1. + -
     * 2. * / %
     * 3. ^ (幂，右结合)
     * 4. 一元 + -
     * 5. 函数调用、括号、数字
     **/
    private static class ExpressionParser {
        final char[] chars;
        int pos = 0;

        ExpressionParser(String expr) {
            this.chars = expr.toCharArray();
        }

        void skipSpaces() {
            while (pos < chars.length && chars[pos] == ' ') pos++;
        }

        // Level 1: + -
        double parseExpression() {
            double result = parseTerm();
            skipSpaces();
            while (pos < chars.length && (chars[pos] == '+' || chars[pos] == '-')) {
                char op = chars[pos++];
                double term = parseTerm();
                result = op == '+' ? result + term : result - term;
                skipSpaces();
            }
            return result;
        }

        // Level 2: * / %
        double parseTerm() {
            double result = parsePower();
            skipSpaces();
            while (pos < chars.length && (chars[pos] == '*' || chars[pos] == '/' || chars[pos] == '%')) {
                char op = chars[pos++];
                double factor = parsePower();
                if (op == '*') result *= factor;
                else if (op == '/') {
                    if (factor == 0) throw new ArithmeticException("Division by zero");
                    result /= factor;
                } else {
                    if (factor == 0) throw new ArithmeticException("Modulo by zero");
                    result %= factor;
                }
                skipSpaces();
            }
            return result;
        }

        // Level 3: ^ (右结合)
        double parsePower() {
            double base = parseUnary();
            skipSpaces();
            if (pos < chars.length && chars[pos] == '^') {
                pos++;
                double exp = parsePower(); // 右结合：递归调用自身
                return Math.pow(base, exp);
            }
            return base;
        }

        // Level 4: 一元 + -
        double parseUnary() {
            skipSpaces();
            if (pos < chars.length && chars[pos] == '+') {
                pos++;
                return parseUnary();
            }
            if (pos < chars.length && chars[pos] == '-') {
                pos++;
                return -parseUnary();
            }
            return parseAtom();
        }

        // Level 5: 数字、括号、函数
        double parseAtom() {
            skipSpaces();

            // 括号
            if (pos < chars.length && chars[pos] == '(') {
                pos++; // skip '('
                double result = parseExpression();
                skipSpaces();
                if (pos < chars.length && chars[pos] == ')') {
                    pos++; // skip ')'
                } else {
                    throw new RuntimeException("Missing closing parenthesis");
                }
                return result;
            }

            // 函数名
            if (pos < chars.length && Character.isLetter(chars[pos])) {
                StringBuilder funcName = new StringBuilder();
                while (pos < chars.length && Character.isLetterOrDigit(chars[pos])) {
                    funcName.append(chars[pos++]);
                }
                skipSpaces();
                if (pos < chars.length && chars[pos] == '(') {
                    pos++; // skip '('
                    double arg = parseExpression();
                    skipSpaces();
                    if (pos < chars.length && chars[pos] == ')') {
                        pos++; // skip ')'
                    }
                    return applyFunction(funcName.toString(), arg);
                }
                throw new RuntimeException("Unknown identifier: " + funcName);
            }

            // 数字
            if (pos < chars.length && (Character.isDigit(chars[pos]) || chars[pos] == '.')) {
                StringBuilder num = new StringBuilder();
                while (pos < chars.length && (Character.isDigit(chars[pos]) || chars[pos] == '.')) {
                    num.append(chars[pos++]);
                }
                // 科学记数法 e/E
                if (pos < chars.length && (chars[pos] == 'e' || chars[pos] == 'E')) {
                    num.append(chars[pos++]);
                    if (pos < chars.length && (chars[pos] == '+' || chars[pos] == '-')) {
                        num.append(chars[pos++]);
                    }
                    while (pos < chars.length && Character.isDigit(chars[pos])) {
                        num.append(chars[pos++]);
                    }
                }
                return Double.parseDouble(num.toString());
            }

            throw new RuntimeException("Unexpected character at position " + pos);
        }

        double applyFunction(String name, double arg) {
            return switch (name.toLowerCase()) {
                case "sqrt"  -> Math.sqrt(arg);
                case "abs"   -> Math.abs(arg);
                case "ceil"  -> Math.ceil(arg);
                case "floor" -> Math.floor(arg);
                case "round" -> Math.round(arg);
                case "log"   -> Math.log(arg);
                case "log10" -> Math.log10(arg);
                case "log2"  -> Math.log(arg) / Math.log(2);
                case "sin"   -> Math.sin(arg);
                case "cos"   -> Math.cos(arg);
                case "tan"   -> Math.tan(arg);
                case "asin"  -> Math.asin(arg);
                case "acos"  -> Math.acos(arg);
                case "atan"  -> Math.atan(arg);
                case "exp"   -> Math.exp(arg);
                default -> throw new RuntimeException("Unknown function: " + name);
            };
        }
    }
}
