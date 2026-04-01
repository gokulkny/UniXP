package com.example.universityportal.rag;

/**
 * Structured chunk extracted from a PDF.
 */
public record DrivePdfChunk(
    int chunkIndex,
    String content,
    String section,
    String topic
) {}
