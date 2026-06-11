package com.admindi.backend.approval;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring-managed registry of {@link ApprovalTaskHandler}s keyed by their {@code eventType}.
 *
 * <p>All beans implementing {@link ApprovalTaskHandler} are auto-discovered at startup and
 * indexed in a {@code Map<String, ApprovalTaskHandler>}. The {@link com.admindi.backend.controller.ActionTaskController}
 * resolves the correct handler dynamically from the task's {@code eventType}, eliminating the
 * hard-coded dispatch that previously existed for PROPERTY_DELETE_REQUESTED.
 *
 * <p>Duplicate registrations for the same eventType will fail the application context at
 * startup — each approval flow must own exactly one handler.
 */
@Component
public class ApprovalTaskRegistry {

    private final Map<String, ApprovalTaskHandler> handlersByEventType;

    public ApprovalTaskRegistry(List<ApprovalTaskHandler> handlers) {
        this.handlersByEventType = handlers.stream()
                .collect(Collectors.toMap(
                        ApprovalTaskHandler::getEventType,
                        h -> h,
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate ApprovalTaskHandler for eventType '"
                                            + a.getEventType() + "': "
                                            + a.getClass().getName() + " vs " + b.getClass().getName());
                        }));
    }

    /**
     * Resolve the handler for a task's eventType. Throws 422 UNPROCESSABLE_ENTITY if no
     * handler is registered (the approve/reject routes only work for approval-tracked events).
     */
    public ApprovalTaskHandler resolveHandler(String eventType) {
        ApprovalTaskHandler handler = handlersByEventType.get(eventType);
        if (handler == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "El tipo de tarea '" + eventType + "' no admite approve/reject. "
                            + "Tipos soportados: " + handlersByEventType.keySet() + ".");
        }
        return handler;
    }

    /** True if an approval handler is registered for the given eventType. */
    public boolean supports(String eventType) {
        return handlersByEventType.containsKey(eventType);
    }

    /** All eventTypes currently served by this registry (useful for diagnostics / frontend hints). */
    public Set<String> supportedEventTypes() {
        return handlersByEventType.keySet();
    }
}
