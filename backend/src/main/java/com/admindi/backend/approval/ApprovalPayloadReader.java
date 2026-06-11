package com.admindi.backend.approval;

import com.admindi.backend.model.ActionTaskEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Read-only helper that parses the JSON {@code payload} stored on {@link ActionTaskEntity}
 * rows produced by {@link ApprovalRequestService}. It exists so every
 * {@link ApprovalTaskHandler} can answer the same question — "who actually asked for this?"
 * — without re-implementing JSON parsing (and its error handling) inline.
 *
 * <p>The payload is free-form JSON by design, but {@link ApprovalRequestService#submit}
 * guarantees a core set of fields:
 * <ul>
 *   <li>{@code initiatedByUserId}  — FK to {@code users.id} of the staff member.</li>
 *   <li>{@code initiatedByEmail}   — human-friendly copy for audit / inbox cards.</li>
 *   <li>{@code initiatedByRole}    — role string at the time of the request.</li>
 *   <li>{@code reason}             — optional free-form text supplied by the staff.</li>
 * </ul>
 *
 * <p>Every getter returns {@link Optional#empty()} on any failure (null payload, malformed
 * JSON, missing field, blank value). Callers can fall back to sensible defaults — usually
 * the task's {@code ownerId} when the initiator can't be resolved — without branching on
 * exceptions.
 */
@Component
public class ApprovalPayloadReader {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalPayloadReader.class);

    private final ObjectMapper objectMapper;

    public ApprovalPayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Returns the {@code initiatedByUserId} recorded when the staff submitted the request. */
    public Optional<String> initiatorUserId(ActionTaskEntity task) {
        return readString(task, "initiatedByUserId");
    }

    /** Returns the initiator's email, useful for audit strings and WhatsApp/email templates. */
    public Optional<String> initiatorEmail(ActionTaskEntity task) {
        return readString(task, "initiatedByEmail");
    }

    /** Returns the staff-supplied reason if present and non-blank. */
    public Optional<String> reason(ActionTaskEntity task) {
        return readString(task, "reason");
    }

    /**
     * Generic string extractor. Always returns {@link Optional#empty()} on failure so
     * handlers can safely do {@code reader.initiatorUserId(task).orElse(task.getOwnerId())}.
     */
    private Optional<String> readString(ActionTaskEntity task, String field) {
        if (task == null) return Optional.empty();
        String raw = task.getPayload();
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode node = root.get(field);
            if (node == null || node.isNull()) return Optional.empty();
            String value = node.asText(null);
            return (value != null && !value.isBlank()) ? Optional.of(value) : Optional.empty();
        } catch (JsonProcessingException e) {
            logger.warn("Approval task {} has malformed payload, cannot read field '{}': {}",
                    task.getId(), field, e.getMessage());
            return Optional.empty();
        }
    }
}
