package com.bigmoe.onedge.tools

import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------------------
// Pure helpers (no Android): unit-tested on the JVM.
// ---------------------------------------------------------------------------------------------------------

class CalcException(message: String) : Exception(message)

/** Exact arithmetic is not the goal; a reliable calculator is, because small models are bad at maths. */
object Calculator {
    private const val MAX_LENGTH = 500
    private const val MAX_DEPTH = 60

    fun evaluate(expression: String): Double {
        val text = expression.trim()
            .replace('×', '*').replace('÷', '/').replace('−', '-').replace("**", "^")
        if (text.isEmpty()) throw CalcException("the expression is empty")
        if (text.length > MAX_LENGTH) throw CalcException("the expression is too long")
        val p = Parser(text)
        val v = p.parseExpression(0)
        p.skipSpaces()
        if (!p.atEnd()) throw CalcException("unexpected \"${p.rest().take(10)}\" in the expression")
        return v
    }

    /** 12 significant digits, no trailing zeros, whole numbers without a decimal point. */
    fun format(v: Double): String {
        if (v.isNaN()) throw CalcException("the result is not a number")
        if (v.isInfinite()) throw CalcException("the result is too large")
        if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
        val bd = BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
        return if (abs(v) >= 1e15 || abs(v) < 1e-6) bd.toString() else bd.toPlainString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length
        fun rest() = s.substring(i)
        fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun peek(): Char { skipSpaces(); return if (i < s.length) s[i] else '\u0000' }

        fun parseExpression(depth: Int): Double {
            if (depth > MAX_DEPTH) throw CalcException("the expression is nested too deeply")
            var v = parseTerm(depth)
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += parseTerm(depth) }
                    '-' -> { i++; v -= parseTerm(depth) }
                    else -> return v
                }
            }
        }

        private fun parseTerm(depth: Int): Double {
            var v = parseUnary(depth)
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= parseUnary(depth) }
                    '/' -> { i++; val d = parseUnary(depth); if (d == 0.0) throw CalcException("division by zero"); v /= d }
                    '%' -> { i++; val d = parseUnary(depth); if (d == 0.0) throw CalcException("division by zero"); v %= d }
                    else -> return v
                }
            }
        }

        private fun parseUnary(depth: Int): Double = when (peek()) {
            '-' -> { i++; -parseUnary(depth + 1) }
            '+' -> { i++; parseUnary(depth + 1) }
            else -> parsePower(depth)
        }

        private fun parsePower(depth: Int): Double {
            val base = parsePrimary(depth)
            if (peek() == '^') {
                i++
                return Math.pow(base, parseUnary(depth + 1)) // right associative: 2^3^2 = 2^(3^2)
            }
            return base
        }

        private fun parsePrimary(depth: Int): Double {
            val c = peek()
            when {
                c == '(' -> {
                    i++
                    val v = parseExpression(depth + 1)
                    if (peek() != ')') throw CalcException("a closing parenthesis is missing")
                    i++
                    return v
                }
                c.isDigit() || c == '.' -> return parseNumber()
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val name = s.substring(start, i).lowercase()
                    if (peek() == '(') {
                        i++
                        val args = ArrayList<Double>()
                        if (peek() == ')') { i++ } else {
                            while (true) {
                                args.add(parseExpression(depth + 1))
                                val n = peek()
                                if (n == ',') { i++; continue }
                                if (n == ')') { i++; break }
                                throw CalcException("a closing parenthesis is missing after $name(")
                            }
                        }
                        return call(name, args)
                    }
                    return when (name) {
                        "pi" -> Math.PI
                        "e" -> Math.E
                        else -> throw CalcException("unknown name \"$name\"")
                    }
                }
                c == '\u0000' -> throw CalcException("the expression ends too early")
                else -> throw CalcException("unexpected \"$c\" in the expression")
            }
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                if (j < s.length && s[j].isDigit()) {
                    while (j < s.length && s[j].isDigit()) j++
                    i = j
                }
            }
            return s.substring(start, i).toDoubleOrNull() ?: throw CalcException("\"${s.substring(start, i)}\" is not a number")
        }

        private fun call(name: String, a: List<Double>): Double {
            fun need(n: Int) { if (a.size != n) throw CalcException("$name() needs $n argument(s)") }
            return when (name) {
                "sqrt" -> { need(1); if (a[0] < 0) throw CalcException("sqrt of a negative number"); Math.sqrt(a[0]) }
                "cbrt" -> { need(1); Math.cbrt(a[0]) }
                "abs" -> { need(1); abs(a[0]) }
                "sin" -> { need(1); Math.sin(a[0]) }
                "cos" -> { need(1); Math.cos(a[0]) }
                "tan" -> { need(1); Math.tan(a[0]) }
                "asin" -> { need(1); Math.asin(a[0]) }
                "acos" -> { need(1); Math.acos(a[0]) }
                "atan" -> { need(1); Math.atan(a[0]) }
                "ln" -> { need(1); if (a[0] <= 0) throw CalcException("ln of a number <= 0"); Math.log(a[0]) }
                "log" -> { need(1); if (a[0] <= 0) throw CalcException("log of a number <= 0"); Math.log10(a[0]) }
                "log2" -> { need(1); if (a[0] <= 0) throw CalcException("log2 of a number <= 0"); Math.log(a[0]) / Math.log(2.0) }
                "exp" -> { need(1); Math.exp(a[0]) }
                "floor" -> { need(1); Math.floor(a[0]) }
                "ceil" -> { need(1); Math.ceil(a[0]) }
                "round" -> { need(1); Math.floor(a[0] + 0.5) }
                "pow" -> { need(2); Math.pow(a[0], a[1]) }
                "min" -> { if (a.isEmpty()) throw CalcException("min() needs arguments"); a.reduce { x, y -> Math.min(x, y) } }
                "max" -> { if (a.isEmpty()) throw CalcException("max() needs arguments"); a.reduce { x, y -> Math.max(x, y) } }
                else -> throw CalcException("unknown function \"$name\"")
            }
        }
    }
}

/** Length, mass, volume, time, data size, speed, area and temperature. */
object UnitConverter {
    private enum class Kind { LENGTH, MASS, VOLUME, TIME, DATA, SPEED, AREA, TEMPERATURE }

    private class U(val kind: Kind, val factor: Double, val label: String)

    private val units = LinkedHashMap<String, U>()

    private fun def(kind: Kind, label: String, factor: Double, vararg names: String) {
        val u = U(kind, factor, label)
        units[label.lowercase()] = u
        for (n in names) units[n.lowercase()] = u
    }

    init {
        // length, base metre
        def(Kind.LENGTH, "mm", 0.001, "millimeter", "millimeters", "millimetre", "millimetres")
        def(Kind.LENGTH, "cm", 0.01, "centimeter", "centimeters", "centimetre", "centimetres")
        def(Kind.LENGTH, "m", 1.0, "meter", "meters", "metre", "metres")
        def(Kind.LENGTH, "km", 1000.0, "kilometer", "kilometers", "kilometre", "kilometres")
        def(Kind.LENGTH, "in", 0.0254, "inch", "inches")
        def(Kind.LENGTH, "ft", 0.3048, "foot", "feet")
        def(Kind.LENGTH, "yd", 0.9144, "yard", "yards")
        def(Kind.LENGTH, "mi", 1609.344, "mile", "miles")
        def(Kind.LENGTH, "nmi", 1852.0, "nautical mile", "nautical miles")
        // mass, base kilogram
        def(Kind.MASS, "mg", 1e-6, "milligram", "milligrams")
        def(Kind.MASS, "g", 0.001, "gram", "grams")
        def(Kind.MASS, "kg", 1.0, "kilogram", "kilograms", "kilo", "kilos")
        def(Kind.MASS, "t", 1000.0, "tonne", "tonnes", "metric ton", "metric tons")
        def(Kind.MASS, "oz", 0.028349523125, "ounce", "ounces")
        def(Kind.MASS, "lb", 0.45359237, "lbs", "pound", "pounds")
        def(Kind.MASS, "st", 6.35029318, "stone", "stones")
        // volume, base litre (US customary)
        def(Kind.VOLUME, "ml", 0.001, "milliliter", "milliliters", "millilitre", "millilitres")
        def(Kind.VOLUME, "l", 1.0, "liter", "liters", "litre", "litres")
        def(Kind.VOLUME, "m3", 1000.0, "cubic meter", "cubic meters")
        def(Kind.VOLUME, "tsp", 0.00492892159375, "teaspoon", "teaspoons")
        def(Kind.VOLUME, "tbsp", 0.01478676478125, "tablespoon", "tablespoons")
        def(Kind.VOLUME, "floz", 0.0295735295625, "fl oz", "fluid ounce", "fluid ounces")
        def(Kind.VOLUME, "cup", 0.2365882365, "cups")
        def(Kind.VOLUME, "pt", 0.473176473, "pint", "pints")
        def(Kind.VOLUME, "qt", 0.946352946, "quart", "quarts")
        def(Kind.VOLUME, "gal", 3.785411784, "gallon", "gallons")
        // time, base second
        def(Kind.TIME, "ms", 0.001, "millisecond", "milliseconds")
        def(Kind.TIME, "s", 1.0, "sec", "second", "seconds")
        def(Kind.TIME, "min", 60.0, "minute", "minutes")
        def(Kind.TIME, "h", 3600.0, "hr", "hour", "hours")
        def(Kind.TIME, "day", 86400.0, "d", "days")
        def(Kind.TIME, "week", 604800.0, "w", "weeks")
        // data, base byte (kB = 1000, KiB = 1024)
        def(Kind.DATA, "bit", 0.125, "bits")
        def(Kind.DATA, "byte", 1.0, "bytes", "b")
        def(Kind.DATA, "kb", 1e3, "kilobyte", "kilobytes")
        def(Kind.DATA, "mb", 1e6, "megabyte", "megabytes")
        def(Kind.DATA, "gb", 1e9, "gigabyte", "gigabytes")
        def(Kind.DATA, "tb", 1e12, "terabyte", "terabytes")
        def(Kind.DATA, "kib", 1024.0, "kibibyte", "kibibytes")
        def(Kind.DATA, "mib", 1024.0 * 1024, "mebibyte", "mebibytes")
        def(Kind.DATA, "gib", 1024.0 * 1024 * 1024, "gibibyte", "gibibytes")
        def(Kind.DATA, "tib", 1024.0 * 1024 * 1024 * 1024, "tebibyte", "tebibytes")
        // speed, base m/s
        def(Kind.SPEED, "m/s", 1.0, "mps")
        def(Kind.SPEED, "km/h", 1.0 / 3.6, "kph", "kmh", "kmph")
        def(Kind.SPEED, "mph", 0.44704, "mi/h")
        def(Kind.SPEED, "kn", 0.5144444444444445, "knot", "knots", "kt")
        // area, base m2
        def(Kind.AREA, "m2", 1.0, "sqm", "square meter", "square meters")
        def(Kind.AREA, "km2", 1e6, "sqkm", "square kilometer", "square kilometers")
        def(Kind.AREA, "cm2", 1e-4, "sqcm")
        def(Kind.AREA, "ha", 1e4, "hectare", "hectares")
        def(Kind.AREA, "acre", 4046.8564224, "acres")
        def(Kind.AREA, "ft2", 0.09290304, "sqft", "square foot", "square feet")
        def(Kind.AREA, "in2", 0.00064516, "sqin")
        def(Kind.AREA, "mi2", 2589988.110336, "sqmi")
        // temperature (handled specially)
        def(Kind.TEMPERATURE, "c", 1.0, "celsius", "°c", "degc")
        def(Kind.TEMPERATURE, "f", 1.0, "fahrenheit", "°f", "degf")
        def(Kind.TEMPERATURE, "k", 1.0, "kelvin")
    }

    private fun canonical(name: String): String =
        name.trim().lowercase().replace('²', '2').replace('³', '3').trimEnd('.').replace(Regex("\\s+"), " ")

    fun convert(value: Double, from: String, to: String): Double {
        val a = units[canonical(from)] ?: throw CalcException("unknown unit \"$from\"")
        val b = units[canonical(to)] ?: throw CalcException("unknown unit \"$to\"")
        if (a.kind != b.kind) throw CalcException("cannot convert ${a.label} (${a.kind.name.lowercase()}) to ${b.label} (${b.kind.name.lowercase()})")
        if (a.kind == Kind.TEMPERATURE) {
            val celsius = when (a.label) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                else -> value - 273.15
            }
            return when (b.label) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                else -> celsius + 273.15
            }
        }
        return value * a.factor / b.factor
    }

    fun label(name: String): String = units[canonical(name)]?.label ?: name

    fun supportedUnits(): String = "length (mm cm m km in ft yd mi), mass (mg g kg t oz lb), volume (ml l tsp tbsp cup pt qt gal), " +
        "time (ms s min h day week), data (bit byte kB MB GB TB KiB MiB GiB), speed (m/s km/h mph kn), area (m2 km2 ha acre ft2), temperature (C F K)"
}

// ---------------------------------------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------------------------------------

class GetDateTimeTool(private val clock: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val name = "get_datetime"
    override val category = ToolCategory.UTILITIES
    override val description = "Current date, time and weekday on this device (you have no clock of your own). Optional timezone such as Asia/Baghdad or Europe/London."
    override val parametersSchema = toolSchema(
        listOf(Triple("timezone", "string", "IANA time zone name (default: the device's)")),
        emptyList()
    )
    override fun describe(arguments: JSONObject) = "Checking the date and time"
    override suspend fun execute(arguments: JSONObject): String {
        val tz = arguments.optString("timezone", "").trim()
        val now = if (tz.isEmpty()) clock() else {
            val zone = try { ZoneId.of(tz) } catch (_: DateTimeException) { throw ToolInputException("unknown time zone \"$tz\" (use a name like Asia/Baghdad)") }
            clock().withZoneSameInstant(zone)
        }
        val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH))
        return "$day, $stamp (${now.zone.id}, UTC${now.offset.id.let { if (it == "Z") "+00:00" else it }})"
    }
}

class CalculateTool : Tool {
    override val name = "calculate"
    override val category = ToolCategory.UTILITIES
    override val description = "Evaluate a maths expression exactly instead of guessing. Supports + - * / % ^ ( ), pi, e and sqrt cbrt abs sin cos tan (radians) " +
        "asin acos atan ln log log2 exp floor ceil round min max pow."
    override val parametersSchema = toolSchema(listOf(Triple("expression", "string", "e.g. (12.5 * 8) / 3 + sqrt(144)")), listOf("expression"))
    override fun describe(arguments: JSONObject) = "Calculating ${arguments.optString("expression").take(40)}"
    override suspend fun execute(arguments: JSONObject): String {
        val expr = arguments.requireString("expression")
        return try {
            "$expr = ${Calculator.format(Calculator.evaluate(expr))}"
        } catch (e: CalcException) {
            throw ToolInputException(e.message ?: "could not calculate that")
        }
    }
}

class ConvertUnitsTool : Tool {
    override val name = "convert_units"
    override val category = ToolCategory.UTILITIES
    override val description = "Convert a value between units: ${UnitConverter.supportedUnits()}."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("value", "number", "The number to convert"),
            Triple("from", "string", "Unit it is in, e.g. km"),
            Triple("to", "string", "Unit wanted, e.g. mi")
        ),
        listOf("value", "from", "to")
    )
    override fun describe(arguments: JSONObject) = "Converting ${arguments.optString("from")} to ${arguments.optString("to")}"
    override suspend fun execute(arguments: JSONObject): String {
        val raw = arguments.requireString("value").trim()
        val value = raw.toDoubleOrNull() ?: throw ToolInputException("\"$raw\" is not a number")
        val from = arguments.requireString("from")
        val to = arguments.requireString("to")
        return try {
            val out = UnitConverter.convert(value, from, to)
            if (out.isNaN() || out.isInfinite()) throw CalcException("the result is too large")
            "${Calculator.format(value)} ${UnitConverter.label(from)} = ${formatSignificant(out)} ${UnitConverter.label(to)}"
        } catch (e: CalcException) {
            throw ToolInputException(e.message ?: "could not convert that")
        }
    }

    private fun formatSignificant(v: Double): String = Calculator.format(BigDecimal(v).round(MathContext(8)).toDouble())
}

class RandomNumberTool(private val random: java.util.Random = java.util.Random()) : Tool {
    override val name = "random_number"
    override val category = ToolCategory.UTILITIES
    override val description = "Random whole numbers (dice, raffles, picking an index). Defaults: 1 to 100, one number."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("min", "integer", "Lowest value (inclusive)"),
            Triple("max", "integer", "Highest value (inclusive)"),
            Triple("count", "integer", "How many numbers, 1-20")
        ),
        emptyList()
    )
    override fun describe(arguments: JSONObject) = "Rolling random numbers"
    override suspend fun execute(arguments: JSONObject): String {
        val lo = arguments.optIntLenient("min") ?: 1
        val hi = arguments.optIntLenient("max") ?: 100
        if (lo > hi) throw ToolInputException("min ($lo) is larger than max ($hi)")
        val count = (arguments.optIntLenient("count") ?: 1).coerceIn(1, 20)
        val span = hi.toLong() - lo.toLong() + 1
        val numbers = List(count) { lo + (random.nextDouble() * span).toLong() }
        return numbers.joinToString(", ")
    }
}

/** What the phone can say about itself; the Android implementation is [AndroidDeviceInfo]. */
fun interface DeviceInfoProvider {
    fun summary(): String
}

class DeviceInfoTool(private val provider: DeviceInfoProvider) : Tool {
    override val name = "device_info"
    override val category = ToolCategory.UTILITIES
    override val description = "Battery level and charging state, free storage, memory and Android version of this phone."
    override val parametersSchema = toolSchema(emptyList(), emptyList())
    override fun describe(arguments: JSONObject) = "Reading device status"
    override suspend fun execute(arguments: JSONObject): String = provider.summary()
}

fun registerUtilityTools(manager: ToolManager, deviceInfo: DeviceInfoProvider?) {
    manager.registerTool(GetDateTimeTool())
    manager.registerTool(CalculateTool())
    manager.registerTool(ConvertUnitsTool())
    manager.registerTool(RandomNumberTool())
    if (deviceInfo != null) manager.registerTool(DeviceInfoTool(deviceInfo))
}

val UTILITY_TOOL_NAMES = setOf("get_datetime", "calculate", "convert_units", "random_number", "device_info")
