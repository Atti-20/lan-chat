package com.lanchat.recovery;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Validates subscriptions after the socket has established its authenticated owner. */
@Service
public class RecoverySubscriptions {
    public static final String EPOCH_ATTRIBUTE="meshxRecoveryStreamEpoch";
    private final MutationStreamReader reader;
    private final BooleanSupplier advertised;
    @Autowired
    public RecoverySubscriptions(MutationStreamReader reader) {this(reader,RecoveryProtocolAvailability::isAdvertised);}
    public RecoverySubscriptions(MutationStreamReader reader,BooleanSupplier advertised) {this.reader=reader;this.advertised=advertised;}
    public String subscribe(Long user,Map<String,Object> payload) {
        if(user==null || user<=0)throw new RecoveryFault(401,"AUTH_REQUIRED");
        if(!advertised.getAsBoolean())throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
        if(payload==null || !payload.keySet().equals(Set.of("capability","protocolVersion","streamEpoch"))
                || !"meshx.mutation-recovery".equals(payload.get("capability")) || !Integer.valueOf(1).equals(payload.get("protocolVersion"))) {
            throw new RecoveryFault(409,"PROTOCOL_VERSION_UNSUPPORTED");
        }
        var stream=reader.stream(user);
        if(!stream.epoch().equals(payload.get("streamEpoch")))throw new RecoveryFault(409,"STREAM_RESET");
        return stream.epoch();
    }
}
