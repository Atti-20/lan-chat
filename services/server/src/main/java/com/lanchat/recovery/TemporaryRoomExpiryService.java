package com.lanchat.recovery;

import com.lanchat.common.ConversationIds;
import com.lanchat.common.TemporaryRoomChangedEvent;
import com.lanchat.mapper.TemporaryRoomMapper;
import com.lanchat.service.ConversationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.TreeMap;

/** One room per transaction: never carry per-user stream locks into another room. */
@Service
public class TemporaryRoomExpiryService {
    private final TemporaryRoomMapper rooms;
    private final ConversationService conversations;
    private final AccessMutationRecorder recorder;
    private final ApplicationEventPublisher events;

    public TemporaryRoomExpiryService(TemporaryRoomMapper rooms, ConversationService conversations,
                                      AccessMutationRecorder recorder, ApplicationEventPublisher events) {
        this.rooms=rooms; this.conversations=conversations; this.recorder=recorder; this.events=events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(Long roomId, LocalDateTime now) {
        String cid = ConversationIds.temporaryConversation(roomId);
        conversations.lockConversationForWrite(cid);
        var room = rooms.selectByIdForUpdate(roomId);
        if (room == null || !"ACTIVE".equals(room.getStatus()) || room.getExpiresAt() == null
                || room.getExpiresAt().isAfter(now)) return false;
        String action = room.getExpireAction() == null ? "FREEZE" : room.getExpireAction().trim().toUpperCase(Locale.ROOT);
        String roomStatus = switch (action) { case "ARCHIVE" -> "ARCHIVED"; case "DESTROY" -> "DESTROYED"; default -> "FROZEN"; };
        String conversationStatus = switch (action) { case "ARCHIVE" -> "ARCHIVED"; case "DESTROY" -> "DESTROYED"; default -> "READ_ONLY"; };
        var users = conversations.getReadableRecipientsForWrite(cid);
        var before = new TreeMap<Long,ConversationWriteGuard.Permission>();
        for (Long user : users) before.put(user,recorder.before(cid,user));
        if (rooms.transitionExpiredRoom(roomId,roomStatus,now) != 1) return false;
        if ("DESTROY".equals(action)) conversations.removeAllConversationMembers(cid);
        conversations.updateStatus(cid,conversationStatus);
        before.forEach((user,permission) -> {
            if ("DESTROY".equals(action)) recorder.revoke(cid,user,permission,MutationFact.Reason.DESTROYED);
            else recorder.refreshReadable(cid,user,permission,MutationFact.Reason.SEND_DENIED,false);
        });
        events.publishEvent(new TemporaryRoomChangedEvent(roomId,cid,roomStatus,users));
        return true;
    }
}
