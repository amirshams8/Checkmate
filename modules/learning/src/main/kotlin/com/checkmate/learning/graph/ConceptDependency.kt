package com.checkmate.learning.graph

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Upgrade Blueprint Phase 1.4. One row per prerequisite edge: [prerequisiteConceptId]
 * must be understood before [conceptId] can be. See [KnowledgeGraph] for how these
 * are seeded and [KnowledgeGraph.diagnosePrerequisiteFailure] for how they're used —
 * e.g. a "Rotational Motion" failure traced back to a weak "Laws of Motion"
 * prerequisite instead of just flagged as its own isolated weak topic, per the
 * blueprint's own example.
 *
 * [ConceptDependencyDao] lives in this same file rather than a separate
 * repository/ file: the blueprint's repository/ listing for Phase 1.4-1.7 names
 * only MasteryDao.kt and ErrorDao.kt, and graph/ already has its own folder per the
 * blueprint's module tree — same "don't invent an unlisted file" reasoning
 * QuestionAttemptDao already established (living inside QuestionDao.kt in Phase
 * 1.1-1.3).
 */
@Entity(
    tableName = "concept_dependencies",
    primaryKeys = ["conceptId", "prerequisiteConceptId"],
    indices = [Index(value = ["conceptId"]), Index(value = ["prerequisiteConceptId"])]
)
data class ConceptDependency(
    val conceptId: String,
    val prerequisiteConceptId: String
)

@Dao
interface ConceptDependencyDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(edges: List<ConceptDependency>)

    @Query("SELECT prerequisiteConceptId FROM concept_dependencies WHERE conceptId = :conceptId")
    suspend fun getPrerequisites(conceptId: String): List<String>

    @Query("SELECT conceptId FROM concept_dependencies WHERE prerequisiteConceptId = :prerequisiteConceptId")
    suspend fun getDependents(prerequisiteConceptId: String): List<String>
}
