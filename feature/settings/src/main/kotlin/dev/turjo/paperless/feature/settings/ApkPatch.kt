package dev.turjo.paperless.feature.settings

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Delta updates: rebuilds the new APK from the installed one plus a small patch, so an update
 * downloads only what changed. `tools/apk-patch/make_patch.py` writes the patches.
 *
 * Format (big-endian), a bsdiff variant with zlib instead of bzip2 so no library is needed:
 * ```
 * "SCNPATCH" | version u32 = 1
 * source size u64 | source SHA-256 (32) | target size u64 | target SHA-256 (32)
 * control length u64 | diff length u64 | extra length u64   (compressed byte counts)
 * zlib(control) | zlib(diff) | zlib(extra)
 * ```
 * Control is a list of (add, copy, seek) i64 triples: add `add` bytes of old+diff, copy `copy`
 * bytes from extra, then move the old position by `seek`.
 */
internal object ApkPatch {
    private val MAGIC = "SCNPATCH".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 8 + 4 + 8 + 32 + 8 + 32 + 8 * 3

    class Header(
        val sourceSize: Long,
        val sourceSha256: ByteArray,
        val targetSize: Long,
        val targetSha256: ByteArray,
        val controlLength: Long,
        val diffLength: Long,
        val extraLength: Long,
    )

    class PatchException(message: String) : Exception(message)

    fun readHeader(patch: File): Header = DataInputStream(BufferedInputStream(FileInputStream(patch))).use { input ->
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(MAGIC) || input.readInt() != 1) throw PatchException("Not a Scanly patch")
        val header = Header(
            sourceSize = input.readLong(),
            sourceSha256 = ByteArray(32).also(input::readFully),
            targetSize = input.readLong(),
            targetSha256 = ByteArray(32).also(input::readFully),
            controlLength = input.readLong(),
            diffLength = input.readLong(),
            extraLength = input.readLong(),
        )
        val lengths = listOf(header.controlLength, header.diffLength, header.extraLength)
        if (header.sourceSize < 0 || header.targetSize < 0 || lengths.any { it < 0 } ||
            HEADER_SIZE + lengths.sum() != patch.length()
        ) throw PatchException("Damaged patch")
        header
    }

    /**
     * Writes the patched file to [target]. Checks the source and the result against the hashes in
     * the patch; throws [PatchException] on any mismatch (the caller then downloads the full APK).
     */
    fun apply(source: File, patch: File, target: File, checkCancelled: () -> Unit = {}) {
        val header = readHeader(patch)
        if (source.length() != header.sourceSize || !sha256(source, checkCancelled).contentEquals(header.sourceSha256)) {
            throw PatchException("Patch is for a different installed version")
        }
        val controlStart = HEADER_SIZE.toLong()
        val diffStart = controlStart + header.controlLength
        val extraStart = diffStart + header.diffLength
        val digest = MessageDigest.getInstance("SHA-256")
        section(patch, controlStart, header.controlLength).use { controlRaw ->
            section(patch, diffStart, header.diffLength).use { diff ->
                section(patch, extraStart, header.extraLength).use { extra ->
                    RandomAccessFile(source, "r").use { old ->
                        target.outputStream().buffered(1 shl 16).use { out ->
                            val control = DataInputStream(controlRaw)
                            val buffer = ByteArray(1 shl 16)
                            val oldBuffer = ByteArray(1 shl 16)
                            var written = 0L
                            var oldPosition = 0L
                            while (written < header.targetSize) {
                                checkCancelled()
                                val add = control.readLong()
                                val copy = control.readLong()
                                val seek = control.readLong()
                                if (add < 0 || copy < 0 || written + add + copy > header.targetSize) {
                                    throw PatchException("Damaged patch")
                                }
                                var left = add
                                while (left > 0) {
                                    val count = minOf(left, buffer.size.toLong()).toInt()
                                    diff.readFully(buffer, count)
                                    // Bytes outside the old file count as zero, as in bspatch.
                                    oldBuffer.fill(0, 0, count)
                                    val start = oldPosition
                                    val from = maxOf(start, 0L)
                                    val to = minOf(start + count, header.sourceSize)
                                    if (from < to) {
                                        old.seek(from)
                                        old.readFully(oldBuffer, (from - start).toInt(), (to - from).toInt())
                                    }
                                    for (index in 0 until count) {
                                        buffer[index] = (buffer[index] + oldBuffer[index]).toByte()
                                    }
                                    out.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    oldPosition += count
                                    left -= count
                                }
                                left = copy
                                while (left > 0) {
                                    val count = minOf(left, buffer.size.toLong()).toInt()
                                    extra.readFully(buffer, count)
                                    out.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    left -= count
                                }
                                written += add + copy
                                oldPosition += seek
                            }
                        }
                    }
                }
            }
        }
        if (target.length() != header.targetSize || !digest.digest().contentEquals(header.targetSha256)) {
            target.delete()
            throw PatchException("Patched file doesn't match the release")
        }
    }

    fun sha256(file: File, checkCancelled: () -> Unit = {}): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun section(file: File, start: Long, length: Long): InputStream {
        val raw = FileInputStream(file)
        var skipped = 0L
        while (skipped < start) {
            val step = raw.skip(start - skipped)
            if (step <= 0) {
                raw.close()
                throw PatchException("Damaged patch")
            }
            skipped += step
        }
        return BufferedInputStream(InflaterInputStream(BoundedInputStream(raw, length)), 1 shl 16)
    }

    private fun InputStream.readFully(buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val read = read(buffer, offset, count - offset)
            if (read == -1) throw EOFException("Damaged patch")
            offset += read
        }
    }

    private class BoundedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val value = input.read()
            if (value >= 0) left--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val count = input.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (count > 0) left -= count
            return count
        }

        override fun close() = input.close()
    }
}
