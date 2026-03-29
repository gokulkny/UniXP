package com.example.universityportal.rag.vector;

import java.util.UUID;

public record VectorChunkRecord(
    UUID id,
    String sourceFileId,
    String sourceFileName,
    int chunkIndex,
    String content,
    float[] embedding
) {}
