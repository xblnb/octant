package com.octant.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HtmlTextExtractor {

    private static final Pattern SKIP_BLOCK =
            Pattern.compile("(?is)<(style|script|template)\\b[^>]*>.*?</\\1\\s*>");

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>(.*)</body\\s*>");

    private static final Pattern BLOCK_BOUNDARY = Pattern.compile(
            "(?i)</?(p|div|section|article|header|footer|main|aside|nav|h1|h2|h3|h4|h5|h6|"
                    + "li|ul|ol|dl|dt|dd|tr|td|th|table|thead|tbody|caption|blockquote|pre|figure|"
                    + "figcaption|br|hr|svg|text|title)\\b[^>]*>");

    private final String html;

    HtmlTextExtractor(String html) {
        this.html = html == null ? "" : html;
    }

    HtmlTextExtractor(byte[] utf8) {
        this(new String(utf8 == null ? new byte[0] : utf8, StandardCharsets.UTF_8));
    }

    String raw() {
        return html;
    }

    boolean startsWithHtmlTag() {
        return html.stripLeading().startsWith("<html");
    }

    boolean hasBom() {
        return !html.isEmpty() && html.charAt(0) == '\uFEFF';
    }

    int countTag(String tag) {
        Matcher m = Pattern.compile("(?i)<" + Pattern.quote(tag) + "\\b").matcher(html);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    List<String> tagTexts(String tag) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<" + Pattern.quote(tag) + "\\b[^>]*>(.*?)</"
                + Pattern.quote(tag) + "\\s*>").matcher(html);
        while (m.find()) {
            out.add(collapse(decodeReferences(stripTags(m.group(1)))));
        }
        return out;
    }

    List<String> attributeValues() {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*\"([^\"]*)\"")
                .matcher(html);
        while (m.find()) {
            out.add(decodeReferences(m.group(2)));
        }
        return out;
    }

    String visibleText() {
        Matcher b = BODY.matcher(html);
        String body = b.find() ? b.group(1) : html;
        return textOf(body);
    }

    String text() {
        return visibleText();
    }

    List<String> scriptLiterals() {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script\\s*>").matcher(html);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    List<String> externalReferences() {
        List<String> hits = new ArrayList<>();
        String[] patterns = {
                "(?i)https?://",
                "(?i)<script\\b[^>]*\\bsrc\\s*=",
                "(?i)<link\\b[^>]*rel\\s*=\\s*[\"']?stylesheet",
                "@import",
                "url\\(\\s*(?!data:|#)",
                "(?i)<iframe\\b",
                "(?i)<object\\b",
                "(?i)<embed\\b",
                "(?i)<base\\s",
                "(?i)xlink:href",
                "(?i)<\\?xml-stylesheet"
        };
        for (String p : patterns) {
            Matcher m = Pattern.compile(p).matcher(html);
            if (m.find()) {
                hits.add(p + " @" + m.start());
            }
        }
        return hits;
    }

    List<String> networkCapabilities() {
        List<String> hits = new ArrayList<>();
        for (String token : List.of("fetch(", "XMLHttpRequest", "Web" + "Socket", "sendBeacon",
                "EventSource")) {
            if (html.contains(token)) {
                hits.add(token);
            }
        }
        return hits;
    }

    byte[] embeddedFontBytes() {
        Matcher m = Pattern.compile(
                "(?is)@font-face\\s*\\{[^}]*src\\s*:\\s*url\\(\\s*data:([a-zA-Z0-9.+/\\-]+)?;base64,([A-Za-z0-9+/=]+)\\)")
                .matcher(html);
        if (!m.find()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    String declaredFontFamily() {
        Matcher m = Pattern.compile("(?is)@font-face\\s*\\{[^}]*font-family\\s*:\\s*[\"']?([^\"';}]+)")
                .matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    boolean usesLocalFontSource() {
        Matcher m = Pattern.compile("(?is)@font-face\\s*\\{[^}]*\\}").matcher(html);
        while (m.find()) {
            if (m.group().contains("local(")) {
                return true;
            }
        }
        return false;
    }

    Set<String> ids(String regex) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(html);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    List<String> attributeOf(String tag, String attribute) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<" + Pattern.quote(tag) + "\\b[^>]*>").matcher(html);
        while (m.find()) {
            Matcher a = Pattern.compile("(?is)\\b" + Pattern.quote(attribute) + "\\s*=\\s*\"([^\"]*)\"")
                    .matcher(m.group());
            if (a.find()) {
                out.add(a.group(1));
            }
        }
        return out;
    }

    List<String> elementsWithAttribute(String tag, String attribute) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<" + Pattern.quote(tag) + "\\b[^>]*\\b"
                + Pattern.quote(attribute) + "\\s*=").matcher(html);
        while (m.find()) {
            int end = html.indexOf('>', m.start());
            out.add(html.substring(m.start(), end < 0 ? html.length() : end + 1));
        }
        return out;
    }

    static String normalize(String fragment) {
        return collapse(decodeReferences(withBlockSeparators(fragment)));
    }

    private static String withBlockSeparators(String fragment) {
        return BLOCK_BOUNDARY.matcher(fragment).replaceAll("\n");
    }

    private static String stripTags(String fragment) {
        String s = SKIP_BLOCK.matcher(fragment).replaceAll("\n");
        s = COMMENT.matcher(s).replaceAll("\n");
        s = BLOCK_BOUNDARY.matcher(s).replaceAll("\n");
        s = s.replaceAll("(?s)<[^>]*>", "");
        return s;
    }

    private String textOf(String fragment) {
        return normalize(stripTags(fragment));
    }

    private static String collapse(String s) {
        return s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    static String decodeReferences(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                out.append(c);
                continue;
            }
            String body = s.substring(i + 1, semi);
            Character decoded = decodeEntity(body);
            if (decoded == null) {
                out.append(c);
            } else {
                out.append(decoded.charValue());
                i = semi;
            }
        }
        return out.toString();
    }

    private static Character decodeEntity(String body) {
        if (body.isEmpty()) {
            return null;
        }
        if (body.charAt(0) == '#') {
            try {
                int cp = (body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X'))
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                if (cp > 0 && cp <= Character.MAX_CODE_POINT
                        && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                    return (char) cp;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
            return null;
        }
        return switch (body) {
            case "amp" -> '&';
            case "lt" -> '<';
            case "gt" -> '>';
            case "quot" -> '"';
            case "apos" -> '\'';
            case "nbsp" -> ' ';
            default -> null;
        };
    }

    static int zeroWidthCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' || c == '\u2060') {
                n++;
            }
        }
        return n;
    }

    static int bidiControlCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u202A' && c <= '\u202E') {
                n++;
            }
        }
        return n;
    }
}
