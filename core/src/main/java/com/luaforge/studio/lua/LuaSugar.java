package com.luaforge.studio.lua;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lua 源码语法糖改写:命名参数。
 *
 * <p>Lua 官方语法不支持在函数调用里写 {@code name = value}:
 * <pre>
 *   Text("hi", size = 20)        -- 语法错误
 *   Text("hi", { size = 20 })    -- 官方写法
 * </pre>
 * 本类在源码加载阶段把前者改写成后者,使 Compose DSL 可以用更自然的形式书写。
 *
 * <h3>改写规则</h3>
 * 在一个函数调用的实参列表里,一旦出现第一个顶层 {@code name = value},
 * 就从该位置起把剩余实参包进一个表构造: {@code f(a, x = 1, y = 2)} ->
 * {@code f(a, {x = 1, y = 2})}。因此命名参数必须写在位置参数之后。
 *
 * <h3>绝不改写的场合</h3>
 * <ul>
 *   <li>字符串(含长括号)、注释里的文本;</li>
 *   <li>赋值语句 {@code x = 1}(不在实参列表里);</li>
 *   <li>比较运算 {@code ==} {@code <=} {@code >=} {@code ~=};</li>
 *   <li>函数体内的语句 —— {@code f(function() x = 1 end)} 里的 {@code x = 1}
 *       是语句而非实参,原样保留。</li>
 * </ul>
 * 判定依靠对括号栈的跟踪:只有当 <em>栈顶</em> 是一个实参左括号 {@code (} 时
 * 才视为命名参数;任何表构造 {@code {} 或语句块(function/if/do/repeat)压栈后,
 * 栈顶就不再是它,从而自动排除。
 */
public final class LuaSugar {

    private LuaSugar() {
    }

    /** 栈条目:实参括号 / 表构造 / 语句块。 */
    private static final int KIND_PAREN = 0;
    private static final int KIND_BRACE = 1;
    private static final int KIND_FUNCTION = 2;
    private static final int KIND_BLOCK = 3;

    /** 单文件改写上限:超大文件不做语法糖,避免拖慢加载。 */
    private static final long MAX_FILE_SIZE = 16L * 1024 * 1024;

    private static final class Frame {
        int kind;
        int open;          // '(' 的下标
        int lastComma;     // 最近一个 ',' 的下标(初始为 '(' 的下标)
        boolean named;     // 已经开启命名参数表

        Frame(int kind, int open) {
            this.kind = kind;
            this.open = open;
            this.lastComma = open;
        }
    }

    /** 关键字:它们不是"变量名",后面跟 '=' 不算命名参数。 */
    private static boolean isKeyword(String w) {
        switch (w) {
            case "and": case "break": case "do": case "else": case "elseif":
            case "end": case "false": case "for": case "function": case "if":
            case "in": case "local": case "nil": case "not": case "or":
            case "repeat": case "return": case "then": case "true": case "until":
            case "while":
                return true;
            default:
                return false;
        }
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9');
    }

    /**
     * 改写源码。
     *
     * @param src 原始 Lua 源码
     * @return 改写后的源码;若无改动返回 {@code null}(调用方可直接跳过重编译)
     */
    public static String rewrite(String src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        final int n = src.length();
        List<Frame> stack = new ArrayList<Frame>();
        List<int[]> braceClose = new ArrayList<int[]>();   // {位置, 字符}
        List<int[]> braceOpen = new ArrayList<int[]>();
        int i = 0;

        while (i < n) {
            char c = src.charAt(i);

            // ---- 长括号字符串 / 长注释 ----
            if (c == '[') {
                int j = i + 1;
                int eq = 0;
                while (j < n && src.charAt(j) == '=') {
                    eq++;
                    j++;
                }
                if (j < n && src.charAt(j) == '[') {
                    i = skipLongBracket(src, j + 1, eq);
                    continue;
                }
            }

            // ---- 短字符串 ----
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == q) {
                        i++;
                        break;
                    }
                    if (d == '\n') {   // 未闭合字符串:尊重源码,不越界
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // ---- 注释 ----
            if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
                int j = i + 2;
                int eq = 0;
                while (j < n && src.charAt(j) == '=') {
                    eq++;
                    j++;
                }
                if (j < n && src.charAt(j) == '[') {
                    i = skipLongBracket(src, j + 1, eq);
                    continue;
                }
                int k = src.indexOf('\n', i);
                i = (k < 0) ? n : k + 1;
                continue;
            }

            // ---- 结构性符号 ----
            if (c == '(') {
                stack.add(new Frame(KIND_PAREN, i));
                i++;
                continue;
            }
            if (c == '{') {
                stack.add(new Frame(KIND_BRACE, i));
                i++;
                continue;
            }
            if (c == '}') {
                popTo(stack, KIND_BRACE);
                i++;
                continue;
            }
            if (c == ',') {
                if (!stack.isEmpty() && stack.get(stack.size() - 1).kind == KIND_PAREN) {
                    stack.get(stack.size() - 1).lastComma = i;
                }
                i++;
                continue;
            }
            if (c == ')') {
                if (!stack.isEmpty() && stack.get(stack.size() - 1).kind == KIND_PAREN) {
                    Frame f = stack.remove(stack.size() - 1);
                    if (f.named) {
                        braceClose.add(new int[]{i, '}'});
                    }
                }
                i++;
                continue;
            }

            // ---- 标识符 / 关键字 ----
            if (isNameStart(c)) {
                int j = i;
                while (j < n && isNameChar(src.charAt(j))) {
                    j++;
                }
                String w = src.substring(i, j);

                if ("function".equals(w)) {
                    stack.add(new Frame(KIND_FUNCTION, i));
                    i = j;
                    continue;
                }
                if ("if".equals(w) || "do".equals(w) || "repeat".equals(w)) {
                    stack.add(new Frame(KIND_BLOCK, i));
                    i = j;
                    continue;
                }
                if ("end".equals(w)) {
                    popBlock(stack);
                    i = j;
                    continue;
                }
                if ("until".equals(w)) {
                    popBlock(stack);
                    i = j;
                    continue;
                }

                // 命名参数判定: name = value (非 ==)
                int k = j;
                while (k < n) {
                    char s = src.charAt(k);
                    if (s == ' ' || s == '\t' || s == '\r' || s == '\n') {
                        k++;
                    } else {
                        break;
                    }
                }
                boolean assign = k < n && src.charAt(k) == '='
                        && (k + 1 >= n || src.charAt(k + 1) != '=')
                        && (i == 0 || (src.charAt(i - 1) != '<' && src.charAt(i - 1) != '>'
                        && src.charAt(i - 1) != '~' && src.charAt(i - 1) != '='));
                if (!isKeyword(w) && assign
                        && !stack.isEmpty()
                        && stack.get(stack.size() - 1).kind == KIND_PAREN) {
                    Frame f = stack.get(stack.size() - 1);
                    if (!f.named) {
                        f.named = true;
                        braceOpen.add(new int[]{f.lastComma + 1, '{'});
                    }
                }
                i = j;
                continue;
            }

            i++;
        }

        if (braceOpen.isEmpty() && braceClose.isEmpty()) {
            return null;
        }

        // 合并插入点,按位置升序;同一位置时 '{' 在 '}' 之前。
        List<int[]> ins = new ArrayList<int[]>();
        ins.addAll(braceOpen);
        ins.addAll(braceClose);
        Collections.sort(ins, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) {
                    return a[0] < b[0] ? -1 : 1;
                }
                return a[1] - b[1];
            }
        });

        StringBuilder sb = new StringBuilder(n + ins.size() * 2);
        int prev = 0;
        for (int[] p : ins) {
            sb.append(src, prev, p[0]);
            sb.append((char) p[1]);
            prev = p[0];
        }
        sb.append(src, prev, n);
        return sb.toString();
    }

    /**
     * 字节级改写入口,供文件/缓冲区加载路径使用。
     *
     * <p>用 ISO-8859-1 做无损往返:该编码把每个字节映射成同值 char,
     * 改写过程只插入 ASCII 的 {@code {}/},
     * 因此 GBK 等非 UTF-8 源文件、以及任意注释内字节都不会被破坏。
     *
     * @param buff 原始字节
     * @return 改写后的字节;无改动返回 {@code null}
     */
    public static byte[] rewriteBytes(byte[] buff) {
        if (buff == null || buff.length == 0) {
            return null;
        }
        // 预编译字节码(Lua 签名 0x1B 'L' 'u' 'a'):绝不改写
        if (buff.length > 4 && (buff[0] & 0xFF) == 0x1B
                && buff[1] == 'L' && buff[2] == 'u' && buff[3] == 'a') {
            return null;
        }
        String src = new String(buff, java.nio.charset.StandardCharsets.ISO_8859_1);
        String out = rewrite(src);
        if (out == null) {
            return null;
        }
        return out.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * 读取文件并改写。
     *
     * @param fileName 源文件路径
     * @return 改写后的字节;无改动或读取失败返回 {@code null}
     */
    public static byte[] rewriteFile(String fileName) {
        java.io.FileInputStream in = null;
        try {
            java.io.File f = new java.io.File(fileName);
            if (!f.isFile() || f.length() > MAX_FILE_SIZE) {
                return null;
            }
            in = new java.io.FileInputStream(f);
            byte[] buff = new byte[(int) f.length()];
            int off = 0;
            while (off < buff.length) {
                int read = in.read(buff, off, buff.length - off);
                if (read < 0) {
                    break;
                }
                off += read;
            }
            if (off != buff.length) {
                return null;
            }
            return rewriteBytes(buff);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 关闭失败不影响主流程
                }
            }
        }
    }

    /** 跳过 {@code ]==]} 形式的长括号内容,返回结束后一位。 */
    private static int skipLongBracket(String src, int from, int eq) {
        int n = src.length();
        int i = from;
        while (i < n) {
            if (src.charAt(i) == ']') {
                int j = i + 1;
                int seen = 0;
                while (j < n && src.charAt(j) == '=' && seen < eq) {
                    seen++;
                    j++;
                }
                if (seen == eq && j < n && src.charAt(j) == ']') {
                    return j + 1;
                }
            }
            i++;
        }
        return n;
    }

    private static void popTo(List<Frame> stack, int kind) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).kind == kind) {
                while (stack.size() > i) {
                    stack.remove(stack.size() - 1);
                }
                return;
            }
        }
    }

    private static void popBlock(List<Frame> stack) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            int k = stack.get(i).kind;
            if (k == KIND_FUNCTION || k == KIND_BLOCK) {
                while (stack.size() > i) {
                    stack.remove(stack.size() - 1);
                }
                return;
            }
        }
    }
}
