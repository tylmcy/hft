package com.hft.trading.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 服务
 *
 * 根据 V12 技术方案：
 * - Access Token 有效期：2小时
 * - Refresh Token 有效期：7天
 * - 密钥从环境变量读取
 */
@Component
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    @Value("${trading.jwt.access-token-expiration:7200000}")
    private long accessTokenExpiration;

    @Value("${trading.jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration;

    @Value("${trading.jwt.secret-key:default-secret-key-for-development-only}")
    private String secretKey;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getEncoder().encode(secretKey.getBytes());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 Access Token
     */
    public String generateAccessToken(Long userId, Long accountId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("accountId", accountId);
        claims.put("username", username);
        claims.put("role", role);

        return Jwts.builder()
            .claims(claims)
            .subject(String.valueOf(userId))
            .issuedAt(new Date(System.currentTimeMillis()))
            .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
            .signWith(getSigningKey())
            .compact();
    }

    /**
     * 生成 Refresh Token
     */
    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .issuedAt(new Date(System.currentTimeMillis()))
            .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiration))
            .signWith(getSigningKey())
            .compact();
    }

    /**
     * 解析 Token 获取用户 ID
     */
    public Long extractUserId(String token) {
        String subject = extractClaim(token, Claims::getSubject);
        return Long.parseLong(subject);
    }

    /**
     * 解析 Token 获取账户 ID
     */
    public Long extractAccountId(String token) {
        Claims claims = extractAllClaims(token);
        Object accountId = claims.get("accountId");
        return accountId != null ? Long.parseLong(accountId.toString()) : null;
    }

    /**
     * 解析 Token 获取用户名
     */
    public String extractUsername(String token) {
        Claims claims = extractAllClaims(token);
        Object username = claims.get("username");
        return username != null ? username.toString() : null;
    }

    /**
     * 解析 Token 获取角色
     */
    public String extractRole(String token) {
        Claims claims = extractAllClaims(token);
        Object role = claims.get("role");
        return role != null ? role.toString() : null;
    }

    /**
     * 解析 Token 获取过期时间
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * 解析指定 Claim
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * 解析所有 Claims
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * 检查 Token 是否过期
     */
    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * 验证 Token
     */
    public Boolean validateToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
            return !isTokenExpired(token);
        } catch (Exception e) {
            logger.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 验证 Token 并检查用户 ID
     */
    public Boolean validateToken(String token, Long userId) {
        try {
            final Long tokenUserId = extractUserId(token);
            return tokenUserId.equals(userId) && !isTokenExpired(token);
        } catch (Exception e) {
            logger.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取 Access Token 过期时间（毫秒）
     */
    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    /**
     * 获取 Refresh Token 过期时间（毫秒）
     */
    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }
}