package burp.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

public class HistoryExportService {
    private final HistoryJsonExportService jsonExportService = new HistoryJsonExportService();
    private final HistoryCsvExportService csvExportService = new HistoryCsvExportService();
    private final HistoryHarExportService harExportService = new HistoryHarExportService();

    public String exportJson(Collection<HistoryEntry> entries) {
        return jsonExportService.export(entries);
    }

    public String exportCsv(Collection<HistoryEntry> entries) {
        return csvExportService.export(entries);
    }

    public String exportHar(Collection<HistoryEntry> entries) {
        return harExportService.export(entries);
    }

    public void writeJson(Collection<HistoryEntry> entries, Path output) throws IOException {
        jsonExportService.write(entries, output);
    }

    public void writeCsv(Collection<HistoryEntry> entries, Path output) throws IOException {
        csvExportService.write(entries, output);
    }

    public void writeHar(Collection<HistoryEntry> entries, Path output) throws IOException {
        harExportService.write(entries, output);
    }
}
