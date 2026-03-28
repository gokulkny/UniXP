package com.example.universityportal.rag;

import java.util.Map;

public record RagChunk(
    String sourceFileId,
    String sourceFileName,
    String text,
    String normalizedLowerText,
    Map<String, Integer> termCounts
) {}

