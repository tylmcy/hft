package com.hft.trading.websocket;

import io.netty.util.AttributeKey;

/**
 * WebSocket Channel 属性定义
 */
public class ChannelAttributes {

    /**
     * 用户 ID
     */
    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("userId");

    /**
     * 账户 ID
     */
    public static final AttributeKey<Long> ACCOUNT_ID = AttributeKey.valueOf("accountId");

    /**
     * 连接时间
     */
    public static final AttributeKey<Long> CONNECT_TIME = AttributeKey.valueOf("connectTime");

    /**
     * 最后活跃时间
     */
    public static final AttributeKey<Long> LAST_ACTIVE_TIME = AttributeKey.valueOf("lastActiveTime");

    /**
     * 是否已认证
     */
    public static final AttributeKey<Boolean> AUTHENTICATED = AttributeKey.valueOf("authenticated");

    private ChannelAttributes() {}
}
