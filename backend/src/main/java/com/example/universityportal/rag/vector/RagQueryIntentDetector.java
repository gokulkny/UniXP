package com.example.universityportal.rag.vector;

import java.util.Locale;
import java.util.Set;

/**
 * Maps user queries to {@link RagQueryIntent} for section-aware retrieval.
 */
public final class RagQueryIntentDetector {
  private static final Set<String> DOC_WORDS = Set.of(
      "document", "documents", "documento", "documentos", "paperwork", "paper", "papers"
  );
  private static final Set<String> PHD_WORDS = Set.of(
      "phd", "doctoral", "doctorate", "doutorado", "doutor"
  );
  private static final Set<String> MASTER_WORDS = Set.of(
      "master", "masters", "mestrado", "msc"
  );
  private static final Set<String> ELIGIBILITY_WORDS = Set.of(
      "eligibility", "eligible", "qualify", "who can", "inscrever", "inscrição", "inscricao",
      "candidato", "candidatura", "admission"
  );
  private static final Set<String> EVAL_WORDS = Set.of(
      "ficha", "produção", "producao", "produccion", "avaliação", "avaliacao", "evaluation",
      "stage 4", "etapa 4", "4.", "curricular", "lattes"
  );

  private RagQueryIntentDetector() {}

  public static RagQueryIntent detect(String query) {
    if (query == null || query.isBlank()) return RagQueryIntent.GENERAL;
    String q = query.toLowerCase(Locale.ROOT);

    boolean eval = containsAny(q, EVAL_WORDS) && !isStrongDocumentAsk(q);
    if (eval) {
      return RagQueryIntent.EVALUATION_OR_PRODUCTION;
    }

    boolean docAsk = containsAny(q, DOC_WORDS) || q.contains("required") && q.contains("document");
    boolean phd = containsAny(q, PHD_WORDS);
    boolean master = containsAny(q, MASTER_WORDS);

    if (docAsk && phd) return RagQueryIntent.DOCUMENTS_PHD;
    if (docAsk && master) return RagQueryIntent.DOCUMENTS_MASTER;
    if (docAsk && (q.contains("phd") || q.contains("doctor") || q.contains("doutorado"))) {
      return RagQueryIntent.DOCUMENTS_PHD;
    }

    if (containsAny(q, ELIGIBILITY_WORDS) && !docAsk) {
      return RagQueryIntent.ELIGIBILITY;
    }

    return RagQueryIntent.GENERAL;
  }

  private static boolean isStrongDocumentAsk(String q) {
    return containsAny(q, DOC_WORDS) || (q.contains("document") && (q.contains("required") || q.contains("necess")));
  }

  private static boolean containsAny(String q, Set<String> words) {
    for (String w : words) {
      if (q.contains(w)) return true;
    }
    return false;
  }
}
