package com.robomori

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DeviceLocaleTest {
    @TempDir lateinit var root: File

    @Test fun `locale tags are canonicalized with their region`() {
        assertEquals(DeviceLocale("pt-BR"), DeviceLocale.parse("pt-br"))
    }

    @Test fun `invalid locale tags are rejected`() {
        assertNull(DeviceLocale.parse("en;reboot"))
    }

    @Test fun `locale search matches native language names`() {
        assertTrue(DeviceLocale("ar-EG").matches("العربية"))
    }

    @Test fun `read parses supported locales and current primary locale`() {
        val service = service { command -> when {
            "getprop" in command.args -> "36"
            command.args.last() == "describe" -> "warning\nROBOMORI_CURRENT\tpt-BR,en-US\nROBOMORI_LOCALE\ten-US\nROBOMORI_LOCALE\tpt-BR"
            else -> ""
        } }
        val info = service.read("device-1")
        assertEquals(DeviceLocale("pt-BR"), info.current)
        assertEquals(setOf(DeviceLocale("en-US"), DeviceLocale("pt-BR")), info.available.toSet())
    }

    @Test fun `apply is rejected when Android readback differs from requested locale`() {
        val service = service { command -> if ("app_process" in command.args) "ROBOMORI_CURRENT\ten-US" else "" }
        assertFailsWith<IllegalStateException> { service.apply("device-1", DeviceLocale("ar-EG")) }
    }

    @Test fun `preferred locales preserve the complete ordered language list`() {
        val service = service { command -> if ("app_process" in command.args)
            "ROBOMORI_CURRENT\tar-EG,en-US,fr-FR" else "" }
        assertEquals(listOf(DeviceLocale("ar-EG"), DeviceLocale("en-US"), DeviceLocale("fr-FR")),
            service.readPreferredLocales("device-1"))
    }

    @Test fun `restoring preferred locales sends and verifies the complete ordered list`() {
        val commands = mutableListOf<Command>()
        val service = service { command ->
            commands += command
            if ("app_process" in command.args) "ROBOMORI_CURRENT\tar-EG,en-US" else ""
        }
        val locales = listOf(DeviceLocale("ar-EG"), DeviceLocale("en-US"))
        assertEquals(locales, service.applyPreferredLocales("device-2", locales))
        assertEquals(listOf("set", "ar-EG,en-US"), commands.first { "app_process" in it.args }.args.takeLast(2))
    }

    @Test fun `device permission denial is reported without false success`() {
        val service = service { command -> if ("app_process" in command.args) "ROBOMORI_ERROR\tDENIED" else "" }
        assertFailsWith<IllegalStateException> { service.apply("device-1", DeviceLocale("ar-EG")) }
    }

    @Test fun `the helper is pushed once per build, clearing out earlier copies`() {
        val commands = mutableListOf<List<String>>()
        var pushed: String? = null
        val service = service { command ->
            commands += command.args
            val script = command.args.last()
            when {
                "push" in command.args -> { pushed = command.args.last(); "" }
                // The device has the helper once it was pushed, at the size it should be.
                "ROBOMORI_PRESENT" in script -> if (pushed != null && pushed!! in script && "= \"3\"" in script) "ROBOMORI_PRESENT" else "ROBOMORI_MISSING"
                "app_process" in command.args -> "ROBOMORI_CURRENT\tar-EG"
                else -> ""
            }
        }

        repeat(2) { service.apply("device-1", DeviceLocale("ar-EG")) }

        val pushes = commands.filter { "push" in it }
        assertEquals(1, pushes.size)
        assertTrue(pushes.single().last().matches(Regex("/data/local/tmp/robomori-locale-[0-9a-f]{16}\\.jar")))
        assertEquals(1, commands.count { it.last() == "rm -f /data/local/tmp/robomori-locale-*.jar" })
        assertTrue(commands.none { "rm" in it && it.last() == pushed })
    }

    @Test fun `Android versions before LocaleList support do not invoke helper`() {
        val commands = mutableListOf<Command>()
        val service = service { command -> commands += command; "23" }
        assertFailsWith<IllegalStateException> { service.read("device-1") }
        assertEquals(1, commands.size)
    }

    private fun service(run: (Command) -> String): DeviceLocaleService {
        val jar = File(root, "helper.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return DeviceLocaleService(AppSettings("/sdk", "/avds"), CommandExecutor { command, _, _ -> run(command) }, { jar })
    }
}

class DeviceLocaleControllerTest {
    private val english = DeviceLocale("en-US")
    private val arabic = DeviceLocale("ar-EG")

    @Test fun `cached choices remain ready while the actual locale refreshes`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val info = DeviceLocaleInfo(english, listOf(english, arabic))
        val service = object : DeviceLocaleAccess {
            override fun read(serial: String): DeviceLocaleInfo {
                started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                return info.copy(current = arabic)
            }
            override fun apply(serial: String, locale: DeviceLocale) = locale
        }
        val controller = DeviceLocaleController(service, "device", this, { true }, {}, initialInfo = info)
        try {
            controller.refresh()
            withTimeout(5000) { started.await() }
            assertEquals(info, assertIs<DeviceLocaleState.Ready>(controller.state).info)
        } finally { release.countDown() }
        childrenDone()
        assertEquals(arabic, assertIs<DeviceLocaleState.Ready>(controller.state).info.current)
    }

    @Test fun `failed background refresh retains cached choices`() = runBlocking {
        val info = DeviceLocaleInfo(english, listOf(english, arabic))
        val service = object : DeviceLocaleAccess {
            override fun read(serial: String): DeviceLocaleInfo = error("Disconnected")
            override fun apply(serial: String, locale: DeviceLocale) = locale
        }
        val controller = DeviceLocaleController(service, "device", this, { true }, {}, initialInfo = info)

        controller.refresh()
        childrenDone()

        assertEquals(info, assertIs<DeviceLocaleState.Failed>(controller.state).info)
    }

    @Test fun `unlicensed apply opens purchase prompt without changing device`() = runBlocking {
        var writes = 0
        var prompts = 0
        val service = access { writes++; it }
        val controller = DeviceLocaleController(service, "device", this, { false }, { prompts++ })
        controller.refresh()
        childrenDone()
        controller.apply(arabic)
        assertEquals(1, prompts)
        assertEquals(0, writes)
    }

    @Test fun `failed apply is replaced by fresh actual state on retry`() = runBlocking {
        val controller = DeviceLocaleController(access { error("Disconnected") }, "device", this, { true }, {})
        controller.refresh()
        childrenDone()
        controller.apply(arabic)
        childrenDone()
        assertIs<DeviceLocaleState.Failed>(controller.state)
        controller.refresh()
        childrenDone()
        assertEquals(DeviceLocaleState.Ready(DeviceLocaleInfo(english, listOf(english, arabic))), controller.state)
    }

    @Test fun `success reflects confirmed locale`() = runBlocking {
        val controller = DeviceLocaleController(access { it }, "device", this, { true }, {})
        controller.refresh()
        childrenDone()
        controller.apply(arabic)
        childrenDone()
        val ready = assertIs<DeviceLocaleState.Ready>(controller.state)
        assertEquals(arabic, ready.info.current)
        assertTrue(ready.applied)
    }

    @Test fun `a new locale stays selected while an earlier write finishes`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val writes = mutableListOf<DeviceLocale>()
        val controller = DeviceLocaleController(access { locale ->
            writes += locale
            if (locale == arabic) {
                started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
            locale
        }, "device", this, { true }, {})
        controller.refresh()
        childrenDone()
        try {
            controller.apply(arabic)
            withTimeout(5000) { started.await() }

            controller.apply(english)

            assertEquals(english, assertIs<DeviceLocaleState.Applying>(controller.state).selection)
        } finally {
            release.countDown()
        }
        childrenDone()
        assertEquals(listOf(arabic, english), writes)
        assertEquals(english, assertIs<DeviceLocaleState.Ready>(controller.state).info.current)
    }

    @Test fun `an older refresh cannot replace the latest device locale`() = runBlocking {
        val firstRead = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val service = object : DeviceLocaleAccess {
            override fun read(serial: String): DeviceLocaleInfo {
                if (reads.incrementAndGet() == 1) {
                    firstRead.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    return DeviceLocaleInfo(english, listOf(english, arabic))
                }
                return DeviceLocaleInfo(arabic, listOf(english, arabic))
            }
            override fun apply(serial: String, locale: DeviceLocale) = locale
        }
        val controller = DeviceLocaleController(service, "device", this, { true }, {})
        try {
            controller.refresh()
            withTimeout(5000) { firstRead.await() }
            controller.refresh()
            withTimeout(5000) { while (controller.state is DeviceLocaleState.Loading) delay(1) }
        } finally {
            release.countDown()
        }
        childrenDone()
        assertEquals(arabic, assertIs<DeviceLocaleState.Ready>(controller.state).info.current)
    }

    private fun access(apply: (DeviceLocale) -> DeviceLocale) = object : DeviceLocaleAccess {
        override fun read(serial: String) = DeviceLocaleInfo(english, listOf(english, arabic))
        override fun apply(serial: String, locale: DeviceLocale) = apply(locale)
    }

    private suspend fun CoroutineScope.childrenDone() { coroutineContext.job.children.toList().joinAll() }
}
