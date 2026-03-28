package com.example.universityportal.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
  private static final String BEARER_PREFIX = "Bearer ";
  private final Map<String, String> tokensToUsers = new ConcurrentHashMap<>();

  public String createToken(String username) {
    String token = UUID.randomUUID().toString();
    tokensToUsers.put(token, username);
    return token;
  }

  public String getUsernameFromRequest(HttpServletRequest request) {
    if (request == null) return null;
    String auth = request.getHeader("Authorization");
    if (auth == null || !auth.startsWith(BEARER_PREFIX)) return null;
    String token = auth.substring(BEARER_PREFIX.length()).trim();
    if (token.isEmpty()) return null;
    return tokensToUsers.get(token);
  }
}

