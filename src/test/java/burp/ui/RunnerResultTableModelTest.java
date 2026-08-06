package burp.ui;

import burp.models.RunnerResult;
import burp.models.RunnerResultSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerResultTableModelTest {
    @Test
    void modelRetainsSummariesAndReturnsFreshCompactCompatibilityResults() {
        RunnerResult result = new RunnerResult();
        result.requestName = "Login";
        result.host = "api.example.test";
        result.path = "/login";
        result.method = "POST";
        result.statusCode = 200;
        result.success = true;
        result.responseBody = "secret-body";
        result.rawRequestBytes = new byte[]{1, 2, 3};
        RunnerResultTableModel model = new RunnerResultTableModel();

        model.addResult(result);

        assertThat(model.getSummaries()).hasSize(1);
        assertThat(model.getSummaryAt(0)).isInstanceOf(RunnerResultSummary.class);
        RunnerResult first = model.getResultAt(0);
        RunnerResult second = model.getResultAt(0);
        assertThat(first).isNotSameAs(second);
        assertThat(first.responseBody).isNull();
        assertThat(first.rawRequestBytes).isNull();
        first.requestName = "mutated";
        assertThat(model.getResultAt(0).requestName).isEqualTo("Login");
        assertThat(RunnerResultTableModel.class.getDeclaredFields())
                .extracting(Field::getType)
                .doesNotContain(RunnerResult.class);
    }
}
