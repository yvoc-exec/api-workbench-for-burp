package burp.ui.contextmenu;

import burp.api.montoya.MontoyaApi;
import burp.importer.BurpTrafficSourceSelection;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Thin Montoya adapter. Reflection is limited to the Montoya 2024.12 context
 * menu boundary so the model workflow remains independently testable.
 */
public final class ApiWorkbenchContextMenuProvider implements AutoCloseable {
    private static final String PROVIDER_CLASS = "burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider";

    private final BiConsumer<List<BurpTrafficSourceSelection>, Boolean> importAction;
    private final AtomicBoolean registered = new AtomicBoolean();
    private volatile Object registration;
    private volatile Object providerProxy;

    public ApiWorkbenchContextMenuProvider(BiConsumer<List<BurpTrafficSourceSelection>, Boolean> importAction) {
        this.importAction = importAction;
    }

    public synchronized boolean register(MontoyaApi api) {
        if (api == null || registered.get()) {
            return registered.get();
        }
        try {
            ClassLoader loader = api.getClass().getClassLoader();
            Class<?> providerType = Class.forName(PROVIDER_CLASS, true, loader);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{providerType}, (ignored, method, args) -> {
                if ("provideMenuItems".equals(method.getName())) {
                    Object event = args != null && args.length > 0 ? args[0] : null;
                    return provideMenuItems(event);
                }
                if ("toString".equals(method.getName())) {
                    return "API Workbench Context Menu Provider";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(this);
                }
                if ("equals".equals(method.getName())) {
                    return args != null && args.length == 1 && args[0] == providerProxy;
                }
                return null;
            });
            Object ui = api.userInterface();
            Method registrationMethod = findRegistrationMethod(ui.getClass(), providerType);
            if (registrationMethod == null) {
                return false;
            }
            Object handle = registrationMethod.invoke(ui, proxy);
            providerProxy = proxy;
            registration = handle;
            registered.set(true);
            return true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            try {
                if (api.logging() != null) {
                    api.logging().logToError("API Workbench context-menu registration failed: " + safeMessage(failure));
                }
            } catch (Throwable ignored) {
                // Logging must never make registration failure fatal.
            }
            return false;
        }
    }

    public boolean isRegistered() {
        return registered.get();
    }

    public List<JMenuItem> provideMenuItems(Object event) {
        List<Object> requestResponses = selectedRequestResponses(event);
        requestResponses.removeIf(value -> unwrap(invokeFirst(value, "request")) == null);
        if (requestResponses.isEmpty()) {
            return List.of();
        }
        List<Object> lightweightReferences = List.copyOf(requestResponses);
        String context = safeContext(invokeFirst(event, "invocationType", "toolType", "context"));
        int count = lightweightReferences.size();
        String importLabel = count == 1
                ? "Send to API Workbench"
                : "Send " + count + " requests to API Workbench";
        String queueLabel = count == 1
                ? "Send to API Workbench and Queue"
                : "Send " + count + " requests to API Workbench and Queue";
        JMenuItem importItem = new JMenuItem(importLabel);
        importItem.addActionListener(ignored -> dispatch(sourceSelections(lightweightReferences, context), false));
        JMenuItem queueItem = new JMenuItem(queueLabel);
        queueItem.addActionListener(ignored -> dispatch(sourceSelections(lightweightReferences, context), true));
        return List.of(importItem, queueItem);
    }

    public List<BurpTrafficSourceSelection> sourceSelections(Object event) {
        List<Object> requestResponses = selectedRequestResponses(event);
        if (requestResponses.isEmpty()) {
            return List.of();
        }
        String context = safeContext(invokeFirst(event, "invocationType", "toolType", "context"));
        return sourceSelections(requestResponses, context);
    }

    private List<BurpTrafficSourceSelection> sourceSelections(List<Object> requestResponses, String context) {
        List<BurpTrafficSourceSelection> out = new ArrayList<>();
        int index = 0;
        for (Object requestResponse : requestResponses) {
            if (unwrap(invokeFirst(requestResponse, "request")) != null) {
                out.add(new BurpTrafficSourceSelection(requestResponse, context, index++));
            }
        }
        return List.copyOf(out);
    }

    private void dispatch(List<BurpTrafficSourceSelection> selections, boolean queue) {
        if (importAction == null || selections == null || selections.isEmpty()) {
            return;
        }
        List<BurpTrafficSourceSelection> detached = new ArrayList<>();
        for (BurpTrafficSourceSelection selection : selections) {
            if (selection != null) {
                detached.add(selection);
            }
        }
        if (detached.isEmpty()) {
            return;
        }
        Runnable action = () -> importAction.accept(List.copyOf(detached), queue);
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    @Override
    public synchronized void close() {
        if (!registered.getAndSet(false)) {
            return;
        }
        Object handle = registration;
        registration = null;
        providerProxy = null;
        if (handle == null) {
            return;
        }
        for (String methodName : List.of("deregister", "close", "remove")) {
            try {
                Method method = handle.getClass().getMethod(methodName);
                method.setAccessible(true);
                method.invoke(handle);
                return;
            } catch (ReflectiveOperationException ignored) {
                // Try the next supported Registration shape.
            }
        }
    }

    private static Method findRegistrationMethod(Class<?> type, Class<?> providerType) {
        for (Method method : type.getMethods()) {
            if (!"registerContextMenuItemsProvider".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType.getName().equals(providerType.getName())
                    || parameterType.isAssignableFrom(providerType)) {
                return method;
            }
        }
        return null;
    }

    private static List<Object> selectedRequestResponses(Object event) {
        Object selected = invokeFirst(event, "selectedRequestResponses", "selectedMessages");
        List<Object> values = flatten(selected);
        if (!values.isEmpty()) {
            return values;
        }
        Object editorValue = invokeFirst(event, "messageEditorRequestResponse", "requestResponse");
        return flatten(editorValue);
    }

    private static List<Object> flatten(Object value) {
        List<Object> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(item -> out.addAll(flatten(item)));
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                out.addAll(flatten(item));
            }
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                out.addAll(flatten(Array.get(value, i)));
            }
        } else {
            out.add(value);
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    private static Object unwrap(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    private static Object invokeFirst(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
                // Try the next accessor.
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        Object unwrapped = unwrap(value);
        return unwrapped != null ? unwrapped.toString() : "";
    }

    private static String safeContext(Object value) {
        String context = stringValue(value);
        if (context.isBlank()) {
            return "Burp";
        }
        return context.replaceAll("[^A-Za-z0-9 _.-]", "_");
    }

    private static String safeMessage(Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return message != null && !message.isBlank()
                ? message
                : failure != null ? failure.getClass().getSimpleName() : "Unknown error";
    }
}
