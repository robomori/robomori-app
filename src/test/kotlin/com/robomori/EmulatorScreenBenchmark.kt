package com.robomori

import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Compares the emulator screen's two transports on a running emulator, without the UI: how many frames each delivers
 * and how much CPU it takes. Runs only when asked, as it needs an emulator playing something:
 *
 *   ROBOMORI_BENCH_AVD=Pixel_10 ROBOMORI_BENCH_SERIAL=emulator-5554 ./gradlew test --tests '*EmulatorScreenBenchmark*'
 */
class EmulatorScreenBenchmark {
    @Test fun `compare screen transports`() {
        val avd = System.getenv("ROBOMORI_BENCH_AVD")
        val serial = System.getenv("ROBOMORI_BENCH_SERIAL")
        assumeTrue(avd != null && serial != null, "Set ROBOMORI_BENCH_AVD and ROBOMORI_BENCH_SERIAL to run")
        val directory = File(System.getProperty("user.home"), ".android/avd/$avd.avd")
        val device = Device(avd!!, directory, readIni(File(directory, "config.ini")), 0)
        val seconds = (System.getenv("ROBOMORI_BENCH_SECONDS") ?: "10").toLong()
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val results = (1..2).flatMap { ScreenTransport.entries }.map { transport ->
            val stream = EmulatorScreenStream()
            var frames = 0
            val times = mutableListOf<Long>()
            val cpuBefore = os.processCpuTime
            val started = System.nanoTime()
            val end = started + seconds * 1_000_000_000
            runBlocking {
                stream.frames(device, serial!!, transport).takeWhile { System.nanoTime() < end }.collect { frames++; times += System.nanoTime() }
            }
            val elapsed = (System.nanoTime() - started) / 1e9
            val cpu = (os.processCpuTime - cpuBefore) / 1e9 / elapsed * 100
            // The busiest second shows how smoothly animations play, as the emulator sends nothing while the screen is still.
            val peak = times.indices.maxOfOrNull { i -> times.count { it >= times[i] && it < times[i] + 1_000_000_000 } } ?: 0
            "%-13s %4d frames in %4.1f s = %5.1f fps, busiest second %3d fps, CPU %5.1f%% of one core".format(transport, frames, elapsed, frames / elapsed, peak, cpu)
                .also(::println)
        }
        File(System.getProperty("java.io.tmpdir"), "robomori-screen-benchmark.txt").writeText(results.joinToString("\n"))
    }
}
