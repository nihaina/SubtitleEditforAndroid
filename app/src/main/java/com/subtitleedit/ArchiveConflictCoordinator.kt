package com.subtitleedit

import com.subtitleedit.util.ArchiveManager

/** Collects per-entry conflict decisions without coupling the Activity to recursion state. */
internal class ArchiveConflictCoordinator(
    private val showConflict: (
        ArchiveManager.DestinationConflict,
        (ArchiveManager.ConflictPolicy, Boolean) -> Unit,
        () -> Unit
    ) -> Unit,
) {
    fun collect(
        conflicts: List<ArchiveManager.DestinationConflict>,
        onCancelled: () -> Unit,
        execute: (ArchiveManager.ConflictPolicy, Map<String, ArchiveManager.ConflictPolicy>) -> Unit
    ) {
        collectAt(conflicts, 0, linkedMapOf(), onCancelled, execute)
    }

    private fun collectAt(
        conflicts: List<ArchiveManager.DestinationConflict>,
        index: Int,
        policies: MutableMap<String, ArchiveManager.ConflictPolicy>,
        onCancelled: () -> Unit,
        execute: (ArchiveManager.ConflictPolicy, Map<String, ArchiveManager.ConflictPolicy>) -> Unit
    ) {
        if (index >= conflicts.size) {
            execute(ArchiveManager.ConflictPolicy.FAIL, policies.toMap())
            return
        }
        val conflict = conflicts[index]
        showConflict(conflict, { policy, applyToAll ->
            if (applyToAll) execute(policy, policies.toMap())
            else {
                policies[conflict.entryName] = policy
                collectAt(conflicts, index + 1, policies, onCancelled, execute)
            }
        }, onCancelled)
    }
}
