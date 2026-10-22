package com.clma.highlight.languages

import com.clma.highlight.core.Language
import com.clma.highlight.languages.bash.bash
import com.clma.highlight.languages.c.c
import com.clma.highlight.languages.cmake.cmake
import com.clma.highlight.languages.cpp.cpp
import com.clma.highlight.languages.csharp.csharp
import com.clma.highlight.languages.css.css
import com.clma.highlight.languages.dart.dart
import com.clma.highlight.languages.diff.diff
import com.clma.highlight.languages.dockerfile.dockerfile
import com.clma.highlight.languages.go.go
import com.clma.highlight.languages.glsl.glsl
import com.clma.highlight.languages.ini.ini
import com.clma.highlight.languages.java.java
import com.clma.highlight.languages.javascript.javascript
import com.clma.highlight.languages.json.json
import com.clma.highlight.languages.kotlin.kotlin
import com.clma.highlight.languages.latex.latex
import com.clma.highlight.languages.lua.lua
import com.clma.highlight.languages.markdown.markdown
import com.clma.highlight.languages.php.php
import com.clma.highlight.languages.powershell.powershell
import com.clma.highlight.languages.properties.properties
import com.clma.highlight.languages.python.python
import com.clma.highlight.languages.rust.rust
import com.clma.highlight.languages.ruby.ruby
import com.clma.highlight.languages.sql.sql
import com.clma.highlight.languages.swift.swift
import com.clma.highlight.languages.typescript.typescript
import com.clma.highlight.languages.xml.xml
import com.clma.highlight.languages.yaml.yaml

/**
 * Every grammar bundled with the highlighter.
 *
 * Each entry builds a fresh mode tree: compilation mutates modes in place, mirroring `highlight.js`.
 */
internal fun builtinLanguages(): List<Language> = listOf(
    json(),
    ini(),
    cmake(),
    go(),
    glsl(),
    yaml(),
    bash(),
    dockerfile(),
    javascript(),
    typescript(),
    xml(),
    css(),
    dart(),
    java(),
    kotlin(),
    latex(),
    lua(),
    powershell(),
    properties(),
    python(),
    c(),
    cpp(),
    csharp(),
    sql(),
    diff(),
    markdown(),
    rust(),
    ruby(),
    php(),
    swift(),
)
