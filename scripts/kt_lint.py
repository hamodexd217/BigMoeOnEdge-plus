#!/usr/bin/env python3
"""Offline sanity checks for the Kotlin sources (there is no kotlinc in the authoring environment).

This is NOT a compiler. It catches the mistakes that unreviewed Compose/Android code gets wrong most often:
  1. unbalanced (), [], {} (string/comment aware)
  2. imports of project classes/functions that do not exist
  3. symbols that are imported somewhere else in the project but used here without an import
  4. imports that are never used
Exit code 1 when a problem is found.
"""
import re, sys, pathlib, collections

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src")
files = sorted(p for p in ROOT.rglob("*.kt"))


def strip(src):
    """Return src with comments blanked and string contents blanked (keeps length/newlines)."""
    out = []
    i, n = 0, len(src)
    stack = []  # 'str' | 'raw' | 'tpl'(inside ${})  -> tracks brace depth for templates
    tpl_depth = []
    while i < n:
        c = src[i]
        top = stack[-1] if stack else None
        if top in (None, 'tpl'):
            if src.startswith("//", i):
                j = src.find("\n", i)
                j = n if j < 0 else j
                out.append(" " * (j - i)); i = j; continue
            if src.startswith("/*", i):
                depth, j = 1, i + 2
                while j < n and depth:
                    if src.startswith("/*", j): depth += 1; j += 2
                    elif src.startswith("*/", j): depth -= 1; j += 2
                    else: j += 1
                out.append("".join(ch if ch == "\n" else " " for ch in src[i:j])); i = j; continue
            if src.startswith('"""', i):
                stack.append('raw'); out.append('"""'); i += 3; continue
            if c == '"':
                stack.append('str'); out.append('"'); i += 1; continue
            if c == "'":
                m = re.match(r"'(\\.|[^\\'])'", src[i:i + 8]) or re.match(r"'\\u[0-9a-fA-F]{4}'", src[i:i + 8])
                if m:
                    out.append("'" + " " * (len(m.group(0)) - 2) + "'"); i += len(m.group(0)); continue
            if top == 'tpl':
                if c == '{': tpl_depth[-1] += 1
                elif c == '}':
                    tpl_depth[-1] -= 1
                    if tpl_depth[-1] == 0:
                        tpl_depth.pop(); stack.pop(); out.append("}"); i += 1; continue
            out.append(c); i += 1; continue
        # inside str / raw
        if top == 'str':
            if c == "\\": out.append("  "); i += 2; continue
            if c == '"': stack.pop(); out.append('"'); i += 1; continue
        if top == 'raw':
            if src.startswith('"""', i):
                j = i
                while j < n and src[j] == '"': j += 1
                run = j - i          # a run of n>=3 quotes: the last three close, the rest are content
                stack.pop(); out.append('"' * run); i = j; continue
        if src.startswith("${", i):
            stack.append('tpl'); tpl_depth.append(1); out.append("${"); i += 2; continue
        out.append("\n" if c == "\n" else " "); i += 1
    return "".join(out)


def balanced(code):
    pairs = {')': '(', ']': '[', '}': '{'}
    st = []
    line = 1
    for ch in code:
        if ch == "\n": line += 1
        elif ch in "([{": st.append((ch, line))
        elif ch in pairs:
            if not st or st[-1][0] != pairs[ch]:
                return f"unexpected '{ch}' at line {line}"
            st.pop()
    return f"unclosed '{st[-1][0]}' opened at line {st[-1][1]}" if st else None


decl_re = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|data|sealed|enum|annotation|open|abstract|inline|value|fun)\s+)*"
    r"(class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?([A-Za-z_]\w*)", re.M)

pkg_of, top_decls = {}, collections.defaultdict(set)
for f in files:
    s = strip(f.read_text(encoding="utf-8"))
    m = re.search(r"^package\s+([\w.]+)", s, re.M)
    pkg = m.group(1) if m else ""
    pkg_of[f] = pkg
    # top level = declarations at column 0
    for m in re.finditer(r"^((?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|data|sealed|enum|annotation|open|abstract|inline|value|const)\s+)*)(class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?([A-Za-z_]\w*)", s, re.M):
        top_decls[pkg].add(m.group(3))
    for m in re.finditer(r"^(?:(?:public|internal)\s+)?fun\s+interface\s+([A-Za-z_]\w*)", s, re.M):
        top_decls[pkg].add(m.group(1))
    # nested declarations importable as Outer.Inner are rare here; sealed subclasses are referenced qualified.

problems = []
# Symbols that need an explicit import but are easy to forget; checked even if no file imports them.
KNOWN = {n: "androidx.compose.foundation.layout." + n for n in (
    "width height size padding fillMaxWidth fillMaxSize fillMaxHeight widthIn heightIn imePadding "
    "navigationBarsPadding consumeWindowInsets Spacer Box Column Row FlowRow").split()}
KNOWN.update({n: "androidx.compose.foundation." + n for n in ("background clickable".split())})
KNOWN.update({"clip": "androidx.compose.ui.draw.clip", "alpha": "androidx.compose.ui.draw.alpha",
              "dp": "androidx.compose.ui.unit.dp", "sp": "androidx.compose.ui.unit.sp",
              "remember": "androidx.compose.runtime.remember", "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
              "mutableStateOf": "androidx.compose.runtime.mutableStateOf", "Composable": "androidx.compose.runtime.Composable",
              "Modifier": "androidx.compose.ui.Modifier", "Alignment": "androidx.compose.ui.Alignment",
              "MaterialTheme": "androidx.compose.material3.MaterialTheme", "Text": "androidx.compose.material3.Text",
              "Icon": "androidx.compose.material3.Icon", "Icons": "androidx.compose.material.icons.Icons",
              "Arrangement": "androidx.compose.foundation.layout.Arrangement"})
all_imports = dict(KNOWN)
for f in files:
    raw = f.read_text(encoding="utf-8")
    code = strip(raw)
    err = balanced(code)
    if err: problems.append(f"{f}: {err}")
    imports = re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", code, re.M)
    for full, alias in imports:
        name = alias or full.rsplit(".", 1)[1]
        all_imports.setdefault(name, full)
        if full.startswith("com.bigmoe.onedge.") and full != "com.bigmoe.onedge.R":  # R is generated by AGP
            pkg, sym = full.rsplit(".", 1)
            if sym not in top_decls.get(pkg, set()) and not any(
                    sym in ds for p, ds in top_decls.items() if p == pkg):
                # allow nested object members (Outer.Inner imports)
                outer_pkg, outer = pkg.rsplit(".", 1) if "." in pkg else ("", pkg)
                if outer not in top_decls.get(outer_pkg, set()):
                    problems.append(f"{f}: import of missing project symbol {full}")

# unused / missing imports
body_no_imports = lambda c: re.sub(r"^(import|package)\s.*$", "", c, flags=re.M)
SKIP_LOWER = {"getValue", "setValue", "provideDelegate", "map", "first", "filter", "collect", "update", "toList"}
for f in files:
    raw = f.read_text(encoding="utf-8")
    code = body_no_imports(strip(raw))
    imported = {}
    for full, alias in re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", strip(raw), re.M):
        imported[alias or full.rsplit(".", 1)[1]] = full
    same_pkg = top_decls.get(pkg_of[f], set())
    # unused
    for name, full in imported.items():
        if name in SKIP_LOWER or full.endswith(".*"): continue
        if not re.search(r"(?<![\w.])" + re.escape(name) + r"\b", code) and not re.search(r"\b" + re.escape(name) + r"\b", code):
            problems.append(f"{f}: unused import {full}")
    # missing
    declared_here = set(m.group(3) for m in re.finditer(r"\b(class|interface|object|fun|val|var)\s+(<[^>]*>\s*)?([A-Za-z_]\w*)", code))
    for name, full in all_imports.items():
        if not name or name in imported or name in same_pkg or name in declared_here or name in SKIP_LOWER: continue
        if name[0].isupper():
            pat = r"(?<![\w.])" + re.escape(name) + r"\b"
            # ignore qualified use like Foo.name
        else:
            pat = r"(?<![\w.])" + re.escape(name) + r"\s*[({]" if False else r"(?<![\w])(?:\.)" + re.escape(name) + r"\s*[({]"
        if re.search(pat, code):
            # capitalised names shadowed by a nested/sealed member (e.g. Text in AgentEvent) are common: only flag if
            # the file does not define a nested class with that name
            if name[0].isupper() and re.search(r"\b(class|object|interface)\s+" + re.escape(name) + r"\b", code):
                continue
            problems.append(f"{f}: '{name}' used but not imported (project imports it as {full})")

for p in problems: print(p)
print(f"checked {len(files)} files, {len(problems)} problem(s)")
sys.exit(1 if problems else 0)
