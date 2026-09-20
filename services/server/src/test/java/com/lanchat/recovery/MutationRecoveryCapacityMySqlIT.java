package com.lanchat.recovery;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static com.lanchat.recovery.MutationJournalMySqlIT.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit owned-MySQL capacity gate; not part of ordinary unit tests. */
class MutationRecoveryCapacityMySqlIT {
    private final MutationJournalMySqlIT fixture = new MutationJournalMySqlIT();
    @BeforeAll static void initialize() throws Exception { database(); }

    @Test @Timeout(value=10, unit=TimeUnit.MINUTES)
    void complete200kManifestPagesAndReadyThenRejectOverflowWithoutTruncation() {
        fixture.cleanOwnedFixture();
        for (int group=1; group<=100; group++) {
            String cid="group:"+group;
            fixture.manifestConversation(cid,0);
            jdbc.update("UPDATE conversation SET last_sequence=2000 WHERE id=?",cid);
            // Bulk fixture insertion only. Runtime driver settings and transaction timeouts are unchanged.
            for(int offset=0;offset<2000;offset+=500) {
                int start=offset;
                String states=IntStream.rangeClosed(start+1,start+500).mapToObj(i->"('"+cid+"-"+i+"','"+cid+"',"+i+",1,'NORMAL')").collect(Collectors.joining(","));
                jdbc.execute("INSERT INTO recovery_message_state VALUES "+states);
                String bodies=IntStream.rangeClosed(start+1,start+500).mapToObj(i->"('"+cid+"-"+i+"','"+cid+"',"+i+",0,0,7,'text','capacity fixture',UTC_TIMESTAMP(6),0)").collect(Collectors.joining(","));
                jdbc.execute("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status,from_user_id,type,content,create_time,is_burn) VALUES "+bodies);
            }
        }
        long start=System.nanoTime();
        var snapshot=fixture.manifests().create(7,"https://capacity.test");
        long manifestMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertEquals(200100,snapshot.itemCount());
        var seen=new HashSet<String>(); var directories=new HashSet<String>();
        var pages=fixture.snapshotPages(); String token=null;int pageCount=0;
        do {
            var page=pages.page(7,"https://capacity.test",snapshot.recoveryId(),token,200);
            assertEquals(snapshot.snapshotId(),page.snapshotId());
            for(var item:page.items()) {
                if("CONVERSATION".equals(item.kind())) assertTrue(directories.add(item.conversationId()));
                else {assertTrue(seen.add(item.messageId()));assertEquals("capacity fixture",item.content());}
            }
            pageCount++;
            if(pageCount%100==0) System.out.println("CAPACITY pages="+pageCount);
            token=page.nextPageToken();assertEquals(token==null,page.snapshotComplete());
        } while(token!=null);
        assertEquals(200000,seen.size());assertEquals(100,directories.size());assertEquals(1001,pageCount);
        var sessions=fixture.resumeSessions();var cut=sessions.cut(7,"https://capacity.test",snapshot.recoveryId());
        assertTrue(sessions.ready(7,"https://capacity.test",snapshot.recoveryId(),cut.through(),true).ready());
        long elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        jdbc.update("UPDATE conversation SET last_sequence=2001 WHERE id='group:1'");
        jdbc.update("INSERT INTO recovery_message_state VALUES ('overflow','group:1',2001,1,'NORMAL')");
        jdbc.update("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status,from_user_id,type,content,create_time,is_burn) VALUES ('overflow','group:1',2001,0,0,7,'text','overflow',UTC_TIMESTAMP(6),0)");
        var overflow=assertThrows(RecoveryFault.class,()->fixture.manifests().create(7,"https://capacity.test"));
        assertEquals(503,overflow.status());assertEquals("RECOVERY_CAPACITY",overflow.reason());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot",Integer.class));
        assertEquals(200100,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot_item",Integer.class));
        sessions.release(7,"https://capacity.test",snapshot.recoveryId());
        System.out.println("CAPACITY_RESULT {\"messages\":200000,\"conversations\":100,\"pages\":1001,\"manifestMs\":"+manifestMs+",\"elapsedMs\":"+elapsedMs+",\"overflow\":\"REJECTED\"}");
    }
}
