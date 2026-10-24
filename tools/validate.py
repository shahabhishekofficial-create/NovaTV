#!/usr/bin/env python3
"""Fast, dependency-free sanity checks that run before the Android build."""
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
RES = APP / "src" / "main" / "res"
errors = []

def fail(message):
    errors.append(message)

# Parse every XML file, including the manifest.
xml_files = [APP / "src" / "main" / "AndroidManifest.xml"] + list(RES.rglob("*.xml"))
for path in xml_files:
    try:
        ET.parse(path)
    except (ET.ParseError, OSError) as exc:
        fail(f"{path.relative_to(ROOT)}: XML: {exc}")

# Collect declared resources from file names and values XML.
resources = {kind: set() for kind in ("id", "string", "color", "drawable", "layout", "xml", "style")}
for path in RES.rglob("*"):
    if path.is_file() and path.parent.name != "values":
        kind = path.parent.name.split("-")[0]
        resources.setdefault(kind, set()).add(path.stem)
for path in RES.rglob("*.xml"):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for node in root.iter():
        if path.parent.name == "values" and node.get("name"):
            resources.setdefault(node.tag, set()).add(node.get("name"))
        for value in node.attrib.values():
            match = re.match(r"@\+id/([A-Za-z0-9_]+)", value)
            if match:
                resources["id"].add(match.group(1))

# Resource references in XML. Framework references are deliberately ignored.
xml_reference = re.compile(r"@(?!\+|android:)(id|string|color|drawable|layout|xml|style)/([A-Za-z0-9_.]+)")
for path in xml_files:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        fail(f"{path.relative_to(ROOT)}: {exc}")
        continue
    for kind, name in xml_reference.findall(text):
        if name not in resources.get(kind, set()):
            fail(f"{path.relative_to(ROOT)}: missing @{kind}/{name}")

# Strip Kotlin comments and quoted strings before balancing braces/parens.
def kotlin_code_only(text):
    out=[]; i=0; state="code"; block_depth=0
    while i < len(text):
        if state == "code":
            if text.startswith('"""',i): state="triple"; out.append('   '); i+=3
            elif text.startswith("//",i): state="line"; out.append('  '); i+=2
            elif text.startswith("/*",i): state="block"; block_depth=1; out.append('  '); i+=2
            elif text[i] == '"': state="string"; out.append(' '); i+=1
            elif text[i] == "'": state="char"; out.append(' '); i+=1
            else: out.append(text[i]); i+=1
        elif state == "line":
            if text[i] == "\n": state="code"; out.append("\n")
            else: out.append(" ")
            i+=1
        elif state == "block":
            if text.startswith("/*",i): block_depth+=1; out.append("  "); i+=2
            elif text.startswith("*/",i):
                block_depth-=1; out.append("  "); i+=2
                if block_depth == 0: state="code"
            else: out.append("\n" if text[i] == "\n" else " "); i+=1
        elif state == "triple":
            if text.startswith('"""',i): state="code"; out.append("   "); i+=3
            else: out.append("\n" if text[i] == "\n" else " "); i+=1
        else:
            if text[i] == "\\": out.append("  "); i+=2
            elif (state == "string" and text[i] == '"') or (state == "char" and text[i] == "'"):
                state="code"; out.append(" "); i+=1
            else: out.append("\n" if text[i] == "\n" else " "); i+=1
    if state in ("block", "string", "char", "triple"):
        fail("Kotlin: unterminated comment or string")
    return "".join(out)

kotlin_files = list((APP / "src").rglob("*.kt"))
for path in kotlin_files:
    text = path.read_text(encoding="utf-8")
    code = kotlin_code_only(text)
    for opening, closing in (("{","}"),("(",")"),("[","]")):
        depth=0
        for ch in code:
            if ch == opening: depth+=1
            elif ch == closing:
                depth-=1
                if depth < 0: break
        if depth != 0:
            fail(f"{path.relative_to(ROOT)}: unbalanced {opening}{closing}")
            break
    for kind,name in re.findall(r"\bR\.(id|string|color|drawable|layout|xml)\.([A-Za-z0-9_]+)",text):
        if name not in resources.get(kind,set()):
            fail(f"{path.relative_to(ROOT)}: missing R.{kind}.{name}")

# Guard the deliberately small runtime dependency set.
gradle = (APP / "build.gradle.kts").read_text(encoding="utf-8")
allowed = {
    "androidx.core:core-ktx", "androidx.recyclerview:recyclerview",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android",
    "androidx.media3:media3-exoplayer", "androidx.media3:media3-exoplayer-hls",
}
for configuration, dependency in re.findall(r'(implementation|api)\("([^"]+)"\)',gradle):
    group_name=":".join(dependency.split(":")[:2])
    if group_name not in allowed:
        fail(f"app/build.gradle.kts: disallowed runtime dependency {dependency}")
for forbidden in ("media3-ui", "coil", "glide", "picasso", "firebase", "leanback"):
    if re.search(r'implementation\("[^"]*'+re.escape(forbidden),gradle,re.I):
        fail(f"app/build.gradle.kts: forbidden dependency containing {forbidden}")
for required in ("minSdk = 21", "targetSdk = 34", "compileSdk = 35"):
    if required not in gradle: fail(f"app/build.gradle.kts: expected {required}")

# Verify the actual PNG dimensions, not just its file name.
banner = RES / "drawable-nodpi" / "tv_banner.png"
try:
    data=banner.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n": raise ValueError("not a PNG")
    width,height=struct.unpack(">II",data[16:24])
    if (width,height) != (320,180): fail(f"TV banner is {width}x{height}; expected 320x180")
except (OSError,ValueError,struct.error) as exc:
    fail(f"tv_banner.png: {exc}")

if errors:
    print("Validation failed:",file=sys.stderr)
    for error in errors: print(f" - {error}",file=sys.stderr)
    sys.exit(1)
print(f"OK: {len(xml_files)} XML files, {len(kotlin_files)} Kotlin files, resources, dependencies, and TV banner validated.")
