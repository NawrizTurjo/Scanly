package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.io.IOException
import java.util.UUID

object PdfPassword {
    suspend fun encrypt(input: File, userPassword: String, out: File): ToolResult {
        if (userPassword.isEmpty()) throw ToolError.Invalid("Enter a password")
        return editPdf(input, out) { document, temporary ->
            val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), userPassword, AccessPermission())
            policy.encryptionKeyLength = 256
            policy.isPreferAES = true
            document.protect(policy)
            document.save(temporary)
            ToolResult(out, document.numberOfPages)
        }
    }

    suspend fun decrypt(input: File, password: String, out: File): ToolResult =
        editPdf(input, out, password) { document, temporary ->
            document.isAllSecurityToBeRemoved = true
            document.save(temporary)
            ToolResult(out, document.numberOfPages)
        }

    fun isEncrypted(input: File): Boolean = try {
        PDDocument.load(input).use { it.isEncrypted }
    } catch (_: InvalidPasswordException) {
        true
    } catch (error: IOException) {
        throw ToolError.Corrupt(error.message ?: "Cannot read PDF")
    }
}
