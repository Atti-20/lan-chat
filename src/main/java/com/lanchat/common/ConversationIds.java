package com.lanchat.common;

import java.util.Optional;

/**
 * 统一会话 ID 生成与解析。
 *
 * <p>MVP 使用确定性 ID，让客户端在离线时也能知道消息所属会话；服务端仍会在
 * 每次读写时重新校验好友/群成员权限，不能把可预测 ID 当作授权凭据。</p>
 */
public final class ConversationIds {

    private static final String PRIVATE_PREFIX = "private:";
    private static final String GROUP_PREFIX = "group:";

    private ConversationIds() {
    }

    public static String privateConversation(Long firstUserId, Long secondUserId) {
        requirePositive(firstUserId, "用户 ID");
        requirePositive(secondUserId, "用户 ID");
        if (firstUserId.equals(secondUserId)) {
            throw new IllegalArgumentException("不能与自己创建私聊会话");
        }
        long lower = Math.min(firstUserId, secondUserId);
        long upper = Math.max(firstUserId, secondUserId);
        return PRIVATE_PREFIX + lower + ":" + upper;
    }

    public static String groupConversation(Long groupId) {
        requirePositive(groupId, "群组 ID");
        return GROUP_PREFIX + groupId;
    }

    public static Optional<PrivateParticipants> parsePrivate(String conversationId) {
        if (conversationId == null || !conversationId.startsWith(PRIVATE_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = conversationId.substring(PRIVATE_PREFIX.length()).split(":", -1);
        if (parts.length != 2) return Optional.empty();
        try {
            long first = Long.parseLong(parts[0]);
            long second = Long.parseLong(parts[1]);
            if (first <= 0 || second <= 0 || first >= second) return Optional.empty();
            return Optional.of(new PrivateParticipants(first, second));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Long> parseGroup(String conversationId) {
        if (conversationId == null || !conversationId.startsWith(GROUP_PREFIX)) {
            return Optional.empty();
        }
        try {
            long groupId = Long.parseLong(conversationId.substring(GROUP_PREFIX.length()));
            return groupId > 0 ? Optional.of(groupId) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + "无效");
        }
    }

    public record PrivateParticipants(long firstUserId, long secondUserId) {
        public boolean contains(Long userId) {
            return userId != null && (userId == firstUserId || userId == secondUserId);
        }

        public long peerOf(Long userId) {
            if (userId == null || !contains(userId)) {
                throw new IllegalArgumentException("用户不属于该私聊会话");
            }
            return userId == firstUserId ? secondUserId : firstUserId;
        }
    }
}
