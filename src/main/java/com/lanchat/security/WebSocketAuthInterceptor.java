package com.lanchat.security;

import com.lanchat.service.UserService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手认证。浏览器原生 WebSocket 无法设置 Authorization 请求头，
 * 因此前端只在握手阶段通过查询参数传入访问令牌，服务端校验后仅把用户 ID
 * 写入 session attributes，后续消息不再信任客户端传入的 userId。
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    public static final String USER_ID_ATTRIBUTE = "authenticatedUserId";

    private final JwtUtil jwtUtil;
    private final UserService userService;

    public WebSocketAuthInterceptor(JwtUtil jwtUtil, @Lazy UserService userService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        try {
            String token = UriComponentsBuilder.fromUri(request.getURI())
                    .build()
                    .getQueryParams()
                    .getFirst("token");
            if (!StringUtils.hasText(token) || !jwtUtil.isAccessToken(token)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            Long userId = jwtUtil.getUserIdFromToken(token);
            String deviceType = jwtUtil.getDeviceTypeFromToken(token);
            if (!userService.isAccessTokenActive(token, userId, deviceType)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            attributes.put(USER_ID_ATTRIBUTE, userId);
            return true;
        } catch (Exception ignored) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}
