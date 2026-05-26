package org.shiroumi.server.runtime.strategy

import org.shiroumi.strategy.contract.StrategySnapshotEnvelope

internal class StrategySnapshotCursor(
    private var sourceInstanceId: String? = null,
    private var version: Long = Long.MIN_VALUE
) {
    fun shouldAccept(snapshot: StrategySnapshotEnvelope<*>): Boolean {
        val sourceChanged = sourceInstanceId != snapshot.sourceInstanceId
        if (!sourceChanged && snapshot.version <= version) {
            return false
        }
        sourceInstanceId = snapshot.sourceInstanceId
        version = snapshot.version
        return true
    }
}
