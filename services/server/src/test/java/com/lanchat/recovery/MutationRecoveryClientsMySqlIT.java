package com.lanchat.recovery;

import com.lanchat.mapper.ChatMessageMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.lanchat.recovery.MutationJournalMySqlIT.*;

/** Explicit local cross-client probe, separate from the server-only MySQL suite. */
class MutationRecoveryClientsMySqlIT {
    private final MutationJournalMySqlIT fixture = new MutationJournalMySqlIT();
    @BeforeAll static void initializeOwnedDatabase() throws Exception {database();}
    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class RecoveryLiveHttpConfiguration implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Override public void extendMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            // Match this project's Spring Boot 3.5 Jackson defaults; standalone MVC otherwise emits date arrays.
            for(var converter:converters) if(converter instanceof org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter jackson) {
                jackson.getObjectMapper().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                jackson.getObjectMapper().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
            }
        }
    }

    @Test
    void realWebAndFlutterClientsRecoverThroughTomcatAndMySql() throws Exception {
        var root=java.nio.file.Path.of(System.getenv("MESHX_RECOVERY_TEST_ROOT"));
        var out=java.nio.file.Path.of(System.getenv("MESHX_RECOVERY_TEST_OUTPUT"));
        assertTrue(java.nio.file.Files.isDirectory(root.resolve("apps/flutter-prototype")));
        var controller=new com.lanchat.controller.RecoveryController(fixture.resumeSessions(),fixture.manifests(),fixture.snapshotPages(),fixture.streamReader(),true,true);
        var mutations=new java.util.concurrent.atomic.AtomicInteger();
        var factory=new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory(0);
        factory.setAddress(java.net.InetAddress.getLoopbackAddress());
        var web=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        web.register(RecoveryLiveHttpConfiguration.class);
        web.addBeanFactoryPostProcessor(beanFactory->beanFactory.registerSingleton("recoveryController",controller));
        var server=factory.getWebServer(servletContext->{
            web.setServletContext(servletContext);web.refresh();
            var servlet=servletContext.addServlet("recovery",new org.springframework.web.servlet.DispatcherServlet(web));
            servlet.setLoadOnStartup(1);servlet.addMapping("/");
            servletContext.addFilter("ownedFixtureUser",(jakarta.servlet.Filter)(request,response,chain)->{
                var req=(jakarta.servlet.http.HttpServletRequest)request;
                var res=(jakarta.servlet.http.HttpServletResponse)response;
                if(!"Bearer fixture".equals(req.getHeader("Authorization"))) {res.setStatus(401);return;}
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new com.lanchat.security.LoginUser(7L,"fixture","web"),null,List.of()));
                try {
                    if(req.getRequestURI().endsWith("/cut") && mutations.get()<2) {
                        int transition=mutations.getAndIncrement();
                        tx.execute(status->{
                            new ConversationWriteGuard(jdbc).lock("group:21");
                            var previous=sqlSession.getMapper(ChatMessageMapper.class).selectOne(
                                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.lanchat.entity.ChatMessage>().eq("message_id","group:21-1").last("FOR UPDATE"));
                            if(transition==0) jdbc.update("UPDATE chat_message SET content='',is_recalled=1 WHERE message_id='group:21-1'");
                            else jdbc.update("DELETE FROM chat_message WHERE message_id='group:21-1'");
                            new MessageMutationRecorder(jdbc,journal,true).record(previous,transition==0?MutationFact.Type.MESSAGE_RECALLED:MutationFact.Type.MESSAGE_UNAVAILABLE,List.of(7L));
                            return null;
                        });
                    }
                    chain.doFilter(request,response);
                } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            }).addMappingForUrlPatterns(java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST),false,"/*");
        });
        try {
            server.start();
            for(String client:List.of("web","flutter")) {
                fixture.cleanOwnedFixture();fixture.manifestConversation("group:21",205);mutations.set(0);
                jdbc.update("UPDATE chat_message SET content='sensitive-first-body' WHERE message_id='group:21-1'");
                var command="web".equals(client)?List.of("node","apps/web/tool/recovery_server_probe.mjs")
                    :List.of("flutter","test","--no-pub","tool/recovery_server_probe.dart");
                var sharedStore=java.nio.file.Files.createTempDirectory("meshx-http-client-restart-");
                try {
                    for(String phase:"flutter".equals(client)?List.of("initial","restart"):List.of("initial")) {
                        var builder=new ProcessBuilder(command).directory(("web".equals(client)?root:root.resolve("apps/flutter-prototype")).toFile());
                        builder.environment().put("MESHX_RECOVERY_HTTP_ORIGIN","http://127.0.0.1:"+server.getPort());
                        builder.environment().put("MESHX_RECOVERY_CLIENT_STORE",sharedStore.toString());
                        builder.environment().put("MESHX_RECOVERY_CLIENT_PHASE",phase);
                        builder.redirectErrorStream(true).redirectOutput(out.resolve(client+"-live-http-"+phase+".log").toFile());
                        var process=builder.start();
                        try {assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Client timed out: "+client+"/"+phase);assertEquals(0,process.exitValue(),"Inspect "+client+"-live-http-"+phase+".log");}
                        finally {if(process.isAlive()) {process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
                    }
                } finally {
                    try(var files=java.nio.file.Files.walk(sharedStore)) {
                        for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(file);
                    }
                }
                assertEquals(2,mutations.get());
                assertEquals("UNAVAILABLE",jdbc.queryForObject("SELECT state FROM recovery_message_state WHERE message_id='group:21-1'",String.class));
            }
        } finally {server.stop();web.close();}
    }

}
