package com.lanchat.service;

import com.lanchat.common.ConversationMembershipChangedEvent;
import com.lanchat.common.ConversationReadChangedEvent;
import com.lanchat.dto.ConversationSummary;
import com.lanchat.entity.ConversationMember;
import com.lanchat.entity.GroupMember;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.ConversationMapper;
import com.lanchat.mapper.ConversationMemberMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.mapper.TemporaryRoomMapper;
import com.lanchat.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceImplTest {

    private ConversationMapper conversationMapper;
    private ConversationMemberMapper memberMapper;
    private ChatMessageMapper messageMapper;
    private GroupMemberMapper groupMemberMapper;
    private FriendService friendService;
    private TemporaryRoomMapper temporaryRoomMapper;
    private ApplicationEventPublisher eventPublisher;
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        memberMapper = mock(ConversationMemberMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        groupMemberMapper = mock(GroupMemberMapper.class);
        friendService = mock(FriendService.class);
        temporaryRoomMapper = mock(TemporaryRoomMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ConversationServiceImpl(
                conversationMapper, memberMapper, messageMapper, groupMemberMapper,
                temporaryRoomMapper, friendService, eventPublisher);
    }

    @Test
    void privateReadPositionIsLockedClampedRecalculatedAndPublished() {
        ConversationMember persisted = new ConversationMember();
        persisted.setLastReadSequence(15L);
        persisted.setUnreadCount(0);
        when(conversationMapper.selectLastSequenceForUpdate("private:7:9")).thenReturn(15L);
        when(memberMapper.selectActiveMember("private:7:9", 7L)).thenReturn(persisted);

        service.markRead("private:7:9", 7L, 100L);

        verify(memberMapper).advanceReadSequence("private:7:9", 7L, 15L);
        verify(memberMapper).recalculateUnread("private:7:9", 7L);
        verify(messageMapper).markPrivateMessagesRead("private:7:9", 7L, 15L);
        ArgumentCaptor<ConversationReadChangedEvent> event =
                ArgumentCaptor.forClass(ConversationReadChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(15L, event.getValue().lastSequence());
        assertEquals(15L, event.getValue().lastReadSequence());
        assertEquals(0L, event.getValue().unreadCount());
    }

    @Test
    void conversationSnapshotComesFromAuthoritativeMapperQuery() {
        ConversationSummary summary = new ConversationSummary();
        summary.setConversationId("group:3");
        summary.setUnreadCount(4L);
        when(conversationMapper.selectSummaries(7L)).thenReturn(List.of(summary));

        List<ConversationSummary> result = service.getConversationSummaries(7L);

        assertEquals(1, result.size());
        assertEquals(4L, result.get(0).getUnreadCount());
    }

    @Test
    void leavingConversationClearsUnreadAndPublishesEviction() {
        when(memberMapper.markLeft("group:3", 7L)).thenReturn(1);

        service.markGroupMemberLeft(3L, 7L);

        ArgumentCaptor<ConversationMembershipChangedEvent> event =
                ArgumentCaptor.forClass(ConversationMembershipChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals("group:3", event.getValue().conversationId());
        assertEquals(7L, event.getValue().userId());
        assertEquals(false, event.getValue().active());
    }

    @Test
    void groupSendPathDoesNotResynchronizeEveryMember() {
        GroupMember membership = new GroupMember();
        membership.setUserId(7L);
        when(groupMemberMapper.selectCount(any())).thenReturn(1L);
        when(groupMemberMapper.selectOne(any())).thenReturn(membership);

        String resolved = service.resolveForMessage(7L, null, 3L, "group:3");

        assertEquals("group:3", resolved);
        verify(conversationMapper).insertIfAbsent("group:3", "GROUP", 3L);
        verify(groupMemberMapper, never()).selectList(any());
    }
}
