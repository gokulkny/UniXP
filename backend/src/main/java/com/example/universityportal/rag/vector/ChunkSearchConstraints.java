package com.example.universityportal.rag.vector;

/**
 * Optional SQL filters for vector / lexical search over {@code rag_chunk*} tables.
 */
public record ChunkSearchConstraints(
    /** Prefer chunks tied to initial submission docs (e.g. §3.5.2 lists), not §4.x evaluation. */
    boolean preferInitialSubmissionDocuments,
    /** Exclude rows whose section starts with major section 4 (evaluation / ficha de produção). */
    boolean excludeMajorSection4,
    /** Optional ILIKE pattern on {@code doc_id} (e.g. {@code %EDITAL%}). */
    String docIdPattern,
    /** Section number prefix from the edital (e.g. {@code 3.5.2} doutorado, {@code 3.5.1} mestrado). */
    String preferredSectionPrefix
) {
  public static ChunkSearchConstraints none() {
    return new ChunkSearchConstraints(false, false, null, null);
  }

  public static ChunkSearchConstraints forIntent(RagQueryIntent intent, String preferredDocIdPattern) {
    String docPat = (preferredDocIdPattern == null || preferredDocIdPattern.isBlank()) ? null : preferredDocIdPattern;
    return switch (intent) {
      case DOCUMENTS_PHD -> new ChunkSearchConstraints(true, true, docPat, "3.5.2");
      case DOCUMENTS_MASTER -> new ChunkSearchConstraints(true, true, docPat, "3.5.1");
      case EVALUATION_OR_PRODUCTION -> new ChunkSearchConstraints(false, false, docPat, null);
      case ELIGIBILITY, GENERAL -> new ChunkSearchConstraints(false, false, docPat, null);
    };
  }

  /** True when vector search should use a narrowed WHERE clause (and may need unconstrained fallback). */
  public boolean hasSqlFilters() {
    return excludeMajorSection4
        || docIdPattern != null
        || (preferInitialSubmissionDocuments && preferredSectionPrefix != null && !preferredSectionPrefix.isBlank());
  }
}
