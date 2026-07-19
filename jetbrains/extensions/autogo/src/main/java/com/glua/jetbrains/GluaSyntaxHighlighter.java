package com.glua.jetbrains;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class GluaSyntaxHighlighter extends SyntaxHighlighterBase {
    // 颜色键必须在高亮器实例化时创建；类静态初始化阶段访问颜色服务会触发 IDEA 线程断言。
    private final TextAttributesKey[] keyword = pack(key("GLUA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD));
    private final TextAttributesKey[] string = pack(key("GLUA_STRING", DefaultLanguageHighlighterColors.STRING));
    private final TextAttributesKey[] number = pack(key("GLUA_NUMBER", DefaultLanguageHighlighterColors.NUMBER));
    private final TextAttributesKey[] comment = pack(key("GLUA_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT));
    private final TextAttributesKey[] operator = pack(key("GLUA_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN));
    private final TextAttributesKey[] functionDeclaration = pack(key("GLUA_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION));
    private final TextAttributesKey[] functionCall = pack(key("GLUA_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL));
    private final TextAttributesKey[] builtinFunction = pack(key("GLUA_BUILTIN_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL));
    private final TextAttributesKey[] memberFunction = pack(key("GLUA_MEMBER_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL));
    private final TextAttributesKey[] library = pack(key("GLUA_LIBRARY", DefaultLanguageHighlighterColors.CLASS_REFERENCE));
    private final TextAttributesKey[] bad = pack(HighlighterColors.BAD_CHARACTER);

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new GluaLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == GluaTokenType.KEYWORD) {
            return keyword;
        }
        if (tokenType == GluaTokenType.STRING) {
            return string;
        }
        if (tokenType == GluaTokenType.NUMBER) {
            return number;
        }
        if (tokenType == GluaTokenType.COMMENT) {
            return comment;
        }
        if (tokenType == GluaTokenType.OPERATOR) {
            return operator;
        }
        if (tokenType == GluaTokenType.FUNCTION_DECLARATION) {
            return functionDeclaration;
        }
        if (tokenType == GluaTokenType.FUNCTION_CALL) {
            return functionCall;
        }
        if (tokenType == GluaTokenType.BUILTIN_FUNCTION) {
            return builtinFunction;
        }
        if (tokenType == GluaTokenType.MEMBER_FUNCTION) {
            return memberFunction;
        }
        if (tokenType == GluaTokenType.LIBRARY) {
            return library;
        }
        if (tokenType == GluaTokenType.BAD_CHARACTER) {
            return bad;
        }
        return TextAttributesKey.EMPTY_ARRAY;
    }

    private static TextAttributesKey key(String externalName, TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey(externalName, fallback);
    }
}
