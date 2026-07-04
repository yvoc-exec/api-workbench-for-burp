package burp.ui.traffic;

import burp.UniversalImporter;
import burp.history.HistoryEntry;
import burp.importer.BurpTrafficConversionResult;
import burp.importer.BurpTrafficImportPlan;
import burp.importer.BurpTrafficImportService;
import burp.importer.BurpTrafficSelection;
import burp.models.ApiCollection;
import burp.models.ApiRequest;
import burp.models.WorkspaceState;
import burp.ui.ImporterPanel;
import burp.ui.tree.RequestTreeNamingPolicy;
import burp.ui.tree.RequestTreePathService;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates detached traffic conversion and one-shot workspace replacement.
 * No HTTP operation is available from this class.
 */
public final class BurpTrafficWorkflowCoordinator {
    @FunctionalInterface
    public interface DestinationPresenter {
        boolean review(Window owner, TrafficDestinationDialogModel model);
    }

    @FunctionalInterface
    public interface MessagePresenter {
        void show(java.awt.Component parent, String title, String message, int messageType);
    }

    private final UniversalImporter importer;
    private final BurpTrafficImportService conversionService;
    private final DestinationPresenter destinationPresenter;
    private final MessagePresenter messagePresenter;

    public BurpTrafficWorkflowCoordinator(UniversalImporter importer) {
        this(importer,
                new BurpTrafficImportService(),
                (owner, model) -> new TrafficDestinationDialog(owner, model).showDialog(),
                BurpTrafficWorkflowCoordinator::showMessage);
    }

    public BurpTrafficWorkflowCoordinator(UniversalImporter importer,
                                          BurpTrafficImportService conversionService,
                                          DestinationPresenter destinationPresenter,
                                          MessagePresenter messagePresenter) {
        this.importer = Objects.requireNonNull(importer, "importer");
        this.conversionService = conversionService != null ? conversionService : new BurpTrafficImportService();
        this.destinationPresenter = destinationPresenter != null
                ? destinationPresenter
                : (owner, model) -> new TrafficDestinationDialog(owner, model).showDialog();
        this.messagePresenter = messagePresenter != null ? messagePresenter : BurpTrafficWorkflowCoordinator::showMessage;
    }

    public void importTraffic(List<BurpTrafficSelection> selections, boolean queueAfterImport) {
        List<BurpTrafficSelection> detached = copySelections(selections);
        if (detached.isEmpty()) {
            return;
        }
        Runnable action = () -> importOnEdt(detached, queueAfterImport);
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException failure) {
            logSafeError("Burp traffic import failed", failure.getCause());
        }
    }

    private void importOnEdt(List<BurpTrafficSelection> selections, boolean queueAfterImport) {
        ImporterPanel ui = importer.getUI();
        if (ui == null) {
            return;
        }
        BurpTrafficConversionResult conversion = conversionService.convert(selections);
        if (conversion.hasFailures()) {
            String message = safeFailureSummary(conversion);
            messagePresenter.show(ui.getPanel(), "Traffic Import Failed", message, JOptionPane.ERROR_MESSAGE);
            return;
        }

        WorkspaceState before = WorkspaceState.copyOf(ui.getWorkspaceStateSnapshotFromModel());
        List<ApiCollection> collections = before.collections != null ? before.collections : new ArrayList<>();
        boolean responseAvailable = conversion.historyEntries.stream().anyMatch(Objects::nonNull);
        TrafficDestinationDialogModel destination = new TrafficDestinationDialogModel(
                collections,
                conversion.requests,
                responseAvailable,
                queueAfterImport);
        Window owner = SwingUtilities.getWindowAncestor(ui.getPanel());
        if (!destinationPresenter.review(owner, destination) || destination.isCancelled()) {
            return;
        }
        destination.confirm();
        if (!destination.isValid()) {
            messagePresenter.show(ui.getPanel(), "Invalid Destination",
                    String.join("\n", destination.validationErrors()), JOptionPane.WARNING_MESSAGE);
            return;
        }

        BurpTrafficImportPlan plan = buildPlan(destination, conversion);
        WorkspaceState after;
        try {
            after = applyPlan(before, plan);
        } catch (RuntimeException invalidPlan) {
            messagePresenter.show(ui.getPanel(), "Traffic Import Failed", safeMessage(invalidPlan), JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            ui.restoreWorkspaceState(after);
            importer.requestWorkspaceStateSaveNow();
            long queuedCount = plan.queueInRunner
                    ? plan.requests.stream().filter(request -> request != null && !request.disabled).count()
                    : 0L;
            String summary = plan.requestCount() + " requests imported; "
                    + queuedCount + " queued; " + plan.historyCount() + " History entries captured.";
            messagePresenter.show(ui.getPanel(), "Burp Traffic Imported", summary, JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException commitFailure) {
            try {
                ui.restoreWorkspaceState(before);
            } catch (RuntimeException rollbackFailure) {
                commitFailure.addSuppressed(rollbackFailure);
            }
            logSafeError("Burp traffic import transaction failed", commitFailure);
            messagePresenter.show(ui.getPanel(), "Traffic Import Failed",
                    "No traffic was imported. " + safeMessage(commitFailure), JOptionPane.ERROR_MESSAGE);
        }
    }

    BurpTrafficImportPlan buildPlan(TrafficDestinationDialogModel destination,
                                    BurpTrafficConversionResult conversion) {
        List<ApiRequest> requests = new ArrayList<>();
        List<String> names = destination.generatedNames();
        for (int i = 0; i < conversion.requests.size(); i++) {
            ApiRequest source = conversion.requests.get(i);
            ApiRequest request = source != null ? source.applyTo(new ApiRequest()) : null;
            if (request == null) {
                throw new IllegalArgumentException("Traffic conversion produced an empty request.");
            }
            request.id = request.id != null && !request.id.isBlank() ? request.id : UUID.randomUUID().toString();
            request.name = names.get(i);
            request.path = RequestTreePathService.normalizeFolderPath(destination.destinationFolder());
            request.sourceCollection = destination.effectiveCollectionName();
            request.disabled = false;
            if (!destination.preserveExactTransport()) {
                request.invalidateExactTransport("EXACT_TRANSPORT_DISABLED_ON_IMPORT");
            }
            requests.add(request);
        }

        List<HistoryEntry> history = new ArrayList<>();
        if (destination.captureResponses()) {
            for (int i = 0; i < conversion.historyEntries.size() && i < requests.size(); i++) {
                HistoryEntry entry = HistoryEntry.copyOf(conversion.historyEntries.get(i));
                ApiRequest request = requests.get(i);
                if (entry == null || request == null) {
                    continue;
                }
                entry.collectionName = destination.effectiveCollectionName();
                entry.requestId = request.id;
                entry.requestName = request.name;
                entry.folderPath = request.path;
                if (entry.requestSnapshot != null && entry.requestSnapshot.authoredRequest != null) {
                    request.applyTo(entry.requestSnapshot.authoredRequest);
                }
                history.add(entry);
            }
        }

        return new BurpTrafficImportPlan(
                destination.createNewCollection() ? null : destination.destinationCollection(),
                destination.newCollectionName(),
                destination.destinationFolder(),
                requests,
                history,
                destination.preserveExactTransport(),
                destination.captureResponses(),
                destination.queueInRunner());
    }

    WorkspaceState applyPlan(WorkspaceState before, BurpTrafficImportPlan plan) {
        WorkspaceState after = WorkspaceState.copyOf(before);
        if (after.collections == null) {
            after.collections = new ArrayList<>();
        }
        ApiCollection destination;
        if (plan.createsCollection()) {
            destination = new ApiCollection();
            destination.id = UUID.randomUUID().toString();
            destination.name = RequestTreeNamingPolicy.uniqueCollectionName(after.collections,
                    RequestTreeNamingPolicy.normalizeTreeLabel(plan.newCollectionName).isBlank()
                            ? "Burp Traffic"
                            : plan.newCollectionName);
            destination.description = "Requests imported from Burp traffic.";
            destination.format = "burp-traffic";
            destination.ensureDefaults();
            after.collections.add(destination);
        } else {
            destination = findCollection(after.collections, plan.existingDestinationCollection);
            if (destination == null) {
                throw new IllegalArgumentException("The selected destination collection is no longer available.");
            }
        }
        destination.ensureDefaults();
        String folder = RequestTreePathService.normalizeFolderPath(plan.destinationFolder);
        if (!folder.isBlank() && !folderExists(destination, folder)) {
            throw new IllegalArgumentException("The selected destination folder is no longer available.");
        }

        List<ApiRequest> inserted = new ArrayList<>();
        for (ApiRequest request : plan.requests) {
            if (request == null) {
                throw new IllegalArgumentException("The import plan contains an empty request.");
            }
            ApiRequest copy = request.applyTo(new ApiRequest());
            copy.sourceCollection = destination.name;
            copy.path = folder;
            destination.requests.add(copy);
            inserted.add(copy);
        }

        if (after.historyEntries == null) {
            after.historyEntries = new ArrayList<>();
        }
        if (plan.captureResponses) {
            for (HistoryEntry source : plan.historyEntries) {
                HistoryEntry entry = HistoryEntry.copyOf(source);
                if (entry == null) {
                    continue;
                }
                entry.collectionId = destination.id;
                entry.collectionName = destination.name;
                after.historyEntries.add(entry);
            }
        }

        if (!inserted.isEmpty()) {
            ApiRequest first = inserted.get(0);
            after.selectedRequestCollectionName = destination.name;
            after.selectedRequestIdentityKey = identityKey(destination.name, first);
            after.selectedRequestPath = first.path;
            after.selectedRequestName = first.name;
        }
        if (plan.queueInRunner) {
            after.runnerQueuedRequestIdentityKeys = new ArrayList<>();
            for (ApiRequest request : inserted) {
                if (request != null && !request.disabled) {
                    after.runnerQueuedRequestIdentityKeys.add(identityKey(destination.name, request));
                }
            }
            after.selectedTabIndex = 3;
        }
        return after;
    }

    private boolean folderExists(ApiCollection collection, String folder) {
        if (collection == null || folder == null || folder.isBlank()) {
            return folder == null || folder.isBlank();
        }
        if (collection.folderPaths != null) {
            for (String candidate : collection.folderPaths) {
                if (Objects.equals(RequestTreePathService.normalizeFolderPath(candidate), folder)) {
                    return true;
                }
            }
        }
        if (collection.requests != null) {
            for (ApiRequest request : collection.requests) {
                if (request != null && Objects.equals(RequestTreePathService.normalizeFolderPath(request.path), folder)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ApiCollection findCollection(List<ApiCollection> collections, ApiCollection selected) {
        if (selected == null || collections == null) {
            return null;
        }
        for (ApiCollection candidate : collections) {
            if (candidate == null) {
                continue;
            }
            if (selected.id != null && !selected.id.isBlank() && Objects.equals(selected.id, candidate.id)) {
                return candidate;
            }
            if ((selected.id == null || selected.id.isBlank()) && Objects.equals(selected.name, candidate.name)) {
                return candidate;
            }
        }
        return null;
    }

    static String identityKey(String collectionName, ApiRequest request) {
        return (collectionName != null ? collectionName : "")
                + '\u001F'
                + "id="
                + (request != null && request.id != null ? request.id.trim() : "");
    }

    private List<BurpTrafficSelection> copySelections(List<BurpTrafficSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        List<BurpTrafficSelection> copy = new ArrayList<>();
        for (BurpTrafficSelection selection : selections) {
            if (selection != null) {
                copy.add(selection.copy());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private String safeFailureSummary(BurpTrafficConversionResult conversion) {
        if (conversion == null || conversion.failures.isEmpty()) {
            return "Traffic conversion failed.";
        }
        List<String> lines = new ArrayList<>();
        for (BurpTrafficConversionResult.Failure failure : conversion.failures) {
            if (failure == null) {
                continue;
            }
            lines.add("Item " + failure.encounterIndex + ": " + failure.reasonCode + " - " + failure.safeMessage);
        }
        return lines.isEmpty() ? "Traffic conversion failed." : String.join("\n", lines);
    }

    private void logSafeError(String label, Throwable failure) {
        try {
            if (importer.getApi() != null && importer.getApi().logging() != null) {
                importer.getApi().logging().logToError(label + ": " + safeMessage(failure));
            }
        } catch (Throwable ignored) {
            // Error reporting must not mutate or obscure the transaction result.
        }
    }

    private static void showMessage(java.awt.Component parent, String title, String message, int messageType) {
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(parent, message, title, messageType);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return message != null && !message.isBlank()
                ? message
                : failure != null ? failure.getClass().getSimpleName() : "Unknown error";
    }
}
