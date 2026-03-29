package com.example.universityportal.rag;

import com.example.universityportal.rag.vector.RagRetrievalProperties;
import com.example.universityportal.rag.vector.VectorRagIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * One-line visibility into whether vector DB indexing can run (profile, mode, beans).
 */
@Component
public class RagStartupDiagnostics implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(RagStartupDiagnostics.class);

  private final Environment environment;
  private final RagRetrievalProperties ragProperties;
  private final VectorRagIndexService vectorRagIndexServiceOrNull;
  private final ObjectProvider<DataSource> dataSource;

  public RagStartupDiagnostics(
      Environment environment,
      RagRetrievalProperties ragProperties,
      ObjectProvider<VectorRagIndexService> vectorRagIndexService,
      ObjectProvider<DataSource> dataSource
  ) {
    this.environment = environment;
    this.ragProperties = ragProperties;
    this.vectorRagIndexServiceOrNull = vectorRagIndexService.getIfAvailable();
    this.dataSource = dataSource;
  }

  @Override
  public void run(ApplicationArguments args) {
    String profiles = String.join(",", environment.getActiveProfiles());
    if (profiles.isEmpty()) {
      profiles = "(default: " + String.join(",", environment.getDefaultProfiles()) + ")";
    }
    boolean ds = dataSource.getIfAvailable() != null;

    log.info(
        "RAG startup: activeProfiles={}, portal.rag.retrieval-mode={}, dataSourceBeanPresent={}, vectorRagIndexServiceBeanPresent={}. "
            + "Vector DB indexing runs only when mode is vector/hybrid, JDBC is enabled (e.g. profile 'vector'), and PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON is set.",
        profiles,
        ragProperties.getRetrievalMode(),
        ds,
        vectorRagIndexServiceOrNull != null
    );
  }
}
