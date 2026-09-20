package com.lanchat.control.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.common.RequestIdFilter;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ControlAuditService {

    private static final Logger log = LoggerFactory.getLogger(ControlAuditService.class);
    private static final int MAX_DETAIL_JSON_LENGTH = 8_000;

    private final ControlAuditMapper auditMapper;
    private final ControlServerProperties controlServerProperties;
    private final ObjectMapper objectMapper;

    public ControlAuditService(ControlAuditMapper auditMapper,
                               ControlServerProperties controlServerProperties,
                               ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.controlServerProperties = controlServerProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void appendRequired(Long actorUserId,
                               String action,
                               String targetType,
                               String targetId,
                               String outcome,
                               Map<String, ?> detail) {
        String normalizedAction = normalizedCode(action, "审计动作");
        String normalizedOutcome = normalizedOutcome(outcome);
        String normalizedTargetType = StringUtils.hasText(targetType)
                ? normalizedCode(targetType, "审计目标类型")
                : null;
        String normalizedTargetId = bounded(targetId, 128, "审计目标标识");
        String detailJson = writeDetail(detail);
        if (auditMapper.insertEvent(
                controlServerProperties.resolvedOrganizationId(),
                actorUserId,
                null,
                normalizedAction,
                normalizedTargetType,
                normalizedTargetId,
                normalizedOutcome,
                currentRequestId(),
                detailJson) != 1) {
            throw new IllegalStateException("Control 管理审计写入失败");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendDeniedSafely(String action,
                                   String targetType,
                                   String targetId,
                                   Map<String, ?> detail) {
        try {
            appendRequired(UserContextHolder.getCurrentUserId(), action, targetType, targetId,
                    "DENIED", detail);
        } catch (RuntimeException exception) {
            log.warn("拒绝事件审计写入失败: action={}, error={}", action, exception.getMessage());
        }
    }

    public List<ControlAuditEventView> recent(String action, String outcome, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        String normalizedAction = StringUtils.hasText(action)
                ? normalizedCode(action, "审计动作")
                : null;
        String normalizedOutcome = StringUtils.hasText(outcome)
                ? normalizedOutcome(outcome)
                : null;
        List<ControlAuditEventView> events = auditMapper.selectRecent(
                controlServerProperties.resolvedOrganizationId(),
                normalizedAction,
                normalizedOutcome,
                limit);
        return events == null ? List.of() : List.copyOf(events);
    }

    private String writeDetail(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            String json = objectMapper.writeValueAsString(detail);
            if (json.length() > MAX_DETAIL_JSON_LENGTH) {
                throw new IllegalArgumentException("审计详情过长");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("审计详情无法序列化", exception);
        }
    }

    private String normalizedCode(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + "不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z][A-Z0-9_]{1,79}$")) {
            throw new IllegalArgumentException(label + "格式无效");
        }
        return normalized;
    }

    private String normalizedOutcome(String value) {
        String normalized = normalizedCode(value, "审计结果");
        if (!List.of("SUCCEEDED", "DENIED", "FAILED").contains(normalized)) {
            throw new IllegalArgumentException("审计结果无效");
        }
        return normalized;
    }

    private String bounded(String value, int maxLength, String label) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(label + "过长");
        return normalized;
    }

    private String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object value = attributes.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value == null ? null : String.valueOf(value);
    }
}
