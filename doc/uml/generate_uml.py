#!/usr/bin/env python3
"""Regenerate the Phonalyser core-class UML diagram (doc/uml/phonalyser-classes.svg).

Usage (from the repo root):
    python doc/uml/generate_uml.py

The script scans the configured module packages, extracts classes and their
relations directly from the Java sources, writes phonalyser-classes.dot and
renders it to SVG with Graphviz dot (a portable Graphviz is auto-downloaded
to tmp/ on first run - no installation needed).

Relations drawn (strongest wins per ordered class pair), color-coded:
    green solid, hollow head   extends
    blue dashed, hollow head   implements
    black solid arrow          holds (field)
    orange dashed arrow        creates (new X)
    gray dashed arrow          uses (constructor parameter / static calls)

Scope: core functional classes only - views, panes, dialogs, widgets and
pure utilities are excluded via EXCLUDE_NAME_RE / per-module "exclude".
Backends: JavaSound (typical soundcard), QA40x (specific device) and the
network backend with its server. Adjust MODULES below when the app changes.
"""

import re
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = Path(__file__).resolve().parent
DOT_FILE = OUT_DIR / "phonalyser-classes.dot"
SVG_FILE = OUT_DIR / "phonalyser-classes.svg"
GRAPHVIZ_VERSION = "12.2.1"
GRAPHVIZ_DIR = REPO / "tmp" / f"Graphviz-{GRAPHVIZ_VERSION}-win64"
GRAPHVIZ_URL = ("https://gitlab.com/api/v4/projects/4207231/packages/generic/"
                f"graphviz-releases/{GRAPHVIZ_VERSION}/"
                f"windows_10_cmake_Release_Graphviz-{GRAPHVIZ_VERSION}-win64.zip")

JAVA = "org/edgo/audio/measure"

# ---------------------------------------------------------------------------
# Configuration: which module shows which packages, minus per-module excludes.
# New classes in these packages appear automatically on regeneration.
# ---------------------------------------------------------------------------

# "layer" fixes the vertical placement of the module rectangles (1 = top);
# modules sharing a layer sit side by side.
MODULES = {
    "phonalyser-core": {
        "layer": 5,
        "packages": [
            f"modules/phonalyser-core/src/main/java/{JAVA}/sound",
            f"modules/phonalyser-core/src/main/java/{JAVA}/generator",
            f"modules/phonalyser-core/src/main/java/{JAVA}/fft",
            f"modules/phonalyser-core/src/main/java/{JAVA}/preferences",
        ],
        # flagship DSP classes only - the full dsp package would drown the diagram
        "extra_files": [
            f"modules/phonalyser-core/src/main/java/{JAVA}/dsp/SpectralDiscontinuityDetector.java",
            f"modules/phonalyser-core/src/main/java/{JAVA}/dsp/TimeDiscontinuityDetector.java",
            f"modules/phonalyser-core/src/main/java/{JAVA}/dsp/ToneLobeLift.java",
            f"modules/phonalyser-core/src/main/java/{JAVA}/dsp/MainsFilters.java",
            f"modules/phonalyser-core/src/main/java/{JAVA}/dsp/FreqRespCalibration.java",
            f"modules/phonalyser-core/src/main/java/{JAVA}/dsp/StereoFreqRespCalibration.java",
        ],
        "exclude": [
            # low-level plumbing / utilities
            "SpscByteArrayRing", "PcmQuantizer", "StereoCaptureProgress",
            "DeviceLossReporter", "DeviceTeardown", "Closeables", "MathUtil",
            "HarmonicsCsv", "FftResultPool",
            # PortAudio backends are out of the requested scope
            "PortAudio", "AbstractPortAudioCapture", "AbstractPortAudioPlayback",
            # preference value objects with no interesting relations
            "FftPreset", "OscPreset", "FreqRespPreset", "FreqRespFilterTypeParams",
            "CalibrationEntry", "BackendKey", "BackendPrefs", "DeviceRange",
            "DeviceEndpointConfig",
        ],
    },
    "phonalyser-gui-core": {
        "layer": 3,
        "packages": [],
        "extra_files": [
            f"modules/phonalyser-gui-core/src/main/java/{JAVA}/gui/bus/MessageBus.java",
            f"modules/phonalyser-gui-core/src/main/java/{JAVA}/gui/common/BackendSettingsRegistry.java",
            f"modules/phonalyser-gui-core/src/main/java/{JAVA}/gui/common/BackendSettingsUi.java",
            f"modules/phonalyser-gui-core/src/main/java/{JAVA}/gui/common/RemoteBackendRegistry.java",
            f"modules/phonalyser-gui-core/src/main/java/{JAVA}/gui/common/RemoteBackendUi.java",
            f"modules/phonalyser-gui-core/src/main/java/{JAVA}/gui/sound/PlaybackLane.java",
        ],
        "exclude": [],
    },
    "phonalyser-gui": {
        "layer": 1,
        "packages": [
            f"modules/phonalyser-gui/src/main/java/{JAVA}/gui/fft",
            f"modules/phonalyser-gui/src/main/java/{JAVA}/gui/scope",
            f"modules/phonalyser-gui/src/main/java/{JAVA}/gui/generator",
            f"modules/phonalyser-gui/src/main/java/{JAVA}/gui/freqresp",
            f"modules/phonalyser-gui/src/main/java/{JAVA}/gui/sound",
        ],
        "exclude": [
            # views / rendering / IO helpers / value holders
            "PhosphorRenderer", "TraceEnvelope", "ZoomedView", "OscParse",
            "ScopeFileSaver", "ScopeOpenSignal", "StereoPcmIo", "ScopeFormat",
            "MeasurementRow", "MeasurementStats", "SignalMeasurements",
            "AmplitudeHistogram", "SignalFileExporter", "WavSignalExporter",
            "FreqRespFormat", "FreqRespLiveMeter", "RiaaCurve", "Cancellable",
            "ProgressCallback", "RawCaptureListener", "FreqRespSweepParams",
            "FreqRespAnalyzerConfig", "BenchCards", "CalibrationCopyOffers",
            "FrequencyAlignerFactory", "SignalBufferReader",
            # only related to excluded views
            "ScopeNav",
        ],
    },
    "backend-javasound": {
        "layer": 4,
        "packages": [
            f"modules/backend-javasound/src/main/java/{JAVA}/sound/javasound",
        ],
        "exclude": ["ProcAsound", "CsjsoundNativePath"],
    },
    "backend-qa40x": {
        "layer": 4,
        "packages": [
            f"modules/backend-qa40x/src/main/java/{JAVA}/sound/qa40x",
        ],
        "exclude": [],
    },
    "backend-qa40x-gui": {
        "layer": 2,
        "packages": [
            f"modules/backend-qa40x-gui/src/main/java/{JAVA}/gui/backend/qa40x",
        ],
        "exclude": [],
    },
    "backend-net-gui": {
        "layer": 2,
        "packages": [
            f"modules/backend-net-gui/src/main/java/{JAVA}/gui/backend/net",
        ],
        "exclude": [],
    },
    "server-net": {
        "layer": 1,
        "packages": [
            "modules/server-net/src/main/java/org/edgo/audio/measure/net/server",
        ],
        "exclude": [
            # scheduling / plumbing helpers and the launcher
            "Ticker", "ScheduledTicker", "NetException", "ServerMain",
        ],
    },
    "backend-net": {
        "layer": 4,
        "packages": [
            "modules/backend-net/src/main/java/org/edgo/audio/measure/net/client",
        ],
        "exclude": [
            # scheduling helpers
            "Ticker", "ScheduledTicker",
        ],
    },
}

# One fill color per layer - modules in the same row share it.
LAYER_COLORS = {
    1: "#EDFBEF",   # applications (desktop GUI, measurement server)
    2: "#FBF4EE",   # per-backend GUI plug-ins
    3: "#EEF4FB",   # GUI core
    4: "#F3EEFB",   # audio backends
    5: "#FDF6E3",   # measurement core
}

# Views, panes, dialogs, widgets, tabs etc. never belong on this diagram,
# whatever package they live in.
EXCLUDE_NAME_RE = re.compile(
    r".*(View|Pane|Dialog|Widget|Tab|TabControl|Window|Splash|Wizard|Painter)$")

# ---------------------------------------------------------------------------
# Java source parsing (regex-based, good enough for this codebase's style)
# ---------------------------------------------------------------------------

DECL_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public\s+)?(?P<abstract>abstract\s+)?(?:final\s+)?(?:sealed\s+)?"
    r"(?P<kind>class|interface|enum|record)\s+(?P<name>\w+)\s*(?:<[^>{]*>)?"
    r"(?:\([^)]*\))?\s*"
    r"(?:extends\s+(?P<extends>[\w.<>,\s]+?))?\s*"
    r"(?:implements\s+(?P<implements>[\w.<>,\s]+?))?\s*\{",
    re.MULTILINE)

TYPE_NAME_RE = re.compile(r"\b([A-Z]\w*)\b")


def strip_comments_and_strings(src: str) -> str:
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.DOTALL)
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    src = re.sub(r"'(?:\\.|[^'\\])'", "' '", src)
    return src


def split_type_list(s):
    """'A, B<C, D>, E.F' -> ['A', 'B', 'E'] (outer simple names only)."""
    if not s:
        return []
    names, depth, cur = [], 0, ""
    for ch in s:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        elif ch == "," and depth == 0:
            names.append(cur)
            cur = ""
            continue
        if depth == 0 and ch not in "<>":
            cur += ch
    names.append(cur)
    return [n.strip().split("<")[0].split(".")[0] for n in names if n.strip()]


class JavaClass:
    def __init__(self, name, kind, is_abstract, module):
        self.name = name
        self.kind = kind            # class | interface | enum | record
        self.is_abstract = is_abstract
        self.module = module
        self.extends = []           # simple names
        self.implements = []
        self.field_types = set()    # types of member fields
        self.ctor_param_types = set()
        self.created_types = set()  # new X(...)
        self.used_types = set()     # any other reference in the body


def parse_java(path: Path, module: str):
    src = strip_comments_and_strings(path.read_text(encoding="utf-8", errors="replace"))
    m = DECL_RE.search(src)
    if not m:
        return None
    cls = JavaClass(m.group("name"), m.group("kind"),
                    bool(m.group("abstract")), module)
    if path.stem != cls.name:      # safety: primary type only
        cls.name = path.stem
    ext = split_type_list(m.group("extends"))
    imp = split_type_list(m.group("implements"))
    if cls.kind == "interface":
        cls.implements = ext + imp  # interface 'extends' drawn as realization
    else:
        cls.extends, cls.implements = ext, imp

    body = src[m.end():]

    # member fields: brace-depth 1 statements ending in ';' without '(' before '='
    depth, stmt = 1, ""
    for ch in body:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            stmt = ""
            continue
        if depth == 1:
            stmt += ch
            if ch == ";":
                decl = stmt.split("=")[0]
                if "(" not in decl:
                    fm = re.match(
                        r"\s*(?:(?:public|protected|private|static|final|"
                        r"transient|volatile)\s+)*"
                        r"([A-Z][\w.]*(?:\s*<[^;]*>)?)(?:\[\])?\s+\w+\s*$",
                        decl.strip().rstrip(";").rstrip())
                    if fm:
                        for t in TYPE_NAME_RE.findall(fm.group(1)):
                            cls.field_types.add(t)
                stmt = ""
        elif depth == 2 and ch == "{":
            # header of a member (method/ctor) just ended in stmt
            hm = re.match(r".*?\b" + re.escape(cls.name) + r"\s*\(([^)]*)\)",
                          stmt, re.DOTALL)
            if hm and "return" not in stmt:
                for t in TYPE_NAME_RE.findall(hm.group(1)):
                    cls.ctor_param_types.add(t)
            stmt = ""

    for t in re.findall(r"\bnew\s+([A-Z]\w*)\s*[(<]", body):
        cls.created_types.add(t)
    for t in TYPE_NAME_RE.findall(body):
        cls.used_types.add(t)
    return cls


# ---------------------------------------------------------------------------
# Collect classes
# ---------------------------------------------------------------------------

def collect():
    classes = {}
    for module, cfg in MODULES.items():
        files = []
        for pkg in cfg["packages"]:
            files += sorted((REPO / pkg).glob("*.java"))
        for f in cfg.get("extra_files", []):
            p = REPO / f
            if p.exists():
                files.append(p)
            else:
                print(f"WARNING: extra file gone, update config: {f}")
        for f in files:
            name = f.stem
            if (name == "package-info" or name in cfg["exclude"]
                    or EXCLUDE_NAME_RE.match(name)):
                continue
            cls = parse_java(f, module)
            if cls is None:
                print(f"WARNING: could not parse declaration in {f}")
                continue
            if cls.kind == "enum":
                continue           # enums excluded from this diagram
            classes[cls.name] = cls
    return classes


# ---------------------------------------------------------------------------
# Emit Graphviz DOT
# ---------------------------------------------------------------------------

# classes with no relations inside their own module are stacked into
# invisible columns of this many rows (keeps the module boxes compact)
FLAT_COLUMN_ROWS = 4

EDGE_STYLES = {
    # rank: (attrs) - lower rank wins per ordered class pair
    0: 'color="#1B7E3C" penwidth=1.8 arrowhead=empty arrowsize=1.3',   # extends
    1: 'color="#1565C0" style=dashed arrowhead=empty arrowsize=1.3',   # implements
    2: 'color="#222222" arrowhead=vee',                                # holds
    3: 'color="#E65100" style=dashed arrowhead=vee',                   # creates
    4: 'color="#999999" style=dashed arrowhead=vee',                   # uses
}

LEGEND = """legend [shape=none margin=0 label=<
  <table border="1" cellborder="0" cellspacing="0" cellpadding="4">
    <tr><td align="left"><b>arrow</b></td><td align="left"><b>meaning</b></td></tr>
    <tr><td align="left"><font color="#1B7E3C">solid, hollow head, green</font></td><td align="left">extends</td></tr>
    <tr><td align="left"><font color="#1565C0">dashed, hollow head, blue</font></td><td align="left">implements</td></tr>
    <tr><td align="left"><font color="#222222">solid arrow, black</font></td><td align="left">holds (field)</td></tr>
    <tr><td align="left"><font color="#E65100">dashed arrow, orange</font></td><td align="left">creates</td></tr>
    <tr><td align="left"><font color="#999999">dashed arrow, gray</font></td><td align="left">uses</td></tr>
  </table>>]"""

def strongly_connected(nodes, edges):
    """Tarjan SCC; returns node -> component id."""
    adj = {n: [] for n in nodes}
    for s, d in edges:
        adj[s].append(d)
    index, low, comp_of = {}, {}, {}
    stack, on_stack = [], set()
    counter = [0]

    def strong(v):
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on_stack.add(v)
        for w in adj[v]:
            if w not in index:
                strong(w)
                low[v] = min(low[v], low[w])
            elif w in on_stack:
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            while True:
                w = stack.pop()
                on_stack.discard(w)
                comp_of[w] = v
                if w == v:
                    break

    for n in nodes:
        if n not in index:
            strong(n)
    return comp_of


def node_label(c):
    if c.kind == "interface":
        return f'<&#171;interface&#187;<br/><i>{c.name}</i>>'
    if c.kind == "record":
        return f'<&#171;record&#187;<br/>{c.name}>'
    if c.is_abstract:
        return f'<&#171;abstract&#187;<br/><i>{c.name}</i>>'
    return f'"{c.name}"'


def emit(classes):
    lines = [
        "// GENERATED by doc/uml/generate_uml.py - do not edit by hand",
        "digraph phonalyser {",
        '  graph [rankdir=TB newrank=true compound=true fontname="Verdana"',
        '         label="Phonalyser - core functional classes by module"',
        '         labelloc=t fontsize=22 nodesep=0.25 ranksep=0.6',
        '         splines=spline outputorder=edgesfirst bgcolor=white]',
        '  node  [shape=box style="rounded,filled" fillcolor=white',
        '         color="#666666" fontname="Verdana" fontsize=12',
        '         margin="0.12,0.05" height=0.35]',
        '  edge  [fontname="Verdana" fontsize=10]',
        "",
    ]

    for i, (module, cfg) in enumerate(MODULES.items()):
        members = sorted((c for c in classes.values() if c.module == module),
                         key=lambda c: c.name)
        if not members:
            continue
        lines.append(f'  subgraph cluster_{i} {{')
        lines.append(f'    label="{module}" fontsize=16 labelloc=t')
        lines.append(f'    style="rounded,filled"'
                     f' fillcolor="{LAYER_COLORS[cfg["layer"]]}"'
                     f' color="#999999" margin=14')
        for c in members:
            lines.append(f"    {c.name} [label={node_label(c)}]")
        lines.append("  }")
        lines.append("")

    # relations - strongest wins per ordered pair
    rel = {}   # (src, dst) -> rank into EDGE_STYLES
    def add(src, dst, rank):
        if src == dst or src not in classes or dst not in classes:
            return
        key = (src, dst)
        if key not in rel or rel[key] > rank:
            rel[key] = rank

    for c in classes.values():
        for t in c.extends:
            add(c.name, t, 0)
        for t in c.implements:
            add(c.name, t, 1)
        for t in c.field_types:
            add(c.name, t, 2)
        for t in c.created_types:
            add(c.name, t, 3)
        for t in c.ctor_param_types:
            add(c.name, t, 4)

    # generic body references ("uses") are noisy - draw them only for classes
    # that would otherwise float unconnected (e.g. static-call-only helpers)
    connected = {n for pair in rel for n in pair}
    for c in classes.values():
        for t in c.used_types:
            if (c.name not in connected) != (t not in connected):
                add(c.name, t, 4)

    # any relation pointing to a HIGHER layer must not constrain ranks,
    # otherwise it would fight the fixed module layering below
    def layer_of(name):
        return MODULES[classes[name].module]["layer"]

    for (src, dst), rank in sorted(rel.items()):
        extra = " constraint=false" if layer_of(dst) < layer_of(src) else ""
        lines.append(f"  {src} -> {dst} [{EDGE_STYLES[rank]}{extra}]")
    lines.append("")

    # stack classes without intra-module relations into short invisible
    # columns, otherwise they all share one rank and the module box becomes
    # one enormously wide row
    chain_pairs = []
    intra_connected = set()
    for src, dst in rel:
        if classes[src].module == classes[dst].module:
            intra_connected.update((src, dst))
    for module in MODULES:
        flat = sorted(c.name for c in classes.values()
                      if c.module == module and c.name not in intra_connected)
        for i in range(0, len(flat), FLAT_COLUMN_ROWS):
            chain = flat[i:i + FLAT_COLUMN_ROWS]
            chain_pairs += zip(chain, chain[1:])
    for a, b in chain_pairs:
        lines.append(f"  {a} -> {b} [style=invis weight=100]")
    lines.append("")

    # fixed module layering: an invisible "ruler" node sits between adjacent
    # layers; each layer's sink classes point to it and it points to the next
    # layer's source classes. This forces strict bands without the width
    # explosion that a full pairwise mesh of invisible edges would cause
    # (every multi-rank invisible edge spawns width-eating virtual nodes).
    layer_classes = {}
    for module, cfg in MODULES.items():
        names = sorted(c.name for c in classes.values() if c.module == module)
        if names:
            layer_classes.setdefault(cfg["layer"], []).extend(names)
    layers = sorted(layer_classes)
    intra = [(s, d) for (s, d) in rel if layer_of(s) == layer_of(d)]
    intra += chain_pairs
    # sinks/sources must be judged per strongly connected component: a cycle
    # (e.g. interface with a static factory creating its implementation)
    # would otherwise have neither and escape the banding constraints
    comp_of = strongly_connected(list(classes), intra)
    out_comps = {comp_of[s] for s, d in intra if comp_of[s] != comp_of[d]}
    in_comps = {comp_of[d] for s, d in intra if comp_of[s] != comp_of[d]}
    for upper, lower in zip(layers, layers[1:]):
        ruler = f"__band_{upper}_{lower}"
        lines.append(f'  {ruler} [shape=point style=invis width=0 label=""]')
        for c in layer_classes[upper]:
            if comp_of[c] not in out_comps:
                lines.append(f"  {c} -> {ruler} [style=invis weight=0]")
        for c in layer_classes[lower]:
            if comp_of[c] not in in_comps:
                lines.append(f"  {ruler} -> {c} [style=invis weight=0]")
    lines.append("")

    # legend, pinned to the bottom layer
    lines.append("  " + LEGEND)
    lines.append(f"  {layer_classes[layers[-1]][0]} -> legend [style=invis]")
    lines.append("}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# SVG interactivity (injected into the rendered file)
# ---------------------------------------------------------------------------
# click a class    -> highlight it and all its relations (peers stay readable)
# click a relation -> highlight it and both connected classes
# click background -> clear
# Works when the SVG is opened as a document (browser); not inside <img>.

SVG_INTERACTIVITY = """
<style>
  g.node, g.edge { cursor: pointer; }
  svg.dim g.node:not(.hl):not(.hl2), svg.dim g.edge:not(.hl) { opacity: 0.15; }
  g.node, g.edge { transition: opacity 0.12s; }
  g.node.hl > path, g.node.hl > polygon { stroke: #D32F2F; stroke-width: 2.5; }
  g.edge.hl path:not(.hit) { stroke-width: 2.8; }
  g.edge.hl polygon { stroke-width: 2; }
</style>
<script type="text/javascript"><![CDATA[
(function () {
  var svg = document.documentElement;
  var nodes = {};
  var edges = [];
  Array.prototype.forEach.call(document.querySelectorAll('g.node'), function (g) {
    var t = g.querySelector('title');
    if (!t) return;
    var name = t.textContent.trim();
    if (name === 'legend' || name.indexOf('__') === 0) return;
    nodes[name] = g;
  });
  Array.prototype.forEach.call(document.querySelectorAll('g.edge'), function (g) {
    var t = g.querySelector('title');
    if (!t) return;
    var m = t.textContent.split('->');
    if (m.length !== 2) return;
    var s = m[0].trim(), d = m[1].trim();
    if (!nodes[s] || !nodes[d]) return;
    var p = g.querySelector('path');
    if (!p) return;
    var hit = p.cloneNode(false);          // fat transparent path = click area
    hit.setAttribute('stroke', 'transparent');
    hit.setAttribute('stroke-width', '12');
    hit.setAttribute('fill', 'none');
    hit.setAttribute('class', 'hit');
    g.appendChild(hit);
    edges.push({ g: g, s: s, d: d });
  });
  function clearSel() {
    svg.classList.remove('dim');
    Array.prototype.forEach.call(document.querySelectorAll('.hl, .hl2'), function (e) {
      e.classList.remove('hl');
      e.classList.remove('hl2');
    });
  }
  Object.keys(nodes).forEach(function (name) {
    nodes[name].addEventListener('click', function (ev) {
      ev.stopPropagation();
      clearSel();
      svg.classList.add('dim');
      nodes[name].classList.add('hl');
      edges.forEach(function (e) {
        if (e.s === name || e.d === name) {
          e.g.classList.add('hl');
          nodes[e.s === name ? e.d : e.s].classList.add('hl2');
        }
      });
    });
  });
  edges.forEach(function (e) {
    e.g.addEventListener('click', function (ev) {
      ev.stopPropagation();
      clearSel();
      svg.classList.add('dim');
      e.g.classList.add('hl');
      nodes[e.s].classList.add('hl');
      nodes[e.d].classList.add('hl');
    });
  });
  svg.addEventListener('click', clearSel);
})();
]]></script>
"""


# ---------------------------------------------------------------------------
# Render
# ---------------------------------------------------------------------------

def ensure_graphviz():
    """Portable Graphviz in tmp/ - downloaded once, no installation."""
    dot = GRAPHVIZ_DIR / "bin" / "dot.exe"
    if dot.exists():
        return dot
    zip_path = REPO / "tmp" / "graphviz.zip"
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading Graphviz to {GRAPHVIZ_DIR} ...")
    urllib.request.urlretrieve(GRAPHVIZ_URL, zip_path)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(REPO / "tmp")
    zip_path.unlink()
    return dot


def main():
    classes = collect()
    print(f"{len(classes)} classes collected")
    DOT_FILE.write_text(emit(classes), encoding="utf-8")
    print(f"wrote {DOT_FILE}")
    dot = ensure_graphviz()
    subprocess.run(
        [str(dot), "-Tsvg", "-o", str(SVG_FILE), str(DOT_FILE)],
        check=True)
    svg = SVG_FILE.read_text(encoding="utf-8")
    svg = svg.replace("</svg>", SVG_INTERACTIVITY + "</svg>")
    SVG_FILE.write_text(svg, encoding="utf-8")
    print(f"wrote {SVG_FILE} (interactive - open in a browser)")


if __name__ == "__main__":
    sys.exit(main())
