package com.checkmate.learning.model

/**
 * Centralizes the [Question.source] values this module writes, so a new provenance
 * tag (like "qbank") isn't scattered as a bare string literal across call sites the
 * way "testmate_report"/"testmate_retention"/"testmate_targeted"/"external_report"
 * currently are (see the audit in chat history for the full existing-literal list —
 * those pre-existing literals are deliberately left as-is, not refactored, as part
 * of this change).
 *
 * BOUNDARY THIS EXISTS TO PROTECT: [QuestionDao.getExternalWrongOrSkipped] is scoped
 * to `source = "external_report"` specifically so a locally-parsed report that was
 * never an actual live Testmate session can still feed the targeted-repair fallback
 * path. A Q-bank row must never carry that source value — if it did,
 * getExternalWrongOrSkipped would start silently mixing Q-bank practice questions
 * into a live repair-test fallback, exactly the leakage the source/pool boundary was
 * designed to prevent (see chat history's full audit of createTargetedTestIfNeeded
 * and the local-vs-remote Testmate selection split). Any code writing a Q-bank
 * [Question] row must use [QBANK], never a raw "qbank" string, so a typo here is a
 * compile error, not a silent no-op filter mismatch at query time.
 */
object QuestionSource {
    const val QBANK = "qbank"
    const val EXTERNAL_REPORT = "external_report"
}
