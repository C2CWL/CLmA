package com.clma.highlight.languages.ini

import com.clma.highlight.core.BACKSLASH_ESCAPE
import com.clma.highlight.core.Language
import com.clma.highlight.core.Mode
import com.clma.highlight.core.NUMBER_RE
import com.clma.highlight.core.comment
import com.clma.highlight.core.concat
import com.clma.highlight.core.either
import com.clma.highlight.core.lookahead
import com.clma.highlight.core.mode

/** TOML, also INI, ported from `lib/languages/ini.js` of `highlight.js` 11.11.1. */
internal fun ini(): Language {
    val numbers = mode {
        scope = "number"
        relevance = 0.0
        variants = listOf(
            { begin = """([+-]+)?[\d]+_[\d_]+""" },
            { begin = NUMBER_RE },
        )
    }
    val comments = comment().apply {
        variants = listOf(
            { begin = ";"; end = "$" },
            { begin = "#"; end = "$" },
        )
    }
    val variables = mode {
        scope = "variable"
        variants = listOf(
            { begin = """\$[\w\d"][\w\d_]*""" },
            { begin = """\$\{(.*?)\}""" },
        )
    }
    val literals = mode {
        scope = "literal"
        begin = """\bon|off|true|false|yes|no\b"""
    }
    val strings = mode {
        scope = "string"
        contains = listOf(BACKSLASH_ESCAPE)
        variants = listOf(
            { begin = "'''"; end = "'''"; relevance = 10.0 },
            { begin = "\"\"\""; end = "\"\"\""; relevance = 10.0 },
            { begin = "\""; end = "\"" },
            { begin = "'"; end = "'" },
        )
    }
    val array = mode {
        begin = """\["""
        end = """\]"""
        contains = listOf(comments, literals, variables, strings, numbers, Mode.SELF)
        relevance = 0.0
    }

    val bareKey = """[A-Za-z0-9_-]+"""
    val quotedKeyDoubleQuote = """"(\\"|[^"])*""""
    val quotedKeySingleQuote = """'[^']*'"""
    val anyKey = either(bareKey, quotedKeyDoubleQuote, quotedKeySingleQuote)
    val dottedKey = concat(anyKey, """(\s*\.\s*""", anyKey, ")*", lookahead("""\s*=\s*[^#\s]"""))

    return Language(
        name = "TOML, also INI",
        aliases = setOf("toml", "ini"),
        caseInsensitive = true,
        root = mode {
            illegal = """\S"""
            contains = listOf(
                comments,
                mode {
                    scope = "section"
                    begin = """\[+"""
                    end = """\]+"""
                },
                mode {
                    begin = dottedKey
                    scope = "attr"
                    starts = mode {
                        end = "$"
                        contains = listOf(comments, array, literals, variables, strings, numbers)
                    }
                },
            )
        },
    )
}
