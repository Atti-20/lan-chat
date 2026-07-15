package com.lanchat.service;

import com.lanchat.entity.ChatMessage;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.service.impl.ChatMessageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    private ChatMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(ChatMessageMapper.class);
        conversationService = mock(ConversationService.class);
        service = new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", messageMapper);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
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
}
