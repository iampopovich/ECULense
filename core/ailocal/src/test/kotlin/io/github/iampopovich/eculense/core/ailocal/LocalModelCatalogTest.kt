package io.github.iampopovich.eculense.core.ailocal

import ai.koog.prompt.executor.clients.litert.LiteRTLLModels
import io.github.iampopovich.eculense.core.datapacks.PackGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogTest {
    @Test
    fun `every model of the Koog LiteRT catalog has a download source with the file name Koog expects`() {
        val entries = LocalModelCatalog.koogEntries()
        assertEquals(LiteRTLLModels.models.map { it.id }, entries.map { it.fileName })
        entries.forEach { e ->
            assertNotNull("${e.fileName} без ссылки", e.url)
            assertTrue(e.url!!.endsWith("/" + e.fileName))
            assertTrue(e.url!!.startsWith("https://huggingface.co/litert-community/"))
            assertNotNull(e.sizeBytes)
            assertEquals(ModelOrigin.KOOG, e.origin)
        }
    }

    @Test
    fun `Gemma 4 entries are open and pinned to a revision with size and sha256, FunctionGemma is gated`() {
        val byFile = LocalModelCatalog.koogEntries().associateBy { it.fileName }
        val e2b = byFile.getValue("gemma-4-E2B-it.litertlm")
        assertEquals(2_588_147_712L, e2b.sizeBytes)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", e2b.sha256)
        assertFalse(e2b.gated)
        assertTrue(e2b.url!!.contains("/resolve/2d74ac41a33d7da82f39f7d5720a04c68514a103/"))
        assertEquals(3_659_530_240L, byFile.getValue("gemma-4-E4B-it.litertlm").sizeBytes)
        assertTrue(byFile.getValue("mobile_actions_q8_ekv1024.litertlm").gated)
    }

    @Test
    fun `entries become resumable model packs`() {
        val pack = LocalModelCatalog.koogEntries().first { it.fileName == "gemma-4-E2B-it.litertlm" }.toPack()!!
        assertEquals(PackGroup.MODEL, pack.group)
        assertTrue(pack.resumable)
        assertEquals("gemma-4-E2B-it.litertlm", pack.files.single().name)
        assertEquals(2_588_147_712L, pack.files.single().sizeBytes)
    }

    @Test
    fun `hugging face links are parsed - resolve, blob and query string`() {
        val r = HfLink.parse("https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm?download=true")!!
        assertEquals("litert-community/Qwen3-0.6B", r.repo)
        assertEquals("main", r.revision)
        assertEquals("Qwen3-0.6B.litertlm", r.path)
        assertEquals("https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm", r.resolveUrl)
        val b = HfLink.parse("huggingface.co/org/repo/blob/abc123/sub/dir/model.litertlm")!!
        assertEquals("sub/dir/model.litertlm", b.path)
        assertEquals("model.litertlm", b.fileName)
        assertEquals("https://huggingface.co/org/repo/resolve/abc123/sub/dir/model.litertlm", b.resolveUrl)
    }

    @Test
    fun `non litertlm or foreign links are rejected with a reason`() {
        assertNull(HfLink.parse("https://example.com/org/repo/resolve/main/m.litertlm"))
        assertNull(HfLink.parse("https://huggingface.co/org/repo"))
        assertEquals(HfLink.Problem.NOT_LITERTLM, HfLink.check("https://huggingface.co/org/repo/resolve/main/model.gguf"))
        assertEquals(HfLink.Problem.NOT_HF, HfLink.check("http://huggingface.co/org/repo/resolve/main/m.litertlm"))
        assertNull(HfLink.check("https://huggingface.co/org/repo/resolve/main/m.litertlm"))
    }

    @Test
    fun `custom model ids are stable and safe for paths`() {
        val id = LocalModelCatalog.customId("org/Repo Name", "weird name.litertlm")
        assertTrue(id, id.matches(Regex("model-custom-[a-z0-9-]+")))
        assertEquals(id, LocalModelCatalog.customId("org/Repo Name", "weird name.litertlm"))
    }
}
