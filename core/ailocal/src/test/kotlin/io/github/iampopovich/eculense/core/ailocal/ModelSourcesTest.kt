package io.github.iampopovich.eculense.core.ailocal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourcesTest {
    private val bundled = GalleryAllowlist.bundled()

    @Test
    fun `allowlist with trailing commas parses leniently`() {
        val text = """
            { "models": [
              { "name": "A", "modelId": "org/a", "modelFile": "a.litertlm", "commitHash": "c1", "sizeInBytes": 10,
                "minDeviceMemoryInGb": 6, "defaultConfig": { "accelerators": "gpu,cpu", }, "taskTypes": ["llm_chat",], },
            ], }
        """.trimIndent()
        val m = GalleryAllowlist.parse(text).single()
        assertEquals("org/a", m.modelId)
        assertEquals(listOf("gpu", "cpu"), m.accelerators)
        assertEquals(6, m.minDeviceMemoryInGb)
    }

    @Test
    fun `bundled allowlist keeps only litertlm chat models with pinned urls`() {
        val list = GalleryAllowlist.toLocalModels(bundled)
        assertEquals(
            setOf(
                "gemma3-1b-it-int4.litertlm",
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                "gemma-4-E2B-it.litertlm",
                "gemma-3n-E2B-it-int4.litertlm",
                "gemma-4-E4B-it.litertlm",
                "gemma-3n-E4B-it-int4.litertlm",
            ),
            list.map { it.fileName }.toSet(),
        )
        val qwen = list.first { it.fileName.startsWith("Qwen2.5") }
        assertEquals(
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/5e6e7a58d03777898b31330a15d2e0a5a9107de3/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            qwen.url,
        )
        assertEquals(1_597_922_856L, qwen.sizeBytes)
        assertEquals(6, qwen.minRamGb)
        assertEquals(ModelOrigin.GALLERY, qwen.origin)
        assertEquals("litert-community/Qwen2.5-1.5B-Instruct", qwen.repo)
        // TTS / embeddings / mobile actions are not chat models
        assertTrue(list.none { it.fileName.contains("kokoro") || it.fileName.contains("embedding") || it.fileName.startsWith("mobile_actions") })
    }

    @Test
    fun `markdown links in descriptions become plain text`() {
        assertEquals("ready on Android using LiteRT-LM. Ok", GalleryAllowlist.plain("ready on Android using [LiteRT-LM](https://x.y/z). Ok"))
    }

    @Test
    fun `merged catalog is deduped by repo and file, sorted by size, Koog pins win`() {
        val merged = LocalModelCatalog.merge(LocalModelCatalog.koogEntries(), GalleryAllowlist.toLocalModels(bundled), emptyList())
        assertEquals(merged.size, merged.map { it.repo to it.fileName }.toSet().size)
        assertEquals(merged.map { it.sizeBytes }, merged.map { it.sizeBytes }.sortedBy { it ?: Long.MAX_VALUE })
        assertEquals("mobile_actions_q8_ekv1024.litertlm", merged.first().fileName)
        assertTrue(merged.first().actionsOnly)
        assertEquals(8, merged.size) // 7 Gallery + FunctionGemma
        val e2b = merged.single { it.fileName == "gemma-4-E2B-it.litertlm" }
        // Koog's verified revision and pack id survive the merge (a staged download resumes), Gallery adds metadata.
        assertEquals("model-gemma-4-e2b-it", e2b.packId)
        assertTrue(e2b.url!!.contains("/resolve/2d74ac41a33d7da82f39f7d5720a04c68514a103/"))
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", e2b.sha256)
        assertEquals(ModelOrigin.GALLERY, e2b.origin)
        assertTrue(e2b.koog)
        assertEquals(8, e2b.minRamGb)
        assertEquals("Apache-2.0", e2b.license)
        assertFalse(merged.single { it.fileName.startsWith("Qwen2.5") }.koog)
    }

    @Test
    fun `custom duplicates of catalog files are dropped, unknown sizes sort last`() {
        val gallery = GalleryAllowlist.toLocalModels(bundled)
        val dup = gallery.first().copy(packId = "model-custom-x", origin = ModelOrigin.CUSTOM_LINK)
        val unknown = gallery.first().copy(packId = "model-custom-y", repo = "o/r", fileName = "y.litertlm", sizeBytes = null, origin = ModelOrigin.CUSTOM_FILE)
        val merged = LocalModelCatalog.merge(emptyList(), gallery, listOf(dup, unknown))
        assertTrue(merged.none { it.packId == "model-custom-x" })
        assertEquals("model-custom-y", merged.last().packId)
    }

    @Test
    fun `gated field of the HF model api maps to needs-token`() {
        assertEquals(false, HfApi.gated("""{"id":"a/b","gated":false}"""))
        assertEquals(true, HfApi.gated("""{"id":"a/b","gated":"auto"}"""))
        assertEquals(true, HfApi.gated("""{"id":"a/b","gated":"manual"}"""))
        assertNull(HfApi.gated("""{"id":"a/b"}"""))
    }

    @Test
    fun `hf search lists generic litertlm files with sizes pinned to the commit, NPU builds skipped`() {
        val repo = """
            {"id":"litert-community/Qwen3-0.6B","sha":"8d42ab5f1db14cad987fac57aeb17d070f9e180d","gated":false,
             "cardData":{"license":"apache-2.0"},
             "siblings":[{"rfilename":".gitattributes","size":1866},
              {"rfilename":"Qwen3-0.6B.litertlm","size":613722264,"lfs":{"sha256":"c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108","size":613722264}},
              {"rfilename":"Qwen3-0.6B.mediatek.mt6993.litertlm","size":1202913280,"lfs":{"sha256":"cc87","size":1202913280}},
              {"rfilename":"Qwen3-0.6B_qualcomm_sm8750.litertlm","size":5,"lfs":{"sha256":"aa","size":5}},
              {"rfilename":"README.md","size":10}]}
        """.trimIndent()
        val models = HfApi.repoModels(repo)
        val m = models.single()
        assertEquals("Qwen3-0.6B.litertlm", m.fileName)
        assertEquals(613_722_264L, m.sizeBytes)
        assertEquals("https://huggingface.co/litert-community/Qwen3-0.6B/resolve/8d42ab5f1db14cad987fac57aeb17d070f9e180d/Qwen3-0.6B.litertlm", m.url)
        assertEquals(ModelOrigin.HF, m.origin)
        assertEquals("apache-2.0", m.license)
        assertEquals(listOf("litert-community/Qwen3-0.6B", "litert-community/Phi-4-mini"), HfApi.searchRepos("""[{"id":"litert-community/Qwen3-0.6B"},{"id":"litert-community/Phi-4-mini"}]"""))
    }

    @Test
    fun `search url is encoded and limited to litert-community`() {
        assertEquals("https://huggingface.co/api/models?author=litert-community&search=phi+4&limit=20", HfApi.searchUrl("phi 4"))
    }
}
