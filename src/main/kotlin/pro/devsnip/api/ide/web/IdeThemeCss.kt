package pro.devsnip.api.ide.web

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIManager

/**
 * DevSnip Pro's pages are styled with VS Code's theme variables
 * (`--vscode-editor-background`, `--vscode-button-background`, ...). In the
 * IDE those variables are filled from the current IntelliJ theme and editor
 * colour scheme, so "System Default" looks native in any IDE theme, exactly as
 * it follows the VS Code theme there.
 */
object IdeThemeCss {
    /** VS Code's body class for the theme kind. */
    fun bodyClass(): String = if (JBColor.isBright()) "vscode-light" else "vscode-dark"

    fun css(surface: String = "panel"): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val editorBg = scheme.defaultBackground
        val editorFg = scheme.defaultForeground
        val panelBg = JBUI.CurrentTheme.ToolWindow.background()
        val fg = UIUtil.getLabelForeground()
        val muted = UIUtil.getContextHelpForeground()
        val accent = JBUI.CurrentTheme.Button.defaultButtonColorStart()
        val accentFg = color("Button.default.foreground") ?: Color.WHITE
        val border = JBColor.border()
        val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val selection = JBUI.CurrentTheme.List.Selection.background(true)
        val selectionFg = JBUI.CurrentTheme.List.Selection.foreground(true)
        val hover = JBUI.CurrentTheme.List.Hover.background(true)
        val tooltipBg = UIUtil.getToolTipBackground()
        val tooltipFg = UIUtil.getToolTipForeground()
        val popupBg = JBUI.CurrentTheme.Popup.BACKGROUND
        val inputBg = UIUtil.getTextFieldBackground()
        val inputFg = UIUtil.getTextFieldForeground()
        val inputBorder = color("Component.borderColor") ?: border
        val focus = JBUI.CurrentTheme.Focus.focusColor()
        val error = UIUtil.getErrorForeground()
        val warning = color("Label.warningForeground") ?: if (JBColor.isBright()) Color(0xB0, 0x6E, 0x00) else Color(0xE0, 0xA0, 0x4A)
        val success = if (JBColor.isBright()) Color(0x2E, 0x7D, 0x32) else Color(0x6A, 0xAB, 0x73)
        val secondaryBg = JBUI.CurrentTheme.Button.buttonColorStart()
        val labelFont = UIUtil.getLabelFont()
        fun ansi(key: TextAttributesKey, fallback: Color) = scheme.getAttributes(key)?.foregroundColor ?: fallback
        fun syntax(key: TextAttributesKey, fallback: Color) = scheme.getAttributes(key)?.foregroundColor ?: fallback
        val searchMatch = scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
            ?: if (JBColor.isBright()) Color(0xF8, 0xC9, 0xAB) else Color(0x51, 0x5C, 0x6A)

        val vars = linkedMapOf(
            "font-family" to "\"${labelFont.family}\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", system-ui, sans-serif",
            "font-size" to "${labelFont.size}px",
            "editor-font-family" to "\"${scheme.editorFontName}\", \"JetBrains Mono\", Menlo, Consolas, monospace",
            "editor-background" to hex(editorBg),
            "editor-foreground" to hex(editorFg),
            "foreground" to hex(fg),
            "sideBar-background" to hex(panelBg),
            "sideBar-foreground" to hex(fg),
            "descriptionForeground" to hex(muted),
            "disabledForeground" to hex(UIUtil.getLabelDisabledForeground()),
            "icon-foreground" to hex(fg),
            "focusBorder" to hex(focus),
            "contrastBorder" to "transparent",
            "panel-border" to hex(border),
            "widget-shadow" to "rgba(0, 0, 0, ${if (JBColor.isBright()) "0.16" else "0.36"})",
            "button-background" to hex(accent),
            "button-foreground" to hex(accentFg),
            "button-hoverBackground" to hex(mix(accent, if (JBColor.isBright()) Color.BLACK else Color.WHITE, 0.1)),
            "button-border" to "transparent",
            "button-secondaryBackground" to hex(secondaryBg),
            "button-secondaryForeground" to hex(fg),
            "button-secondaryHoverBackground" to hex(mix(secondaryBg, fg, 0.08)),
            "input-background" to hex(inputBg),
            "input-foreground" to hex(inputFg),
            "input-border" to hex(inputBorder),
            "input-placeholderForeground" to hex(muted),
            "textLink-foreground" to hex(link),
            "textCodeBlock-background" to hex(mix(editorBg, editorFg, 0.06)),
            "list-hoverBackground" to hex(hover),
            // DevSnip draws selection as a light accent tint, so selected text keeps the strong foreground
            // (as VS Code's Light/Dark Modern themes do); IntelliJ's selection foreground is for solid fills.
            "list-activeSelectionForeground" to hex(fg),
            "list-highlightForeground" to hex(link),
            "tree-indentGuidesStroke" to hex(mix(panelBg, fg, 0.25)),
            "toolbar-hoverBackground" to hex(JBUI.CurrentTheme.ActionButton.hoverBackground()),
            "menu-background" to hex(popupBg),
            "menu-foreground" to hex(fg),
            "menu-border" to hex(border),
            "menu-selectionBackground" to hex(selection),
            "menu-selectionForeground" to hex(selectionFg),
            "editorWidget-background" to hex(popupBg),
            "editorHoverWidget-background" to hex(tooltipBg),
            "editorHoverWidget-foreground" to hex(tooltipFg),
            "editorHoverWidget-border" to hex(border),
            "notifications-background" to hex(JBUI.CurrentTheme.Notification.BACKGROUND),
            "notifications-foreground" to hex(JBUI.CurrentTheme.Notification.FOREGROUND),
            "notifications-border" to hex(border),
            "errorForeground" to hex(error),
            "editorWarning-foreground" to hex(warning),
            "editorInfo-foreground" to hex(link),
            "testing-iconPassed" to hex(success),
            "charts-purple" to hex(ansi(ConsoleHighlighterKeys.MAGENTA, Color(0xB1, 0x80, 0xD7))),
            "terminal-ansiBrightBlue" to hex(ansi(ConsoleHighlighterKeys.BRIGHT_BLUE, Color(0x3B, 0x8E, 0xEA))),
            "terminal-ansiBrightCyan" to hex(ansi(ConsoleHighlighterKeys.BRIGHT_CYAN, Color(0x29, 0xB8, 0xDB))),
            "terminal-ansiBrightGreen" to hex(ansi(ConsoleHighlighterKeys.BRIGHT_GREEN, Color(0x23, 0xD1, 0x8B))),
            "terminal-ansiBrightMagenta" to hex(ansi(ConsoleHighlighterKeys.BRIGHT_MAGENTA, Color(0xD6, 0x70, 0xD6))),
            "terminal-ansiBrightRed" to hex(ansi(ConsoleHighlighterKeys.BRIGHT_RED, Color(0xF1, 0x4C, 0x4C))),
            "terminal-ansiBrightWhite" to hex(ansi(ConsoleHighlighterKeys.BRIGHT_WHITE, if (JBColor.isBright()) Color(0xA5, 0xA5, 0xA5) else Color(0xE5, 0xE5, 0xE5))),
            "scrollbarSlider-background" to rgba(fg, 0.24),
            "scrollbarSlider-hoverBackground" to rgba(fg, 0.36),
            "scrollbarSlider-activeBackground" to rgba(fg, 0.48),
            "textLink-activeForeground" to hex(link),
            "textPreformat-foreground" to hex(editorFg),
            "textPreformat-background" to hex(mix(editorBg, editorFg, 0.08)),
            "textBlockQuote-background" to hex(mix(editorBg, editorFg, 0.04)),
            "textBlockQuote-border" to hex(border),
            "terminal-ansiBrightYellow" to hex(ansi(ConsoleHighlighterKeys.BRIGHT_YELLOW, if (JBColor.isBright()) Color(0xB5, 0xBA, 0x00) else Color(0xF5, 0xF5, 0x43))),
            // Used by the REST API Client page.
            "list-activeSelectionBackground" to hex(selection),
            "dropdown-background" to hex(inputBg),
            "charts-red" to hex(ansi(ConsoleHighlighterKeys.RED, Color(0xF1, 0x4C, 0x4C))),
            "charts-green" to hex(ansi(ConsoleHighlighterKeys.GREEN, Color(0x89, 0xD1, 0x85))),
            "charts-yellow" to hex(ansi(ConsoleHighlighterKeys.YELLOW, Color(0xCC, 0xA7, 0x00))),
            "charts-blue" to hex(ansi(ConsoleHighlighterKeys.BLUE, Color(0x3B, 0x8E, 0xEA))),
            "editor-findMatchBackground" to hex(searchMatch),
            "editor-findMatchHighlightBackground" to rgba(searchMatch, 0.45),
            "symbolIcon-propertyForeground" to hex(syntax(DefaultLanguageHighlighterColors.INSTANCE_FIELD, link)),
            "debugTokenExpression-name" to hex(syntax(DefaultLanguageHighlighterColors.INSTANCE_FIELD, link)),
            "debugTokenExpression-string" to hex(syntax(DefaultLanguageHighlighterColors.STRING, success)),
            "debugTokenExpression-number" to hex(syntax(DefaultLanguageHighlighterColors.NUMBER, link)),
            "debugTokenExpression-boolean" to hex(syntax(DefaultLanguageHighlighterColors.KEYWORD, warning)),
        )
        // The backdrop VS Code paints behind a webview (sidebar or editor), and VS Code's default webview stylesheet.
        val backdrop = if (surface == "sidebar") "--vscode-sideBar-background" else "--vscode-editor-background"
        return ":root {\n" + vars.entries.joinToString("\n") { (k, v) -> "  --vscode-$k: $v;" } + "\n}\n" + """
html { background-color: var($backdrop); scrollbar-color: var(--vscode-scrollbarSlider-background) var($backdrop); }
body { overscroll-behavior-x: none; color: var(--vscode-editor-foreground); font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); margin: 0; }
img, video { max-width: 100%; max-height: 100%; }
a, a code { color: var(--vscode-textLink-foreground); }
a:hover { color: var(--vscode-textLink-activeForeground); }
a:focus, input:focus, select:focus, textarea:focus { outline: 1px solid -webkit-focus-ring-color; outline-offset: -1px; }
code { font-family: var(--vscode-editor-font-family); color: var(--vscode-textPreformat-foreground); background-color: var(--vscode-textPreformat-background); padding: 1px 3px; border-radius: 4px; }
pre code { padding: 0; }
blockquote { background: var(--vscode-textBlockQuote-background); border-color: var(--vscode-textBlockQuote-border); }
::-webkit-scrollbar { width: 10px; height: 10px; }
::-webkit-scrollbar-corner { background-color: var($backdrop); }
::-webkit-scrollbar-thumb { background-color: var(--vscode-scrollbarSlider-background); }
::-webkit-scrollbar-thumb:hover { background-color: var(--vscode-scrollbarSlider-hoverBackground); }
::-webkit-scrollbar-thumb:active { background-color: var(--vscode-scrollbarSlider-activeBackground); }
"""
    }

    private fun color(key: String): Color? = UIManager.getColor(key)

    private fun mix(a: Color, b: Color, amount: Double): Color = Color(
        (a.red + (b.red - a.red) * amount).toInt().coerceIn(0, 255),
        (a.green + (b.green - a.green) * amount).toInt().coerceIn(0, 255),
        (a.blue + (b.blue - a.blue) * amount).toInt().coerceIn(0, 255),
    )

    private fun rgba(c: Color, alpha: Double) = "rgba(${c.red}, ${c.green}, ${c.blue}, $alpha)"

    private fun hex(c: Color): String =
        if (c.alpha == 255) "#%02x%02x%02x".format(c.red, c.green, c.blue) else "rgba(${c.red}, ${c.green}, ${c.blue}, ${"%.3f".format(java.util.Locale.ROOT, c.alpha / 255.0)})"

    /** The editor scheme's console ANSI colours (Settings | Editor | Color Scheme | Console Colors). */
    private object ConsoleHighlighterKeys {
        val MAGENTA: TextAttributesKey = TextAttributesKey.find("CONSOLE_MAGENTA_OUTPUT")
        val RED: TextAttributesKey = TextAttributesKey.find("CONSOLE_RED_OUTPUT")
        val GREEN: TextAttributesKey = TextAttributesKey.find("CONSOLE_GREEN_OUTPUT")
        val YELLOW: TextAttributesKey = TextAttributesKey.find("CONSOLE_YELLOW_OUTPUT")
        val BLUE: TextAttributesKey = TextAttributesKey.find("CONSOLE_BLUE_OUTPUT")
        val BRIGHT_BLUE: TextAttributesKey = TextAttributesKey.find("CONSOLE_BLUE_BRIGHT_OUTPUT")
        val BRIGHT_CYAN: TextAttributesKey = TextAttributesKey.find("CONSOLE_CYAN_BRIGHT_OUTPUT")
        val BRIGHT_GREEN: TextAttributesKey = TextAttributesKey.find("CONSOLE_GREEN_BRIGHT_OUTPUT")
        val BRIGHT_MAGENTA: TextAttributesKey = TextAttributesKey.find("CONSOLE_MAGENTA_BRIGHT_OUTPUT")
        val BRIGHT_RED: TextAttributesKey = TextAttributesKey.find("CONSOLE_RED_BRIGHT_OUTPUT")
        val BRIGHT_WHITE: TextAttributesKey = TextAttributesKey.find("CONSOLE_GRAY_OUTPUT")
        val BRIGHT_YELLOW: TextAttributesKey = TextAttributesKey.find("CONSOLE_YELLOW_BRIGHT_OUTPUT")
    }
}
