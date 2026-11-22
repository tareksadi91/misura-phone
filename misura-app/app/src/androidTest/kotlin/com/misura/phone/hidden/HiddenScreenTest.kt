package com.misura.phone.hidden

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldNotBeExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Needs Shizuku running on the device with Misura granted. */
@RunWith(AndroidJUnit4::class)
class HiddenScreenTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private lateinit var screen: ShizukuHiddenScreen
    private val opened = mutableListOf<DisplaySession>()

    private fun shell(cmd: String): String =
        inst.uiAutomation.executeShellCommand(cmd).use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText() }

    private suspend fun open() = screen.open(720, 1280, 320).also { opened += it }

    @Before fun setUp() {
        // The Shizuku binder reaches a fresh process asynchronously.
        repeat(50) { if (ShizukuBridge.ready()) return@repeat; Thread.sleep(100) }
        check(ShizukuBridge.ready()) { "Shizuku not running or Misura not granted" }
        shell("am force-stop $TEST_PKG")
        screen = ShizukuHiddenScreen(ctx)
    }

    @After fun tearDown(): Unit = runBlocking { opened.forEach { runCatching { screen.close(it) } } }

    @Test fun openReturnsNonZeroDisplay(): Unit = runBlocking {
        val s = open()
        s.displayId shouldNotBeExactly 0
    }

    @Test fun launchTestappLandsOnHiddenDisplay(): Unit = runBlocking {
        val s = open()
        screen.launch(s, TEST_PKG) shouldBe true
        screen.taskDisplayOf(TEST_PKG) shouldBe s.displayId
    }

    @Test fun displayZeroRejected(): Unit = runBlocking {
        val main = DisplaySession(opened.firstOrNull()?.sessionId ?: "fake", 0, 0L)
        shouldThrow<IllegalArgumentException> { screen.launch(main, TEST_PKG) }
        Unit
    }

    @Test fun staleSessionRejectedAfterClose(): Unit = runBlocking {
        val s = open()
        screen.close(s)
        shouldThrow<IllegalStateException> { screen.launch(s, TEST_PKG) }
        Unit
    }

    @Test fun closeDestroysTestappTaskInsteadOfMovingItToDisplayZero(): Unit = runBlocking {
        val s = open()
        screen.launch(s, TEST_PKG) shouldBe true
        screen.close(s)
        var where: Int? = -1
        repeat(30) { where = screen.taskDisplayOf(TEST_PKG); if (where == null) return@repeat; delay(100) }
        where shouldBe null
    }

    @Test fun serviceDeathInvalidatesSession(): Unit = runBlocking {
        val s = open()
        val pid = shell("pidof $SERVICE_PROC").trim()
        check(pid.isNotEmpty()) { "service process not found" }
        shell("kill -9 $pid")
        delay(1000)
        shouldThrow<IllegalStateException> { screen.launch(s, TEST_PKG) }
        Unit
    }

    @Test fun closingAnotherInstancesSessionKeepsThisOneAlive(): Unit = runBlocking {
        val other = ShizukuHiddenScreen(ctx)
        val mine = open()
        val theirs = other.open(720, 1280, 320)
        other.close(theirs)
        screen.launch(mine, TEST_PKG) shouldBe true
    }

    @Test fun grabReturnsACropOfThePicture(): Unit = runBlocking {
        val s = open()
        screen.launch(s, TEST_PKG) shouldBe true
        var full: ByteArray? = null
        repeat(20) { full = screen.grab(s, intArrayOf(0, 0, 720, 1280)); if (full != null) return@repeat; delay(250) }
        val jpg = full!!
        val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
        bmp.width shouldBe 720
        bmp.height shouldBe 1280
        val colours = (1..9).flatMap { x -> (1..9).map { y -> bmp.getPixel(x * 70, y * 125) } }.toSet()
        (colours.size > 1) shouldBe true
        val crop = screen.grab(s, intArrayOf(0, 0, 200, 200))!!.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        crop.width shouldBe 200
        crop.height shouldBe 200
        screen.grab(s, intArrayOf(10, 10, 10, 10)) shouldBe null
    }

    companion object {
        const val TEST_PKG = "com.misura.testapp"
        const val SERVICE_PROC = "com.misura.phone:shell"
    }
}
