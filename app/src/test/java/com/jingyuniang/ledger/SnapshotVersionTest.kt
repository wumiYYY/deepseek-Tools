package com.jingyuniang.ledger

import com.jingyuniang.ledger.io.SnapshotManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 迁移前快照的核心判断：读 SQLite 文件头里的 user_version。
 * 判错了要么不做快照（迁移失败就没救），要么每次都做（浪费空间），所以单独测。
 */
class SnapshotVersionTest {

    private fun buildSqliteHeader(version: Int): ByteArray {
        val header = ByteArray(200)
        val magic = "SQLite format 3".toByteArray(Charsets.US_ASCII)
        magic.copyInto(header, 0)
        header[15] = 0
        // user_version @ 偏移 60，4 字节大端
        header[60] = ((version shr 24) and 0xFF).toByte()
        header[61] = ((version shr 16) and 0xFF).toByte()
        header[62] = ((version shr 8) and 0xFF).toByte()
        header[63] = (version and 0xFF).toByte()
        return header
    }

    @Test
    fun `读取 sqlite 的 user_version`() {
        val f = File.createTempFile("whale", ".db")
        try {
            f.writeBytes(buildSqliteHeader(1))
            assertEquals(1, SnapshotManager.readUserVersion(f))
            f.writeBytes(buildSqliteHeader(2))
            assertEquals(2, SnapshotManager.readUserVersion(f))
            f.writeBytes(buildSqliteHeader(0))
            assertEquals(0, SnapshotManager.readUserVersion(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `非 sqlite 文件返回 0（不做快照，避免误判）`() {
        val f = File.createTempFile("notdb", ".db")
        try {
            f.writeText("hello world, this is not a database but long enough to read 100 bytes" + " ".repeat(60))
            assertEquals(0, SnapshotManager.readUserVersion(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `太短的文件返回 0`() {
        val f = File.createTempFile("tiny", ".db")
        try {
            f.writeBytes(ByteArray(10))
            assertEquals(0, SnapshotManager.readUserVersion(f))
        } finally {
            f.delete()
        }
    }
}