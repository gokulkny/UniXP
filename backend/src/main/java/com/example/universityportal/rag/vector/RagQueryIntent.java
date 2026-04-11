package com.example.universityportal.rag.vector;

/**
 * High-level retrieval intent for section/topic-aware search.
 */
public enum RagQueryIntent {
  /** User asks for required documents for PhD / doutorado (prefer §3.5.2, avoid §4.x production forms). */
  DOCUMENTS_PHD,
  /** Required documents for master's / mestrado (prefer §3.5.1-style lists). */
  DOCUMENTS_MASTER,
  /** Who can apply, eligibility, enrollment rules. */
  ELIGIBILITY,
  /** Ficha de produção, evaluation stages, section 4.x. */
  EVALUATION_OR_PRODUCTION,
  /** No special section filter. */
  GENERAL
}
