package com.example.universityportal.rag;

public final class RagEnvKeys {
  private RagEnvKeys() {}

  public static int getEnvInt(String key, int defaultValue) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return defaultValue;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
