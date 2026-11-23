package com.macro.visionpaint

/**
 * Leitor de JSON mínimo (sem Android) para o cabeçalho dos arquivos glTF/GLB.
 * Devolve Map<String, Any?>, List<Any?>, String, Double, Boolean ou null.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw IllegalArgumentException("JSON: texto sobrando na posição ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("JSON: fim inesperado")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || (c in '0'..'9')) num() else
                    throw IllegalArgumentException("JSON: caractere inesperado '$c' na posição $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("JSON: valor inválido na posição $i")
            i += word.length
            return v
        }

        private fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDouble()
        }

        private fun str(): String {
            i++ // aspas de abertura
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e) // \" \\ \/
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("JSON: texto sem fim")
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value())
                skipWs()
                val c = s[i++]
                if (c == ']') return out
                if (c != ',') throw IllegalArgumentException("JSON: esperava , ou ] na posição ${i - 1}")
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                if (s[i++] != ':') throw IllegalArgumentException("JSON: esperava : na posição ${i - 1}")
                out[k] = value()
                skipWs()
                val c = s[i++]
                if (c == '}') return out
                if (c != ',') throw IllegalArgumentException("JSON: esperava , ou } na posição ${i - 1}")
            }
        }
    }
}

/** Atalhos para ler o JSON do glTF sem repetir conversões. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asList(): List<Any?>? = this as? List<Any?>

internal fun Any?.asInt(): Int? = (this as? Number)?.toInt()

internal fun Any?.asFloat(): Float? = (this as? Number)?.toFloat()
