package io.github.iampopovich.eculense.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Диспетчер на таблице формулировок (в том числе как спрашивает Алекс) + реестр ролей и промпты. */
class ChatRouterTest {
    private val today = LocalDate.of(2026, 7, 2)
    private fun route(q: String) = ChatRouter.route(q, today)
    private fun ids(q: String) = route(q).roles.map { it.id }

    private data class Row(val question: String, val intent: ChatIntent, val lead: String?, val days: Int? = null)

    private val table = listOf(
        Row("как моя машина ведет себя последние полгода", ChatIntent.TREND, "usage", 180),
        Row("Как машина вела себя за полгода", ChatIntent.TREND, "usage", 180),
        Row("how did my car behave over the last 6 months", ChatIntent.TREND, "usage", 180),
        Row("объясни мои ошибки", ChatIntent.DIAGNOSE, "diagnostician"),
        Row("Объясни мои ошибки", ChatIntent.DIAGNOSE, "diagnostician"),
        Row("что значит P0171?", ChatIntent.DIAGNOSE, "diagnostician"),
        Row("сколько я трачу на бензин", ChatIntent.FUEL, "fuel"),
        Row("Сколько трачу на топливо", ChatIntent.FUEL, "fuel"),
        Row("какой расход за месяц", ChatIntent.FUEL, "fuel", 30),
        Row("когда менять масло", ChatIntent.MAINTENANCE, "service"),
        Row("Что пора обслужить", ChatIntent.MAINTENANCE, "service"),
        Row("почему плавают обороты на холостых", ChatIntent.DIAGNOSE, "engine"),
        Row("что с аккумулятором", ChatIntent.SENSOR, "electrical"),
        Row("греется ли мотор", ChatIntent.SENSOR, "cooling"),
        Row("какие болячки у sorento 3.5", ChatIntent.MODEL, "model_expert"),
        Row("hi", ChatIntent.SMALLTALK, null),
        Row("привет", ChatIntent.SMALLTALK, null),
        Row("Привет!", ChatIntent.SMALLTALK, null),
        Row("ну и как", ChatIntent.OVERVIEW, "usage", 180),
    )

    @Test
    fun `phrase table routes to the right lead expert, intent and period`() {
        val failures = table.mapNotNull { row ->
            val r = route(row.question)
            val lead = r.roles.firstOrNull()?.id
            if (r.intent == row.intent && lead == row.lead && r.days == row.days) null
            else "«${row.question}»: ${r.intent}/$lead/${r.days}, ждали ${row.intent}/${row.lead}/${row.days}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `half a year question is not only the diagnostician`() {
        val r = ids("как моя машина ведет себя последние полгода")
        assertEquals("usage", r.first())
        assertTrue(r.toString(), r.containsAll(listOf("diagnostician", "engine")))
        assertTrue(r.size in 2..ChatRouter.MAX_ROLES)
    }

    @Test
    fun `unclear question is an overview with usage, diagnostician and engine`() {
        val r = route("расскажи что-нибудь")
        assertEquals(ChatIntent.OVERVIEW, r.intent)
        assertEquals(listOf("usage", "diagnostician", "engine"), r.roles.map { it.id })
        assertEquals(180, r.periodDays)
    }

    @Test
    fun `greeting and off-topic small talk use no experts and no data`() {
        listOf("hi", "привет", "спасибо!", "кто ты?").forEach { q ->
            val r = route(q)
            assertEquals(q, ChatIntent.SMALLTALK, r.intent)
            assertTrue(q, r.roles.isEmpty())
            assertTrue(q, r.digestSections.isEmpty())
        }
    }

    @Test
    fun `single topic questions stay with one expert`() {
        assertEquals(listOf("diagnostician"), ids("объясни мои ошибки"))
        assertEquals(listOf("fuel"), ids("сколько я трачу на бензин"))
        assertEquals(listOf("service"), ids("когда менять масло"))
        assertEquals(listOf("electrical"), ids("что с аккумулятором"))
        // «почему…» — ведущий по теме, Диагност вторым
        assertEquals(listOf("engine", "diagnostician"), ids("почему плавают обороты на холостых"))
    }

    @Test
    fun `periods from the question`() {
        assertEquals(180, ChatRouter.days("за полгода", today))
        assertEquals(180, ChatRouter.days("последние 6 месяцев", today))
        assertEquals(180, ChatRouter.days("half a year", today))
        assertEquals(30, ChatRouter.days("за месяц", today))
        assertEquals(365, ChatRouter.days("за год", today))
        assertEquals(7, ChatRouter.days("за неделю", today))
        assertEquals(14, ChatRouter.days("последние 2 недели", today))
        assertEquals(90, ChatRouter.days("за квартал", today))
        assertEquals(10, ChatRouter.days("за 10 дней", today))
        // с весны = с 1 марта этого года
        assertEquals(123, ChatRouter.days("как ездил с весны", today))
        // с зимы: 1 декабря прошлого года
        assertEquals(213, ChatRouter.days("с зимы", today))
        assertNull(ChatRouter.days("объясни мои ошибки", today))
        // год выпуска — не период
        assertNull(ChatRouter.days("болячки kia 2005", today))
    }

    @Test
    fun `phone digest for a trend question starts with the car and trends, not with codes`() {
        val sections = route("как моя машина ведет себя последние полгода").digestSections
        assertEquals("carProfile", sections.first())
        assertEquals("trends", sections[1])
        assertTrue(sections.indexOf("trends") < sections.indexOf("dtcHistory"))
        assertEquals(listOf("carProfile", "dtcHistory", "findings"), route("объясни мои ошибки").digestSections)
    }

    @Test
    fun `role registry - unique ids, own tool sets, prompts without the fault template`() {
        val roles = ExpertRoles.ALL
        assertEquals(roles.size, roles.map { it.id }.toSet().size)
        assertEquals(
            listOf("diagnostician", "engine", "cooling", "electrical", "usage", "fuel", "service", "model_expert"),
            roles.map { it.id },
        )
        val trend = route("как моя машина ведет себя последние полгода")
        roles.forEach { r ->
            assertTrue(r.id, r.tools.isNotEmpty() && r.keywords.isNotEmpty())
            listOf(r.prompt, SystemPrompts.expert(r, trend), SystemPrompts.direct(r, trend)).forEach { p ->
                assertFalse("${r.id}: $p", p.contains("что проверить самому") || p.contains("когда ехать в сервис"))
            }
            assertTrue(r.id, SystemPrompts.expert(r, trend).contains("days=180"))
        }
        assertEquals(setOf("dtcHistory", "findings", "carProfile"), ExpertRoles.DIAGNOSTICIAN.tools)
        assertEquals(setOf("trends", "tripStats", "listSessions"), ExpertRoles.USAGE.tools)
        assertEquals(setOf("carProfile"), ExpertRoles.MODEL_EXPERT.tools)
        // старый шаблон живёт только в форме ответа на вопрос о неисправности
        assertTrue(SystemPrompts.answerForm(ChatIntent.DIAGNOSE).contains("что проверить самому"))
        ChatIntent.entries.filter { it != ChatIntent.DIAGNOSE }.forEach { i ->
            assertFalse(i.name, SystemPrompts.answerForm(i).contains("что проверить самому"))
        }
        assertFalse(SystemPrompts.MECHANIC.contains("что проверить самому"))
        assertTrue("Знаток модели помечает общие знания", ExpertRoles.MODEL_EXPERT.prompt.contains("по общим сведениям, не по вашим данным"))
    }

    @Test
    fun `statuses name the role and the period`() {
        assertEquals("Аналитик эксплуатации смотрит поездки за 6 месяцев…", ExpertRoles.USAGE.statusText(180))
        assertEquals("Электрик смотрит напряжение за месяц…", ExpertRoles.ELECTRICAL.statusText(30))
        assertEquals("Диагност смотрит коды ошибок…", ExpertRoles.DIAGNOSTICIAN.statusText(180))
        assertEquals("2 месяца", ExpertRoles.periodText(60))
        assertEquals("5 месяцев", ExpertRoles.periodText(150))
    }
}
