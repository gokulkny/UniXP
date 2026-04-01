package com.example.universityportal.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrivePdfTextUtils {
  private DrivePdfTextUtils() {}
  private static final Pattern SECTION_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+){1,3})\\b.*");

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

  public static List<DrivePdfChunk> chunkStructuredText(String text, int targetMinChars, int targetMaxChars) {
    String normalized = normalizeWhitespace(text);
    if (normalized.isBlank()) return List.of();
    int minChars = Math.max(700, targetMinChars);
    int maxChars = Math.max(minChars + 120, targetMaxChars);
    String[] sentences = normalized.split("(?<=[.!?])\\s+");
    List<DrivePdfChunk> out = new ArrayList<>();
    String currentSection = "unknown";
    StringBuilder buffer = new StringBuilder();
    int chunkIndex = 0;

    for (String raw : sentences) {
      String sentence = normalizeWhitespace(raw);
      if (sentence.isBlank()) continue;
      Matcher matcher = SECTION_PATTERN.matcher(sentence);
      if (matcher.matches()) {
        currentSection = matcher.group(1);
      }
      boolean overflow = buffer.length() + sentence.length() + 1 > maxChars;
      if (overflow && buffer.length() >= minChars) {
        String content = normalizeWhitespace(buffer.toString());
        out.add(new DrivePdfChunk(chunkIndex++, content, currentSection, inferTopic(content)));
        buffer = new StringBuilder();
      }
      if (!buffer.isEmpty()) buffer.append(' ');
      buffer.append(sentence);
    }

    if (!buffer.isEmpty()) {
      String content = normalizeWhitespace(buffer.toString());
      out.add(new DrivePdfChunk(chunkIndex, content, currentSection, inferTopic(content)));
    }
    return out;
  }

  private static String inferTopic(String content) {
    String lc = content.toLowerCase(Locale.ROOT);
    if (lc.contains("doutorado") && (lc.contains("documento") || lc.contains("inscri"))) {
      return "documentos doutorado";
    }
    if (lc.contains("mestrado") && lc.contains("requisito")) {
      return "requisitos mestrado";
    }
    if (lc.contains("inscri")) {
      return "inscricao";
    }
    return "geral";
  }

  public static String extractPdfText(byte[] pdfBytes) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      return stripper.getText(doc);
    }
  }
}
