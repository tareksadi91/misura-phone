package com.misura.phone.hands

import com.misura.core.agent.Draft
import com.misura.core.agent.Drafter
import com.misura.core.approval.Approval
import com.misura.core.exec.Attempt
import com.misura.core.exec.Controller
import com.misura.core.exec.TaskStore
import com.misura.core.task.Task
import com.misura.phone.notify.Chat
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatDrafterTest {
    private val real = Chat("real-chat", "Recipient", "com.whatsapp", 100)
    private val forged = Chat("forged-chat", "Recipient", "example.spoof", Long.MAX_VALUE)

    @Test fun newerSpoofedAppLabelIncludesActualPackageInSavedApproval() = runTest {
        val tasks = DraftTasks()
        val labels = mapOf("com.whatsapp" to "WhatsApp", "example.spoof" to "WhatsApp")
        val drafter = ChatDrafter({ listOf(real, forged) }, { labels.getValue(it) }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { labels.keys.toList() })

        val ready = drafter.draft("Recipient", "private reply") as Draft.Ready

        ready.pkg shouldBe "example.spoof"
        ready.app shouldBe "WhatsApp (example.spoof)"
        val payload = tasks.load(ready.taskId)!!.payload!!
        payload["app"] shouldBe "WhatsApp (example.spoof)"
        payload["pkg"] shouldBe "example.spoof"
        payload["recipient"] shouldBe "forged-chat"
    }

    @Test fun invisibleControlsCannotHideAnAppLabelCollision() = runTest {
        val tasks = DraftTasks()
        val labels = mapOf("com.whatsapp" to "WhatsApp", "example.spoof" to "\u202eWhats\u200bApp\u202c\n")
        val drafter = ChatDrafter({ listOf(forged) }, { labels.getValue(it) }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { labels.keys.toList() })

        val ready = drafter.draft("Recipient", "reply") as Draft.Ready

        ready.app shouldBe "WhatsApp (example.spoof)"
        tasks.load(ready.taskId)!!.payload!!["app"] shouldBe "WhatsApp (example.spoof)"
    }

    @Test fun caseAndCompatibilityCharactersCannotHideAnAppLabelCollision() = runTest {
        for (forgedLabel in listOf("wHaTsApP", "ＷｈａｔｓＡｐｐ")) {
            val tasks = DraftTasks()
            val labels = mapOf("com.whatsapp" to "WhatsApp", "example.spoof" to forgedLabel)
            val drafter = ChatDrafter({ listOf(real, forged) }, { labels.getValue(it) }, tasks,
                Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { labels.keys.toList() })

            val ready = drafter.draft("Recipient", "reply") as Draft.Ready

            ready.app shouldBe "$forgedLabel (example.spoof)"
        }
    }

    @Test fun distinctAppLabelsStillPickTheNewestWithoutExtraIdentityText() = runTest {
        val tasks = DraftTasks()
        val labels = mapOf("com.whatsapp" to "WhatsApp", "example.spoof" to "Other Chat")
        val drafter = ChatDrafter({ listOf(real, forged) }, { labels.getValue(it) }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { labels.keys.toList() })

        val ready = drafter.draft("Recipient", "reply") as Draft.Ready

        ready.app shouldBe "Other Chat"
        tasks.load(ready.taskId)!!.payload!!["pkg"] shouldBe "example.spoof"
    }

    @Test fun unavailableAppLabelUsesActualPackage() = runTest {
        val tasks = DraftTasks()
        val drafter = ChatDrafter({ listOf(forged) }, { "" }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { listOf(forged.pkg) })

        val ready = drafter.draft("Recipient", "reply") as Draft.Ready

        ready.app shouldBe "example.spoof"
        tasks.load(ready.taskId)!!.payload!!["app"] shouldBe "example.spoof"
    }

    @Test fun appLabelCannotImpersonateAnotherPackagesQualifiedIdentity() = runTest {
        val tasks = DraftTasks()
        val labels = mapOf("com.whatsapp" to "WhatsApp", "example.spoof" to "WhatsApp (com.whatsapp)")
        val drafter = ChatDrafter({ listOf(real, forged) }, { labels.getValue(it) }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { labels.keys.toList() })

        val ready = drafter.draft("Recipient", "reply") as Draft.Ready

        ready.app shouldBe "WhatsApp (com.whatsapp) (example.spoof)"
    }

    @Test fun appLabelCannotImpersonateAnotherPackagesBareIdentity() = runTest {
        for (realLabel in listOf("WhatsApp", "")) {
            val tasks = DraftTasks()
            val labels = mapOf("com.whatsapp" to realLabel, "example.spoof" to "com.whatsapp")
            val drafter = ChatDrafter({ listOf(real, forged) }, { labels.getValue(it) }, tasks,
                Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { labels.keys.toList() })

            val ready = drafter.draft("Recipient", "reply") as Draft.Ready

            ready.app shouldBe "com.whatsapp (example.spoof)"
        }
    }

    @Test fun installedAppWithoutNotificationsStillDisambiguatesANoLauncherSource() = runTest {
        val tasks = DraftTasks()
        val drafter = ChatDrafter({ listOf(forged) }, { "WhatsApp" }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { listOf(forged.pkg) },
            installedApps = { listOf("WhatsApp" to "com.whatsapp") })

        val ready = drafter.draft("Recipient", "reply") as Draft.Ready

        ready.app shouldBe "WhatsApp (example.spoof)"
    }

    @Test fun explicitPackageNeverSelectsANewerChatInAnotherApp() = runTest {
        val tasks = DraftTasks()
        val drafter = ChatDrafter({ listOf(real, forged) }, { "WhatsApp" }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { listOf(real.pkg, forged.pkg) })

        val ready = drafter.draft("Recipient", "reply", "com.whatsapp") as Draft.Ready

        ready.pkg shouldBe "com.whatsapp"
        tasks.load(ready.taskId)!!.payload!!["recipient"] shouldBe "real-chat"
        drafter.draft("Recipient", "reply", "COM.WHATSAPP") shouldBe Draft.NoChat("Recipient", emptyList())
    }

    @Test fun explicitMissingPackageDoesNotFallBackToAnAvailableChat() = runTest {
        val tasks = DraftTasks()
        val drafter = ChatDrafter({ listOf(forged) }, { "Other Chat" }, tasks,
            Controller(tasks, emptyList(), { 1000 }, this), { 1000 }, { listOf(forged.pkg) })

        drafter.draft("Recipient", "reply", "com.whatsapp") shouldBe Draft.NoChat("Recipient", emptyList())
        tasks.unfinished() shouldBe emptyList()
    }

    @Test fun legacyDrafterRejectsUnsupportedExplicitPackageButPreservesUnboundCalls() = runTest {
        val ready = Draft.Ready("task", "Recipient", "Other Chat", "example.chat")
        val drafter = object : Drafter {
            override suspend fun draft(to: String, text: String) = ready
            override suspend fun discard(taskId: String) = Unit
        }

        drafter.draft("Recipient", "reply", null) shouldBe ready
        drafter.draft("Recipient", "reply", "com.whatsapp") shouldBe Draft.NoChat("Recipient", emptyList())
    }

    private class DraftTasks : TaskStore {
        private val tasks = mutableMapOf<String, Task>()
        override suspend fun load(id: String) = tasks[id]
        override suspend fun save(t: Task) { tasks[t.id] = t }
        override suspend fun unfinished() = tasks.values.toList()
        override suspend fun unreportedProblems() = emptyList<Task>()
        override suspend fun saveApproval(a: Approval) = error("draft must not approve")
        override suspend fun approvalFor(taskId: String): Approval? = error("draft must not approve")
        override suspend fun claimAttempt(taskId: String, attempt: Attempt, now: Long): Attempt? = error("draft must not send")
        override suspend fun updateAttempt(a: Attempt) = error("draft must not send")
        override suspend fun openAttempts() = emptyList<Attempt>()
        override suspend fun attemptsFor(taskId: String) = emptyList<Attempt>()
    }
}
