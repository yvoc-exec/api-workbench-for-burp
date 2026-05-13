package burp.parser;

import java.io.File;
import java.util.*;

/**
 * Registry of all collection parsers. Auto-detects format.
 */
public class ParserRegistry {
    private final List<CollectionParser> parsers = new ArrayList<>();

    public ParserRegistry() {
        parsers.add(new PostmanParser());
        parsers.add(new BrunoParser());
        parsers.add(new OpenApiParser());
        parsers.add(new InsomniaParser());
        parsers.add(new HarParser());
    }

    public CollectionParser detectParser(File file) {
        for (CollectionParser parser : parsers) {
            if (parser.canParse(file)) {
                return parser;
            }
        }
        return null;
    }

    public List<CollectionParser> getAllParsers() {
        return new ArrayList<>(parsers);
    }

    public String[] getAllSupportedExtensions() {
        Set<String> exts = new HashSet<>();
        for (CollectionParser p : parsers) {
            exts.addAll(Arrays.asList(p.getSupportedExtensions()));
        }
        return exts.toArray(new String[0]);
    }
}
