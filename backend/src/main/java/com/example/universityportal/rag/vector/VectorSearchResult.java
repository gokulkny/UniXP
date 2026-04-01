package com.example.universityportal.rag.vector;

import java.util.UUID;

public record VectorSearchResult(
    UUID id,
    String sourceFileId,
    String sourceFileName,
    String docId,
    String section,
    String topic,
    String content,
    double score
) {}
