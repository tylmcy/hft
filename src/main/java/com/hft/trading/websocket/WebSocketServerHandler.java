package com.hft.trading.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hft.trading.security.JwtService;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * WebSocket 服务器处理器
 */
public class WebSocketServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerHandler.class);

    private static final String WEBSOCKET_PATH = "/ws";

    private final WebSocketServer server;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketServerHandshaker handshaker;

    public WebSocketServerHandler(WebSocketServer server, JwtService jwtService) {
        this.server = server;
        this.jwtService = jwtService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            // HTTP 请求，执行握手
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            // WebSocket 帧
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    /**
     * 处理 HTTP 请求（握手）
     */
    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        // 检查是否为 WebSocket 握手请求
        if (!req.decoderResult().isSuccess()) {
            sendHttpResponse(ctx, req, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        // 提取 token 参数
        String uri = req.uri();
        String token = extractToken(uri);

        if (token == null || !validateToken(token, ctx)) {
            sendHttpResponse(ctx, req, HttpResponseStatus.UNAUTHORIZED);
            return;
        }

        // 握手
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
            getWebSocketLocation(req), null, true, 65536);
        handshaker = factory.newHandshaker(req);

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req);
            server.addChannel(ctx.channel());
            ctx.channel().attr(ChannelAttributes.CONNECT_TIME).set(System.currentTimeMillis());
            ctx.channel().attr(ChannelAttributes.AUTHENTICATED).set(true);
            logger.info("WebSocket handshake completed: channel={}", ctx.channel().id());
        }
    }

    /**
     * 处理 WebSocket 帧
     */
    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // 检查是否为关闭帧
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            return;
        }

        // 检查是否为 Ping 帧
        if (frame instanceof PingWebSocketFrame) {
            ctx.write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        // 只处理文本帧
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException("Unsupported frame type: " + frame.getClass().getName());
        }

        // 更新活跃时间
        ctx.channel().attr(ChannelAttributes.LAST_ACTIVE_TIME).set(System.currentTimeMillis());

        // 解析消息
        String request = ((TextWebSocketFrame) frame).text();
        try {
            WebSocketMessage message = objectMapper.readValue(request, WebSocketMessage.class);
            handleMessage(ctx, message);
        } catch (Exception e) {
            logger.error("Failed to parse WebSocket message: {}", e.getMessage());
            sendError(ctx, "Invalid message format");
        }
    }

    /**
     * 处理业务消息
     */
    private void handleMessage(ChannelHandlerContext ctx, WebSocketMessage message) {
        String type = message.getType();

        switch (type) {
            case "subscribe":
                handleSubscribe(ctx, message);
                break;
            case "unsubscribe":
                handleUnsubscribe(ctx, message);
                break;
            case "ping":
                sendPong(ctx);
                break;
            default:
                logger.warn("Unknown message type: {}", type);
        }
    }

    /**
     * 处理订阅请求
     */
    private void handleSubscribe(ChannelHandlerContext ctx, WebSocketMessage message) {
        String channel = message.getChannel();
        Set<String> symbols = parseSymbols(message.getSymbols());

        if (channel == null || symbols.isEmpty()) {
            sendError(ctx, "Invalid subscribe request");
            return;
        }

        // 检查订阅限制
        if (symbols.size() > 50) {
            sendError(ctx, "Too many symbols, max 50");
            return;
        }

        server.subscribe(ctx.channel(), channel, symbols);
        sendConfirm(ctx, "subscribe", message.getMessageId(), channel, symbols);
    }

    /**
     * 处理取消订阅请求
     */
    private void handleUnsubscribe(ChannelHandlerContext ctx, WebSocketMessage message) {
        String channel = message.getChannel();
        Set<String> symbols = parseSymbols(message.getSymbols());

        if (channel == null) {
            sendError(ctx, "Invalid unsubscribe request");
            return;
        }

        server.unsubscribe(ctx.channel(), channel, symbols);
        sendConfirm(ctx, "unsubscribe", message.getMessageId(), channel, symbols);
    }

    /**
     * 发送确认消息
     */
    private void sendConfirm(ChannelHandlerContext ctx, String action, String messageId,
                              String channel, Set<String> symbols) {
        try {
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("type", action + "_confirm");
            response.put("messageId", messageId);
            response.put("channel", channel);
            response.put("symbols", symbols);
            response.put("timestamp", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(response);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("Failed to send confirm message: {}", e.getMessage());
        }
    }

    /**
     * 发送 Pong
     */
    private void sendPong(ChannelHandlerContext ctx) {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("type", "pong");
        response.put("timestamp", System.currentTimeMillis());

        try {
            String json = objectMapper.writeValueAsString(response);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("Failed to send pong: {}", e.getMessage());
        }
    }

    /**
     * 发送错误消息
     */
    private void sendError(ChannelHandlerContext ctx, String errorMessage) {
        try {
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("type", "error");
            response.put("message", errorMessage);
            response.put("timestamp", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(response);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("Failed to send error message: {}", e.getMessage());
        }
    }

    /**
     * 从 URI 提取 token
     */
    private String extractToken(String uri) {
        if (uri == null) return null;
        int idx = uri.indexOf("token=");
        if (idx < 0) return null;
        int start = idx + 6;
        int end = uri.indexOf('&', start);
        return end < 0 ? uri.substring(start) : uri.substring(start, end);
    }

    /**
     * 验证 token
     */
    private boolean validateToken(String token, ChannelHandlerContext ctx) {
        try {
            if (!jwtService.validateToken(token)) {
                logger.warn("Invalid JWT token");
                return false;
            }

            Long userId = jwtService.extractUserId(token);
            Long accountId = jwtService.extractAccountId(token);

            ctx.channel().attr(ChannelAttributes.USER_ID).set(userId);
            ctx.channel().attr(ChannelAttributes.ACCOUNT_ID).set(accountId);

            logger.info("JWT validated: userId={}, accountId={}", userId, accountId);
            return true;

        } catch (Exception e) {
            logger.error("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析 symbols
     */
    private Set<String> parseSymbols(String[] symbols) {
        if (symbols == null) return new HashSet<>();
        return new HashSet<>(Arrays.asList(symbols));
    }

    /**
     * 发送 HTTP 响应
     */
    private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status) {
        io.netty.handler.codec.http.DefaultFullHttpResponse response =
            new io.netty.handler.codec.http.DefaultFullHttpResponse(
                req.protocolVersion(), status);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 获取 WebSocket URL
     */
    private String getWebSocketLocation(FullHttpRequest req) {
        return "ws://" + req.headers().get(io.netty.handler.codec.http.HttpHeaderNames.HOST) + WEBSOCKET_PATH;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        server.removeChannel(ctx.channel());
        logger.info("Channel disconnected: {}", ctx.channel().id());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket exception: {}", cause.getMessage());
        ctx.close();
    }

    /**
     * WebSocket 消息 DTO
     */
    public static class WebSocketMessage {
        private String type;
        private String channel;
        private String[] symbols;
        private String messageId;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public String[] getSymbols() { return symbols; }
        public void setSymbols(String[] symbols) { this.symbols = symbols; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
    }
}
