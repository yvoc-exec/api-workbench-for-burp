package burp.parser;

import burp.models.ApiCollection;
import burp.scripts.capabilities.ScriptImportOrigin;
import burp.scripts.capabilities.ScriptTrustImportService;
import burp.scripts.capabilities.ScriptTrustReviewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Registry of all collection parsers. Auto-detects format and applies the
 * centralized file-import script trust policy before parsed collections leave
 * a registry-owned parser.
 */
public class ParserRegistry {
    private static volatile Function<ScriptTrustReviewModel, ScriptTrustReviewModel.Decision> trustReviewHandler;

    private final List<CollectionParser> parsers = new ArrayList<>();
    private final ScriptTrustImportService trustImportService;

    public ParserRegistry() {
        this(new ScriptTrustImportService());
    }

    ParserRegistry(ScriptTrustImportService trustImportService) {
        this.trustImportService = trustImportService != null ? trustImportService : new ScriptTrustImportService();
        parsers.add(new TrustAwareApiWorkbenchCollectionParser());
        parsers.add(new TrustAwarePostmanParser());
        parsers.add(new TrustAwareBrunoParser());
        parsers.add(new TrustAwareOpenApiParser());
        parsers.add(new TrustAwareInsomniaParser());
        parsers.add(new TrustAwareHarParser());
    }

    public static void setScriptTrustReviewHandler(
            Function<ScriptTrustReviewModel, ScriptTrustReviewModel.Decision> handler) {
        trustReviewHandler = handler;
    }

    public static void clearScriptTrustReviewHandler() {
        trustReviewHandler = null;
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
        for (CollectionParser parser : parsers) {
            exts.addAll(Arrays.asList(parser.getSupportedExtensions()));
        }
        return exts.toArray(new String[0]);
    }

    private ApiCollection finalizeFileImport(ApiCollection collection) throws ScriptImportCancelledException {
        if (collection == null) {
            return null;
        }
        ScriptTrustReviewModel model = trustImportService.prepare(
                List.of(collection),
                ScriptImportOrigin.FILE_IMPORT);
        if (model.totalScriptCount() == 0) {
            return collection;
        }
        Function<ScriptTrustReviewModel, ScriptTrustReviewModel.Decision> handler = trustReviewHandler;
        ScriptTrustReviewModel.Decision decision = handler != null
                ? handler.apply(model)
                : ScriptTrustReviewModel.Decision.KEEP_ALL_DISABLED;
        model.setDecision(decision);
        if (decision == ScriptTrustReviewModel.Decision.CANCEL_IMPORT) {
            throw new ScriptImportCancelledException("Collection import cancelled during script trust review.");
        }
        trustImportService.applyDecision(List.of(collection), model);
        return collection;
    }

    private final class TrustAwareApiWorkbenchCollectionParser extends ApiWorkbenchCollectionParser {
        @Override
        public ApiCollection parse(File file) throws Exception {
            return finalizeFileImport(super.parse(file));
        }
    }

    private final class TrustAwarePostmanParser extends PostmanParser {
        @Override
        public ApiCollection parse(File file) throws Exception {
            return finalizeFileImport(super.parse(file));
        }
    }

    private final class TrustAwareBrunoParser extends BrunoParser {
        @Override
        public ApiCollection parse(File file) throws Exception {
            return finalizeFileImport(super.parse(file));
        }
    }

    private final class TrustAwareOpenApiParser extends OpenApiParser {
        @Override
        public ApiCollection parse(File file) throws Exception {
            return finalizeFileImport(super.parse(file));
        }
    }

    private final class TrustAwareInsomniaParser extends InsomniaParser {
        @Override
        public ApiCollection parse(File file) throws Exception {
            return finalizeFileImport(super.parse(file));
        }
    }

    private final class TrustAwareHarParser extends HarParser {
        @Override
        public ApiCollection parse(File file) throws Exception {
            return finalizeFileImport(super.parse(file));
        }
    }

    public static final class ScriptImportCancelledException extends Exception {
        public ScriptImportCancelledException(String message) {
            super(message);
        }
    }
}
