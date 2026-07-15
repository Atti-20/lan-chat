package com.lanchat.common;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    @Test
    void missingStaticResourceReturnsNotFoundWithoutErrorPayload() {
        var response = new GlobalExceptionHandler().handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "favicon.ico"));

        assertEquals(404, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    @Test
    void clientDisconnectDoesNotWriteJsonToImageResponse() throws Exception {
        MockMvc mockMvc = standaloneSetup(new DisconnectController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/disconnect"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[0]))
                .andExpect(result -> assertInstanceOf(
                        AsyncRequestNotUsableException.class, result.getResolvedException()));
    }

    @RestController
    static class DisconnectController {

        @GetMapping("/disconnect")
        void disconnect(HttpServletResponse response) throws AsyncRequestNotUsableException {
            response.setContentType(MediaType.IMAGE_PNG_VALUE);
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe");
        }
    }
}
