package com.lanchat.controller;

import com.lanchat.security.LoginUser;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.GroupService;
import com.lanchat.service.ConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private ChatMessageService chatMessageService;
    private GroupService groupService;
    private ConversationService conversationService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        chatMessageService = mock(ChatMessageService.class);
        groupService = mock(GroupService.class);
        conversationService = mock(ConversationService.class);
        controller = new ChatController();
        ReflectionTestUtils.setField(controller, "chatMessageService", chatMessageService);
        ReflectionTestUtils.setField(controller, "groupService", groupService);
        ReflectionTestUtils.setField(controller, "conversationService", conversationService);

        LoginUser loginUser = new LoginUser(7L, "alice", "web", "access-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void privateHistoryAlwaysUsesAuthenticatedUserId() {
        when(chatMessageService.getPrivateHistory(7L, 8L, 50)).thenReturn(List.of());

        controller.getPrivateHistory(99L, 8L, 50);

        verify(chatMessageService).getPrivateHistory(7L, 8L, 50);
        verify(chatMessageService, never()).getPrivateHistory(99L, 8L, 50);
    }

    @Test
    void groupHistoryRejectsNonMembers() {
        when(groupService.isMember(12L, 7L)).thenReturn(false);

        var result = controller.getGroupHistory(12L, 50);

        assertEquals(403, result.getCode());
        verify(chatMessageService, never()).getGroupHistory(12L, 50);
    }

    @Test
    void unifiedHistoryUsesAuthenticatedUserAndSequenceCursor() {
        when(chatMessageService.getConversationHistory("private:7:8", 7L, 41L, 25))
                .thenReturn(List.of());

        var result = controller.getConversationHistory("private:7:8", 41L, 25);

        assertEquals(200, result.getCode());
        verify(chatMessageService).getConversationHistory("private:7:8", 7L, 41L, 25);
    }
}
