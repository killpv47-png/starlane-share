package com.starlane.core

import com.starlane.core.crypto.CipherSuite
import com.starlane.core.engine.FileListSource
import com.starlane.core.engine.TransferState
import com.starlane.core.engine.TransferTuning
import com.starlane.core.engine.TurboNote
import com.starlane.core.engine.TurboStatus
import com.starlane.core.link.SocketTuning
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Measures real end-to-end throughput (encrypt + TCP loopback + decrypt + SHA-256 + disk write)
 * for several engine configurations. Results are MEASURED on the machine running the tests
 * (CI runner), not a phone; they are used to compare configurations, not to claim phone speeds.
 * A markdown table is written to build/reports/starlane/benchmark.md.
 */
class BenchmarkTest {
    private data class Cfg(val name: String, val lanes: Int, val chunk: Int, val seg: Int, val sock: SocketTuning)

    @Test
    fun throughputByConfiguration() {
        val sizeMb = (System.getProperty("starlane.largeFileMb") ?: "64").toLong().coerceIn(16, 2048)
        val dir = TestFiles.tempDir("starlane-bench")
        val a = Peer("bench-a", File(dir, "a"))
        val b = Peer("bench-b", File(dir, "b"))
        val report = StringBuilder()
        try {
            val file = TestFiles.random(File(dir, "src/large.bin"), sizeMb * 1024 * 1024)
            val expected = TestFiles.sha(file)
            val conn = a.connectTo(b)
            val cipherBench = CipherSuite.benchmark(80)
            report.append("# Starlane engine benchmark\n\n")
            report.append("Machine: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ")
            report.append("${Runtime.getRuntime().availableProcessors()} cores, Java ${System.getProperty("java.version")}\n\n")
            report.append("Cipher throughput (single thread, MB/s): ")
            report.append(cipherBench.entries.joinToString { "${it.key}=${it.value}" }).append("\n\n")
            report.append("Session cipher (chosen by benchmark of both peers): ${conn.cipherSuite}\n\n")
            report.append("File: $sizeMb MiB, real TCP loopback, SHA-256 verified, written to disk.\n\n")
            report.append("| Configuration | Lanes | Chunk | Segment | Time (s) | Throughput (MB/s) | Verified |\n")
            report.append("|---|---|---|---|---|---|---|\n")
            val configs = listOf(
                Cfg("Low-end profile", 1, 32 * 1024, 4 shl 20, SocketTuning.LOW_RAM),
                Cfg("Normal", 2, 64 * 1024, 4 shl 20, SocketTuning.DEFAULT),
                Cfg("1 lane / 256K chunk", 1, 256 * 1024, 8 shl 20, SocketTuning.TURBO),
                Cfg("Turbo 2 lanes", 2, 256 * 1024, 8 shl 20, SocketTuning.TURBO),
                Cfg("Turbo 3 lanes", 3, 256 * 1024, 8 shl 20, SocketTuning.TURBO),
                Cfg("Turbo 4 lanes", 4, 256 * 1024, 8 shl 20, SocketTuning.TURBO),
            )
            for ((i, c) in configs.withIndex()) {
                val tuning = TransferTuning(c.lanes, c.chunk, c.seg, c.sock, TurboStatus.ACTIVE, TurboNote.NONE)
                val t0 = System.nanoTime()
                val t = awaitTerminal(conn.send(FileListSource(listOf(file to "run$i.bin")), true, tuning), 600)
                val secs = (System.nanoTime() - t0) / 1e9
                assertEquals(t.reason, TransferState.COMPLETED, t.state)
                Thread.sleep(300) // receiver commit
                val ok = TestFiles.sha(File(b.inbox, "run$i.bin")) == expected
                File(b.inbox, "run$i.bin").delete()
                val mbps = file.length() / secs / 1e6
                report.append("| ${c.name} | ${c.lanes} | ${c.chunk / 1024} KiB | ${c.seg shr 20} MiB | ")
                report.append(String.format("%.2f | %.1f | %s |\n", secs, mbps, if (ok) "yes" else "NO"))
                println(String.format("BENCH %-22s %.1f MB/s", c.name, mbps))
            }
        } finally {
            a.close(); b.close()
            dir.deleteRecursively()
        }
        val out = File(System.getProperty("starlane.reportDir") ?: "build/reports/starlane")
        out.mkdirs()
        File(out, "benchmark.md").writeText(report.toString())
        println(report)
    }
}
