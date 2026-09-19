package com.lanchat.controller;

import com.lanchat.recovery.*;
import com.lanchat.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecoveryControllerTest {
    final RecoveryResumeSessions sessions=mock(RecoveryResumeSessions.class);
    final RecoverySnapshotManifest manifests=mock(RecoverySnapshotManifest.class);
    final RecoverySnapshotPages pages=mock(RecoverySnapshotPages.class);
    final MutationStreamReader reader=mock(MutationStreamReader.class);
    MockMvc mvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new RecoveryController(sessions,manifests,pages,reader,enabled,enabled)).build();
    }
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(7L,"synthetic","web"),null,List.of()));
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}

    @Test void unauthenticatedRecoveryReturnsReal401AndNeverCallsServices() throws Exception {
        mvc(true).perform(get("/api/v1/chat/recovery/capabilities")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401)).andExpect(jsonPath("$.data.reason").value("AUTH_REQUIRED"))
                .andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(sessions,manifests,pages,reader);
    }
    @Test void disabledCandidateCannotAdvertiseOrStartRecovery() throws Exception {
        login();var mvc=mvc(false);
        mvc.perform(get("/api/v1/chat/recovery/capabilities")).andExpect(status().isOk()).andExpect(jsonPath("$.data.versions").isEmpty());
        mvc.perform(post("/api/v1/chat/recovery/sessions").contentType("application/json").content("{\"protocolVersion\":1,\"mode\":\"rebuild\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
        verifyNoInteractions(sessions,manifests,pages,reader);
    }
    @Test void resumeUsesAuthenticatedOwnerAndServerOriginAndErrorsCarryActualBounds() throws Exception {
        login();when(sessions.open(7,"http://localhost","old","0","request-a")).thenThrow(new RecoveryFault(409,"STREAM_RESET"));
        when(reader.stream(7)).thenReturn(new MutationStreamReader.Stream("current",2,4));
        mvc(true).perform(post("/api/v1/chat/recovery/sessions").header("Origin","https://untrusted.test").header("Idempotency-Key","request-a")
                        .contentType("application/json").content("{\"protocolVersion\":1,\"mode\":\"resume\",\"userId\":99,\"cursor\":{\"streamEpoch\":\"old\",\"position\":\"0\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409)).andExpect(jsonPath("$.data.reason").value("STREAM_RESET"))
                .andExpect(jsonPath("$.data.floor").value("2")).andExpect(jsonPath("$.data.latest").value("4"))
                .andExpect(jsonPath("$.data.rebuildRequired").value(true));
        verify(sessions).open(7,"http://localhost","old","0","request-a");
    }
    @Test void malformedPagingAndProtocolVersionFailWithoutDatabaseCalls() throws Exception {
        login();var mvc=mvc(true);
        mvc.perform(get("/api/v1/chat/recovery/sessions/example/snapshot").param("limit","invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/chat/recovery/sessions").contentType("application/json").content("{\"protocolVersion\":2,\"mode\":\"rebuild\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.reason").value("PROTOCOL_VERSION_UNSUPPORTED"));
        verifyNoInteractions(sessions,manifests,pages,reader);
    }
    @Test void localRebuildResponseReportsActualReplacementScopeIncludingFullFallback() throws Exception {
        login();var mvc=mvc(true);
        when(manifests.create(7,"http://localhost","local",List.of("group:21")))
                .thenReturn(new RecoverySnapshotManifest.Snapshot("session","snapshot","epoch","2","0","2","expiry",3,"rebuild-conversations"))
                .thenReturn(new RecoverySnapshotManifest.Snapshot("session2","snapshot2","epoch","2","0","2","expiry",5,"rebuild"));
        for(String mode:List.of("rebuild-conversations","rebuild")) {
            mvc.perform(post("/api/v1/chat/recovery/sessions").header("Idempotency-Key","local").contentType("application/json")
                            .content("{\"protocolVersion\":1,\"mode\":\"rebuild-conversations\",\"conversationIds\":[\"group:21\"]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value(mode));
        }
        verify(manifests,times(2)).create(7,"http://localhost","local",List.of("group:21"));
    }
}
