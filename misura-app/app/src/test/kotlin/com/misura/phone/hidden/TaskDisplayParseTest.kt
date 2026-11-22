package com.misura.phone.hidden

import io.kotest.matchers.shouldBe
import org.junit.Test

class TaskDisplayParseTest {
    private val dump = """
        Display #7 (activities from top to bottom):
          * Task{ffd34d4 #14 type=standard A=10232:com.misura.testapp U=0 visible=true}
            * Hist  #0: ActivityRecord{92567361 u0 com.misura.testapp/.ChatActivity t14}
        Display #0 (activities from top to bottom):
          * Task{d567e37 #1 type=home U=0 visible=true}
            * Hist  #1: ActivityRecord{251806510 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t3}
          ResumedActivity: ActivityRecord{92567361 u0 com.misura.testapp/.ChatActivity t14}
    """.trimIndent()

    @Test fun findsDisplayOfPackageTask() { parseTaskDisplay(dump, "com.misura.testapp") shouldBe 7 }
    @Test fun findsMainDisplay() { parseTaskDisplay(dump, "com.google.android.apps.nexuslauncher") shouldBe 0 }
    @Test fun absentPackageIsNull() { parseTaskDisplay(dump, "com.whatsapp") shouldBe null }
    @Test fun prefixOfAnotherPackageDoesNotMatch() { parseTaskDisplay(dump, "com.misura.test") shouldBe null }
}

class RootTaskParseTest {
    // Shape from `cmd activity stack list` on the emulator (long configuration= lines cut).
    private val dump = """
        RootTask id=1 bounds=[0,0][1080,2400] displayId=0 userId=0
         configuration={1.0 310mcc260mnc}
          taskId=2: com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{x/y}

        RootTask id=9 bounds=[0,0][1080,2400] displayId=7 userId=0
         configuration={1.0 310mcc260mnc}
          taskId=14: com.misura.testapp/com.misura.testapp.ChatActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.misura.testapp/com.misura.testapp.ChatActivity}
    """.trimIndent()

    @Test fun findsTheRootTaskOnThatDisplay() { parseRootTask(dump, "com.misura.testapp", 7) shouldBe 9 }
    @Test fun notOnAnotherDisplayOrForAnotherPackage() {
        parseRootTask(dump, "com.misura.testapp", 0) shouldBe null
        parseRootTask(dump, "com.misura.test", 7) shouldBe null
        parseRootTask(dump, "com.google.android.apps.nexuslauncher", 0) shouldBe 1
    }
}
