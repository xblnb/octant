package com.octant.pipeline.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class LangIndex {

    private static final Pattern FLAT = Pattern.compile(
            "\"((?>[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?>[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern MODS_BLOCK = Pattern.compile("\\[\\[mods\\]\\]([\\s\\S]{0,600}?)displayName\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MOD_ID = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern FABRIC_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern LANG_ENTRY = Pattern.compile("assets/([^/]+)/lang/(zh_cn|en_us)\\.json$");
    private static final Pattern ASSET_OBJ = Pattern.compile(
            "\"minecraft/lang/(zh_cn|en_us)\\.json\"\\s*:\\s*\\{\\s*\"hash\"\\s*:\\s*\"([0-9a-f]+)\"");

    private final Map<String, String> zh = new HashMap<>();
    private final Map<String, String> en = new HashMap<>();
    private final Map<String, String> modNames = new TreeMap<>();
    private final Map<String, Path> nsJar = new TreeMap<>();
    private final List<String> notes = new ArrayList<>();

    private LangIndex() {
    }

    public static LangIndex build(Path modsDir, Path assetsDir, Path assetIndexJson) throws IOException {
        LangIndex li = new LangIndex();
        li.loadVanilla(assetsDir, assetIndexJson);
        if (modsDir != null && Files.isDirectory(modsDir)) {
            List<Path> jars = new ArrayList<>();
            try (var s = Files.list(modsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
            }
            for (Path jar : jars) {
                li.loadJar(jar);
            }
        }
        return li;
    }

    private void loadVanilla(Path assetsDir, Path assetIndexJson) {
        if (assetsDir == null || assetIndexJson == null || !Files.isRegularFile(assetIndexJson)) {
            notes.add("原版语言索引缺失，原版实体/物品名将回落为 id");
            return;
        }
        try {
            String idx = Files.readString(assetIndexJson, StandardCharsets.UTF_8);
            Matcher m = ASSET_OBJ.matcher(idx);
            int got = 0;
            while (m.find()) {
                String hash = m.group(2);
                Path obj = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                if (!Files.isRegularFile(obj)) {
                    continue;
                }
                Map<String, String> into = "zh_cn".equals(m.group(1)) ? zh : en;
                into.putAll(flat(Files.readString(obj, StandardCharsets.UTF_8)));
                got++;
            }
            notes.add("原版语言对象装入 " + got + " 个");
        } catch (Exception e) {
            notes.add("原版语言装入失败：" + e);
        }
    }

    private void loadJar(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            String ns = null;
            boolean zhLoaded = false;
            boolean enLoaded = false;
            for (var e = zf.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                Matcher lm = LANG_ENTRY.matcher(ze.getName());
                if (lm.find()) {
                    String n = lm.group(1);
                    String lang = lm.group(2);
                    if (zhLoaded && enLoaded) {
                        continue;
                    }
                    try (InputStream in = zf.getInputStream(ze)) {
                        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        if ("zh_cn".equals(lang)) {
                            zhLoaded = true;
                            zh.putAll(flat(text));
                        } else {
                            enLoaded = true;
                            en.putAll(flat(text));
                        }
                        if (ns == null) {
                            ns = n;
                        }
                    }
                }
            }
            String name = null;
            ZipEntry toml = zf.getEntry("META-INF/mods.toml");
            if (toml == null) {
                toml = zf.getEntry("META-INF/neoforge.mods.toml");
            }
            if (toml != null) {
                try (InputStream in = zf.getInputStream(toml)) {
                    String t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher dm = MODS_BLOCK.matcher(t);
                    if (dm.find()) {
                        name = dm.group(2);
                    }
                    Matcher im = MOD_ID.matcher(t);
                    if (im.find()) {
                        ns = im.group(1);
                    }
                }
            }
            if (name == null) {
                ZipEntry fmj = zf.getEntry("fabric.mod.json");
                if (fmj != null) {
                    try (InputStream in = zf.getInputStream(fmj)) {
                        Matcher nm = FABRIC_NAME.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        if (nm.find()) {
                            name = nm.group(1);
                        }
                    }
                }
            }
            if (ns != null) {
                nsJar.putIfAbsent(ns, jar);
                if (name != null) {
                    modNames.putIfAbsent(ns, name);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Map<String, String> flat(String json) {
        Map<String, String> m = new HashMap<>();
        Matcher mm = FLAT.matcher(json);
        while (mm.find()) {
            m.put(unescape(mm.group(1)), unescape(mm.group(2)));
        }
        return m;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String pick(String key) {
        String v = zh.get(key);
        if (v == null || v.isBlank()) {
            v = en.get(key);
        }
        return (v == null || v.isBlank()) ? null : v;
    }

    public String byKey(String key) {
        String v = pick(key);
        return v == null ? null : v;
    }

    public String entity(String id) {
        String[] p = split(id);
        return orId(pick("entity." + p[0] + "." + p[1]), id);
    }

    public String itemOrBlock(String id) {
        String[] p = split(id);
        String v = pick("item." + p[0] + "." + p[1]);
        if (v == null) {
            v = pick("block." + p[0] + "." + p[1]);
        }
        return orId(v, id);
    }

    public String advancement(String id) {
        String[] p = split(id);
        String path = p[1].replace('/', '.');
        String v = pick("advancements." + p[0] + "." + path + ".title");
        return orId(v, id);
    }

    public String mod(String ns) {
        String v = modNames.get(ns);
        return (v == null || v.isBlank()) ? ns : v;
    }

    public Path jarOf(String ns) {
        return nsJar.get(ns);
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zhKeys", zh.size());
        m.put("enKeys", en.size());
        m.put("modsNamed", modNames.size());
        m.put("notes", notes);
        return m;
    }

    private static String orId(String v, String id) {
        return (v == null || v.isBlank()) ? id : v;
    }

    private static String[] split(String id) {
        int i = id.indexOf(':');
        return i < 0 ? new String[]{"minecraft", id} : new String[]{id.substring(0, i), id.substring(i + 1)};
    }
}
