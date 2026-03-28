package com.example.universityportal.dto;

public record ChatResponse(String reply, String error) {
  public static ChatResponse ok(String reply) {
    return new ChatResponse(reply, null);
  }

  public static ChatResponse fail(String error) {
    return new ChatResponse(null, error);
  }
}

