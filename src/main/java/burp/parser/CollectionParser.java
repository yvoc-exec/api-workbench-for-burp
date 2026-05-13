package burp.parser;

import burp.models.ApiCollection;
import java.io.File;

/**
 * Interface for all collection parsers.
 */
public interface CollectionParser {
    boolean canParse(File file);
    ApiCollection parse(File file) throws Exception;
    String getFormatName();
    String[] getSupportedExtensions();
}
