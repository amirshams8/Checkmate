    override fun resumeTask(taskId: String, resumedAt: Long) {
        val task = tasks.getValue(taskId)
        val pausedAt = task.pausedAt
        val elapsed = if (pausedAt != null && pausedAt > 0L) resumedAt - pausedAt else 0L
        tasks[taskId] = task.copy(
            state = TaskState.ACTIVE,
            pausedAt = null,
            totalPausedMs = task.totalPausedMs + elapsed
        )
    }
