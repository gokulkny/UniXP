package com.example.universityportal.rag;

import java.util.List;

/**
 * Raw text chunks for one PDF from Drive (before embedding).
 */
public record DrivePdfDocument(
    String sourceFileId,
    String sourceFileName,
    List<String> chunkTexts
) {}
