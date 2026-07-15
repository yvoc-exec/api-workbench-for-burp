package burp.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRequestParameterTest {
    @Test
    void applyToDeepCopiesParameterMetadata() {
        ApiRequest source = new ApiRequest();
        ApiRequest.Parameter parameter = completeParameter();
        source.parameters.add(parameter);
        source.parameters.add(null);

        ApiRequest copy = source.applyTo(new ApiRequest());

        assertThat(copy.parameters).isNotSameAs(source.parameters).hasSize(2);
        assertThat(copy.parameters.get(0)).isNotSameAs(parameter)
                .usingRecursiveComparison().isEqualTo(parameter);
        assertThat(copy.parameters.get(1)).isNull();
        copy.parameters.get(0).key = "changed";
        assertThat(parameter.key).isEqualTo("tag");
    }

    @Test
    void semanticFingerprintChangesWhenTransportParameterChanges() {
        ApiRequest request = requestWithParameter();
        String before = request.computeSemanticFingerprint();
        request.parameters.get(0).rawValue = "changed";
        assertThat(request.computeSemanticFingerprint()).isNotEqualTo(before);
    }

    @Test
    void semanticFingerprintChangesWhenParameterEnabledStateChanges() {
        ApiRequest request = requestWithParameter();
        String before = request.computeSemanticFingerprint();
        request.parameters.get(0).disabled = true;
        assertThat(request.computeSemanticFingerprint()).isNotEqualTo(before);
    }

    private static ApiRequest requestWithParameter() {
        ApiRequest request = new ApiRequest();
        request.method = "GET";
        request.url = "https://example.test";
        request.parameters.add(completeParameter());
        return request;
    }

    private static ApiRequest.Parameter completeParameter() {
        ApiRequest.Parameter parameter = new ApiRequest.Parameter("query", "tag", "one");
        parameter.rawKey = "t%61g";
        parameter.rawValue = "%6Fne";
        parameter.valuePresent = true;
        parameter.disabled = false;
        parameter.required = true;
        parameter.type = "string";
        parameter.description = "A tag";
        parameter.style = "form";
        parameter.explode = Boolean.FALSE;
        parameter.allowReserved = true;
        parameter.source = "test";
        return parameter;
    }
}
