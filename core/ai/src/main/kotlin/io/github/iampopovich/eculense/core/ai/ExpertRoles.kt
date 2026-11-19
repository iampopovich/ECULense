package io.github.iampopovich.eculense.core.ai

/** Тип вопроса: от него зависят эксперты, данные и форма ответа (а не один шаблон на всё). */
enum class ChatIntent { DIAGNOSE, OVERVIEW, TREND, FUEL, MAINTENANCE, MODEL, SENSOR, SMALLTALK }

/**
 * Роль-эксперт: кто смотрит данные и с какой стороны. [tools] — имена инструментов :core:aitools, которые роли
 * доступны (у каждого эксперта свой набор); [digestSections] — разделы сводки для модели на телефоне;
 * [keywords] — фрагменты регулярных выражений для [ChatRouter] (ищутся с начала слова, ру + англ.).
 */
data class ExpertRole(
    val id: String,
    val title: String,
    val focus: String,
    val prompt: String,
    val tools: Set<String>,
    val digestSections: List<String>,
    val keywords: List<String>,
    /** Что роль делает, для статуса в чате; «%s» — период («за 6 месяцев»). */
    val status: String,
) {
    fun statusText(days: Int): String = status.replace("%s", ExpertRoles.periodText(days))
}

/** Заметка эксперта для редактора и для блока «Заметки экспертов» под ответом. */
data class ExpertNote(val roleId: String, val title: String, val text: String, val failed: Boolean = false)

/**
 * Реестр ролей — данные, а не код в when: добавить роль = добавить элемент. Промпт роли говорит, кто ты, на что
 * смотришь и чего не касаешься; формат заметки и общие правила — в [SystemPrompts].
 */
object ExpertRoles {
    val DIAGNOSTICIAN = ExpertRole(
        id = "diagnostician",
        title = "Диагност",
        focus = "коды ошибок, их связь между собой, срочность, Check Engine",
        prompt = """
            Ты — Диагност. Смотришь коды ошибок (DTC): какие были, сколько раз, когда впервые и последний раз, как коды связаны между собой и
            с находками анализа, насколько это срочно, горит ли Check Engine. Не касаешься расхода, поездок и регламента ТО (см. Экономист,
            Аналитик эксплуатации, Мастер ТО).
        """.trimIndent(),
        tools = setOf("dtcHistory", "findings", "carProfile"),
        digestSections = listOf("dtcHistory", "findings"),
        keywords = listOf(
            "ошибк", "код", "dtc", "check engine", "чек", "[pubc][0-3][0-9a-f]{3}", "лампа", "горит", "диагност", "неисправн",
            "error", "trouble code", "fault", "warning light", "codes?\\b",
        ),
        status = "Диагност смотрит коды ошибок…",
    )

    val ENGINE = ExpertRole(
        id = "engine",
        title = "Моторист",
        focus = "смесь, коррекции топлива, MAF, датчики кислорода, пропуски, холостой ход",
        prompt = """
            Ты — Моторист. Смотришь работу двигателя: топливные коррекции (LTFT/STFT по банкам), состав смеси, MAF, датчики кислорода,
            пропуски зажигания, обороты и устойчивость холостого хода — по записям и их изменению за период. Не касаешься кодов как
            таковых (см. Диагност), охлаждения (см. Специалист по охлаждению) и денег (см. Экономист).
        """.trimIndent(),
        tools = setOf("sensorSummary", "sessionStats", "findings", "trends"),
        digestSections = listOf("trends", "findings"),
        keywords = listOf(
            "смес", "коррекц", "ltft", "stft", "fuel trim", "maf", "дмрв", "лямбд", "кислород", "o2\\b", "пропуск", "троит", "misfire",
            "холост", "хх\\b", "idle", "оборот", "rpm", "плава", "глохн", "stall", "детонац", "зажиган", "rough",
        ),
        status = "Моторист смотрит смесь и холостой ход за %s…",
    )

    val COOLING = ExpertRole(
        id = "cooling",
        title = "Специалист по охлаждению",
        focus = "температура ОЖ, прогрев, перегрев, термостат",
        prompt = """
            Ты — Специалист по охлаждению. Смотришь температуру охлаждающей жидкости: медиану и максимумы по месяцам, время прогрева,
            эпизоды перегрева, признаки неисправного термостата (долгий прогрев, низкая рабочая температура) и вентилятора. Не касаешься
            смеси и кодов, не связанных с охлаждением (см. Моторист, Диагност).
        """.trimIndent(),
        tools = setOf("sensorSummary", "trends", "findings"),
        digestSections = listOf("trends", "findings"),
        keywords = listOf(
            "охлажд", "температур", "ож\\b", "антифриз", "тосол", "перегр", "грее", "грет", "прогрев", "термостат", "радиатор", "вентилятор",
            "coolant", "overheat", "temperature", "thermostat", "hot\\b",
        ),
        status = "Специалист по охлаждению смотрит температуру ОЖ за %s…",
    )

    val ELECTRICAL = ExpertRole(
        id = "electrical",
        title = "Электрик",
        focus = "аккумулятор, зарядка, напряжение бортовой сети",
        prompt = """
            Ты — Электрик. Смотришь напряжение бортовой сети на работающем двигателе (медиана, минимумы, провалы) и его изменение по
            месяцам: заряжает ли генератор, не садится ли аккумулятор. Помни: напряжение адаптера (ATRV) обычно на 0,2–0,3 В ниже
            реального. Не касаешься смеси, охлаждения и расхода (см. Моторист, Специалист по охлаждению, Экономист).
        """.trimIndent(),
        tools = setOf("sensorSummary", "trends", "findings"),
        digestSections = listOf("trends", "findings"),
        keywords = listOf(
            "аккумулятор", "акб", "батаре", "заряд", "напряжен", "генератор", "вольт", "стартер", "не заводит",
            "battery", "voltage", "alternator", "charging", "starter",
        ),
        status = "Электрик смотрит напряжение за %s…",
    )

    val USAGE = ExpertRole(
        id = "usage",
        title = "Аналитик эксплуатации",
        focus = "поездки, пробег, режимы и стиль езды, холостой ход, как машина «ведёт себя» за период",
        prompt = """
            Ты — Аналитик эксплуатации. Смотришь, как машиной пользуются и как она «ведёт себя» за период: число и длина поездок, пробег,
            доля времени на месте (пробки, прогрев на месте), режимы, изменения по месяцам (тренды). Не ставишь диагнозов по кодам
            (см. Диагност) и не считаешь деньги (см. Экономист).
        """.trimIndent(),
        tools = setOf("trends", "tripStats", "listSessions"),
        digestSections = listOf("trends", "tripStats", "listSessions"),
        keywords = listOf(
            "ве(дет|ла|л|ли|дут) себя", "как (моя |наша )?(машина|машинка|авто)", "поездк", "пробег", "езжу", "езди", "езда", "стиль", "режим",
            "в целом", "обзор", "итог", "статистик", "тренд", "динамик", "изменил", "простаива", "пробк",
            "behave", "trips?\\b", "driving", "mileage", "overall", "how (is|did|does|has) my car", "summary", "trend", "usage",
        ),
        status = "Аналитик эксплуатации смотрит поездки за %s…",
    )

    val FUEL = ExpertRole(
        id = "fuel",
        title = "Экономист",
        focus = "расход топлива, траты на топливо, сравнение периодов",
        prompt = """
            Ты — Экономист. Смотришь расход топлива (л/100 км) и траты на топливо за период, сравниваешь месяцы между собой, связываешь
            изменения с режимом езды (доля стоянки, длина поездок). Оговаривай, если расход оценён по MAF, а не измерен. Не касаешься кодов
            ошибок (см. Диагност).
        """.trimIndent(),
        tools = setOf("tripStats", "trends"),
        digestSections = listOf("tripStats", "trends"),
        keywords = listOf(
            "расход", "топлив", "бензин", "дизел", "солярк", "трач", "затрат", "деньг", "стоимост", "литр", "л/100", "заправ",
            "fuel", "petrol", "gas\\b", "consumption", "mpg", "spend", "cost", "economy",
        ),
        status = "Экономист считает расход за %s…",
    )

    val SERVICE = ExpertRole(
        id = "service",
        title = "Мастер ТО",
        focus = "регламент обслуживания, что пора менять, пробег",
        prompt = """
            Ты — Мастер ТО. Смотришь план обслуживания: какие работы просрочены, какие скоро, сколько осталось по пробегу и по времени,
            насколько точен пробег (введён или оценён по поездкам). Не ставишь диагнозов по датчикам (см. Моторист).
        """.trimIndent(),
        tools = setOf("maintenanceDue", "carProfile"),
        digestSections = listOf("maintenanceDue"),
        keywords = listOf(
            "(пройти|пройду|следующее|очередное|плановое|ближайшее) то\\b", "то-?\\d", "техобслуж", "обслуж", "масл", "замен", "менять",
            "фильтр", "свеч", "ремен", "грм", "регламент", "интервал", "пора ",
            "maintenance", "service", "oil", "replace", "filter", "spark plug", "timing belt",
        ),
        status = "Мастер ТО смотрит план обслуживания…",
    )

    val MODEL_EXPERT = ExpertRole(
        id = "model_expert",
        title = "Знаток модели",
        focus = "особенности этой марки и двигателя, типичные слабые места",
        prompt = """
            Ты — Знаток модели. По профилю машины (марка, модель, год, двигатель) рассказываешь об особенностях и типичных слабых местах
            этой модели и двигателя. Это общие знания, а не данные пользователя: каждое такое утверждение помечай
            «по общим сведениям, не по вашим данным». Не выдумывай пробеги, цифры и коды пользователя.
        """.trimIndent(),
        tools = setOf("carProfile"),
        digestSections = emptyList(),
        keywords = listOf(
            "болячк", "слаб(ые|ое) мест", "типичн", "особенност", "надежн", "ресурс", "известн", "sorento", "соренто", "kia\\b", "киа\\b",
            "hyundai", "хендай", "хёндай", "g6cu", "common problems", "known (issues|problems)", "weak (spots|points)", "reliab",
        ),
        status = "Знаток модели вспоминает особенности модели…",
    )

    val ALL: List<ExpertRole> = listOf(DIAGNOSTICIAN, ENGINE, COOLING, ELECTRICAL, USAGE, FUEL, SERVICE, MODEL_EXPERT)

    fun byId(id: String): ExpertRole? = ALL.firstOrNull { it.id == id }

    /** «за 6 месяцев», «за месяц»… — для статусов и промптов. */
    fun periodText(days: Int): String = when {
        days == 7 -> "неделю"
        days == 30 -> "месяц"
        days == 365 -> "год"
        days % 30 == 0 && days in 60..330 -> "${days / 30} ${months(days / 30)}"
        else -> "$days дн."
    }

    private fun months(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "месяц"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "месяца"
        else -> "месяцев"
    }
}
