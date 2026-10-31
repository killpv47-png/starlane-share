package com.starlane.core

import com.starlane.core.engine.ConflictPolicy
import com.starlane.core.engine.FileListSource
import com.starlane.core.engine.FileStatus
import com.starlane.core.engine.Reasons
import com.starlane.core.engine.TransferState
import com.starlane.core.proto.FileKind
import com.starlane.core.proto.GroupInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests of the real engine over real TCP loopback sockets: handshake, encryption,
 * multi-lane transfer, integrity checks, resume, cancellation, rejection and duplicate handling.
 */
class TransferEngineTest {
    private lateinit var dir: File
    private lateinit var a: Peer
    private lateinit var b: Peer

    @Before
    fun setUp() {
        dir = TestFiles.tempDir("starlane-e2e")
        a = Peer("alice", File(dir, "a"))
        b = Peer("bob", File(dir, "b"))
    }

    @After
    fun tearDown() {
        a.close(); b.close()
        dir.deleteRecursively()
    }

    @Test
    fun handshakeAuthenticatesBothPeers() {
        val conn = a.connectTo(b)
        val other = b.connected.poll(10, TimeUnit.SECONDS)
        assertNotNull(other)
        assertEquals(b.identity.deviceId, conn.peer.deviceId)
        assertEquals(a.identity.deviceId, other!!.peer.deviceId)
        assertEquals("bob", conn.peer.profile.username)
        assertEquals("alice", other.peer.profile.username)
    }

    @Test(expected = Exception::class)
    fun connectingToWrongIdentityFails() {
        val link = com.starlane.core.link.SocketLink.dial(
            "127.0.0.1", b.server.localPort, com.starlane.core.link.LinkKind.LOOPBACK,
            com.starlane.core.link.SocketTuning.DEFAULT,
        )
        a.node.connect(link, expectedDeviceId = "0000000000000000")
    }

    @Test
    fun multiFileTransferIsBitExact() {
        val src = File(dir, "src")
        val files = listOf(
            TestFiles.random(File(src, "empty.bin"), 0),
            TestFiles.random(File(src, "tiny.txt"), 17),
            TestFiles.random(File(src, "photo.jpg"), 3_500_000),
            TestFiles.random(File(src, "album/clip.mp4"), 21_000_000),
        )
        val source = FileListSource(files.map { it to it.relativeTo(src).path })
        val conn = a.connectTo(b)
        val t = awaitTerminal(conn.send(source, turbo = true))
        assertEquals(t.reason, TransferState.COMPLETED, t.state)
        assertEquals(files.size, t.count(FileStatus.DONE))
        for (f in files) {
            val got = File(b.inbox, f.relativeTo(src).path)
            assertTrue("missing ${got.path}", got.isFile)
            assertEquals(TestFiles.sha(f), TestFiles.sha(got))
        }
        assertEquals(t.totalBytes, t.doneBytes)
    }

    @Test
    fun turboUsesMultipleLanes() {
        val big = TestFiles.random(File(dir, "src/big.bin"), 48_000_000)
        val conn = a.connectTo(b)
        val t = conn.send(FileListSource(listOf(big to "big.bin")), turbo = true)
        var maxLanes = 0
        while (!t.state.terminal) { maxLanes = maxOf(maxLanes, conn.laneCount); Thread.sleep(5) }
        assertEquals(TransferState.COMPLETED, t.state)
        assertTrue("expected >1 lane, saw $maxLanes", maxLanes > 1)
        assertEquals(TestFiles.sha(big), TestFiles.sha(File(b.inbox, "big.bin")))
    }

    @Test
    fun rejectionIsReportedToSender() {
        b.autoAccept = false
        val f = TestFiles.random(File(dir, "src/x.bin"), 1000)
        val conn = a.connectTo(b)
        val t = conn.send(FileListSource(listOf(f to "x.bin")), turbo = false)
        val req = b.requests.poll(10, TimeUnit.SECONDS)
        assertNotNull(req)
        assertEquals("alice", req!!.connection.peer.profile.username)
        req.reject()
        awaitTerminal(t, 20)
        assertEquals(TransferState.REJECTED, t.state)
        assertFalse(File(b.inbox, "x.bin").exists())
    }

    @Test
    fun cancellationStopsBothSidesAndCleansUp() {
        val f = TestFiles.random(File(dir, "src/huge.bin"), 200_000_000)
        val conn = a.connectTo(b)
        val t = conn.send(FileListSource(listOf(f to "huge.bin")), turbo = false)
        assertTrue(awaitState(t, 30) { it.doneBytes > 5_000_000 })
        conn.cancel(t)
        awaitTerminal(t, 20)
        assertEquals(TransferState.CANCELLED, t.state)
        Thread.sleep(500)
        assertFalse(File(b.inbox, "huge.bin").exists())
        val parts = File(b.inbox, ".starlane-parts").listFiles()?.size ?: 0
        assertEquals(0, parts)
    }

    @Test
    fun interruptedTransferResumesFromVerifiedSegments() {
        val f = TestFiles.random(File(dir, "src/movie.mkv"), 60_000_000)
        val source = FileListSource(listOf(f to "movie.mkv"))
        val conn = a.connectTo(b)
        val t1 = conn.send(source, turbo = false)
        assertTrue(awaitState(t1, 30) { it.doneBytes > 20_000_000 })
        conn.pause(t1, true) // freeze mid-transfer so the drop happens before completion
        Thread.sleep(2500) // let the receiver checkpoint verified segments
        assertTrue(t1.state == TransferState.RUNNING)
        conn.close(Reasons.CONNECTION_LOST) // simulate the Wi-Fi link dropping
        awaitTerminal(t1, 20)
        assertEquals(TransferState.INTERRUPTED, t1.state)
        Thread.sleep(300)

        val conn2 = a.connectTo(b)
        val t2 = awaitTerminal(conn2.resume(t1, source, turbo = false))
        assertEquals(t2.reason, TransferState.COMPLETED, t2.state)
        assertTrue("resume should skip verified data, started at ${t2.bytesAtStart}", t2.bytesAtStart >= 4_000_000)
        assertTrue("resumed bytes must be below the total", t2.bytesAtStart < t2.totalBytes)
        assertEquals(t2.totalBytes, t2.doneBytes)
        assertEquals(TestFiles.sha(f), TestFiles.sha(File(b.inbox, "movie.mkv")))
    }

    @Test
    fun duplicatesRenameOrSkipPerPolicy() {
        val f = TestFiles.random(File(dir, "src/doc.pdf"), 123_456)
        val source = FileListSource(listOf(f to "doc.pdf"))
        val conn = a.connectTo(b)
        assertEquals(TransferState.COMPLETED, awaitTerminal(conn.send(source, false)).state)
        // Same file again with RENAME: a second copy with a numbered name.
        assertEquals(TransferState.COMPLETED, awaitTerminal(conn.send(source, false)).state)
        assertTrue(File(b.inbox, "doc (1).pdf").isFile)
        // SKIP: identical existing file is detected and not re-sent.
        b.policy = ConflictPolicy.SKIP
        val t3 = awaitTerminal(conn.send(source, false))
        assertEquals(TransferState.COMPLETED, t3.state)
        assertEquals(FileStatus.SKIPPED, t3.fileStatus[0])
        assertFalse(File(b.inbox, "doc (2).pdf").exists())
    }

    @Test
    fun pathTraversalNamesAreSanitised() {
        val f = TestFiles.random(File(dir, "src/evil.txt"), 10)
        val conn = a.connectTo(b)
        val t = awaitTerminal(conn.send(FileListSource(listOf(f to "../../../etc/evil.txt")), false))
        assertEquals(TransferState.COMPLETED, t.state)
        val received = awaitTerminal(b.requests.poll(10, TimeUnit.SECONDS)!!.transfer)
        assertEquals(TransferState.COMPLETED, received.state)
        assertTrue(File(b.inbox, "etc/evil.txt").isFile)
        assertFalse(File(dir, "etc/evil.txt").exists())
        assertTrue(File(received.fileLocation[0]!!).canonicalPath.startsWith(b.inbox.canonicalPath))
    }

    @Test
    fun appGroupMetadataIsDelivered() {
        val base = TestFiles.random(File(dir, "src/base.apk"), 2_000_000)
        val split = TestFiles.random(File(dir, "src/split_config.arm64_v8a.apk"), 500_000)
        val group = GroupInfo("com.example.game", GroupInfo.TYPE_APP, mapOf("label" to "Example Game", "versionName" to "1.2"))
        val source = FileListSource(
            listOf(base to "Apps/com.example.game/base.apk", split to "Apps/com.example.game/split_config.arm64_v8a.apk"),
            groups = listOf(group),
            kinds = mapOf(0 to FileKind.APK_BASE, 1 to FileKind.APK_SPLIT),
            groupOf = mapOf(0 to group.id, 1 to group.id),
        )
        val conn = a.connectTo(b)
        conn.send(source, false)
        val req = b.requests.poll(10, TimeUnit.SECONDS)!!
        assertEquals("Example Game", req.transfer.manifest.groups.single().meta["label"])
        assertEquals(FileKind.APK_SPLIT, req.transfer.manifest.files[1].kind)
        awaitTerminal(req.transfer)
        assertEquals(TransferState.COMPLETED, req.transfer.state)
    }

    @Test
    fun receiveRequestReachesPeer() {
        val conn = a.connectTo(b)
        conn.requestReceive()
        assertNotNull(b.receiveAsked.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun insufficientStorageIsRejected() {
        val tiny = object : com.starlane.core.engine.FileSystemStorage(
            File(dir, "tiny"), com.starlane.core.engine.ResumeStore(File(dir, "tiny-r")),
        ) { override fun freeBytes() = 1_000_000L }
        val c = Peer("carol", File(dir, "c"), storageOverride = tiny)
        try {
            val conn = a.connectTo(c)
            val f = TestFiles.random(File(dir, "src/large.bin"), 5_000_000)
            val t = awaitTerminal(conn.send(FileListSource(listOf(f to "large.bin")), false), 20)
            assertEquals(TransferState.REJECTED, t.state)
            assertEquals(Reasons.NO_SPACE, t.reason)
        } finally {
            c.close()
        }
    }
}
