package burp.ui.contextmenu;

import burp.api.montoya.MontoyaApi;
import burp.importer.BurpTrafficSelection;

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

    private final BiConsumer<List<BurpTrafficSelection>, Boolean> importAction;
    private final AtomicBoolean registered = new AtomicBoolean();
    private volatile Object registration;
    private volatile Object providerProxy;

    public ApiWorkbenchContextMenuProvider(BiConsumer<List<BurpTrafficSelection>, Boolean> importAction) {
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
                    return args != null && args.length == 1 && args[0] == proxy;
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
            if (api.logging() != null) {
                api.logging().logToError("API Workbench context-menu registration failed: " + safeMessage(failure));
            }
            return false;
        }
    }

    public boolean isRegistered() {
        return registered.get();
    }

    public List<JMenuItem> provideMenuItems(Object event) {
        List<BurpTrafficSelection> selections = detachSelections(event);
        if (selections.isEmpty()) {
            return List.of();
        }
        int count = selections.size();
        String importLabel = count == 1
                ? "Send to API Workbench"
                : "Send " + count + " requests to API Workbench";
        String queueLabel = count == 1
                ? "Send to API Workbench and Queue"
                : "Send " + count + " requests to API Workbench and Queue";
        JMenuItem importItem = new JMenuItem(importLabel);
        importItem.addActionListener(ignored -> dispatch(selections, false));
        JMenuItem queueItem = new JMenuItem(queueLabel);
        queueItem.addActionListener(ignored -> dispatch(selections, true));
        return List.of(importItem, queueItem);
    }

    List<BurpTrafficSelection> detachSelections(Object event) {
        List<Object> requestResponses = selectedRequestResponses(event);
        if (requestResponses.isEmpty()) {
            return List.of();
        }
        String context = safeContext(invokeFirst(event, "invocationType", "toolType", "context"));
        List<BurpTrafficSelection> out = new ArrayList<>();
        int index = 0;
        for (Object requestResponse : requestResponses) {
            Object request = invokeFirst(requestResponse, "request");
            if (request == null) {
                continue;
            }
            byte[] requestBytes = bytesFrom(invokeFirst(request, "toByteArray", "body"));
            if (requestBytes.length == 0) {
                continue;
            }
            Object response = invokeFirst(requestResponse, "response");
            byte[] responseBytes = response != null ? bytesFrom(invokeFirst(response, "toByteArray")) : new byte[0];
            Object service = invokeFirst(request, "httpService");
            if (service == null) {
                service = invokeFirst(requestResponse, "httpService");
            }
            String host = stringValue(invokeFirst(service, "host"));
            int port = intValue(invokeFirst(service, "port"));
            boolean secure = booleanValue(invokeFirst(service, "secure"));
            if (host.isBlank()) {
                host = stringValue(invokeFirst(request, "httpService", "host"));
            }
            String method = stringValue(invokeFirst(request, "method"));
            String suggestedName = method;
            out.add(new BurpTrafficSelection(
                    requestBytes.clone(),
                    responseBytes.length > 0 ? responseBytes.clone() : null,
                    host,
                    port,
                    secure,
                    context,
                    suggestedName,
                    method,
                    index++));
        }
        return List.copyOf(out);
    }

    private void dispatch(List<BurpTrafficSelection> selections, boolean queue) {
        if (importAction == null || selections == null || selections.isEmpty()) {
            return;
        }
        List<BurpTrafficSelection> detached = new ArrayList<>();
        for (BurpTrafficSelection selection : selections) {
            detached.add(selection != null ? selection.copy() : null);
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
            if (method.getParameterTypes()[0].isAssignableFrom(providerType)
                    || method.getParameterTypes()[0].isInstance(providerType)) {
                return method;
            }
            if (method.getParameterTypes()[0].getName().equals(providerType.getName())) {
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
            out.addAll(collection);
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                out.add(Array.get(value, i));
            }
        } else {
            out.add(value);
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
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

    private static byte[] bytesFrom(Object value) {
        if (value == null) {
            return new byte[0];
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        Object bytes = invokeFirst(value, "getBytes", "toByteArray");
        return bytes instanceof byte[] array ? array.clone() : new byte[0];
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean flag && flag;
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
        return message != null && !message.isBlank() ? message : failure != null ? failure.getClass().getSimpleName() : "Unknown error";
    }
}
