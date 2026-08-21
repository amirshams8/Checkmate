package com.checkmate.learning.graph

import android.content.Context
import com.checkmate.core.ExamSyllabus
import com.checkmate.learning.model.Concept
import com.checkmate.learning.repository.LearningDatabase
import java.security.MessageDigest

/**
 * Upgrade Blueprint Phase 1.4 ("Concept-level knowledge graph, not chapter-level
 * weakness"). See [Concept]'s class doc for the honest limitation on granularity —
 * one Concept here = one [ExamSyllabus] topic, not a true sub-topic concept, since
 * nothing in this codebase defines concepts finer than that.
 */
object KnowledgeGraph {

    // BUG CAUGHT AGAINST REAL DATA: Testmate's own report.md carries exam strings
    // like "NEET-2027" (title-embedded year — see the sample
    // ft-01b-full-test-neet-2027--report.md), but ExamSyllabus's top-level keys are
    // bare exam names ("NEET", "JEE", ...). Without normalizing, every real import
    // would key concepts under "neet-2027-..." while seedExamSyllabus("NEET") keys
    // them under "neet-...", and the two would never join — mastery computed from
    // real attempts would sit in a completely different set of rows than the
    // syllabus-seeded prerequisite graph. Stripping a trailing "-YYYY" fixes the
    // common case. An exam string that doesn't end in a year, or an ExamSyllabus
    // key that later grows its own year suffix, would need this revisited.
    private val YEAR_SUFFIX = Regex("""-(19|20)\d{2}$""")
    // Was private — widened to internal so the one real call site for
    // seedExamSyllabus (TestResultNormalizer, wiring it in for the first time) can
    // normalize a report's raw exam string ("NEET-2027") to an ExamSyllabus key
    // ("NEET") before calling it, without duplicating this regex there.
    internal fun normalizeExam(exam: String): String = YEAR_SUFFIX.replace(exam.trim(), "")

    /**
     * Deterministic id from (exam, chapter, topic) — deliberately NOT subject. See
     * [com.checkmate.learning.engine.MasteryEngine]'s class doc: real
     * TestResultNormalizer-imported Questions never carry a subject (report.md has
     * no subject field), so keying on subject would split a seeded syllabus concept
     * and its real-import attempts into two rows that never join. `topic` falls
     * back to `chapter` when null, matching TestReportParser's own dash-to-null
     * normalization (chapter-level granularity when Testmate reports no topic).
     */
    fun conceptId(exam: String, chapter: String, topic: String?): String {
        val key = "${normalizeExam(exam).lowercase()}|${chapter.trim().lowercase()}|" +
            "${(topic ?: chapter).trim().lowercase()}"
        return slugify(key)
    }

    private fun slugify(raw: String): String {
        val slug = raw.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        // Hash suffix guards against two different raw keys slugifying to the same
        // truncated prefix — cheap insurance, not expected to matter at this data scale.
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
        return "${slug.take(60)}-$hash"
    }

    /**
     * Illustrative starter set, per the blueprint's own example ("Rolling Motion
     * depends on torque, energy, angular velocity"). NOT exhaustive and NOT
     * validated against the official NTA syllabus tree — the blueprint calls for
     * versioning the syllabus tree against NTA's published document, which is a
     * real research task, not something to fabricate here. Extend this list as
     * that research happens; [seedExamSyllabus] applies whatever's here.
     */
    private data class PrerequisiteSeed(
        val exam: String, val chapter: String, val topic: String,
        val prerequisiteChapter: String, val prerequisiteTopic: String
    )

    private val SEED_PREREQUISITES = listOf(
        // NEET Physics — Mechanics chapter, per ExamSyllabus.
        PrerequisiteSeed("NEET", "Mechanics", "Rotational Motion", "Mechanics", "Laws of Motion"),
        PrerequisiteSeed("NEET", "Mechanics", "Rotational Motion", "Mechanics", "Work Energy Power"),
        PrerequisiteSeed("NEET", "Mechanics", "Rotational Motion", "Mechanics", "Kinematics"),
        PrerequisiteSeed("NEET", "Mechanics", "Oscillations", "Mechanics", "Work Energy Power"),
        PrerequisiteSeed("NEET", "Mechanics", "Waves", "Mechanics", "Oscillations"),
        // NEET Chemistry — Equilibrium and Electrochemistry lean on earlier Physical Chemistry topics.
        PrerequisiteSeed("NEET", "Physical Chemistry", "Equilibrium", "Physical Chemistry", "Thermodynamics"),
        PrerequisiteSeed("NEET", "Physical Chemistry", "Electrochemistry", "Physical Chemistry", "Redox Reactions"),
        // NEET Biology — Biotechnology leans on Genetics and Evolution.
        PrerequisiteSeed("NEET", "Biology", "Biotechnology", "Biology", "Genetics and Evolution")
    )

    /**
     * Inserts one [Concept] row per (subject, chapter, topic) leaf of
     * ExamSyllabus.data[exam], plus whatever [SEED_PREREQUISITES] edges apply to
     * that exam. `exam` here must be an ExamSyllabus key ("NEET"), not a
     * Testmate-report exam string ("NEET-2027") — [conceptId] normalizes both to
     * the same slug internally, so this still joins correctly with real imported
     * attempts either way.
     *
     * WIRING: called from [com.checkmate.learning.testmate.TestResultNormalizer.normalizeAndPersist]
     * on every import, using `report.exam` (normalized via [normalizeExam]) as the
     * exam key — no separate onboarding flow exists to declare a student's exam
     * ahead of time, but every real import already carries its own exam string, so
     * seeding opportunistically off that is the actual first real signal available,
     * not a guess. Safe to call repeatedly for the same exam: [Concept] upsertAll
     * overwrites identically and [ConceptDependencyDao.insertAll] is
     * `OnConflictStrategy.IGNORE`, so a re-imported report re-seeding the same exam
     * is a no-op past the first time, not a duplicate/corruption risk.
     */
    suspend fun seedExamSyllabus(context: Context, exam: String) {
        val subjects = ExamSyllabus.data[exam] ?: return
        val db = LearningDatabase.getInstance(context)

        val concepts = subjects.flatMap { (subject, chapters) ->
            chapters.flatMap { (chapter, topics) ->
                topics.map { topic ->
                    Concept(
                        id = conceptId(exam, chapter, topic),
                        exam = exam,
                        subject = subject,
                        chapter = chapter,
                        topic = topic
                    )
                }
            }
        }
        db.conceptDao().upsertAll(concepts)

        val edges = SEED_PREREQUISITES
            .filter { it.exam == exam }
            .map {
                ConceptDependency(
                    conceptId = conceptId(exam, it.chapter, it.topic),
                    prerequisiteConceptId = conceptId(exam, it.prerequisiteChapter, it.prerequisiteTopic)
                )
            }
        db.conceptDependencyDao().insertAll(edges)
    }

    /**
     * Diagnoses a weak concept as a prerequisite failure where possible, per the
     * blueprint's "rolling motion failure diagnosed as prerequisite failure"
     * example. Returns the prerequisite [Concept]s whose mastery is below
     * [masteryThreshold] — empty if [conceptId] has no seeded prerequisite edges
     * (e.g. [seedExamSyllabus] was never run for its exam) or none are actually weak.
     */
    suspend fun diagnosePrerequisiteFailure(
        context: Context,
        studentId: String,
        conceptId: String,
        // Kept numerically in sync with MasteryEngine.MASTERY_THRESHOLD by hand, not
        // imported directly — avoids a graph<->engine circular reference for one constant.
        masteryThreshold: Double = 0.75
    ): List<Concept> {
        val db = LearningDatabase.getInstance(context)
        val prerequisiteIds = db.conceptDependencyDao().getPrerequisites(conceptId)
        if (prerequisiteIds.isEmpty()) return emptyList()

        return prerequisiteIds.mapNotNull { prereqId ->
            val mastery = db.masteryDao().getByConcept(studentId, prereqId)
            val isWeak = mastery == null || mastery.mastery < masteryThreshold
            if (isWeak) db.conceptDao().getById(prereqId) else null
        }
    }
}
