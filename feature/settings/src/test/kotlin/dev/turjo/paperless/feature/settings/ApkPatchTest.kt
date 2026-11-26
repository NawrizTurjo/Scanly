package dev.turjo.paperless.feature.settings

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkPatchTest {
    @get:Rule val folder = TemporaryFolder()

    private val old = ByteArray(5000) { (it * 7 % 251).toByte() }
    // Same as old with a changed middle, an inserted block and a new tail.
    private val new = old.copyOfRange(0, 2000) + ByteArray(300) { 9 } + old.copyOfRange(2000, 5000).also {
        it[100] = (it[100] + 1).toByte()
    } + "tail".toByteArray()

    /** A valid patch built by hand: diff over old[0..2000), 300 extra, diff over old[2000..5000), 4 extra. */
    private fun patchBytes(source: ByteArray = old, target: ByteArray = new): ByteArray {
        val control = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeLong(2000); it.writeLong(300); it.writeLong(0)
                it.writeLong(3000); it.writeLong(4); it.writeLong(0)
            }
        }.toByteArray()
        val diff = ByteArray(5000) { index -> (target[if (index < 2000) index else index + 300] - old[index]).toByte() }
        val extra = target.copyOfRange(2000, 2300) + target.copyOfRange(5300, 5304)
        val sections = listOf(control, diff, extra).map { part ->
            ByteArrayOutputStream().also { bytes -> DeflaterOutputStream(bytes).use { it.write(part) } }.toByteArray()
        }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("SCNPATCH".toByteArray())
                out.writeInt(1)
                out.writeLong(source.size.toLong()); out.write(sha256(source))
                out.writeLong(target.size.toLong()); out.write(sha256(target))
                sections.forEach { out.writeLong(it.size.toLong()) }
                sections.forEach { out.write(it) }
            }
        }.toByteArray()
    }

    @Test fun rebuildsTheTarget() {
        val source = folder.newFile().apply { writeBytes(old) }
        val patch = folder.newFile().apply { writeBytes(patchBytes()) }
        val target = File(folder.root, "out.apk")
        ApkPatch.apply(source, patch, target)
        assertArrayEquals(new, target.readBytes())
    }

    @Test(expected = ApkPatch.PatchException::class)
    fun rejectsADifferentSource() {
        val source = folder.newFile().apply { writeBytes(old.copyOf().also { it[0] = 99 }) }
        val patch = folder.newFile().apply { writeBytes(patchBytes()) }
        ApkPatch.apply(source, patch, File(folder.root, "out.apk"))
    }

    @Test fun rejectsAWrongResultAndDeletesIt() {
        val source = folder.newFile().apply { writeBytes(old) }
        // Header promises a different target hash than the blocks produce.
        val patch = folder.newFile().apply { writeBytes(patchBytes(target = new).also { bytes -> bytes[12 + 8 + 32 + 8] = (bytes[12 + 8 + 32 + 8] + 1).toByte() }) }
        val target = File(folder.root, "out.apk")
        try {
            ApkPatch.apply(source, patch, target)
            throw AssertionError("expected a mismatch")
        } catch (_: ApkPatch.PatchException) {
            assertFalse(target.exists())
        }
    }

    @Test(expected = ApkPatch.PatchException::class)
    fun rejectsATruncatedPatch() {
        val patch = folder.newFile().apply { writeBytes(patchBytes().copyOf(200)) }
        ApkPatch.readHeader(patch)
    }

    /** Real release pair: set SCANLY_PATCH_CHECK=old.apk;patch;new.apk to run. */
    @Test fun appliesARealReleasePatch() {
        val paths = System.getenv("SCANLY_PATCH_CHECK")?.split(';')
        assumeTrue(paths?.size == 3)
        val (oldApk, patch, newApk) = paths!!.map(::File)
        val target = File(folder.root, "rebuilt.apk")
        ApkPatch.apply(oldApk, patch, target)
        assertArrayEquals(sha256(newApk.readBytes()), sha256(target.readBytes()))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
