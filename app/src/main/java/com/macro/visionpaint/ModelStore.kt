package com.macro.visionpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Um modelo 3D de referência e as regras dele.
 * [up] = qual eixo do arquivo é "para cima" ([UpAxis]); [fx], [fy], [fz] = ponto de mira dentro da caixa
 * do modelo (frações de 0 a 1 de cada eixo, já com o "para cima" aplicado); [radius] = raio da área de
 * mira, em unidades do modelo (o maior lado do modelo = 1); [color] nulo = cor padrão;
 * [drag] = o dedo deve arrastar até este alvo.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val ext: String,
    val up: Int,
    val fx: Float,
    val fy: Float,
    val fz: Float,
    val radius: Float,
    val color: Int?,
    val drag: Boolean
)

/**
 * Guarda os modelos 3D (o arquivo original, as vistas já calculadas e uma miniatura) e as regras
 * de cada um, na ordem da lista. O arquivo original fica na pasta interna do app: se o usuário apagar
 * o original do celular, o modelo continua aqui.
 */
object ModelStore {
    private const val PREFS = "models_meta"
    private const val KEY = "list"

    /** Muda quando o jeito de gerar as vistas muda (o cache antigo deixa de valer). */
    private const val BANK_VERSION = 1

    const val DEFAULT_RADIUS = 0.07f
    const val MIN_RADIUS = 0.01f
    const val MAX_RADIUS = 0.30f

    private class State(val list: List<ModelInfo>) {
        val byId: Map<String, ModelInfo> = list.associateBy { it.id }
        val order: Map<String, Int> = list.mapIndexed { i, t -> t.id to i }.toMap()
    }

    private lateinit var appCtx: Context

    @Volatile
    private var state = State(emptyList())

    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        reload()
    }

    private fun dir(): File = File(appCtx.filesDir, "models").apply { mkdirs() }

    fun file(info: ModelInfo): File = File(dir(), "${info.id}.${info.ext}")

    private fun bankFile(id: String): File = File(dir(), "$id.bank")

    private fun thumbFile(id: String): File = File(dir(), "$id.png")

    fun list(): List<ModelInfo> = state.list

    fun count(): Int = state.list.size

    fun info(id: String): ModelInfo? = state.byId[id]

    /** Posição do modelo na lista (0 = primeiro). */
    fun orderOf(id: String): Int = state.order[id] ?: Int.MAX_VALUE

    /** Miniatura salva (a imagem que a tela de edição gerou) ou null se ainda não existe. */
    fun thumb(id: String): Bitmap? {
        val f = thumbFile(id)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    fun saveThumb(id: String, bmp: Bitmap) {
        try {
            FileOutputStream(thumbFile(id)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            // Sem miniatura não é erro: a lista mostra um ícone no lugar.
        }
    }

    @Synchronized
    private fun reload() {
        val saved = ArrayList<ModelInfo>()
        try {
            val json = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                saved.add(
                    ModelInfo(
                        id = o.getString("id"),
                        name = o.optString("name", "modelo"),
                        ext = o.optString("ext", "glb"),
                        up = o.optInt("up", UpAxis.Y),
                        fx = o.optDouble("fx", 0.5).toFloat(),
                        fy = o.optDouble("fy", 0.5).toFloat(),
                        fz = o.optDouble("fz", 0.5).toFloat(),
                        radius = o.optDouble("radius", DEFAULT_RADIUS.toDouble()).toFloat(),
                        color = if (o.has("color")) o.getInt("color") else null,
                        drag = o.optBoolean("drag", true)
                    )
                )
            }
        } catch (e: Exception) {
            // Sem dados salvos ou corrompido: lista vazia.
        }
        // Só fica o que ainda tem o arquivo do modelo.
        commit(saved.filter { file(it).exists() })
    }

    private fun commit(list: List<ModelInfo>) {
        state = State(list)
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject()
            o.put("id", m.id)
            o.put("name", m.name)
            o.put("ext", m.ext)
            o.put("up", m.up)
            o.put("fx", m.fx.toDouble())
            o.put("fy", m.fy.toDouble())
            o.put("fz", m.fz.toDouble())
            o.put("radius", m.radius.toDouble())
            val c = m.color
            if (c != null) o.put("color", c)
            o.put("drag", m.drag)
            arr.put(o)
        }
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
            // Cai para o fim do endereço.
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "modelo"
    }

    /**
     * Copia o modelo escolhido pelo usuário para a pasta interna do app, confere que dá para ler
     * (já com o "para cima" padrão) e o põe na lista. Lança [ModelFormatException] com mensagem
     * pronta para mostrar se não der.
     */
    @Synchronized
    fun addFromUri(ctx: Context, uri: Uri): ModelInfo {
        val name = displayName(ctx, uri)
        val dot = name.lastIndexOf('.')
        val ext = (if (dot >= 0) name.substring(dot + 1) else "").lowercase().filter { it.isLetterOrDigit() }.take(6)
            .ifEmpty { "bin" }
        val base = if (dot > 0) name.substring(0, dot) else name

        val read: ByteArray? = try {
            ctx.contentResolver.openInputStream(uri)?.use { readLimited(it) }
        } catch (e: ModelFormatException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val bytes = read ?: throw ModelFormatException("Não consegui abrir o arquivo.")

        val id = "mdl_${System.currentTimeMillis()}_${(0..9999).random()}"
        val up = if (ext == "stl") UpAxis.Z else UpAxis.Y
        val info = ModelInfo(id, base, ext, up, 0.5f, 0.5f, 0.5f, DEFAULT_RADIUS, null, true)

        // Confere antes de guardar: se não lê, o usuário fica sabendo agora e não só ao iniciar.
        val mesh = ModelLoader.load(bytes, "$base.$ext", textureDecoder())
        ModelPrep.prepare(mesh, up)

        FileOutputStream(file(info)).use { it.write(bytes) }
        commit(state.list + info)
        return info
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(1 shl 20)
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > ModelLoader.MAX_BYTES) throw ModelFormatException("Arquivo grande demais (máximo 120 MB).")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Decodifica as texturas do .glb/.gltf com no máximo 1024 px de lado (economiza memória). */
    private fun textureDecoder(): (ByteArray) -> TexImage? = { data ->
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            if (bmp == null) {
                null
            } else {
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val t = TexImage(bmp.width, bmp.height, px)
                bmp.recycle()
                t
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Lê o arquivo do modelo e o deixa pronto para desenhar (com o "para cima" já aplicado). */
    fun loadModel(info: ModelInfo): Model3D {
        val bytes = FileInputStream(file(info)).use { readLimited(it) }
        val mesh = ModelLoader.load(bytes, "${info.name}.${info.ext}", textureDecoder())
        return ModelPrep.prepare(mesh, info.up)
    }

    private fun bankKey(m: ModelInfo): String =
        "v$BANK_VERSION|up${m.up}|%.4f|%.4f|%.4f|r%.4f".format(java.util.Locale.US, m.fx, m.fy, m.fz, m.radius)

    /**
     * As vistas do modelo: lê do cache se ele foi feito com o mesmo ponto de mira, raio e eixo;
     * senão gera (alguns segundos para modelos pesados) e guarda.
     */
    fun loadBank(info: ModelInfo): ViewBank {
        val key = bankKey(info)
        val bf = bankFile(info.id)
        if (bf.exists()) {
            val cached = try {
                FileInputStream(bf).use { BankIO.read(key, it) }
            } catch (e: Exception) {
                null
            }
            if (cached != null) return cached
        }
        val model = loadModel(info)
        val bank = BankBuilder.build(model, model.fromFractions(info.fx, info.fy, info.fz), info.radius)
        try {
            FileOutputStream(bf).use { BankIO.write(bank, key, it) }
        } catch (e: Exception) {
            bf.delete()
        }
        return bank
    }

    @Synchronized
    fun update(id: String, change: (ModelInfo) -> ModelInfo) {
        commit(state.list.map { if (it.id == id) change(it) else it })
    }

    /** Guarda o ponto de mira escolhido (frações da caixa) e o raio. */
    fun setAim(id: String, fx: Float, fy: Float, fz: Float, radius: Float) = update(id) {
        it.copy(
            fx = fx.coerceIn(0f, 1f), fy = fy.coerceIn(0f, 1f), fz = fz.coerceIn(0f, 1f),
            radius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)
        )
    }

    fun setUp(id: String, up: Int) = update(id) { it.copy(up = up) }

    fun setName(id: String, name: String) = update(id) { it.copy(name = name.ifBlank { it.name }) }

    fun setColor(id: String, color: Int?) = update(id) { it.copy(color = color) }

    fun setDrag(id: String, drag: Boolean) = update(id) { it.copy(drag = drag) }

    /** Move o modelo na lista: delta = -1 sobe, +1 desce. */
    @Synchronized
    fun move(id: String, delta: Int) {
        val l = state.list.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= l.size) return
        val t = l[i]
        l[i] = l[j]
        l[j] = t
        commit(l)
    }

    @Synchronized
    fun remove(id: String) {
        val m = state.byId[id]
        if (m != null) file(m).delete()
        bankFile(id).delete()
        thumbFile(id).delete()
        commit(state.list.filter { it.id != id })
    }

    @Synchronized
    fun clear() {
        for (m in state.list) {
            file(m).delete()
            bankFile(m.id).delete()
            thumbFile(m.id).delete()
        }
        commit(emptyList())
    }
}
