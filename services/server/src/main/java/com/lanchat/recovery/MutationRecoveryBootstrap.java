package com.lanchat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Explicit maintenance operation only; no scheduler, HTTP route, epoch reset or capability advertisement. */
@Service
public class MutationRecoveryBootstrap {
    private final JdbcTemplate jdbc;
    private final ConversationWriteGuard guard;
    public MutationRecoveryBootstrap(JdbcTemplate jdbc, ConversationWriteGuard guard) { this.jdbc=jdbc;this.guard=guard; }
    public record Result(long messagesExamined,long messagesInserted,long accessExamined,long accessInserted) {}

    @Transactional
    public Result initializeUnderMaintenance() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Recovery bootstrap requires a maintenance write transaction");
        }
        // Requires the deployment barrier to have removed all legacy writers. Lock every
        // conversation first, including the insertion range, before message/access rows.
        List<String> conversations=jdbc.queryForList("SELECT id FROM conversation ORDER BY id FOR UPDATE",String.class);
        var conversationSet=new java.util.HashSet<>(conversations);
        var messages=jdbc.queryForList("SELECT message_id,conversation_id,sequence,is_recalled,status FROM chat_message ORDER BY conversation_id,message_id FOR UPDATE");
        long inserted=0, accessExamined=0, accessInserted=0;
        for (var message : messages) {
            String id=(String)message.get("message_id"),cid=(String)message.get("conversation_id");
            Number sequence=(Number)message.get("sequence");
            Number recalled=(Number)message.get("is_recalled"),status=(Number)message.get("status");
            if (id==null || id.isBlank() || cid==null || !conversationSet.contains(cid) || sequence==null || sequence.longValue()<=0
                    || recalled==null || (recalled.intValue()!=0 && recalled.intValue()!=1)
                    || status==null || status.intValue()<0 || status.intValue()>2
                    || (recalled.intValue()==1 && status.intValue()==2)) {
                throw new IllegalStateException("Legacy message requires repair before recovery bootstrap");
            }
            String state=recalled.intValue()==1 ? "RECALLED" : status.intValue()==2 ? "BURNED" : "NORMAL";
            var existing=jdbc.queryForList("SELECT conversation_id,message_sequence,state FROM recovery_message_state WHERE message_id=? FOR UPDATE",id);
            if (existing.isEmpty()) {
                jdbc.update("INSERT INTO recovery_message_state(message_id,conversation_id,message_sequence,object_version,state) VALUES (?,?,?,?,?)",
                        id,cid,sequence.longValue(),"NORMAL".equals(state)?1:2,state);
                inserted++;
            } else if (!Objects.equals(cid,existing.get(0).get("conversation_id"))
                    || sequence.longValue()!=((Number)existing.get(0).get("message_sequence")).longValue()
                    || !state.equals(existing.get(0).get("state"))) {
                throw new IllegalStateException("Existing message recovery metadata diverged; refusing overwrite");
            }
        }
        for (String cid : conversations) {
            for (Long user : guard.recipients(cid)) {
                var permission=guard.permission(cid,user);
                if (!permission.readAllowed()) continue;
                accessExamined++;
                var existing=jdbc.query("SELECT read_allowed,send_allowed FROM recovery_access_state WHERE conversation_id=? AND user_id=? FOR UPDATE",
                        (rs,row) -> new ConversationWriteGuard.Permission(rs.getBoolean(1),rs.getBoolean(2)),cid,user);
                if (existing.isEmpty()) {
                    jdbc.update("INSERT INTO recovery_access_state VALUES (?,?,1,?,?)",cid,user,permission.readAllowed(),permission.sendAllowed());
                    accessInserted++;
                } else if (existing.get(0).readAllowed()!=permission.readAllowed() || existing.get(0).sendAllowed()!=permission.sendAllowed()) {
                    throw new IllegalStateException("Existing access recovery metadata diverged; reconcile before bootstrap");
                }
            }
        }
        return new Result(messages.size(),inserted,accessExamined,accessInserted);
    }
}
