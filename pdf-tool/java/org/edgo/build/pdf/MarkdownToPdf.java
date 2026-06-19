package org.edgo.build.pdf;

import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build-time Markdown &rarr; PDF converter for designated documentation files.
 * <p>
 * flexmark renders the Markdown to HTML with GitHub-style heading ids; Open HTML
 * to PDF renders the PDF with DejaVu embedded (so the maths glyphs survive) and
 * real internal {@code #anchor} links. Every intra-document link is validated
 * against the generated heading ids, and the build fails loudly on a dangling
 * link &mdash; that is what guarantees the "links within" actually resolve in the
 * PDF.
 * <p>
 * Invoked from the Maven {@code pdf} profile with explicit file arguments, so it
 * only ever converts the documents it is handed, never a glob of every Markdown
 * file in the tree.
 */
public final class MarkdownToPdf {

    private static final Pattern ID_ATTR = Pattern.compile("\\sid=\"([^\"]+)\"");
    private static final Pattern HREF_FRAGMENT = Pattern.compile("href=\"#([^\"]+)\"");
    private static final int MIN_FONT_WEIGHT = 400;
    private static final int BOLD_FONT_WEIGHT = 700;

    private final Path fontsDir;
    private final Path outputDir;
    private final Parser parser;
    private final HtmlRenderer renderer;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: MarkdownToPdf <fontsDir> <outputDir> <file.md>...");
        }
        MarkdownToPdf converter = new MarkdownToPdf(Path.of(args[0]), Path.of(args[1]));
        for (int i = 2; i < args.length; i++) {
            converter.convert(Path.of(args[i]));
        }
    }

    MarkdownToPdf(Path fontsDir, Path outputDir) {
        this.fontsDir = fontsDir;
        this.outputDir = outputDir;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        options.set(HtmlRenderer.GENERATE_HEADER_ID, true);
        options.set(HtmlRenderer.RENDER_HEADER_ID, true);
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    void convert(Path markdownFile) throws Exception {
        String markdown = Files.readString(markdownFile, StandardCharsets.UTF_8);
        Node document = parser.parse(markdown);
        String bodyHtml = renderer.render(document);
        validateInternalLinks(markdownFile, bodyHtml);

        String html = wrapInHtmlDocument(bodyHtml);
        Files.createDirectories(outputDir);
        Path pdf = outputDir.resolve(baseName(markdownFile) + ".pdf");
        try (OutputStream out = Files.newOutputStream(pdf)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            registerFonts(builder);
            builder.withHtmlContent(html, markdownFile.toUri().toString());
            builder.toStream(out);
            builder.run();
        }
        System.out.println("[md-to-pdf] " + markdownFile + " -> " + pdf);
    }

    /**
     * Fail the build if any {@code [text](#fragment)} link in the document points
     * at a heading id that the renderer did not generate. This is the explicit
     * guarantee that internal links survive into the PDF.
     */
    void validateInternalLinks(Path markdownFile, String html) {
        Set<String> ids = new HashSet<>();
        Matcher idMatcher = ID_ATTR.matcher(html);
        while (idMatcher.find()) {
            ids.add(idMatcher.group(1));
        }
        List<String> dangling = new ArrayList<>();
        Matcher hrefMatcher = HREF_FRAGMENT.matcher(html);
        int total = 0;
        while (hrefMatcher.find()) {
            total++;
            String target = hrefMatcher.group(1);
            if (!ids.contains(target)) {
                dangling.add(target);
            }
        }
        if (!dangling.isEmpty()) {
            throw new IllegalStateException(markdownFile + ": " + dangling.size()
                    + " internal link(s) point at missing heading ids: " + dangling);
        }
        System.out.println("[md-to-pdf] " + markdownFile + ": " + total
                + " internal link(s) resolved against " + ids.size() + " heading id(s)");
    }

    void registerFonts(PdfRendererBuilder builder) throws Exception {
        registerFont(builder, "DejaVuSans.ttf", "DejaVu Sans",
                MIN_FONT_WEIGHT, BaseRendererBuilder.FontStyle.NORMAL);
        registerFont(builder, "DejaVuSans-Bold.ttf", "DejaVu Sans",
                BOLD_FONT_WEIGHT, BaseRendererBuilder.FontStyle.NORMAL);
        registerFont(builder, "DejaVuSans-Oblique.ttf", "DejaVu Sans",
                MIN_FONT_WEIGHT, BaseRendererBuilder.FontStyle.ITALIC);
        registerFont(builder, "DejaVuSansMono.ttf", "DejaVu Sans Mono",
                MIN_FONT_WEIGHT, BaseRendererBuilder.FontStyle.NORMAL);
    }

    /** Registers one embedded font, or warns and skips if the file is absent. */
    void registerFont(PdfRendererBuilder builder, String fileName, String family,
                      int weight, BaseRendererBuilder.FontStyle style) throws Exception {
        Path file = fontsDir.resolve(fileName);
        if (!Files.exists(file)) {
            System.out.println("[md-to-pdf] WARNING missing font " + file
                    + " (glyph coverage / style may degrade)");
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        FSSupplier<InputStream> supplier = () -> new ByteArrayInputStream(bytes);
        builder.useFont(supplier, family, weight, style, true);
    }

    String wrapInHtmlDocument(String bodyHtml) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n"
                + "<head>\n<meta charset=\"UTF-8\"/>\n<style>\n" + css() + "</style>\n</head>\n"
                + "<body>\n" + bodyHtml + "\n</body>\n</html>\n";
    }

    String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    String css() {
        return "@page { size: A4; margin: 18mm 15mm; }\n"
                + "body { font-family: 'DejaVu Sans', sans-serif; font-size: 10pt;"
                + " line-height: 1.42; color: #222; }\n"
                + "h1, h2, h3, h4 { font-family: 'DejaVu Sans', sans-serif; font-weight: bold;"
                + " line-height: 1.25; }\n"
                + "h1 { font-size: 21pt; }\n"
                + "h2 { font-size: 15pt; margin-top: 16pt; border-bottom: 1px solid #cccccc;"
                + " padding-bottom: 3pt; }\n"
                + "h3 { font-size: 12pt; margin-top: 12pt; }\n"
                + "h4 { font-size: 10.5pt; }\n"
                + "code { font-family: 'DejaVu Sans Mono', monospace; font-size: 8.6pt;"
                + " background: #f3f3f3; padding: 0 2pt; }\n"
                + "pre { font-family: 'DejaVu Sans Mono', monospace; font-size: 8.4pt;"
                + " background: #f6f8fa; border: 1px solid #e1e4e8; padding: 6pt;"
                + " white-space: pre-wrap; word-wrap: break-word; }\n"
                + "pre code { background: transparent; padding: 0; }\n"
                + "table { border-collapse: collapse; width: 100%; font-size: 8.8pt;"
                + " margin: 6pt 0; }\n"
                + "th, td { border: 1px solid #c8c8c8; padding: 3pt 5pt; text-align: left;"
                + " vertical-align: top; }\n"
                + "th { background: #f0f0f0; }\n"
                + "a { color: #1a5fb4; text-decoration: none; }\n"
                + "blockquote { border-left: 3px solid #d0d7de; margin: 6pt 0;"
                + " padding: 2pt 10pt; color: #555555; }\n"
                + "hr { border: 0; border-top: 1px solid #d0d7de; }\n";
    }
}
