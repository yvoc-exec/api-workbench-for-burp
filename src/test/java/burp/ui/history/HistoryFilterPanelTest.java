package burp.ui.history;

import burp.history.HistoryEntry;
import burp.history.HistoryFilterCriteria;
import burp.history.HistorySource;
import burp.history.HistoryStore;
import burp.testsupport.HistoryTestFixtures;
import burp.testsupport.ImporterPanelTestSupport;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;
import java.awt.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryFilterPanelTest {
    private static final Duration DEBOUNCE_WAIT = Duration.ofMillis(900);
    private static final Duration QUIET_WAIT = Duration.ofMillis(450);

    @Test
    void everyTextFieldUpdatesWithoutEnter() throws Exception {
        List<FieldCase> cases = List.of(
                new FieldCase("freeTextField", "login", criteria -> assertThat(criteria.freeText).isEqualTo("login")),
                new FieldCase("exactStatusField", "404", criteria -> assertThat(criteria.exactStatusCode).isEqualTo(404)),
                new FieldCase("collectionField", "Payments", criteria -> assertThat(criteria.collection).isEqualTo("Payments")),
                new FieldCase("folderField", "Admin", criteria -> assertThat(criteria.folder).isEqualTo("Admin")),
                new FieldCase("requestField", "Create User", criteria -> assertThat(criteria.requestName).isEqualTo("Create User")),
                new FieldCase("environmentField", "UAT", criteria -> assertThat(criteria.environment).isEqualTo("UAT")),
                new FieldCase("tagField", "Auth, Evidence", criteria -> assertThat(criteria.tagText).isEqualTo("Auth, Evidence")),
                new FieldCase("fromField", "2026-06-01T00:00:00Z", criteria -> assertThat(criteria.fromTimestamp).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))),
                new FieldCase("toField", "2026-06-30T23:59:59Z", criteria -> assertThat(criteria.toTimestamp).isEqualTo(Instant.parse("2026-06-30T23:59:59Z"))),
                new FieldCase("attemptField", "2", criteria -> assertThat(criteria.attemptNumber).isEqualTo(2)),
                new FieldCase("totalAttemptsField", "3", criteria -> assertThat(criteria.totalAttempts).isEqualTo(3))
        );

        for (FieldCase testCase : cases) {
            HistoryFilterPanel panel = new HistoryFilterPanel();
            AtomicInteger callbacks = new AtomicInteger();
            panel.setChangeListener(callbacks::incrementAndGet);
            setText(panel, testCase.fieldName(), testCase.text());

            awaitCondition(() -> callbacks.get() == 1, DEBOUNCE_WAIT);
            assertThat(callbacks.get()).isEqualTo(1);
            testCase.assertCriteria().accept(panel.getCriteria());
        }
    }

    @Test
    void rapidTypingIsCoalesced() throws Exception {
        HistoryFilterPanel panel = new HistoryFilterPanel();
        AtomicInteger callbacks = new AtomicInteger();
        panel.setChangeListener(callbacks::incrementAndGet);

        setText(panel, "freeTextField", "l");
        setText(panel, "freeTextField", "lo");
        setText(panel, "freeTextField", "log");
        setText(panel, "freeTextField", "login");

        awaitCondition(() -> callbacks.get() >= 1, DEBOUNCE_WAIT);
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(panel.getCriteria().freeText).isEqualTo("login");
    }

    @Test
    void enterAppliesImmediatelyWithoutDelayedDuplicate() throws Exception {
        HistoryFilterPanel panel = new HistoryFilterPanel();
        AtomicInteger callbacks = new AtomicInteger();
        panel.setChangeListener(callbacks::incrementAndGet);

        setText(panel, "freeTextField", "login");
        clickActionOnEdt((JTextField) textField(panel, "freeTextField"));

        awaitCondition(() -> callbacks.get() == 1, DEBOUNCE_WAIT);
        assertThat(callbacks.get()).isEqualTo(1);
        pauseBeyondDebounce();
        assertThat(callbacks.get()).isEqualTo(1);
    }

    @Test
    void setCriteriaIsSilent() throws Exception {
        HistoryFilterPanel panel = new HistoryFilterPanel();
        AtomicInteger callbacks = new AtomicInteger();
        panel.setChangeListener(callbacks::incrementAndGet);

        HistoryFilterCriteria criteria = new HistoryFilterCriteria();
        criteria.freeText = "login";
        criteria.source = HistorySource.RUNNER;
        criteria.method = "POST";
        criteria.statusClass = "4xx";
        criteria.exactStatusCode = 404;
        criteria.collection = "Payments";
        criteria.folder = "Admin";
        criteria.requestName = "Create User";
        criteria.environment = "UAT";
        criteria.resultType = burp.history.HistoryResult.ERROR;
        criteria.pinnedState = "Pinned";
        criteria.tagText = "Auth";
        criteria.fromTimestamp = Instant.parse("2026-06-01T00:00:00Z");
        criteria.toTimestamp = Instant.parse("2026-06-30T23:59:59Z");
        criteria.hasResponseBody = Boolean.TRUE;
        criteria.hasError = Boolean.TRUE;
        criteria.hasAssertionFailure = Boolean.TRUE;
        criteria.attemptNumber = 2;
        criteria.totalAttempts = 3;
        criteria.retriesOnly = Boolean.TRUE;

        SwingUtilities.invokeAndWait(() -> panel.setCriteria(criteria));

        pauseBeyondDebounce();
        assertThat(callbacks.get()).isZero();
        assertThat(textField(panel, "freeTextField").getText()).isEqualTo("login");
        assertThat(((JComboBox<?>) component(panel, "sourceCombo")).getSelectedItem()).isEqualTo("Runner");
        assertThat(((JComboBox<?>) component(panel, "methodCombo")).getSelectedItem()).isEqualTo("POST");
        assertThat(((JComboBox<?>) component(panel, "statusClassCombo")).getSelectedItem()).isEqualTo("4xx");
        assertThat(textField(panel, "exactStatusField").getText()).isEqualTo("404");
        assertThat(textField(panel, "collectionField").getText()).isEqualTo("Payments");
        assertThat(textField(panel, "folderField").getText()).isEqualTo("Admin");
        assertThat(textField(panel, "requestField").getText()).isEqualTo("Create User");
        assertThat(textField(panel, "environmentField").getText()).isEqualTo("UAT");
        assertThat(((JComboBox<?>) component(panel, "resultCombo")).getSelectedItem()).isEqualTo("Error");
        assertThat(((JComboBox<?>) component(panel, "pinnedStateCombo")).getSelectedItem()).isEqualTo("Pinned");
        assertThat(textField(panel, "tagField").getText()).isEqualTo("Auth");
        assertThat(textField(panel, "fromField").getText()).isEqualTo("2026-06-01T00:00:00Z");
        assertThat(textField(panel, "toField").getText()).isEqualTo("2026-06-30T23:59:59Z");
        assertThat(((JCheckBox) component(panel, "hasResponseBodyBox")).isSelected()).isTrue();
        assertThat(((JCheckBox) component(panel, "hasErrorBox")).isSelected()).isTrue();
        assertThat(((JCheckBox) component(panel, "hasAssertionFailureBox")).isSelected()).isTrue();
        assertThat(textField(panel, "attemptField").getText()).isEqualTo("2");
        assertThat(textField(panel, "totalAttemptsField").getText()).isEqualTo("3");
        assertThat(((JCheckBox) component(panel, "retriesOnlyBox")).isSelected()).isTrue();
        assertThat(panel.getCriteria().freeText).isEqualTo("login");
        assertThat(panel.getCriteria().source).isEqualTo(HistorySource.RUNNER);
        assertThat(panel.getCriteria().resultType).isEqualTo(burp.history.HistoryResult.ERROR);
        assertThat(panel.getCriteria().pinnedState).isEqualTo("Pinned");
        assertThat(panel.getCriteria().tagText).isEqualTo("Auth");
    }

    @Test
    void pinnedStateAndTagSearchUpdateImmediately() throws Exception {
        HistoryFilterPanel panel = new HistoryFilterPanel();
        AtomicInteger callbacks = new AtomicInteger();
        panel.setChangeListener(callbacks::incrementAndGet);

        SwingUtilities.invokeAndWait(() -> {
            ((JComboBox<?>) component(panel, "pinnedStateCombo")).setSelectedItem("Pinned");
            textField(panel, "tagField").setText("Evidence");
        });

        awaitCondition(() -> callbacks.get() == 1, DEBOUNCE_WAIT);
        assertThat(panel.getCriteria().pinnedState).isEqualTo("Pinned");
        assertThat(panel.getCriteria().tagText).isEqualTo("Evidence");
    }

    @Test
    void clearFiltersNotifiesExactlyOnceAndCancelsPendingDebounce() throws Exception {
        HistoryFilterPanel panel = new HistoryFilterPanel();
        AtomicInteger callbacks = new AtomicInteger();
        panel.setChangeListener(callbacks::incrementAndGet);

        setText(panel, "freeTextField", "login");
        awaitCondition(() -> callbacks.get() == 1, DEBOUNCE_WAIT);

        callbacks.set(0);
        clickClear(panel);
        awaitCondition(() -> callbacks.get() == 1, DEBOUNCE_WAIT);
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(panel.getCriteria().freeText).isNull();

        callbacks.set(0);
        setText(panel, "freeTextField", "login");
        clickClear(panel);
        awaitCondition(() -> callbacks.get() == 1, DEBOUNCE_WAIT);
        pauseBeyondDebounce();
        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(panel.getCriteria().freeText).isNull();
    }

    @Test
    void historyTableFiltersWithoutEnter() throws Exception {
        HistoryStore store = new HistoryStore();
        HistoryEntry first = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleWorkbenchEntry(),
                "history-workbench", Instant.parse("2026-06-15T01:00:00Z"));
        HistoryEntry second = HistoryTestFixtures.copyEntry(HistoryTestFixtures.sampleRunnerEntry(),
                "history-runner", Instant.parse("2026-06-15T01:01:00Z"));
        first.id = "history-filter-control";
        first.analystNotes = "control-only-token-alpha";
        second.id = "history-filter-target";
        second.analystNotes = "target-only-live-filter-token-beta";
        store.addAll(List.of(first, second));

        AtomicReference<HistoryPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelRef.set(new HistoryPanel(
                store,
                new burp.history.HistoryExportService(),
                new burp.history.HistoryDiffService(),
                new HistoryLoadResultNotifier())));
        HistoryPanel panel = panelRef.get();
        assertThat(panel).as("HistoryPanel should be initialized on EDT").isNotNull();
        assertThat(rowCountOnEdt(panel)).isEqualTo(2);

        setFilterAndAwaitRowCount(panel, "target-only-live-filter-token-beta", 1);
        assertThat(visibleEntryIdsOnEdt(panel)).containsExactly("history-filter-target");

        setFilterAndAwaitRowCount(panel, "", 2);
        assertThat(visibleEntryIdsOnEdt(panel))
                .containsExactlyInAnyOrder("history-filter-control", "history-filter-target");
    }

    private static void clickClear(HistoryFilterPanel panel) throws Exception {
        SwingUtilities.invokeAndWait(panel.getClearButton()::doClick);
    }

    private static void clickActionOnEdt(JTextField field) throws Exception {
        SwingUtilities.invokeAndWait(field::postActionEvent);
    }

    private static void setText(HistoryFilterPanel panel, String fieldName, String text) throws Exception {
        JTextField field = textField(panel, fieldName);
        SwingUtilities.invokeAndWait(() -> field.setText(text));
    }

    private static void setFilterAndAwaitRowCount(
            HistoryPanel panel,
            String text,
            int expectedRowCount) throws Exception {
        CountDownLatch rowCountReached = new CountDownLatch(1);
        AtomicReference<TableModel> modelRef = new AtomicReference<>();
        TableModelListener listener = event -> {
            if (panel.getHistoryTable().getRowCount() == expectedRowCount) {
                rowCountReached.countDown();
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                TableModel model = panel.getHistoryTable().getModel();
                modelRef.set(model);
                model.addTableModelListener(listener);
                textField(panel.getFilterPanel(), "freeTextField").setText(text);
                if (panel.getHistoryTable().getRowCount() == expectedRowCount) {
                    rowCountReached.countDown();
                }
            });

            if (!rowCountReached.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for history row count "
                        + expectedRowCount
                        + "; actual=" + rowCountOnEdt(panel)
                        + "; filterText='" + text + "'"
                        + "; criteriaFreeText='" + criteriaFreeTextOnEdt(panel) + "'"
                        + "; visibleEntryIds=" + visibleEntryIdsOnEdt(panel));
            }
            assertThat(rowCountOnEdt(panel)).isEqualTo(expectedRowCount);
        } finally {
            TableModel model = modelRef.get();
            if (model != null) {
                SwingUtilities.invokeAndWait(() -> model.removeTableModelListener(listener));
            }
        }
    }

    private static int rowCountOnEdt(HistoryPanel panel) {
        if (SwingUtilities.isEventDispatchThread()) {
            return panel.getHistoryTable().getRowCount();
        }
        AtomicInteger rowCount = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(
                    () -> rowCount.set(panel.getHistoryTable().getRowCount()));
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to read history row count on EDT",
                    failure);
        }
        return rowCount.get();
    }

    private static List<String> visibleEntryIdsOnEdt(HistoryPanel panel) {
        if (SwingUtilities.isEventDispatchThread()) {
            return visibleEntryIds(panel);
        }
        AtomicReference<List<String>> ids = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> ids.set(visibleEntryIds(panel)));
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to read visible history entry IDs on EDT",
                    failure);
        }
        return ids.get();
    }

    private static List<String> visibleEntryIds(HistoryPanel panel) {
        HistoryTableModel model = (HistoryTableModel) panel.getHistoryTable().getModel();
        List<String> ids = new ArrayList<>();
        for (HistoryEntry entry : model.getEntries()) {
            ids.add(entry.id);
        }
        return ids;
    }

    private static String criteriaFreeTextOnEdt(HistoryPanel panel) {
        if (SwingUtilities.isEventDispatchThread()) {
            return panel.getFilterPanel().getCriteria().freeText;
        }
        AtomicReference<String> freeText = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(
                    () -> freeText.set(panel.getFilterPanel().getCriteria().freeText));
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Unable to read filter criteria on EDT",
                    failure);
        }
        return freeText.get();
    }

    private static void pauseBeyondDebounce() throws Exception {
        Thread.sleep(QUIET_WAIT.toMillis());
        ImporterPanelTestSupport.awaitEdt();
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ImporterPanelTestSupport.awaitEdt();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        ImporterPanelTestSupport.awaitEdt();
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static JTextField textField(Object target, String fieldName) {
        return component(target, fieldName);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T component(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to access " + fieldName, e);
        }
    }

    private record FieldCase(String fieldName, String text, java.util.function.Consumer<HistoryFilterCriteria> assertCriteria) {
    }
}
