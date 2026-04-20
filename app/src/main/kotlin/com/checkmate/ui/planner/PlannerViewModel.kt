// Note: This file references com.checkmate.psyche package — ensure modules:psyche is on the classpath.
// The PlannerViewModel import for PsycheEngine is via the psyche module dependency chain.
// (File already written above — no duplicate needed)

// ─────────────────────────────────────────────────────────────────────────────
// CROSS-MODULE IMPORT NOTE
// PlannerState is defined in app module for the UI but SubjectConfig is in
// modules:planner. AdaptivePlanner accepts PlannerState directly to avoid
// circular deps — this is intentional.
// ─────────────────────────────────────────────────────────────────────────────
