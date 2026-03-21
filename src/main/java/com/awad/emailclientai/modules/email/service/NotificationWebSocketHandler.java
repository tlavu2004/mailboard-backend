package com.awad.emailclientai.modules.email.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    // Map accountId -> Set of WebSocket sessions
    private final Map<Long, Set<WebSocketSession>> accountSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long accountId = getAccountId(session);
        if (accountId != null) {
            accountSessions.computeIfAbsent(accountId, k -> new CopyOnWriteArraySet<>()).add(session);
            log.info("WebSocket connection established for account: {}", accountId);
        } else {
            log.warn("WebSocket connection attempt without accountId");
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (IOException e) {
                log.error("Error closing session", e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long accountId = getAccountId(session);
        if (accountId != null) {
            Set<WebSocketSession> sessions = accountSessions.get(accountId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    accountSessions.remove(accountId);
                }
            }
            log.info("WebSocket connection closed for account: {}", accountId);
        }
    }

    public void sendNotification(Long accountId, String payload) {
        Set<WebSocketSession> sessions = accountSessions.get(accountId);
        if (sessions != null) {
            TextMessage message = new TextMessage(payload);
            sessions.forEach(session -> {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                } catch (IOException e) {
                    log.error("Error sending WebSocket message to account: {}", accountId, e);
                }
            });
        }
    }

    private Long getAccountId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null && query.contains("accountId=")) {
            try {
                return Long.parseLong(query.split("accountId=")[1].split("&")[0]);
            } catch (Exception e) {
                log.error("Error parsing accountId from query: {}", query);
            }
        }
        return null;
    }
}
