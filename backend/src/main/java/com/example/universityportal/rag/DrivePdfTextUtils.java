package com.example.universityportal.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class DrivePdfTextUtils {
  private DrivePdfTextUtils() {}

  public static String normalizeWhitespace(String s) {
    if (s == null) return "";
    return s.replaceAll("\\s+", " ").trim();
  }

  public static List<String> chunkText(String text, int chunkSizeChars, int overlapChars) {
    String normalized = normalizeWhitespace(text);
    if (normalized.length() <= chunkSizeChars) return List.of(normalized);

    int step = Math.max(1, chunkSizeChars - overlapChars);
    List<String> chunks = new ArrayList<>();
    int i = 0;
    while (i < normalized.length()) {
      int end = Math.min(normalized.length(), i + chunkSizeChars);
      String part = normalized.substring(i, end);
      chunks.add(part);
      if (end >= normalized.length()) break;
      i += step;
    }
    return chunks;
  }

  public static String extractPdfText(byte[] pdfBytes) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      return stripper.getText(doc);
    }
  }
}
