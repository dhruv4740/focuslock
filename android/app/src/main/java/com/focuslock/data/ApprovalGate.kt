package com.focuslock.data

/**
 * Pure decision logic for whether a pending (deferred) loosening change should
 * apply now, deciding between the two competing governance modes a
 * [FocusPolicyClient.PendingChange] can be under:
 *
 *  - No accountability partner designated at request time: the existing pure
 *    time-delay ratchet governs alone (see [PolicyRatchet.isDue]) — this
 *    object is not involved at all in that case.
 *  - A partner designated at request time: partner approval REPLACES the
 *    timer entirely, rather than racing it. This is a deliberate product
 *    choice (see FocusPolicyClient/MainActivity call sites): a user who has
 *    opted into a stricter, partner-gated mode should not get an "or, just
 *    wait it out" escape hatch, otherwise designating a partner would be
 *    strictly cosmetic. Approved -> due right away, independent of elapsed
 *    time. Rejected -> the pending change is cancelled outright (see
 *    [isCancelled]), not merely re-delayed.
 */
object ApprovalGate {

    enum class Status { PENDING, APPROVED, REJECTED }

    /** Whether an approval-governed pending change should apply now. */
    fun isDue(status: Status): Boolean = status == Status.APPROVED

    /** Whether an approval-governed pending change should be dropped outright. */
    fun isCancelled(status: Status): Boolean = status == Status.REJECTED
}
