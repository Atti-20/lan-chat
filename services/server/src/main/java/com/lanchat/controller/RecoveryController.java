package com.lanchat.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lanchat.common.Result;
import com.lanchat.recovery.*;
import com.lanchat.security.UserContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Candidate recovery endpoints; disabled until the migration/deployment gates are satisfied. */
@RestController
@RequestMapping("/api/v1/chat/recovery")
public class RecoveryController {
    private final RecoveryResumeSessions sessions;
    private final RecoverySnapshotManifest manifests;
    private final RecoverySnapshotPages pages;
    private final MutationStreamReader reader;
    private final boolean enabled;
    public RecoveryController(RecoveryResumeSessions sessions,RecoverySnapshotManifest manifests,RecoverySnapshotPages pages,
                              MutationStreamReader reader,@Value("${meshx.recovery.api-enabled:false}") boolean apiEnabled,
                              @Value("${meshx.recovery.dual-write-enabled:false}") boolean dualWriteEnabled) {
        this.sessions=sessions;this.manifests=manifests;this.pages=pages;this.reader=reader;this.enabled=apiEnabled&&dualWriteEnabled;
    }
    public record Cursor(String streamEpoch,String position) {}
    public record Open(Integer protocolVersion,String mode,Cursor cursor,List<String> conversationIds) {}
    public record ReadyRequest(String appliedCursor,Boolean snapshotComplete) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionResponse(String recoveryId,String streamEpoch,String mode,String startCursor,String floor,String latest,
                                  String snapshotId,String snapshotBoundary,String expiresAt) {}
    public record Capabilities(String capability,List<Integer> versions,int recordVersion,boolean recoveryRequiredForSafeMode,
                               boolean legacyFullRebuildCertified,int retentionSeconds,int sessionTtlSeconds,int maxPageSize,
                               int pollIntervalSeconds,int staleAfterSeconds) {}

    @GetMapping("/capabilities")
    public ResponseEntity<Result<Capabilities>> capabilities() {
        owner();
        // Candidate API testing must not advertise certification before local rebuild/dispatch/barrier gates close.
        return ok(new Capabilities("meshx.mutation-recovery",enabled&&RecoveryProtocolAvailability.isAdvertised()?List.of(1):List.of(),1,true,false,2592000,900,200,5,30));
    }
    @PostMapping("/sessions")
    public ResponseEntity<Result<SessionResponse>> open(@RequestBody Open request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest servlet) {
        long user=availableOwner();String origin=origin(servlet);
        if(request.protocolVersion()==null || request.protocolVersion()!=1) throw new RecoveryFault(409,"PROTOCOL_VERSION_UNSUPPORTED");
        if("resume".equals(request.mode())) {
            if(request.cursor()==null || request.conversationIds()!=null) throw new RecoveryFault(400,"INVALID_CURSOR");
            var row=sessions.open(user,origin,request.cursor().streamEpoch(),request.cursor().position(),key);
            return ok(new SessionResponse(row.recoveryId(),row.streamEpoch(),row.mode(),row.startCursor(),row.floor(),row.latest(),null,null,row.expiresAt()));
        }
        boolean partial="rebuild-conversations".equals(request.mode());
        if((!partial && !"rebuild".equals(request.mode())) || request.cursor()!=null
                || (partial && request.conversationIds()==null) || (!partial && request.conversationIds()!=null)) throw new RecoveryFault(400,"INVALID_RECOVERY_MODE");
        var row=manifests.create(user,origin,key,request.conversationIds());
        return ok(new SessionResponse(row.recoveryId(),row.streamEpoch(),row.mode(),row.startCursor(),row.floor(),row.latest(),row.snapshotId(),row.startCursor(),row.expiresAt()));
    }
    @GetMapping("/sessions/{id}/snapshot")
    public ResponseEntity<Result<RecoverySnapshotPages.SnapshotPage>> snapshot(@PathVariable String id,
            @RequestParam(required=false) String pageToken,@RequestParam(defaultValue="100") int limit,HttpServletRequest servlet) {
        return ok(pages.page(availableOwner(),origin(servlet),id,pageToken,limit));
    }
    @PostMapping("/sessions/{id}/cut")
    public ResponseEntity<Result<RecoveryResumeSessions.Cut>> cut(@PathVariable String id,HttpServletRequest servlet) {
        return ok(sessions.cut(availableOwner(),origin(servlet),id));
    }
    @GetMapping("/sessions/{id}/mutations")
    public ResponseEntity<Result<MutationStreamReader.MutationPage>> mutations(@PathVariable String id,@RequestParam String after,
            @RequestParam String through,@RequestParam(defaultValue="100") int limit,HttpServletRequest servlet) {
        return ok(sessions.mutations(availableOwner(),origin(servlet),id,after,through,limit));
    }
    @PostMapping("/sessions/{id}/ready")
    public ResponseEntity<Result<RecoveryResumeSessions.Ready>> ready(@PathVariable String id,@RequestBody ReadyRequest request,HttpServletRequest servlet) {
        if(request.snapshotComplete()==null) throw new RecoveryFault(400,"RANGE_MISMATCH");
        return ok(sessions.ready(availableOwner(),origin(servlet),id,request.appliedCursor(),request.snapshotComplete()));
    }
    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> release(@PathVariable String id,HttpServletRequest servlet) {
        sessions.release(availableOwner(),origin(servlet),id);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @ExceptionHandler(RecoveryFault.class)
    public ResponseEntity<Result<Map<String,Object>>> failure(RecoveryFault fault) {
        Map<String,Object> data=new LinkedHashMap<>();data.put("reason",fault.reason());
        if(List.of("CURSOR_EXPIRED","CURSOR_AHEAD","STREAM_RESET").contains(fault.reason())) {
            data.put("rebuildRequired",true);Long user=UserContextHolder.getCurrentUserId();
            if(user!=null) try {
                var stream=reader.stream(user);data.put("floor",Long.toString(stream.floor()));
                data.put("latest",Long.toString(stream.latest()));data.put("streamEpoch",stream.epoch());
            } catch(RuntimeException unavailable) { /* no fabricated stream bounds */ }
        }
        Result<Map<String,Object>> result=Result.error(fault.status(),fault.reason());result.setData(data);
        return ResponseEntity.status(fault.status()).cacheControl(CacheControl.noStore()).body(result);
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Map<String,Object>>> invalidRequest(Exception ignored) {return failure(new RecoveryFault(400,"INVALID_REQUEST"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<Map<String,Object>>> unavailable(Exception ignored) {return failure(new RecoveryFault(503,"RECOVERY_UNAVAILABLE"));}
    private long owner() {Long user=UserContextHolder.getCurrentUserId();if(user==null || user<=0)throw new RecoveryFault(401,"AUTH_REQUIRED");return user;}
    private long availableOwner() {long user=owner();if(!enabled)throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");return user;}
    private String origin(HttpServletRequest request) {
        String scheme=request.getScheme().toLowerCase(java.util.Locale.ROOT),host=request.getServerName().toLowerCase(java.util.Locale.ROOT);
        if(host.contains(":") && !host.startsWith("["))host="["+host+"]";
        int port=request.getServerPort();return scheme+"://"+host+(("https".equals(scheme)&&port==443)||("http".equals(scheme)&&port==80)?"":":"+port);
    }
    private <T> ResponseEntity<Result<T>> ok(T data) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(data));}
}
