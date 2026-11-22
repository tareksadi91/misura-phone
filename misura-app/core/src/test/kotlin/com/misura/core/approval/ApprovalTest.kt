package com.misura.core.approval

import com.misura.core.task.Payload
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ApprovalTest {
    private val p1 = Payload(mapOf("channel" to "whatsapp", "recipient" to "friendb", "content" to "Running 10 late"))
    private fun approval(p: Payload, at: Long = 0, consumed: String? = null) =
        Approval(id = "a1", taskId = "t1", payloadDigest = p.digest(), grantedAt = at, expiresAt = at + APPROVAL_TTL_MS, source = ApprovalSource.BUTTON, consumedByAttempt = consumed)

    @Test fun `covers identical payload`() { approval(p1).covers("t1", p1, now = 1_000) shouldBe true }
    @Test fun `rejects edited content`() { approval(p1).covers("t1", Payload(p1.fields + ("content" to "edited")), 1) shouldBe false }
    @Test fun `rejects changed recipient`() { approval(p1).covers("t1", Payload(p1.fields + ("recipient" to "colleaguea")), 1) shouldBe false }
    @Test fun `rejects changed channel`() { approval(p1).covers("t1", Payload(p1.fields + ("channel" to "email")), 1) shouldBe false }
    @Test fun `rejects after ttl`() { approval(p1, at = 0).covers("t1", p1, now = APPROVAL_TTL_MS + 1) shouldBe false }
    @Test fun `rejects other task`() { approval(p1).covers("t2", p1, 1) shouldBe false }
    @Test fun `rejects consumed approval`() { approval(p1, consumed = "att1").covers("t1", p1, 1) shouldBe false }
}
