package burp.parser;

import burp.models.ApiRequest;
import burp.utils.RequestParameterSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class QueryParameterImportSupport {
    private QueryParameterImportSupport() {
    }

    static List<ApiRequest.Parameter> reconcileStructuredQueryWithRawUrl(
            String url,
            List<ApiRequest.Parameter> structuredParameters,
            String rawSource,
            String unmatchedRawSource) {
        List<ApiRequest.Parameter> rawParameters =
                RequestParameterSupport.parseQueryParameters(url, rawSource);
        if (structuredParameters == null) {
            return new ArrayList<>(rawParameters);
        }

        List<ApiRequest.Parameter> result = new ArrayList<>(structuredParameters);
        boolean[] consumedRaw = new boolean[rawParameters.size()];
        for (ApiRequest.Parameter structured : structuredParameters) {
            if (structured == null) {
                continue;
            }
            int match = findFirstUnconsumedRawMatch(rawParameters, consumedRaw, structured, true);
            if (match < 0) {
                match = findFirstUnconsumedRawMatch(rawParameters, consumedRaw, structured, false);
            }
            if (match >= 0) {
                ApiRequest.Parameter raw = rawParameters.get(match);
                if (structured.rawKey == null) {
                    structured.rawKey = raw.rawKey;
                }
                if (structured.rawValue == null) {
                    structured.rawValue = raw.rawValue;
                }
                consumedRaw[match] = true;
            }
        }
        for (int i = 0; i < rawParameters.size(); i++) {
            if (!consumedRaw[i]) {
                ApiRequest.Parameter raw = rawParameters.get(i);
                if (raw != null) {
                    raw.source = unmatchedRawSource;
                }
                result.add(raw);
            }
        }
        return result;
    }

    private static int findFirstUnconsumedRawMatch(
            List<ApiRequest.Parameter> rawParameters,
            boolean[] consumedRaw,
            ApiRequest.Parameter structured,
            boolean requireMatchingValuePresence) {
        for (int i = 0; i < rawParameters.size(); i++) {
            if (!consumedRaw[i]
                    && equivalentDecodedParameter(rawParameters.get(i), structured,
                    requireMatchingValuePresence)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equivalentDecodedParameter(
            ApiRequest.Parameter left,
            ApiRequest.Parameter right,
            boolean requireMatchingValuePresence) {
        return left != null
                && right != null
                && Objects.equals(left.key, right.key)
                && Objects.equals(normalizedValue(left.value), normalizedValue(right.value))
                && (!requireMatchingValuePresence || left.valuePresent == right.valuePresent);
    }

    private static String normalizedValue(String value) {
        return value != null ? value : "";
    }
}
