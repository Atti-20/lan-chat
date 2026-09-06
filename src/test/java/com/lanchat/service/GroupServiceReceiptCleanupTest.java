package com.lanchat.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.entity.ChatGroup;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.GroupMember;
import com.lanchat.mapper.ChatGroupMapper;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.mapper.MessageRecallMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.GroupServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupServiceReceiptCleanupTest {

    private ChatGroupMapper groupMapper;
    private ChatMessageMapper messageMapper;
    private GroupMemberMapper memberMapper;
    private MessageRecallMapper recallMapper;
    private ConversationService conversationService;
    private GroupServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeTableInfo(ChatMessage.class);
        initializeTableInfo(GroupMember.class);
        groupMapper = mock(ChatGroupMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        memberMapper = mock(GroupMemberMapper.class);
        recallMapper = mock(MessageRecallMapper.class);
        conversationService = mock(ConversationService.class);
        service = new GroupServiceImpl();
        ReflectionTestUtils.setField(service, "groupMapper", groupMapper);
        ReflectionTestUtils.setField(service, "chatMessageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "messageRecallMapper", recallMapper);
        ReflectionTestUtils.setField(service, "userMapper", mock(UserMapper.class));
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
    }

    @Test
    void dissolveGroupDeletesMentionReceiptsBeforeDeletingItsMessages() {
        ChatGroup group = new ChatGroup();
        group.setId(12L);
        group.setOwnerId(7L);
        ChatMessage message = new ChatMessage();
        message.setMessageId("group-message-1");
        when(groupMapper.selectById(12L)).thenReturn(group);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        assertTrue(service.dissolveGroup(12L, 7L));

        InOrder cleanup = inOrder(messageMapper, recallMapper);
        cleanup.verify(messageMapper).deleteMentionReceiptsByGroupId(12L);
        cleanup.verify(recallMapper).delete(any());
        cleanup.verify(messageMapper).delete(any());
        verify(conversationService).archiveGroupConversation(12L);
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
