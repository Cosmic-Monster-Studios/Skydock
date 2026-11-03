package dev.skydock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps every player-facing string in en_us.json: referenced keys must exist, and no text may be hard-coded. */
class LangCoverageTest {
    private static final Path MAIN = Path.of("src", "main");
    private static final Pattern KEY = Pattern.compile("\"((?:message|status|error|hud|gui|key)\\.skydock\\.[a-z0-9_.]+|pattern\\.skydock\\.[a-z0-9_]+\\.(?:name|description))\"");
    private static final Pattern LITERAL = Pattern.compile("Component\\.literal\\(|\\btell\\([^,()]+,\\s*\"(?!(?:message|status|error)\\.skydock\\.)");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve("java"))) { return files.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
    }

    private static JsonObject lang() throws IOException {
        return JsonParser.parseString(Files.readString(MAIN.resolve("resources/assets/skydock/lang/en_us.json"))).getAsJsonObject();
    }

    @Test void everyReferencedKeyExists() throws IOException {
        JsonObject lang = lang(); TreeSet<String> missing = new TreeSet<>();
        for (Path source : sources()) {
            Matcher matcher = KEY.matcher(Files.readString(source));
            while (matcher.find()) if (!lang.has(matcher.group(1))) missing.add(matcher.group(1) + "  (" + source.getFileName() + ")");
        }
        assertTrue(missing.isEmpty(), "Keys missing from en_us.json: " + missing);
    }

    /** Assembly failures derive their cancelled/paused status keys from the error key, so each variant must exist. */
    @Test void derivedAssemblyStatusKeysExist() throws IOException {
        JsonObject lang = lang(); TreeSet<String> missing = new TreeSet<>();
        for (String key : lang.keySet()) if (key.startsWith("error.skydock.assembly_")) {
            String suffix = key.substring("error.skydock.".length());
            if (!lang.has("status.skydock.cancelled_" + suffix)) missing.add("status.skydock.cancelled_" + suffix);
        }
        for (String suffix : List.of("assembly_entities", "assembly_ship")) if (!lang.has("status.skydock.paused_" + suffix)) missing.add("status.skydock.paused_" + suffix);
        assertTrue(missing.isEmpty(), "Derived status keys missing: " + missing);
    }

    @Test void noLiteralPlayerText() throws IOException {
        TreeSet<String> offenders = new TreeSet<>();
        for (Path source : sources()) {
            if (source.getFileName().toString().equals("SkydockCommands.java")) continue; // operator diagnostics
            String[] lines = Files.readString(source).split("\n");
            for (int i = 0; i < lines.length; i++) if (LITERAL.matcher(lines[i]).find()) offenders.add(source.getFileName() + ":" + (i + 1) + "  " + lines[i].strip());
        }
        assertTrue(offenders.isEmpty(), "Hard-coded player text: " + offenders);
    }
}
