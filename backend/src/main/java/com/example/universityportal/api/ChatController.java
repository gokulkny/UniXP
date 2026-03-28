package com.example.universityportal.api;

import com.example.universityportal.ai.ChatService;
import com.example.universityportal.dto.ChatRequest;
import com.example.universityportal.dto.ChatResponse;
import com.example.universityportal.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {
  private static final Logger log = LoggerFactory.getLogger(ChatController.class);

  private final TokenService tokenService;
  private final ChatService chatService;

  public ChatController(TokenService tokenService, ChatService chatService) {
    this.tokenService = tokenService;
    this.chatService = chatService;
  }

  @PostMapping("/chat")
  public ResponseEntity<?> chat(HttpServletRequest request, @RequestBody ChatRequest body) {
    if (body == null || body.message() == null || body.message().isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "message is required"));
    }

    String requestId = UUID.randomUUID().toString();
    String username = tokenService.getUsernameFromRequest(request);
    if (username == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ChatResponse(null, "Unauthorized"));
    }

    log.info("Chat request: requestId={}, username={}", requestId, username);

    boolean strictFromDocs = Boolean.TRUE.equals(body.strictFromDocs());
    ChatResponse response = chatService.chat(username, body.message().trim(), strictFromDocs, requestId);
    if (response.error() != null) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
    return ResponseEntity.ok(response);
  }
}

