package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.round

internal const val TODAY_BRIEF_TOOL_NAME = "today_brief"
internal const val ENGINEERING_CALCULATOR_TOOL_NAME = "engineering_calculator"

internal fun assistantMvpTools(
    calendarGateway: AssistantCalendarGateway,
    reminderStore: AssistantReminderStore,
    epochClock: () -> Long = System::currentTimeMillis,
    zoneId: () -> ZoneId = { ZoneId.systemDefault() },
): List<AssistantToolDefinition> = listOf(
    TodayBriefTool(calendarGateway, reminderStore, epochClock, zoneId),
    EngineeringCalculatorTool(),
)

internal class TodayBriefTool(
    private val calendarGateway: AssistantCalendarGateway,
    private val reminderStore: AssistantReminderStore,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) : TextAssistantTool() {
    override val name = TODAY_BRIEF_TOOL_NAME
    override val description =
        "Give a compact brief for today from the phone calendar and pending Nexus reminders. " +
            "Use this when the user asks what is happening today, what is on the schedule today, " +
            "or asks for a morning/day brief. Do not invent tasks that are not returned by this tool."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{},"additionalProperties":false}""",
    )
    override val sideEffecting = false
    override val progressLabel = "Checking today…"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val parsed = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        return if (parsed.length() == 0) {
            AssistantToolValidation.Valid(parsed)
        } else {
            AssistantToolValidation.Invalid()
        }
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        val nowMillis = epochClock()
        val zone = zoneId()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val start = now.toLocalDate().atStartOfDay(zone)
        val end = start.plusDays(1)
        val startMillis = start.toInstant().toEpochMilli()
        val endMillis = end.toInstant().toEpochMilli()

        val events = if (calendarGateway.canReadCalendar()) {
            calendarGateway.instances(startMillis, endMillis, MAX_TODAY_EVENTS)
        } else {
            emptyList()
        }
        val reminders = reminderStore.pending()
            .filter { reminder -> reminder.epochMillis in startMillis until endMillis }
            .take(MAX_TODAY_REMINDERS)

        AssistantToolResult.Json(
            JSONObject()
                .put("date", now.toLocalDate().toString())
                .put("now", now.format(TODAY_TIME_FORMAT))
                .put("calendar_available", calendarGateway.canReadCalendar())
                .put(
                    "events",
                    JSONArray().apply {
                        events.forEach { event ->
                            val eventTime = Instant.ofEpochMilli(event.startMillis).atZone(zone)
                            put(
                                JSONObject()
                                    .put("title", event.title)
                                    .put(
                                        "time",
                                        if (event.allDay) "all day"
                                        else eventTime.format(TODAY_CLOCK_FORMAT),
                                    )
                                    .apply {
                                        if (!event.location.isNullOrBlank()) {
                                            put("location", event.location)
                                        }
                                    },
                            )
                        }
                    },
                )
                .put(
                    "reminders",
                    JSONArray().apply {
                        reminders.forEach { reminder ->
                            put(
                                JSONObject()
                                    .put("label", reminder.label)
                                    .put(
                                        "time",
                                        Instant.ofEpochMilli(reminder.epochMillis)
                                            .atZone(zone)
                                            .format(TODAY_CLOCK_FORMAT),
                                    )
                                    .put("kind", reminder.kind.wireValue),
                            )
                        }
                    },
                )
                .toString(),
        )
    }

    private companion object {
        const val MAX_TODAY_EVENTS = 50
        const val MAX_TODAY_REMINDERS = 50
        val TODAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm xxx",
            Locale.ENGLISH,
        )
        val TODAY_CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

internal class EngineeringCalculatorTool : TextAssistantTool() {
    override val name = ENGINEERING_CALCULATOR_TOOL_NAME
    override val description =
        "Deterministic construction/engineering calculator. Use it instead of mental arithmetic for " +
            "steel pipe mass, rebar mass, cylindrical pile/concrete volume, rectangular volume, " +
            "percentage adjustments, or productivity duration. Inputs are metric. Steel density " +
            "defaults to 7850 kg/m3. Return the exact tool result and keep the units."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"operation":{"type":"string","enum":["pipe_mass","rebar_mass","cylinder_volume","rectangular_volume","percent","duration"]},"outer_diameter_mm":{"type":["number","null"]},"wall_mm":{"type":["number","null"]},"diameter_mm":{"type":["number","null"]},"length_m":{"type":["number","null"]},"width_m":{"type":["number","null"]},"height_m":{"type":["number","null"]},"quantity":{"type":["number","null"]},"density_kg_m3":{"type":["number","null"]},"base_value":{"type":["number","null"]},"percent":{"type":["number","null"]},"productivity_per_shift":{"type":["number","null"]},"units_count":{"type":["number","null"]}},"required":["operation","outer_diameter_mm","wall_mm","diameter_mm","length_m","width_m","height_m","quantity","density_kg_m3","base_value","percent","productivity_per_shift","units_count"],"additionalProperties":false}""",
    )
    override val sideEffecting = false
    override val progressLabel = "Calculating…"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val parsed = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val allowed = setOf(
            "operation",
            "outer_diameter_mm",
            "wall_mm",
            "diameter_mm",
            "length_m",
            "width_m",
            "height_m",
            "quantity",
            "density_kg_m3",
            "base_value",
            "percent",
            "productivity_per_shift",
            "units_count",
        )
        val keys = parsed.keys()
        while (keys.hasNext()) {
            if (keys.next() !in allowed) return AssistantToolValidation.Invalid()
        }
        val operation = parsed.optString("operation")
        if (operation !in OPERATIONS) return AssistantToolValidation.Invalid()
        return AssistantToolValidation.Valid(parsed)
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.Default) {
        when (arguments.getString("operation")) {
            "pipe_mass" -> pipeMass(arguments)
            "rebar_mass" -> rebarMass(arguments)
            "cylinder_volume" -> cylinderVolume(arguments)
            "rectangular_volume" -> rectangularVolume(arguments)
            "percent" -> percent(arguments)
            "duration" -> duration(arguments)
            else -> AssistantToolResult.Error("engineering_calculator_failed")
        }
    }

    private fun pipeMass(args: JSONObject): AssistantToolResult {
        val d = args.positive("outer_diameter_mm") ?: return missing("outer_diameter_mm")
        val t = args.positive("wall_mm") ?: return missing("wall_mm")
        if (2.0 * t >= d) return invalid("wall_mm", "Wall thickness must be less than half the outer diameter.")
        val density = args.positiveOrDefault("density_kg_m3", DEFAULT_STEEL_DENSITY)
        val length = args.positiveOrDefault("length_m", 1.0)
        val quantity = args.positiveOrDefault("quantity", 1.0)
        val inner = d - 2.0 * t
        val areaM2 = PI / 4.0 * (d * d - inner * inner) / 1_000_000.0
        val kgPerM = areaM2 * density
        val totalKg = kgPerM * length * quantity
        return jsonResult(
            JSONObject()
                .put("operation", "pipe_mass")
                .put("kg_per_m", rounded(kgPerM))
                .put("total_kg", rounded(totalKg))
                .put("total_t", rounded(totalKg / 1000.0))
                .put("outer_diameter_mm", d)
                .put("wall_mm", t)
                .put("length_m", length)
                .put("quantity", quantity),
        )
    }

    private fun rebarMass(args: JSONObject): AssistantToolResult {
        val d = args.positive("diameter_mm") ?: return missing("diameter_mm")
        val density = args.positiveOrDefault("density_kg_m3", DEFAULT_STEEL_DENSITY)
        val length = args.positiveOrDefault("length_m", 1.0)
        val quantity = args.positiveOrDefault("quantity", 1.0)
        val areaM2 = PI / 4.0 * d * d / 1_000_000.0
        val kgPerM = areaM2 * density
        val totalKg = kgPerM * length * quantity
        return jsonResult(
            JSONObject()
                .put("operation", "rebar_mass")
                .put("kg_per_m", rounded(kgPerM))
                .put("total_kg", rounded(totalKg))
                .put("total_t", rounded(totalKg / 1000.0))
                .put("diameter_mm", d)
                .put("length_m", length)
                .put("quantity", quantity),
        )
    }

    private fun cylinderVolume(args: JSONObject): AssistantToolResult {
        val d = args.positive("diameter_mm") ?: return missing("diameter_mm")
        val length = args.positive("length_m") ?: return missing("length_m")
        val quantity = args.positiveOrDefault("quantity", 1.0)
        val radiusM = d / 2000.0
        val one = PI * radiusM * radiusM * length
        return jsonResult(
            JSONObject()
                .put("operation", "cylinder_volume")
                .put("volume_each_m3", rounded(one))
                .put("total_m3", rounded(one * quantity))
                .put("diameter_mm", d)
                .put("length_m", length)
                .put("quantity", quantity),
        )
    }

    private fun rectangularVolume(args: JSONObject): AssistantToolResult {
        val length = args.positive("length_m") ?: return missing("length_m")
        val width = args.positive("width_m") ?: return missing("width_m")
        val height = args.positive("height_m") ?: return missing("height_m")
        val quantity = args.positiveOrDefault("quantity", 1.0)
        val one = length * width * height
        return jsonResult(
            JSONObject()
                .put("operation", "rectangular_volume")
                .put("volume_each_m3", rounded(one))
                .put("total_m3", rounded(one * quantity))
                .put("quantity", quantity),
        )
    }

    private fun percent(args: JSONObject): AssistantToolResult {
        val base = args.finite("base_value") ?: return missing("base_value")
        val pct = args.finite("percent") ?: return missing("percent")
        val delta = base * pct / 100.0
        return jsonResult(
            JSONObject()
                .put("operation", "percent")
                .put("base_value", rounded(base))
                .put("percent", rounded(pct))
                .put("delta", rounded(delta))
                .put("result", rounded(base + delta)),
        )
    }

    private fun duration(args: JSONObject): AssistantToolResult {
        val quantity = args.positive("quantity") ?: return missing("quantity")
        val productivity = args.positive("productivity_per_shift")
            ?: return missing("productivity_per_shift")
        val units = args.positiveOrDefault("units_count", 1.0)
        val capacity = productivity * units
        val shifts = quantity / capacity
        return jsonResult(
            JSONObject()
                .put("operation", "duration")
                .put("quantity", rounded(quantity))
                .put("productivity_per_shift", rounded(productivity))
                .put("units_count", rounded(units))
                .put("shifts_exact", rounded(shifts))
                .put("shifts_rounded_up", kotlin.math.ceil(shifts).toLong()),
        )
    }

    private fun jsonResult(value: JSONObject): AssistantToolResult =
        AssistantToolResult.Json(value.toString())

    private fun missing(field: String): AssistantToolResult.Error = AssistantToolResult.Error(
        code = "engineering_input_required",
        detailsJson = JSONObject().put("field", field).toString(),
    )

    private fun invalid(field: String, message: String): AssistantToolResult.Error =
        AssistantToolResult.Error(
            code = "engineering_input_invalid",
            detailsJson = JSONObject().put("field", field).put("message", message).toString(),
        )

    private fun JSONObject.finite(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val value = optDouble(name, Double.NaN)
        return value.takeIf(Double::isFinite)
    }

    private fun JSONObject.positive(name: String): Double? = finite(name)?.takeIf { it > 0.0 }

    private fun JSONObject.positiveOrDefault(name: String, default: Double): Double =
        positive(name) ?: default

    private fun rounded(value: Double): Double = round(value * 1_000_000.0) / 1_000_000.0

    private companion object {
        val OPERATIONS = setOf(
            "pipe_mass",
            "rebar_mass",
            "cylinder_volume",
            "rectangular_volume",
            "percent",
            "duration",
        )
        const val DEFAULT_STEEL_DENSITY = 7850.0
    }
}
