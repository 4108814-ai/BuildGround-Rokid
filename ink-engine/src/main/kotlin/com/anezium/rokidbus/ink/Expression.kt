package com.anezium.rokidbus.ink

import org.json.JSONObject
import kotlin.math.abs

data class InkExpressionResult(
    val value: Any?,
    val readPaths: Set<String>,
    val problems: List<InkProblem>,
)

object InkExpressions {
    fun evaluate(expression: String, data: JSONObject): InkExpressionResult = try {
        val ast = ExpressionParser(expression).parse()
        val result = ExpressionEvaluator(data.toInkObject()).evaluate(ast)
        InkExpressionResult(result.value, result.dependencies.mapTo(linkedSetOf()) { it.toString() }, emptyList())
    } catch (error: ExpressionException) {
        InkExpressionResult(
            null,
            emptySet(),
            listOf(
                InkProblem(
                    error.code,
                    error.message ?: "Invalid expression",
                    line = 1,
                    column = error.offset + 1,
                    feature = expression,
                ),
            ),
        )
    }
}

internal sealed interface Expr {
    data class Literal(val value: Any?) : Expr
    data class Path(val root: String, val segments: List<PathSegment>) : Expr
    data class Unary(val operator: String, val operand: Expr) : Expr
    data class Binary(val operator: String, val left: Expr, val right: Expr) : Expr
    data class Ternary(val condition: Expr, val whenTrue: Expr, val whenFalse: Expr) : Expr
}

internal sealed interface PathSegment {
    data class Property(val name: String) : PathSegment
    data class Index(val expression: Expr) : PathSegment
}

internal class ExpressionException(
    val code: String,
    override val message: String,
    val offset: Int,
) : RuntimeException(message)

internal class ExpressionParser(private val source: String) {
    private val lexer = ExpressionLexer(source)
    private var token = lexer.next()

    fun parse(): Expr {
        val expression = parseTernary()
        if (token.kind != TokenKind.END) invalid("Unexpected '${token.text}'", token.offset)
        if (depth(expression) > MAX_AST_DEPTH) {
            throw ExpressionException(
                InkProblemCodes.EXPR_LIMIT,
                "Expression AST depth exceeds $MAX_AST_DEPTH",
                token.offset,
            )
        }
        return expression
    }

    private fun parseTernary(): Expr {
        val condition = parseOr()
        if (!accept("?")) return condition
        val whenTrue = parseTernary()
        expect(":")
        return Expr.Ternary(condition, whenTrue, parseTernary())
    }

    private fun parseOr(): Expr = parseLeftAssociative(::parseAnd, setOf("||"))
    private fun parseAnd(): Expr = parseLeftAssociative(::parseEquality, setOf("&&"))
    private fun parseEquality(): Expr = parseLeftAssociative(::parseComparison, setOf("==", "!=", "===", "!=="))
    private fun parseComparison(): Expr = parseLeftAssociative(::parseAdditive, setOf(">", ">=", "<", "<="))
    private fun parseAdditive(): Expr = parseLeftAssociative(::parseMultiplicative, setOf("+", "-"))
    private fun parseMultiplicative(): Expr = parseLeftAssociative(::parseUnary, setOf("*", "/", "%"))

    private fun parseLeftAssociative(next: () -> Expr, operators: Set<String>): Expr {
        var expression = next()
        while (token.text in operators) {
            val operator = token.text
            advance()
            expression = Expr.Binary(operator, expression, next())
        }
        return expression
    }

    private fun parseUnary(): Expr {
        if (token.text == "!" || token.text == "-") {
            val operator = token.text
            advance()
            return Expr.Unary(operator, parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        if (accept("(")) {
            val expression = parseTernary()
            expect(")")
            return expression
        }
        if (token.kind == TokenKind.NUMBER) {
            val value = token.text.toDoubleOrNull() ?: invalid("Invalid number", token.offset)
            advance()
            return Expr.Literal(value)
        }
        if (token.kind == TokenKind.STRING) {
            val value = token.text
            advance()
            return Expr.Literal(value)
        }
        if (token.kind != TokenKind.IDENTIFIER) invalid("Expected an expression", token.offset)
        val name = token.text
        advance()
        when (name) {
            "true" -> return Expr.Literal(true)
            "false" -> return Expr.Literal(false)
            "null" -> return Expr.Literal(null)
        }
        val segments = mutableListOf<PathSegment>()
        while (true) {
            when {
                accept(".") -> {
                    if (token.kind != TokenKind.IDENTIFIER) invalid("Expected a property name", token.offset)
                    segments += PathSegment.Property(token.text)
                    advance()
                }
                accept("[") -> {
                    val index = parseTernary()
                    expect("]")
                    segments += PathSegment.Index(index)
                }
                else -> return Expr.Path(name, segments)
            }
        }
    }

    private fun depth(expression: Expr): Int = when (expression) {
        is Expr.Literal -> 1
        is Expr.Path -> 1 + (expression.segments.mapNotNull { (it as? PathSegment.Index)?.expression }
            .maxOfOrNull(::depth) ?: 0)
        is Expr.Unary -> 1 + depth(expression.operand)
        is Expr.Binary -> 1 + maxOf(depth(expression.left), depth(expression.right))
        is Expr.Ternary -> 1 + maxOf(depth(expression.condition), depth(expression.whenTrue), depth(expression.whenFalse))
    }

    private fun accept(text: String): Boolean {
        if (token.text != text) return false
        advance()
        return true
    }

    private fun expect(text: String) {
        if (!accept(text)) invalid("Expected '$text'", token.offset)
    }

    private fun advance() {
        token = lexer.next()
    }

    private fun invalid(message: String, offset: Int): Nothing = throw ExpressionException(
        InkProblemCodes.EXPR_INVALID,
        message,
        offset,
    )

    private companion object {
        const val MAX_AST_DEPTH = 32
    }
}

private enum class TokenKind { NUMBER, STRING, IDENTIFIER, SYMBOL, END }

private data class Token(val kind: TokenKind, val text: String, val offset: Int)

private class ExpressionLexer(private val source: String) {
    private var index = 0

    fun next(): Token {
        while (index < source.length && source[index].isWhitespace()) index++
        if (index >= source.length) return Token(TokenKind.END, "", source.length)
        val start = index
        val char = source[index]
        if (char.isDigit() || (char == '.' && source.getOrNull(index + 1)?.isDigit() == true)) {
            if (char == '.') index++
            while (source.getOrNull(index)?.isDigit() == true) index++
            if (source.getOrNull(index) == '.') {
                index++
                while (source.getOrNull(index)?.isDigit() == true) index++
            }
            if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
                index++
                if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
                while (source.getOrNull(index)?.isDigit() == true) index++
            }
            return Token(TokenKind.NUMBER, source.substring(start, index), start)
        }
        if (char == '\'' || char == '"') return readString(char, start)
        if (char.isLetter() || char == '_' || char == '$') {
            index++
            while (source.getOrNull(index)?.let { it.isLetterOrDigit() || it == '_' || it == '$' } == true) index++
            return Token(TokenKind.IDENTIFIER, source.substring(start, index), start)
        }
        val operator = listOf("===", "!==", ">=", "<=", "==", "!=", "&&", "||")
            .firstOrNull { source.startsWith(it, index) }
        if (operator != null) {
            index += operator.length
            return Token(TokenKind.SYMBOL, operator, start)
        }
        if (char in "!+-*/%><()?:.[]") {
            index++
            return Token(TokenKind.SYMBOL, char.toString(), start)
        }
        throw ExpressionException(InkProblemCodes.EXPR_INVALID, "Unexpected '$char'", start)
    }

    private fun readString(quote: Char, start: Int): Token {
        index++
        val result = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            if (char == quote) return Token(TokenKind.STRING, result.toString(), start)
            if (char != '\\') {
                result.append(char)
                continue
            }
            if (index >= source.length) break
            when (val escaped = source[index++]) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'u' -> {
                    if (index + 4 > source.length) {
                        throw ExpressionException(InkProblemCodes.EXPR_INVALID, "Invalid Unicode escape", index - 2)
                    }
                    val code = source.substring(index, index + 4).toIntOrNull(16)
                        ?: throw ExpressionException(InkProblemCodes.EXPR_INVALID, "Invalid Unicode escape", index)
                    result.append(code.toChar())
                    index += 4
                }
                else -> result.append(escaped)
            }
        }
        throw ExpressionException(InkProblemCodes.EXPR_INVALID, "Unclosed string literal", start)
    }
}

internal data class DataPath(val segments: List<Any>) {
    fun child(segment: Any): DataPath = DataPath(segments + segment)

    fun intersects(other: DataPath): Boolean {
        val shared = minOf(segments.size, other.segments.size)
        return segments.take(shared) == other.segments.take(shared)
    }

    override fun toString(): String = buildString {
        segments.forEachIndexed { index, segment ->
            if (segment is Int) append("[$segment]") else {
                if (index > 0) append('.')
                append(segment)
            }
        }
    }
}

internal data class LocalValue(val value: Any?, val sourcePath: DataPath?)

internal data class EvaluationContext(
    val data: InkObject,
    val locals: Map<String, LocalValue> = emptyMap(),
)

internal data class EvaluationResult(val value: Any?, val dependencies: Set<DataPath>, val steps: Int)

internal class ExpressionEvaluator(private val rootData: InkObject) {
    private var steps = 0
    private val dependencies = linkedSetOf<DataPath>()

    fun evaluate(expression: Expr, context: EvaluationContext = EvaluationContext(rootData)): EvaluationResult {
        steps = 0
        dependencies.clear()
        val value = evaluateNode(expression, context)
        return EvaluationResult(value, dependencies.toSet(), steps)
    }

    private fun evaluateNode(expression: Expr, context: EvaluationContext): Any? {
        step()
        return when (expression) {
            is Expr.Literal -> expression.value
            is Expr.Path -> evaluatePath(expression, context)
            is Expr.Unary -> when (expression.operator) {
                "!" -> !truthy(evaluateNode(expression.operand, context))
                "-" -> -number(evaluateNode(expression.operand, context))
                else -> null
            }
            is Expr.Binary -> evaluateBinary(expression, context)
            is Expr.Ternary -> if (truthy(evaluateNode(expression.condition, context))) {
                evaluateNode(expression.whenTrue, context)
            } else {
                evaluateNode(expression.whenFalse, context)
            }
        }
    }

    private fun evaluatePath(expression: Expr.Path, context: EvaluationContext): Any? {
        val local = context.locals[expression.root]
        var value: Any? = local?.value ?: context.data[expression.root]
        var path = local?.sourcePath ?: DataPath(listOf(expression.root))
        expression.segments.forEach { segment ->
            step()
            val key = when (segment) {
                is PathSegment.Property -> segment.name
                is PathSegment.Index -> evaluateNode(segment.expression, context)
            }
            if (key == "__proto__" || key == "prototype" || key == "constructor") {
                value = null
                return@forEach
            }
            val normalizedKey = if (key is Number && key.toDouble() == key.toInt().toDouble()) key.toInt() else key?.toString()
            path = path.child(normalizedKey ?: "null")
            value = when {
                value is Map<*, *> -> value[normalizedKey.toString()]
                value is List<*> && normalizedKey is Int -> value.getOrNull(normalizedKey)
                else -> null
            }
        }
        dependencies += path
        return value
    }

    private fun evaluateBinary(expression: Expr.Binary, context: EvaluationContext): Any? {
        val left = evaluateNode(expression.left, context)
        if (expression.operator == "&&") return if (truthy(left)) evaluateNode(expression.right, context) else left
        if (expression.operator == "||") return if (truthy(left)) left else evaluateNode(expression.right, context)
        val right = evaluateNode(expression.right, context)
        return when (expression.operator) {
            "+" -> if (left is String || right is String) jsString(left) + jsString(right) else number(left) + number(right)
            "-" -> number(left) - number(right)
            "*" -> number(left) * number(right)
            "/" -> number(left) / number(right)
            "%" -> number(left) % number(right)
            ">" -> compare(left, right) > 0
            ">=" -> compare(left, right) >= 0
            "<" -> compare(left, right) < 0
            "<=" -> compare(left, right) <= 0
            "==" -> looseEquals(left, right)
            "!=" -> !looseEquals(left, right)
            "===" -> strictEquals(left, right)
            "!==" -> !strictEquals(left, right)
            else -> null
        }
    }

    private fun step() {
        steps++
        if (steps > MAX_STEPS) {
            throw ExpressionException(InkProblemCodes.EXPR_LIMIT, "Expression evaluation exceeds $MAX_STEPS steps", 0)
        }
    }

    private fun compare(left: Any?, right: Any?): Int = if (left is String && right is String) {
        left.compareTo(right)
    } else {
        number(left).compareTo(number(right))
    }

    private fun strictEquals(left: Any?, right: Any?): Boolean {
        if (left == null || right == null) return left == right
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        if (left::class != right::class) return false
        return left == right
    }

    private fun looseEquals(left: Any?, right: Any?): Boolean {
        if (left == null || right == null) return left == null && right == null
        if (left is Number || right is Number || left is Boolean || right is Boolean) {
            val a = number(left)
            val b = number(right)
            return !a.isNaN() && !b.isNaN() && a == b
        }
        return left == right
    }

    private fun number(value: Any?): Double = when (value) {
        null -> 0.0
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        is String -> if (value.isBlank()) 0.0 else value.toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    private fun jsString(value: Any?): String = when (value) {
        null -> "null"
        is Double -> if (value.isFinite() && abs(value - value.toLong()) < 1e-10) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    companion object {
        const val MAX_STEPS = 10_000

        fun truthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
            is String -> value.isNotEmpty()
            else -> true
        }
    }
}
