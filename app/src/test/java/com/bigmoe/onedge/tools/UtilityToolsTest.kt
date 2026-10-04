package com.bigmoe.onedge.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UtilityToolsTest {

    private fun calc(e: String) = Calculator.format(Calculator.evaluate(e))

    @Test
    fun calculatorFollowsPrecedence() {
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("512", calc("2^3^2"))
        assertEquals("0.125", calc("2^-3"))
        assertEquals("1", calc("7 % 3"))
        assertEquals("2.5", calc("5 / 2"))
    }

    @Test
    fun calculatorFunctionsAndConstants() {
        assertEquals("12", calc("sqrt(144)"))
        assertEquals("3.14159265359", calc("pi"))
        assertEquals("5", calc("max(1, 5, 3)"))
        assertEquals("1", calc("min(4, 1, 3)"))
        assertEquals("3", calc("round(2.5)"))
        assertEquals("1000", calc("10^3"))
        assertEquals("1000000", calc("1e6"))
    }

    @Test
    fun calculatorAcceptsUnicodeOperators() {
        assertEquals("6", calc("2 × 3"))
        assertEquals("4", calc("8 ÷ 2"))
    }

    @Test
    fun calculatorRejectsBadInput() {
        for (bad in listOf("", "1 / 0", "2 +", "(1 + 2", "foo(3)", "abc", "sqrt(-1)", "1 2")) {
            try {
                Calculator.evaluate(bad)
                fail("should have failed: $bad")
            } catch (_: CalcException) {
            }
        }
    }

    @Test
    fun calculatorToolReturnsTheExpressionAndResult() = runBlocking {
        val out = CalculateTool().execute(JSONObject().put("expression", "(12.5 * 8) / 3"))
        assertEquals("(12.5 * 8) / 3 = 33.3333333333", out)
        try {
            CalculateTool().execute(JSONObject().put("expression", "1/0"))
            fail()
        } catch (e: ToolInputException) {
            assertTrue(e.message!!.contains("zero"))
        }
    }

    @Test
    fun unitConversions() {
        assertEquals(1000.0, UnitConverter.convert(1.0, "km", "m"), 1e-9)
        assertEquals(1.609344, UnitConverter.convert(1.0, "mile", "km"), 1e-9)
        assertEquals(212.0, UnitConverter.convert(100.0, "C", "F"), 1e-9)
        assertEquals(273.15, UnitConverter.convert(0.0, "celsius", "kelvin"), 1e-9)
        assertEquals(1024.0, UnitConverter.convert(1.0, "KiB", "bytes"), 1e-9)
        assertEquals(1.0, UnitConverter.convert(3.6, "km/h", "m/s"), 1e-9)
    }

    @Test
    fun unitConversionRejectsMixedOrUnknownUnits() {
        try { UnitConverter.convert(1.0, "kg", "m"); fail() } catch (e: CalcException) { assertTrue(e.message!!.contains("cannot convert")) }
        try { UnitConverter.convert(1.0, "parsec-ish", "m"); fail() } catch (e: CalcException) { assertTrue(e.message!!.contains("unknown unit")) }
    }

    @Test
    fun convertToolFormatsTheAnswer() = runBlocking {
        val out = ConvertUnitsTool().execute(JSONObject().put("value", 5).put("from", "km").put("to", "mi"))
        assertEquals("5 km = 3.106856 mi", out)
    }

    @Test
    fun dateTimeUsesTheClockAndTimeZone() = runBlocking {
        val fixed = ZonedDateTime.of(2026, 10, 4, 12, 0, 0, 0, ZoneId.of("UTC"))
        val tool = GetDateTimeTool { fixed }
        assertEquals("Sunday, 2026-10-04 12:00:00 (UTC, UTC+00:00)", tool.execute(JSONObject()))
        assertTrue(tool.execute(JSONObject().put("timezone", "Asia/Baghdad")).startsWith("Sunday, 2026-10-04 15:00:00 (Asia/Baghdad"))
        try { tool.execute(JSONObject().put("timezone", "Mars/Olympus")); fail() } catch (e: ToolInputException) { assertTrue(e.message!!.contains("time zone")) }
    }

    @Test
    fun randomNumberStaysInRangeAndHonoursCount() = runBlocking {
        val tool = RandomNumberTool(java.util.Random(42))
        repeat(50) {
            val parts = tool.execute(JSONObject().put("min", 3).put("max", 6).put("count", 5)).split(", ")
            assertEquals(5, parts.size)
            assertTrue(parts.all { it.toInt() in 3..6 })
        }
        try { tool.execute(JSONObject().put("min", 9).put("max", 2)); fail() } catch (_: ToolInputException) {}
    }

    @Test
    fun utilityToolsRegisterAndAreNamedInTheSet() {
        val m = ToolManager()
        registerUtilityTools(m, DeviceInfoProvider { "Battery: 50%" })
        assertEquals(UTILITY_TOOL_NAMES, m.toolNames())
        val prompt = m.getToolsDescriptionPrompt(UTILITY_TOOL_NAMES)
        assertTrue(prompt.contains("calculate(expression)"))
        assertTrue(prompt.contains("get_datetime"))
        val withoutDevice = ToolManager().also { registerUtilityTools(it, null) }
        assertEquals(UTILITY_TOOL_NAMES - "device_info", withoutDevice.toolNames())
    }
}
