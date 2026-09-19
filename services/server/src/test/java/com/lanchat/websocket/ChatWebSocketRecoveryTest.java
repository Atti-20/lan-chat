package com.lanchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.WebSocketEnvelope;
import com.lanchat.recovery.*;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("chat-websocket-online-sessions")
class ChatWebSocketRecoveryTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final String epoch=UUID.randomUUID().toString();
    private final MutationStreamReader reader=mock(MutationStreamReader.class);
    private ChatWebSocketHandler handler;
    private WebSocketSession session;
    private final List<String> frames=new ArrayList<>();
    private void setup(boolean authenticated,boolean advertised) throws Exception {
        var jwt=mock(JwtUtil.class);var users=mock(UserService.class);
        when(jwt.isAccessToken("synthetic-token")).thenReturn(true);
        when(users.isAccessTokenActive("synthetic-token",7L,"web")).thenReturn(true);
        handler=new ChatWebSocketHandler(mapper,jwt,mock(ChatMessageService.class),mock(ConversationService.class),mock(FileService.class),users,
                mock(GroupService.class),mock(FriendService.class),mock(FileTransferService.class),mock(BroadcastService.class));
        ReflectionTestUtils.setField(handler,"recoverySubscriptions",new RecoverySubscriptions(reader,()->advertised));
        session=mock(WebSocketSession.class);when(session.isOpen()).thenReturn(true);
        Map<String,Object> attributes=new HashMap<>();
        if(authenticated) {attributes.put("authenticatedUserId",7L);attributes.put("authenticatedDeviceType","web");attributes.put("authenticatedAccessToken","synthetic-token");}
        when(session.getAttributes()).thenReturn(attributes);
        doAnswer(call->{frames.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(session).sendMessage(any());
        when(reader.stream(7)).thenReturn(new MutationStreamReader.Stream(epoch,0,3));
    }
    private void subscribe(int version) throws Exception {
        var event=new WebSocketEnvelope();event.setEvent("RECOVERY_SUBSCRIBE");event.setRequestId("subscription-request");
        event.setPayload(Map.of("capability","meshx.mutation-recovery","protocolVersion",version,"streamEpoch",epoch));
        handler.handleMessage(session,new TextMessage(mapper.writeValueAsString(event)));
    }
    private boolean hint(String hintEpoch) {
        var event=new WebSocketEnvelope();event.setEvent("MUTATION_AVAILABLE");event.setPayload(Map.of("streamEpoch",hintEpoch,"latestCursor","3"));
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(handler,"sendEvent",session,event));
    }
    @AfterEach void cleanup() {if(handler!=null)handler.shutdownExecutor();}
    @Test void hintsOnlyReachAuthenticatedSubscribedSessionsWithTheSameEpoch() throws Exception {
        setup(true,true);assertFalse(hint(epoch));subscribe(1);
        var ack=mapper.readTree(frames.get(0));assertEquals("RECOVERY_SUBSCRIBED",ack.get("event").asText());
        assertEquals("subscription-request",ack.get("requestId").asText());assertEquals(epoch,ack.at("/payload/streamEpoch").asText());
        assertTrue(hint(epoch));assertFalse(hint(UUID.randomUUID().toString()));
        subscribe(2);assertFalse(hint(epoch));assertFalse(session.getAttributes().containsKey(RecoverySubscriptions.EPOCH_ATTRIBUTE));
    }
    @Test void unavailableCapabilityRejectsSubscriptionWithoutReadingTheStream() throws Exception {
        setup(true,false);subscribe(1);assertEquals("ERROR",mapper.readTree(frames.get(0)).get("event").asText());
        assertFalse(hint(epoch));verifyNoInteractions(reader);
    }
    @Test void unauthenticatedSubscriptionUsesTheExistingAuthBoundary() throws Exception {
        setup(false,true);subscribe(1);assertFalse(hint(epoch));
        verify(session).close(CloseStatus.POLICY_VIOLATION);verifyNoInteractions(reader);
    }
}
