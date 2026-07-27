/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.helpviewer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rebuilds the offline help search assets from a language help folder:
 *
 * <ul>
 *   <li>{@code search-index.js} — {@code window.HELP_DOCS = [...]}, one entry
 *       per heading-anchored section (id, url, anchor, page title, heading,
 *       body text).  The {@code help-index.html} page builds a lunr index from
 *       this in the browser.</li>
 *   <li>{@code help-index.html} — the "Index &amp; search" page: a search box
 *       plus an auto A–Z list of topics (headings) and terms (acronyms +
 *       emphasised concept words).</li>
 * </ul>
 *
 * <p>This is the in-app / offline twin of the refresh-help skill's
 * {@code build-help-index.py}: a translator who corrects a language bundle can
 * regenerate its search index without the Python toolchain — either from the
 * Help menu (Rebuild help search index…) or from the command line via
 * {@link #main}.
 *
 * <p>Search-result links carry the query terms as a {@code ?hl=} parameter;
 * the help viewer injects a highlighter that reads it (see
 * {@code HelpViewer}'s highlight script).
 */
public final class HelpIndexBuilder {

    private static final String OUT_JS_NAME   = "search-index.js";
    private static final String OUT_PAGE_NAME = "help-index.html";
    private static final String INDEX_FILE    = "index.html";

    /** Pages to skip (the generated search page itself). */
    private static final Set<String> SKIP = Set.of(OUT_PAGE_NAME);

    private static final Set<String> TERM_STOP = Set.of(
            "DOCTYPE", "UTF", "HTML", "CSS", "SVG", "PNG", "JPG", "JPEG", "GIF", "URL",
            "TOC", "ASCII", "ID", "OK", "AM", "PM", "CPU", "GPU", "OS", "UI", "FAQ",
            "AND", "BUILD", "ALGORITHMS", "ON", "OR", "IS", "TRUE", "AIR", "MB", "PC");

    /** Lowercase function words — a phrase containing one is a sentence
     *  fragment, not an index term. */
    private static final Set<String> FUNCTION_WORDS = Set.of(
            "of", "the", "a", "an", "to", "in", "on", "for", "with", "and", "or", "is",
            "are", "from", "by", "as", "at", "into", "than", "that", "its", "it");

    /** Capitalised sentence-openers to drop from concept-term extraction. */
    private static final Set<String> WORD_STOP = Set.of(
            "The", "This", "That", "These", "Those", "When", "While", "With", "Each",
            "Both", "From", "For", "And", "But", "Use", "Used", "Using", "Note", "See",
            "Set", "Sets", "Same", "Drag", "Click", "Press", "Show", "Shows", "After",
            "Before", "Over", "Under", "Live", "Read", "Write", "Save", "Load", "Open",
            "Pick", "Type", "Pop", "Pops", "Step", "Auto", "Left", "Right", "Top",
            "Bottom", "Active", "Only", "Phonalyser", "Theory", "Back", "Contents");

    /** Proper nouns kept from generic capitalised-word extraction. */
    private static final Set<String> KNOWN_PROPER = Set.of(
            "farina", "lanczos", "nyquist", "schmitt", "voss", "mccartney", "goertzel",
            "dolph", "chebyshev", "hann", "blackman", "harris", "kaiser", "bessel",
            "riaa", "wasapi", "javasound", "wdm", "lipshitz", "vanderkooy", "wannamaker",
            "dewesoft", "microsoft", "analog", "devices");

    private static final Pattern WS          = Pattern.compile("\\s+");
    private static final Pattern TAG_RE      = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern HEAD_RE      = Pattern.compile("<h([1-4])\\b([^>]*)>(.*?)</h\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ID_RE       = Pattern.compile("id=\"([^\"]+)\"");
    private static final Pattern TITLE_RE    = Pattern.compile("<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DROP_BLOCKS = Pattern.compile("<(script|style|svg)\\b.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern B_RE        = Pattern.compile("<(?:b|strong|em|i)>(.*?)</(?:b|strong|em|i)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ACRONYM_RE  = Pattern.compile("\\b[A-Z][A-Z0-9]{1,}(?:-[A-Z0-9]{2,})?\\b");
    private static final Pattern HYPHEN_RE   = Pattern.compile("\\b[A-Z][A-Za-z]+[–-][A-Z][A-Za-z]+\\b");
    private static final Pattern PROPER_RE   = Pattern.compile("\\b[A-Z][a-z]{2,}\\b");
    private static final Pattern VALUE_RE    = Pattern.compile("^[\\W\\d]*[\\d][\\d\\s.,%/A-Za-zµ -]*$");
    private static final Pattern HEX_RE      = Pattern.compile("^[0-9A-Fa-f]{6}$");
    private static final Pattern FILE_RE     = Pattern.compile("\\.(md|pdf|html|txt|py|java|json|yaml|yml)$",
            Pattern.CASE_INSENSITIVE);

    private final Path helpDir;

    public HelpIndexBuilder(Path helpDir) {
        this.helpDir = helpDir;
    }

    /**
     * Scans the help folder and writes {@code search-index.js} and
     * {@code help-index.html} into it.
     *
     * @return {@code {pageCount, topicCount, termCount}}
     */
    public int[] build() throws IOException {
        List<Page> pages = new ArrayList<>();
        for (Path p : listPages()) pages.add(parsePage(p));
        List<Term> terms = collectTerms(pages);
        List<Topic> topics = buildTopics(pages);
        writeIndexJs(pages);
        writePage(topics, terms);
        return new int[]{ pages.size(), topics.size(), terms.size() };
    }

    // -------------------------------------------------------------------------
    // Scanning & parsing
    // -------------------------------------------------------------------------

    private List<Path> listPages() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(helpDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".html"))
                .filter(p -> !SKIP.contains(p.getFileName().toString()))
                .forEach(out::add);
        }
        out.sort(Comparator.comparing(this::relUrl));
        return out;
    }

    private String relUrl(Path path) {
        return helpDir.relativize(path).toString().replace('\\', '/');
    }

    private Page parsePage(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String url = relUrl(path);
        Matcher tm = TITLE_RE.matcher(raw);
        String title = tm.find() ? strip(tm.group(1)).replace("Phonalyser — ", "") : url;
        title = title.replaceFirst("^Theory:\\s*", "").replaceFirst("^Further reading:\\s*", "");
        if (title.equals("Theory of operation")) title = "Overview";

        List<Section> secs = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<String> attrs = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        Matcher hm = HEAD_RE.matcher(raw);
        while (hm.find()) {
            spans.add(new int[]{ hm.start(), hm.end() });
            attrs.add(hm.group(2));
            heads.add(strip(hm.group(3)));
        }
        for (int i = 0; i < spans.size(); i++) {
            Matcher im = ID_RE.matcher(attrs.get(i));
            String anchor = im.find() ? im.group(1) : "";
            int end = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : raw.length();
            String text = strip(raw.substring(spans.get(i)[1], end));
            secs.add(new Section(anchor, heads.get(i), text));
        }
        return new Page(url, title, secs, raw);
    }

    /** Drops script/style/svg blocks and every tag, unescapes entities, and
     *  collapses whitespace — the plain text used for indexing. */
    private String strip(String html) {
        String s = DROP_BLOCKS.matcher(html).replaceAll(" ");
        s = TAG_RE.matcher(s).replaceAll(" ");
        s = unescape(s);
        return WS.matcher(s).replaceAll(" ").trim();
    }

    private String pageLabel(String title, String url) {
        if (url.startsWith("theory/"))   return "Theory ▸ " + title;
        if (url.startsWith("external/")) return "Further reading ▸ " + title;
        return title;
    }

    // -------------------------------------------------------------------------
    // Terms (acronyms + concept words) and topics (headings)
    // -------------------------------------------------------------------------

    private List<Term> collectTerms(List<Page> pages) {
        Map<String, Term> terms = new TreeMap<>();
        for (Page p : pages) {
            String label = pageLabel(p.title, p.url);
            for (Section s : p.secs) {
                String secLabel = label + (s.heading.isEmpty() ? "" : " ▸ " + s.heading);
                for (String t : unique(ACRONYM_RE, s.text)) {
                    if (!TERM_STOP.contains(t) && t.length() >= 2) addTerm(terms, t, p.url, s.anchor, secLabel);
                }
                for (String t : unique(HYPHEN_RE, s.text)) {
                    addTerm(terms, t, p.url, s.anchor, secLabel);
                }
                for (String t : unique(PROPER_RE, s.text)) {
                    if (KNOWN_PROPER.contains(t.toLowerCase(Locale.ROOT))) addTerm(terms, t, p.url, s.anchor, secLabel);
                }
            }
            // Concept terms emphasised in body text — skip bold sentence
            // lead-ins (those end with a period) and keep only short,
            // term-like phrases.
            String pageAnchor = p.secs.isEmpty() ? "" : p.secs.get(0).anchor;
            Matcher bm = B_RE.matcher(p.raw);
            while (bm.find()) {
                String rawT = bm.group(1);
                if (rawT.stripTrailing().endsWith(".")) continue;
                String t = stripEdge(strip(rawT));
                if (goodPhrase(t)) addTerm(terms, t, p.url, pageAnchor, label);
            }
        }
        return new ArrayList<>(terms.values());
    }

    private void addTerm(Map<String, Term> terms, String termRaw, String url, String anchor, String label) {
        String term = stripEdge(WS.matcher(termRaw).replaceAll(" ")).trim();
        if (term.length() < 2 || term.length() > 32 || FILE_RE.matcher(term).find()
                || HEX_RE.matcher(term).matches() || TERM_STOP.contains(term)) {
            return;
        }
        Term e = terms.computeIfAbsent(term.toLowerCase(Locale.ROOT), k -> new Term(term));
        String[] ref = { url, anchor, label };
        if (e.refs.size() < 6 && e.refs.stream().noneMatch(r ->
                r[0].equals(ref[0]) && r[1].equals(ref[1]) && r[2].equals(ref[2]))) {
            e.refs.add(ref);
        }
    }

    /** An emphasised concept term, not a sentence lead-in or a value. */
    private boolean goodPhrase(String t) {
        if (t.isEmpty()) return false;
        String[] words = t.split("\\s+");
        if (words.length < 1 || words.length > 3 || t.length() > 28) return false;
        if (Character.isLowerCase(t.charAt(0)) || WORD_STOP.contains(words[0])) return false;
        if (VALUE_RE.matcher(t).matches()) return false;
        for (String w : words) if (FUNCTION_WORDS.contains(w.toLowerCase(Locale.ROOT))) return false;
        for (String w : words) if (!w.isEmpty() && Character.isUpperCase(w.charAt(0))) return true;
        return false;
    }

    private List<Topic> buildTopics(List<Page> pages) {
        List<Topic> topics = new ArrayList<>();
        for (Page p : pages) {
            String label = pageLabel(p.title, p.url);
            for (Section s : p.secs) {
                if (s.anchor.isEmpty() || s.heading.isEmpty()) continue;
                String blurb = "";
                if (!s.text.isEmpty()) {
                    String head = s.text.length() > 130 ? s.text.substring(0, 130) : s.text;
                    int sp = head.lastIndexOf(' ');
                    blurb = sp >= 0 ? head.substring(0, sp) : head;
                    if (!blurb.isEmpty() && !head.endsWith(" ")) blurb += "…";
                }
                topics.add(new Topic(s.heading, p.url, s.anchor, label, blurb));
            }
        }
        topics.sort(Comparator
                .comparing((Topic e) -> e.name.toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.label));
        return topics;
    }

    private List<String> unique(Pattern pat, String text) {
        Set<String> set = new LinkedHashSet<>();
        Matcher m = pat.matcher(text);
        while (m.find()) set.add(m.group());
        return new ArrayList<>(set);
    }

    // -------------------------------------------------------------------------
    // Output files
    // -------------------------------------------------------------------------

    private void writeIndexJs(List<Page> pages) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by HelpIndexBuilder - do not edit by hand.\n");
        sb.append("window.HELP_DOCS = [");
        int id = 0;
        boolean first = true;
        for (Page p : pages) {
            for (Section s : p.secs) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(id);
                sb.append(",\"u\":"); jsonStr(sb, p.url);
                sb.append(",\"a\":"); jsonStr(sb, s.anchor);
                sb.append(",\"t\":"); jsonStr(sb, p.title);
                sb.append(",\"h\":"); jsonStr(sb, s.heading);
                sb.append(",\"x\":"); jsonStr(sb, s.text);
                sb.append('}');
                id++;
            }
        }
        sb.append("];\n");
        Files.writeString(helpDir.resolve(OUT_JS_NAME), sb, StandardCharsets.UTF_8);
    }

    private void writePage(List<Topic> topics, List<Term> terms) throws IOException {
        StringBuilder sb = new StringBuilder(PAGE_HEAD);

        sb.append("  <h2 id=\"idx-topics\">Topics</h2>\n  <div class=\"azindex\">\n");
        for (Group<Topic> g : alphaGroups(topics, t -> t.name)) {
            sb.append("    <h3>").append(esc(g.letter)).append("</h3>\n    <ul>\n");
            for (Topic e : g.items) {
                String href = e.url + "#" + e.anchor;
                String blurb = e.blurb.isEmpty() ? ""
                        : " — <span class=\"small\">" + esc(e.blurb) + "</span>";
                sb.append("      <li><a href=\"").append(esc(href)).append("\">").append(esc(e.name))
                  .append("</a> <span class=\"small\">(").append(esc(e.label)).append(")</span>")
                  .append(blurb).append("</li>\n");
            }
            sb.append("    </ul>\n");
        }
        sb.append("  </div>\n");

        sb.append("  <h2 id=\"idx-terms\">Terms &amp; acronyms</h2>\n  <div class=\"azindex\">\n");
        for (Group<Term> g : alphaGroups(terms, t -> t.display)) {
            sb.append("    <h3>").append(esc(g.letter)).append("</h3>\n    <ul>\n");
            for (Term e : g.items) {
                StringBuilder links = new StringBuilder();
                for (int i = 0; i < e.refs.size(); i++) {
                    String[] r = e.refs.get(i);
                    if (i > 0) links.append(", ");
                    links.append("<a href=\"").append(esc(r[0])).append('#').append(esc(r[1]))
                         .append("\">").append(esc(r[2])).append("</a>");
                }
                sb.append("      <li><b>").append(esc(e.display)).append("</b> — ")
                  .append(links).append("</li>\n");
            }
            sb.append("    </ul>\n");
        }
        sb.append("  </div>\n");

        sb.append(PAGE_TAIL);
        Files.writeString(helpDir.resolve(OUT_PAGE_NAME), sb, StandardCharsets.UTF_8);
    }

    private <T> List<Group<T>> alphaGroups(List<T> entries, Function<T, String> name) {
        Map<String, List<T>> groups = new TreeMap<>();
        for (T e : entries) {
            String n = name.apply(e);
            char c0 = n.isEmpty() ? '#' : Character.toUpperCase(n.charAt(0));
            String key = Character.isLetter(c0) ? String.valueOf(c0) : "#";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<Group<T>> out = new ArrayList<>();
        for (Map.Entry<String, List<T>> en : groups.entrySet()) out.add(new Group<>(en.getKey(), en.getValue()));
        return out;
    }

    // -------------------------------------------------------------------------
    // Escaping helpers
    // -------------------------------------------------------------------------

    private void jsonStr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** HTML escape matching Python's {@code html.escape(s, quote=True)}. */
    private String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&'  -> b.append("&amp;");
                case '<'  -> b.append("&lt;");
                case '>'  -> b.append("&gt;");
                case '"'  -> b.append("&quot;");
                case '\'' -> b.append("&#x27;");
                default   -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Strips leading / trailing whitespace and {@code . , : ;} characters
     *  (Python's {@code strip(" \t.,:;")}). */
    private String stripEdge(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isEdge(s.charAt(a))) a++;
        while (b > a && isEdge(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private boolean isEdge(char c) {
        return c == ' ' || c == '\t' || c == '.' || c == ',' || c == ':' || c == ';';
    }

    /** Minimal HTML-entity unescape covering the entities used in the help
     *  pages, plus numeric {@code &#nnn;} / {@code &#xhh;} references. */
    private String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') { b.append(c); i++; continue; }
            int sc = s.indexOf(';', i + 1);
            if (sc < 0 || sc - i > 12) { b.append(c); i++; continue; }
            String ent = s.substring(i + 1, sc);
            String rep = entity(ent);
            if (rep != null) { b.append(rep); i = sc + 1; }
            else { b.append(c); i++; }
        }
        return b.toString();
    }

    private String entity(String ent) {
        if (ent.startsWith("#")) {
            try {
                int cp = ent.charAt(1) == 'x' || ent.charAt(1) == 'X'
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                return new String(Character.toChars(cp));
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return switch (ent) {
            case "amp"    -> "&";
            case "lt"     -> "<";
            case "gt"     -> ">";
            case "quot"   -> "\"";
            case "apos"   -> "'";
            case "nbsp"   -> " ";
            case "period" -> ".";
            case "times"  -> "×";
            case "middot" -> "·";
            case "deg"    -> "°";
            case "plusmn" -> "±";
            case "micro"  -> "µ";
            case "le"     -> "≤";
            case "ge"     -> "≥";
            case "ne"     -> "≠";
            case "asymp"  -> "≈";
            case "hellip" -> "…";
            case "rarr"   -> "→";
            case "larr"   -> "←";
            case "harr"   -> "↔";
            case "ndash"  -> "–";
            case "mdash"  -> "—";
            case "minus"  -> "−";
            case "sup2"   -> "²";
            case "sup3"   -> "³";
            case "frac12" -> "½";
            case "alpha"  -> "α";
            case "beta"   -> "β";
            case "phi"    -> "φ";
            case "omega"  -> "ω";
            case "Delta"  -> "Δ";
            case "pi"     -> "π";
            case "mu"     -> "μ";
            case "copy"   -> "©";
            case "reg"    -> "®";
            case "trade"  -> "™";
            case "hairsp" -> " ";
            case "thinsp" -> " ";
            default       -> null;
        };
    }

    // -------------------------------------------------------------------------
    // CLI entry point (offline / translator use)
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0]
                : Paths.get("src", "main", "resources", "help", "en").toString());
        if (!Files.isRegularFile(dir.resolve(INDEX_FILE))) {
            System.err.println("Not a help folder (no " + INDEX_FILE + "): " + dir.toAbsolutePath());
            System.exit(2);
        }
        int[] s = new HelpIndexBuilder(dir).build();
        System.out.printf("pages=%d topics=%d terms=%d%n", s[0], s[1], s[2]);
        System.out.println("wrote " + dir.resolve(OUT_JS_NAME));
        System.out.println("wrote " + dir.resolve(OUT_PAGE_NAME));
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    private record Page(String url, String title, List<Section> secs, String raw) {}

    private record Section(String anchor, String heading, String text) {}

    private record Topic(String name, String url, String anchor, String label, String blurb) {}

    private static final class Term {
        final String display;
        final List<String[]> refs = new ArrayList<>();
        Term(String display) { this.display = display; }
    }

    private record Group<T>(String letter, List<T> items) {}

    // -------------------------------------------------------------------------
    // Static page chrome (kept identical to build-help-index.py's output).
    // Closing delimiter at column 0 so authored leading spaces are literal.
    // -------------------------------------------------------------------------

    private static final String PAGE_HEAD =
"""
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <title>Phonalyser — Index &amp; search</title>
  <link rel="stylesheet" href="style.css"/>
  <script src="lunr.min.js"></script>
  <script src="search-index.js"></script>
</head>
<body>
  <p class="back"><a href="index.html">◀ Back to contents</a></p>
  <h1>Index &amp; search</h1>
  <p>Search the whole manual, or jump straight to a topic or term below.</p>

  <div class="search">
    <input id="q" type="text" autocomplete="off" spellcheck="false"
           placeholder="Search the help…"/>
    <div id="results" class="results"></div>
  </div>
""";

    private static final String PAGE_TAIL =
"""

  <script>
  (function () {
    var DOCS = window.HELP_DOCS || [];
    var byId = {}; for (var i=0;i<DOCS.length;i++) byId[DOCS[i].id]=DOCS[i];
    var q = document.getElementById('q');
    var out = document.getElementById('results');
    if (typeof lunr === 'undefined') { out.innerHTML='<p class="small">Search engine failed to load.</p>'; return; }
    // Build the lunr index in the browser from the section docs (~ms).
    var idx = lunr(function () {
      this.ref('id');
      this.field('t', {boost: 10});
      this.field('h', {boost: 5});
      this.field('x');
      var self = this;
      DOCS.forEach(function (d) { self.add(d); });
    });
    function esc(s){return s.replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}
    function terms(s){return s.toLowerCase().split(/[^a-z0-9µ]+/).filter(function(t){return t.length>1;});}
    function snippet(text, toks){
      var low=text.toLowerCase(), at=-1;
      for(var i=0;i<toks.length;i++){var p=low.indexOf(toks[i]); if(p>=0&&(at<0||p<at))at=p;}
      if(at<0)at=0;
      var start=Math.max(0,at-50), s=text.substring(start,start+170);
      if(start>0)s='…'+s; if(start+170<text.length)s=s+'…';
      s=esc(s);
      for(var j=0;j<toks.length;j++){
        s=s.replace(new RegExp('('+toks[j].replace(/[.*+?^${}()|[\\]\\\\]/g,'\\\\$&')+')','ig'),'<mark>$1</mark>');
      }
      return s;
    }
    function run(query){
      out.innerHTML='';
      var toks=terms(query); if(!toks.length) return;
      // Prefix (as-you-type) + stemmed-exact, OR-combined for recall.
      var lq = toks.map(function(t){return t+'* '+t;}).join(' ');
      var res;
      try { res = idx.search(lq); }
      catch(e){ try { res = idx.search(toks.join(' ')); } catch(e2){ res=[]; } }
      if(!res.length){ out.innerHTML='<p class="small">No matches.</p>'; return; }
      res = res.slice(0,40);
      var head=document.createElement('p'); head.className='small';
      head.textContent=res.length+(res.length===40?'+ ':' ')+'result'+(res.length===1?'':'s');
      out.appendChild(head);
      var hl='hl='+encodeURIComponent(toks.join(' '));
      res.forEach(function(r){
        var d=byId[r.ref]; if(!d) return;
        // Carry the query terms so the opened page highlights them (the help
        // viewer injects a highlighter that reads ?hl=… — see HelpViewer).
        var href=d.u+'?'+hl+(d.a?('#'+d.a):'');
        var label=d.t+(d.h?(' ▸ '+d.h):'');
        var div=document.createElement('div'); div.className='hit';
        div.innerHTML='<a href="'+esc(href)+'">'+esc(label)+'</a><div class="snip">'+snippet(d.x,toks)+'</div>';
        out.appendChild(div);
      });
    }
    var timer=null;
    q.addEventListener('input',function(){clearTimeout(timer);timer=setTimeout(function(){run(q.value);},120);});
    q.focus();
  })();
  </script>

  <hr/>
  <p class="back"><a href="index.html">◀ Back to contents</a></p>
</body>
</html>
""";
}
