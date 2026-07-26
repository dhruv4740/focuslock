package com.focuslock.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalGateTest {

    @Test
    fun `pending is neither due nor cancelled`() {
        assertEquals(false, ApprovalGate.isDue(ApprovalGate.Status.PENDING))
        assertEquals(false, ApprovalGate.isCancelled(ApprovalGate.Status.PENDING))
    }

    @Test
    fun `approved is due, not cancelled`() {
        assertEquals(true, ApprovalGate.isDue(ApprovalGate.Status.APPROVED))
        assertEquals(false, ApprovalGate.isCancelled(ApprovalGate.Status.APPROVED))
    }

    @Test
    fun `rejected is cancelled, not due`() {
        assertEquals(false, ApprovalGate.isDue(ApprovalGate.Status.REJECTED))
        assertEquals(true, ApprovalGate.isCancelled(ApprovalGate.Status.REJECTED))
    }
}
