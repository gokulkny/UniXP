package com.example.universityportal.rag.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VectorTableNamesTest {

  @Test
  void maps1536ToDefaultTable() {
    assertEquals("rag_chunk", VectorTableNames.chunksTable(1536));
    assertEquals("rag_chunk", VectorTableNames.chunksTable(768));
  }

  @Test
  void maps4096ToDedicatedTable() {
    assertEquals("rag_chunk_4096", VectorTableNames.chunksTable(4096));
  }
}
