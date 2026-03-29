package com.example.universityportal.rag;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Downloads configured Drive PDFs and produces normalized text chunks.
 */
@Component
public class GoogleDrivePdfLoader {
  private static final Logger log = LoggerFactory.getLogger(GoogleDrivePdfLoader.class);
  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

  @Value("${portal.drive.fileIds:}")
  private String fileIdsCsv;

  public List<DrivePdfDocument> loadChunkedDocuments(
      String serviceAccountJson,
      int chunkSize,
      int chunkOverlap,
      int minChunkChars
  ) throws Exception {
    if (fileIdsCsv == null || fileIdsCsv.isBlank()) {
      log.warn("GoogleDrivePdfLoader: portal.drive.fileIds is empty — no PDFs will be loaded");
      return List.of();
    }

    Drive drive = buildDriveClient(serviceAccountJson);
    List<String> fileIds = parseFileIds(fileIdsCsv);
    log.info(
        "GoogleDrivePdfLoader: loading {} Drive file id(s), chunkSize={}, overlap={}, minChunkChars={}",
        fileIds.size(), chunkSize, chunkOverlap, minChunkChars
    );
    List<DrivePdfDocument> out = new ArrayList<>();

    for (String fileId : fileIds) {
      log.info("GoogleDrivePdfLoader: fetching metadata for Drive file id={}", fileId);
      File meta = drive.files().get(fileId).setFields("id,name,mimeType").execute();
      if (meta == null) {
        log.warn("GoogleDrivePdfLoader: Drive returned no metadata for id={}", fileId);
        continue;
      }
      if (meta.getMimeType() != null && !meta.getMimeType().toLowerCase(Locale.ROOT).contains("pdf")) {
        log.warn("Skipping non-PDF file: id={}, name={}, mimeType={}", meta.getId(), meta.getName(), meta.getMimeType());
        continue;
      }

      byte[] pdfBytes = downloadFileBytes(drive, fileId);
      if (pdfBytes == null || pdfBytes.length == 0) {
        log.warn("GoogleDrivePdfLoader: empty download for id={}, name={}", meta.getId(), meta.getName());
        continue;
      }
      log.info(
          "GoogleDrivePdfLoader: downloaded {} bytes for id={}, name={}",
          pdfBytes.length, meta.getId(), meta.getName()
      );

      String text = DrivePdfTextUtils.extractPdfText(pdfBytes);
      if (text == null || text.isBlank()) {
        log.warn("No extractable text for PDF: id={}, name={}", meta.getId(), meta.getName());
        continue;
      }

      List<String> chunkTexts = DrivePdfTextUtils.chunkText(text, chunkSize, chunkOverlap);
      List<String> kept = new ArrayList<>();
      for (String ct : chunkTexts) {
        String normalized = DrivePdfTextUtils.normalizeWhitespace(ct);
        if (normalized.length() >= minChunkChars) kept.add(normalized);
      }
      if (!kept.isEmpty()) {
        out.add(new DrivePdfDocument(meta.getId(), meta.getName() != null ? meta.getName() : "", kept));
        log.info("Prepared {} chunks for PDF id={}, name={}", kept.size(), meta.getId(), meta.getName());
      } else {
        log.warn(
            "GoogleDrivePdfLoader: no chunks met min length {} for id={}, name={} (raw chunks={}, all too short?)",
            minChunkChars, meta.getId(), meta.getName(), chunkTexts.size()
        );
      }
    }
    log.info("GoogleDrivePdfLoader: finished; {} document(s) with at least one chunk", out.size());
    return out;
  }

  private List<String> parseFileIds(String csv) {
    String[] parts = csv.split(",");
    List<String> ids = new ArrayList<>();
    for (String p : parts) {
      String v = p.trim();
      if (!v.isEmpty()) ids.add(v);
    }
    return ids;
  }

  private Drive buildDriveClient(String serviceAccountJson) throws Exception {
    GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
        new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))
    ).createScoped(Collections.singleton(DriveScopes.DRIVE_READONLY));

    HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credentials);
    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    return new Drive.Builder(httpTransport, JSON_FACTORY, requestInitializer)
        .setApplicationName("UniPortal-Demo")
        .build();
  }

  private byte[] downloadFileBytes(Drive drive, String fileId) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      drive.files().get(fileId).executeMediaAndDownloadTo(out);
      return out.toByteArray();
    } finally {
      out.close();
    }
  }
}
