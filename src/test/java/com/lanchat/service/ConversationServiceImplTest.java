package com.lanchat.service;

import com.lanchat.entity.GroupMember;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.ConversationMapper;
import com.lanchat.mapper.ConversationMemberMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.mapper.TemporaryRoomMapper;
import com.lanchat.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        memberMapper = mock(ConversationMemberMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        groupMemberMapper = mock(GroupMemberMapper.class);
        friendService = mock(FriendService.class);
        temporaryRoomMapper = mock(TemporaryRoomMapper.class);
        service = new ConversationServiceImpl(
                conversationMapper, memberMapper, messageMapper, groupMemberMapper,
                temporaryRoomMapper, friendService);
    }

    @Test
    void privateReadPositionIsClampedAndPersisted() {
        when(conversationMapper.selectLastSequence("private:7:9")).thenReturn(15L);

        service.markRead("private:7:9", 7L, 100L);

        verify(memberMapper).advanceReadSequence("private:7:9", 7L, 15L);
        verify(messageMapper).markPrivateMessagesRead("private:7:9", 7L, 15L);
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
