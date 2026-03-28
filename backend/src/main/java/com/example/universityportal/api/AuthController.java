package com.example.universityportal.api;

import com.example.universityportal.dto.LoginRequest;
import com.example.universityportal.dto.LoginResponse;
import com.example.universityportal.dto.MeResponse;
import com.example.universityportal.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final TokenService tokenService;

  // Demo-only: any username is allowed if password is exactly "demo".
  private static final String DEMO_PASSWORD = "demo";

  public AuthController(TokenService tokenService) {
    this.tokenService = tokenService;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@RequestBody LoginRequest req) {
    if (req == null
        || req.username() == null
        || req.username().isBlank()
        || req.password() == null
        || req.password().isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "username and password are required"));
    }

    if (!DEMO_PASSWORD.equals(req.password())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("Invalid credentials"));
    }

    String username = req.username().trim();
    String token = tokenService.createToken(username);
    return ResponseEntity.ok(new LoginResponse(token, username));
  }

  @GetMapping("/me")
  public ResponseEntity<?> me(HttpServletRequest request) {
    String username = tokenService.getUsernameFromRequest(request);
    if (username == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("Unauthorized"));
    }

    return ResponseEntity.ok(new MeResponse(username, username));
  }
}

