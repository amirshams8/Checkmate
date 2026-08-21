package com.checkmate.learning.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Upgrade Blueprint Phase 1.4 ("Concept-level knowledge graph, not chapter-level
 * weakness").
 *
 * `id` is a deterministic slug of (exam, chapter, topic) — see
 * [com.checkmate.learning.graph.KnowledgeGraph.conceptId] — so the same topic
 * resolves to the same Concept row whether it arrives via
 * [com.checkmate.learning.graph.KnowledgeGraph.seedExamSyllabus] (which knows
 * `subject`) or via [com.checkmate.learning.engine.MasteryEngine] bootstrapping a
 * row from real imported attempt data (which usually doesn't — report.md carries
 * no subject field at all, only chapter/topic). `subject` is therefore descriptive
 * only, never part of identity, and may be null on rows MasteryEngine created
 * itself before any syllabus seeding ever ran.
 *
 * HONEST LIMITATION: the blueprint's own example ("Rolling Motion depends on
 * torque, energy, angular velocity") describes concepts *finer* than
 * [com.checkmate.core.ExamSyllabus]'s Topic level — nothing in this codebase
 * defines a sub-topic breakdown, and inventing one here would be fabricating
 * syllabus content the blueprint explicitly says to version against the real NTA
 * document instead. One Concept = one ExamSyllabus topic here, or one chapter when
 * Testmate reports no topic (`topic` then equals `chapter`, matching
 * TestReportParser's own dash-to-null normalization).
 */
@Entity(
    tableName = "concepts",
    indices = [Index(value = ["exam"]), Index(value = ["subject"]), Index(value = ["chapter"])]
)
data class Concept(
    @PrimaryKey val id: String,
    val exam: String,
    val subject: String? = null,
    val chapter: String,
    val topic: String
)
