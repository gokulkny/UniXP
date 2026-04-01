package com.example.universityportal.rag.vector;

import java.util.UUID;

public record VectorChunkRecord(
    UUID id,
    String sourceFileId,
    String sourceFileName,
    String docId,
    int chunkIndex,
    String section,
    String topic,
    String content,
    float[] embedding
) {}
