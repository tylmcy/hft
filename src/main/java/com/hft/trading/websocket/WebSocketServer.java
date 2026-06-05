package com.hft.trading.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hft.trading.security.JwtService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocketServer - WebSocket 服务器
 *
 * 根据 V12 技术方案 8.3 节：
 * - 连接端点：ws://host:port/ws?token=<jwt_token>
 * - 支持 market_snapshot, kline, orderbook, order_update 等频道
 * - 心跳：30秒间隔
 */
@Component
public class WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);

    private final int port = 8081;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Netty 组件
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    // 连接管理
    private final Set<Channel> activeChannels = new CopyOnWriteArraySet<>();
    private final Map<Channel, Set<String>> channelSubscriptions = new ConcurrentHashMap<>();
    private final Map<Long, Set<Channel>> userChannels = new ConcurrentHashMap<>();

    public WebSocketServer(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * 启动 WebSocket 服务器
     */
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(8);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new WebSocketServerHandler(WebSocketServer.this, jwtService));
                    }
                });

            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            logger.info("WebSocket server started on port: {}", port);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("WebSocket server start interrupted", e);
        }
    }

    /**
     * 停止 WebSocket 服务器
     */
    @PreDestroy
    public void stop() {
        logger.info("Stopping WebSocket server...");

        // 关闭所有活跃连接
        for (Channel channel : activeChannels) {
            channel.close();
        }

        // 关闭服务器 Channel
        if (serverChannel != null) {
            serverChannel.close();
        }

        // 关闭 EventLoopGroup
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        logger.info("WebSocket server stopped");
    }

    /**
     * 添加活跃连接
     */
    public void addChannel(Channel channel) {
        activeChannels.add(channel);
        channelSubscriptions.put(channel, ConcurrentHashMap.newKeySet());

        Long userId = channel.attr(ChannelAttributes.USER_ID).get();
        if (userId != null) {
            userChannels.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(channel);
        }

        logger.debug("Channel added: {}, total: {}", channel.id(), activeChannels.size());
    }

    /**
     * 移除连接
     */
    public void removeChannel(Channel channel) {
        activeChannels.remove(channel);
        channelSubscriptions.remove(channel);

        Long userId = channel.attr(ChannelAttributes.USER_ID).get();
        if (userId != null) {
            Set<Channel> channels = userChannels.get(userId);
            if (channels != null) {
                channels.remove(channel);
                if (channels.isEmpty()) {
                    userChannels.remove(userId);
                }
            }
        }

        logger.debug("Channel removed: {}, total: {}", channel.id(), activeChannels.size());
    }

    /**
     * 订阅频道
     */
    public void subscribe(Channel channel, String channelName, Set<String> symbols) {
        Set<String> subscriptions = channelSubscriptions.get(channel);
        if (subscriptions != null) {
            for (String symbol : symbols) {
                subscriptions.add(channelName + ":" + symbol);
            }
            logger.debug("Channel {} subscribed: {} for symbols: {}",
                channel.id(), channelName, symbols);
        }
    }

    /**
     * 取消订阅
     */
    public void unsubscribe(Channel channel, String channelName, Set<String> symbols) {
        Set<String> subscriptions = channelSubscriptions.get(channel);
        if (subscriptions != null) {
            for (String symbol : symbols) {
                subscriptions.remove(channelName + ":" + symbol);
            }
            logger.debug("Channel {} unsubscribed: {} for symbols: {}",
                channel.id(), channelName, symbols);
        }
    }

    /**
     * 推送消息给所有订阅者
     */
    public void broadcast(String channelName, String symbol, Object message) {
        String subscriptionKey = channelName + ":" + symbol;

        for (Channel channel : activeChannels) {
            Set<String> subscriptions = channelSubscriptions.get(channel);
            if (subscriptions != null && subscriptions.contains(subscriptionKey)) {
                sendMessage(channel, message);
            }
        }
    }

    /**
     * 推送消息给特定用户
     */
    public void sendToUser(long userId, String channelName, Object message) {
        Set<Channel> channels = userChannels.get(userId);
        if (channels != null) {
            for (Channel channel : channels) {
                sendMessage(channel, message);
            }
        }
    }

    /**
     * 发送消息到 Channel
     */
    private void sendMessage(Channel channel, Object message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            channel.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("Failed to send message to channel: {}", e.getMessage());
        }
    }

    /**
     * 获取活跃连接数
     */
    public int getActiveConnectionCount() {
        return activeChannels.size();
    }
}
