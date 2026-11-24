package com.aisocial.copilot

import com.aisocial.copilot.domain.model.DefaultProviders
import com.aisocial.copilot.domain.model.ModelCatalog
import com.aisocial.copilot.domain.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    @Test
    fun `provider list matches the v1_1 spec and keeps the documented order`() {
        assertEquals(
            listOf("deepseek", "openai", "claude", "gemini", "kimi", "qwen", "glm"),
            DefaultProviders.ids
        )
        assertEquals(7, DefaultProviders.list.size)
    }

    @Test
    fun `the removed custom endpoint is gone`() {
        assertTrue(DefaultProviders.list.none { it.id == "custom" })
        assertTrue(DefaultProviders.list.none { it.displayName.contains("自定义") })
    }

    @Test
    fun `every provider has a protocol kind, a base url and a curated list`() {
        DefaultProviders.list.forEach { provider ->
            assertTrue(provider.id, provider.baseUrl.startsWith("https://"))
            assertTrue(provider.id, provider.model.isNotBlank())
            assertTrue(provider.id, ModelCatalog.curated(provider.id).isNotEmpty())
        }
    }

    @Test
    fun `openai compatible vendors all share the openai transport`() {
        listOf("deepseek", "openai", "kimi", "qwen", "glm").forEach { id ->
            assertEquals(id, ProviderKind.OPENAI, DefaultProviders.byId(id).kind)
        }
        assertEquals(ProviderKind.CLAUDE, DefaultProviders.byId("claude").kind)
        assertEquals(ProviderKind.GEMINI, DefaultProviders.byId("gemini").kind)
    }

    @Test
    fun `model choices put the current model first and include fetched ids`() {
        val config = DefaultProviders.deepSeek.copy(
            model = "deepseek-v4.1-flash",
            fetchedModels = listOf("deepseek-v4p", "deepseek-chat"),
        )

        val choices = config.modelChoices()

        assertEquals("deepseek-v4.1-flash", choices.first())
        assertTrue(choices.contains("deepseek-v4p"))
        assertTrue(choices.contains("deepseek-chat"))
        // no duplicates even though deepseek-chat appears in the curated list too
        assertEquals(choices.size, choices.distinct().size)
    }

    @Test
    fun `model choices never contain blanks`() {
        val config = DefaultProviders.kimi.copy(fetchedModels = listOf("", "  ", "kimi-k2-0905-preview"))
        val choices = config.modelChoices()

        assertTrue(choices.none { it.isBlank() })
        assertTrue(choices.contains("kimi-k2-0905-preview"))
    }

    @Test
    fun `unknown provider id falls back to deepseek instead of crashing`() {
        assertEquals("deepseek", DefaultProviders.byId("nope").id)
    }

    @Test
    fun `vision capable vendors are marked and deepseek is not`() {
        assertFalse(DefaultProviders.deepSeek.supportsVision)
        listOf("openai", "claude", "gemini", "kimi", "qwen", "glm").forEach { id ->
            assertTrue(id, DefaultProviders.byId(id).supportsVision)
        }
    }
}
