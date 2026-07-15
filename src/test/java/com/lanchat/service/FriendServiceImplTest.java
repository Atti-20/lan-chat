package com.lanchat.service;

import com.lanchat.entity.User;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.FriendRequestMapper;
import com.lanchat.mapper.FriendshipMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.FriendServiceImpl;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import com.lanchat.entity.FriendRequest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FriendServiceImplTest {

    private FriendRequestMapper requestMapper;
    private FriendshipMapper friendshipMapper;
    private UserMapper userMapper;
    private FriendServiceImpl service;
    private ChatWebSocketHandler webSocketHandler;

    @BeforeEach
    void setUp() {
        requestMapper = mock(FriendRequestMapper.class);
        friendshipMapper = mock(FriendshipMapper.class);
        userMapper = mock(UserMapper.class);
        webSocketHandler = mock(ChatWebSocketHandler.class);
        service = new FriendServiceImpl();

        ReflectionTestUtils.setField(service, "friendRequestMapper", requestMapper);
        ReflectionTestUtils.setField(service, "friendshipMapper", friendshipMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "chatMessageMapper", mock(ChatMessageMapper.class));
        ReflectionTestUtils.setField(service, "webSocketHandler", webSocketHandler);
    }

    @Test
    void notifiesRecipientAfterFriendRequestIsPersisted() {
        User recipient = new User();
        recipient.setId(2L);
        recipient.setStatus(1);
        User sender = new User();
        sender.setId(1L);
        sender.setNickname("Alice");

        when(userMapper.selectById(2L)).thenReturn(recipient);
        when(userMapper.selectById(1L)).thenReturn(sender);
        when(friendshipMapper.selectCount(any())).thenReturn(0L);
        when(friendshipMapper.selectOne(any())).thenReturn(null);
        when(requestMapper.selectList(any())).thenReturn(List.of());
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.insert(any(FriendRequest.class))).thenReturn(1);

        assertTrue(service.sendFriendRequest(1L, 2L, "你好"));

        verify(webSocketHandler).sendFriendNotification(2L, "Alice 向你发送了好友申请");
    }
}
