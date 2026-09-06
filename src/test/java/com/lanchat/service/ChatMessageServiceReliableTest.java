package com.lanchat.service;

import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.ConversationMember;
import com.lanchat.entity.User;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.ConversationMemberMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.GroupService;
import com.lanchat.service.impl.ChatMessageServiceImpl;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageServiceReliableTest {

    private ChatMessageMapper messageMapper;
    private ConversationService conversationService;
    private ConversationMemberMapper conversationMemberMapper;
    private UserMapper userMapper;
    private GroupService groupService;
    private ChatMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeTableInfo(ChatMessage.class);
        messageMapper = mock(ChatMessageMapper.class);
        conversationService = mock(ConversationService.class);
        conversationMemberMapper = mock(ConversationMemberMapper.class);
        userMapper = mock(UserMapper.class);
        groupService = mock(GroupService.class);
        service = new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", messageMapper);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "conversationMemberMapper", conversationMemberMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "groupService", groupService);
    }

    @Test
    void persistsServerIdentityAndConversationSequenceBeforeAckResult() {
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.insert(any(ChatMessage.class))).thenReturn(1);
        when(conversationService.resolveForMessage(7L, 8L, null, "private:7:8"))
                .thenReturn("private:7:8");
        when(conversationService.nextSequence("private:7:8")).thenReturn(42L);

        ChatMessage message = new ChatMessage();
        message.setClientMsgId("client_message_123");
        message.setFromUserId(7L);
        message.setToUserId(8L);
        message.setType("text");
        message.setContent("hello");

        var result = service.saveReliableMessage(message, "private:7:8");

        assertFalse(result.duplicated());
        assertEquals("private:7:8", result.message().getConversationId());
        assertEquals(42L, result.message().getSequence());
        assertNotEquals(result.message().getClientMsgId(), result.message().getMessageId());
        verify(messageMapper).insert(message);
        verify(conversationService).updateLastMessage("private:7:8", message.getMessageId(), 7L);
    }

    @Test
    void duplicateClientMessageReturnsOriginalWithoutAllocatingSequence() {
        ChatMessage existing = new ChatMessage();
        existing.setMessageId("server_message_1");
        existing.setClientMsgId("client_message_123");
        existing.setFromUserId(7L);
        existing.setConversationId("private:7:8");
        existing.setSequence(9L);
        ChatMessageServiceImpl duplicateService = spy(service);
        doReturn(existing).when(duplicateService).getByClientMsgId(7L, "client_message_123");

        ChatMessage retry = new ChatMessage();
        retry.setClientMsgId("client_message_123");
        retry.setFromUserId(7L);
        retry.setToUserId(8L);

        var result = duplicateService.saveReliableMessage(retry, "private:7:8");

        assertTrue(result.duplicated());
        assertEquals(existing, result.message());
        verify(conversationService, never()).nextSequence(any());
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    void systemDirectMessageReturnsExistingIdempotentCardWithoutAllocatingConversationState() {
        ChatMessage existing = new ChatMessage();
        existing.setMessageId("system_message_1");
        existing.setClientMsgId("broadcast-card-1");
        when(messageMapper.selectBySenderAndClientMsgIdForUpdate(101L, "broadcast-card-1"))
                .thenReturn(existing);

        ChatMessage result = service.saveSystemDirectMessage(
                101L, 202L, "broadcast", "{\"kind\":\"BROADCAST_OVERVIEW\"}", "broadcast-card-1");

        assertSame(existing, result);
        verify(conversationService, never()).ensurePrivateConversation(any(), any());
        verify(conversationService, never()).nextSequence(any());
        verify(conversationService, never()).updateLastMessage(any(), any(), any());
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    void systemDirectMessageReturnsConcurrentlySavedCardAfterDuplicateKeyWithoutAdvancingConversation() {
        ChatMessage concurrent = new ChatMessage();
        concurrent.setMessageId("system_message_concurrent");
        concurrent.setClientMsgId("broadcast-card-2");
        ChatMessageServiceImpl racingService = spy(service);
        when(messageMapper.selectBySenderAndClientMsgIdForUpdate(101L, "broadcast-card-2"))
                .thenReturn(null);
        doReturn(concurrent).when(racingService).getByClientMsgId(101L, "broadcast-card-2");
        when(conversationService.ensurePrivateConversation(101L, 202L)).thenReturn("private:101:202");
        when(conversationService.nextSequence("private:101:202")).thenReturn(7L);
        when(messageMapper.insert(any(ChatMessage.class)))
                .thenThrow(new DuplicateKeyException("duplicate client message id"));

        ChatMessage result = racingService.saveSystemDirectMessage(
                101L, 202L, "broadcast", "{\"kind\":\"BROADCAST_OVERVIEW\"}", "broadcast-card-2");

        assertSame(concurrent, result);
        verify(conversationService).ensurePrivateConversation(101L, 202L);
        verify(conversationService).nextSequence("private:101:202");
        verify(conversationService, never()).updateLastMessage(any(), any(), any());
        verify(messageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void systemDirectMessageUsesAForUpdateIdempotencyLookupBeforeAllocatingSequence() throws Exception {
        Select select = ChatMessageMapper.class
                .getMethod("selectBySenderAndClientMsgIdForUpdate", Long.class, String.class)
                .getAnnotation(Select.class);

        assertNotNull(select);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");
        assertTrue(sql.contains("from_user_id = #{senderId}"), sql);
        assertTrue(sql.contains("client_msg_id = #{clientMsgId}"), sql);
        assertTrue(sql.toUpperCase().contains("FOR UPDATE"), sql);
    }

    @Test
    void redactingBroadcastCardsMatchesTheExactJsonBroadcastId() {
        ChatMessage matching = broadcastCard("card-12", "{\"broadcastId\":12,\"kind\":\"BROADCAST_OVERVIEW\"}");
        ChatMessage prefixOnly = broadcastCard("card-123", "{\"broadcastId\":123,\"kind\":\"BROADCAST_OVERVIEW\"}");
        when(messageMapper.selectList(any())).thenReturn(List.of(matching, prefixOnly));
        when(messageMapper.update(any(), any())).thenReturn(1);

        List<ChatMessage> redacted = service.redactSystemBroadcastCards(101L, 202L, 12L);

        assertEquals(List.of("card-12"), redacted.stream().map(ChatMessage::getMessageId).toList());
        assertEquals(1, matching.getIsRecalled());
        assertEquals("", matching.getContent());
        assertEquals(0, prefixOnly.getIsRecalled());
        assertEquals("{\"broadcastId\":123,\"kind\":\"BROADCAST_OVERVIEW\"}", prefixOnly.getContent());
    }

    private ChatMessage broadcastCard(String messageId, String content) {
        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setFromUserId(101L);
        message.setToUserId(202L);
        message.setType("broadcast");
        message.setContent(content);
        message.setIsRecalled(0);
        return message;
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    @Test
    void mentionReceiptDoesNotTreatPostRejoinCursorAsReadingAnOlderMention() {
        LocalDateTime sentAt = LocalDateTime.of(2026, 9, 5, 10, 0);
        ChatMessage message = new ChatMessage();
        message.setMessageId("mention_1");
        message.setFromUserId(7L);
        message.setGroupId(13L);
        message.setConversationId("group:13");
        message.setSequence(10L);
        message.setMentionUserIds("9");
        message.setCreateTime(sentAt);
        ChatMessageServiceImpl receiptService = spy(service);
        doReturn(message).when(receiptService).getByMessageId("mention_1");
        when(groupService.getMemberRole(13L, 7L)).thenReturn(1);

        ConversationMember rejoinedMember = new ConversationMember();
        rejoinedMember.setUserId(9L);
        rejoinedMember.setLastReadSequence(30L);
        rejoinedMember.setReceiptStartSequence(31L);
        when(conversationMemberMapper.selectList(any())).thenReturn(List.of(rejoinedMember));
        User recipient = new User();
        recipient.setId(9L);
        recipient.setNickname("成员");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(recipient));
        when(messageMapper.selectMentionReceiptReaderIds("mention_1")).thenReturn(List.of());

        var receipt = receiptService.getMentionReadReceipt("mention_1", 7L);

        assertEquals(0, receipt.readCount());
        assertFalse(receipt.recipients().get(0).read());
    }

    @Test
    void mentionReceiptAllowsAStillPresentOrdinaryMemberToInspectTheirDirectMention() {
        ChatMessage message = new ChatMessage();
        message.setMessageId("mention_member_sender");
        message.setFromUserId(7L);
        message.setGroupId(13L);
        message.setConversationId("group:13");
        message.setSequence(10L);
        message.setMentionUserIds("9");
        ChatMessageServiceImpl receiptService = spy(service);
        doReturn(message).when(receiptService).getByMessageId("mention_member_sender");
        when(groupService.getMemberRole(13L, 7L)).thenReturn(0);

        ConversationMember target = new ConversationMember();
        target.setUserId(9L);
        target.setLastReadSequence(10L);
        target.setJoinTime(LocalDateTime.of(2026, 9, 5, 9, 0));
        when(conversationMemberMapper.selectList(any())).thenReturn(List.of(target));
        User recipient = new User();
        recipient.setId(9L);
        recipient.setNickname("成员");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(recipient));
        when(messageMapper.selectMentionReceiptReaderIds("mention_member_sender")).thenReturn(List.of(9L));

        var receipt = receiptService.getMentionReadReceipt("mention_member_sender", 7L);

        assertEquals(1, receipt.expectedCount());
        assertEquals(1, receipt.readCount());
        assertTrue(receipt.recipients().get(0).read());
    }

    @Test
    void mentionReceiptRejectsASenderWhoHasLeftTheGroup() {
        ChatMessage message = new ChatMessage();
        message.setMessageId("mention_departed_sender");
        message.setFromUserId(7L);
        message.setGroupId(13L);
        message.setConversationId("group:13");
        message.setSequence(10L);
        message.setMentionUserIds("9");
        ChatMessageServiceImpl receiptService = spy(service);
        doReturn(message).when(receiptService).getByMessageId("mention_departed_sender");
        when(groupService.getMemberRole(13L, 7L)).thenReturn(-1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> receiptService.getMentionReadReceipt("mention_departed_sender", 7L));

        assertEquals("只有仍在群内的发送者可查看@成员已读状态", error.getMessage());
        verify(conversationMemberMapper, never()).selectList(any());
        verify(userMapper, never()).selectBatchIds(any());
    }

    @Test
    void persistedMentionReceiptSurvivesTargetLeavingAndRejoining() {
        LocalDateTime sentAt = LocalDateTime.of(2026, 9, 5, 10, 0);
        ChatMessage message = new ChatMessage();
        message.setMessageId("mention_immutable_receipt");
        message.setFromUserId(7L);
        message.setGroupId(13L);
        message.setConversationId("group:13");
        message.setSequence(10L);
        message.setMentionUserIds("9");
        message.setCreateTime(sentAt);
        ChatMessageServiceImpl receiptService = spy(service);
        doReturn(message).when(receiptService).getByMessageId("mention_immutable_receipt");
        when(groupService.getMemberRole(13L, 7L)).thenReturn(1);

        ConversationMember rejoinedMember = new ConversationMember();
        rejoinedMember.setUserId(9L);
        rejoinedMember.setLastReadSequence(30L);
        rejoinedMember.setReceiptStartSequence(31L);
        when(conversationMemberMapper.selectList(any())).thenReturn(List.of(rejoinedMember));
        User recipient = new User();
        recipient.setId(9L);
        recipient.setNickname("成员");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(recipient));
        when(messageMapper.selectMentionReceiptReaderIds("mention_immutable_receipt")).thenReturn(List.of(9L));

        var receipt = receiptService.getMentionReadReceipt("mention_immutable_receipt", 7L);

        assertEquals(1, receipt.readCount());
        assertTrue(receipt.recipients().get(0).read());
    }
}
