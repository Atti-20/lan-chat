package com.lanchat.recovery;

import com.lanchat.cluster.RealtimeRouter;
import com.lanchat.dto.WebSocketEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Outbox hints accelerate authoritative REST polling; routing never runs inside the claim transaction. */
@Component
@ConditionalOnProperty(name={"meshx.recovery.dual-write-enabled","meshx.recovery.hint-dispatch-enabled"},havingValue="true")
public class MutationHintDispatcher {
    private static final Logger log=LoggerFactory.getLogger(MutationHintDispatcher.class);
    private final MutationDispatchQueue queue;
    private final MutationStreamReader reader;
    private final RealtimeRouter router;
    private final java.util.function.BooleanSupplier advertised;
    @org.springframework.beans.factory.annotation.Autowired
    public MutationHintDispatcher(MutationDispatchQueue queue,MutationStreamReader reader,RealtimeRouter router) {
        this(queue,reader,router,RecoveryProtocolAvailability::isAdvertised);
    }
    public MutationHintDispatcher(MutationDispatchQueue queue,MutationStreamReader reader,RealtimeRouter router,java.util.function.BooleanSupplier advertised) {
        this.queue=queue;this.reader=reader;this.router=router;this.advertised=advertised;
    }
    @Scheduled(fixedDelayString="${meshx.recovery.hint-dispatch-delay-ms:1000}")
    public void dispatch() {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Recovery hint transport must run outside database transactions");
        }
        if(!advertised.getAsBoolean())return;
        for(var lease:queue.claim(100)) {
            try {
                var stream=reader.stream(lease.userId());
                if(!stream.epoch().equals(lease.epoch())) {queue.acknowledge(lease);continue;}
                if(stream.latest()<MutationStreamReader.cursor(lease.cursor()))throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
                var event=new WebSocketEnvelope();event.setEvent("MUTATION_AVAILABLE");event.setTimestamp(System.currentTimeMillis());
                event.setPayload(Map.of("streamEpoch",stream.epoch(),"latestCursor",Long.toString(stream.latest())));
                if(router.sendToUserWithReceipt(lease.userId(),event,()->{}))queue.acknowledge(lease);
                else queue.retry(lease);
            } catch(RuntimeException failure) {
                try {queue.retry(lease);} catch(RuntimeException retryFailure) {failure.addSuppressed(retryFailure);}
                // Lease expiry still permits takeover if persisting the retry itself failed.
                log.warn("Recovery hint delivery failed; durable record retained",failure);
            }
        }
    }
}
