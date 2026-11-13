package com.macro.visionpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Dados de uma imagem de referência.
 * [color] nulo = usa a cor padrão; [drag] = o dedo deve arrastar até esta imagem.
 */
data class Tpl(val id: String, val color: Int?, val drag: Boolean)

/**
 * Guarda as imagens de referência (os "modelos" que o app procura na tela)
 * e as regras de cada uma, na ordem da lista.
 */
object TemplateStore {
    private const val PREFS = "templates_meta"
    private const val KEY = "list"

    private class State(val list: List<Tpl>) {
        val byId: Map<String, Tpl> = list.associateBy { it.id }
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

    private fun dir(): File = File(appCtx.filesDir, "templates").apply { mkdirs() }

    fun file(id: String): File = File(dir(), id)

    /** Miniatura leve da imagem (lado de uns [maxPx] pixels, no máximo o dobro disso). */
    fun thumb(id: String, maxPx: Int = 128): Bitmap? {
        val path = file(id).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    fun list(): List<Tpl> = state.list

    fun count(): Int = state.list.size

    fun info(id: String): Tpl? = state.byId[id]

    /** Posição da imagem na lista (0 = primeira). */
    fun orderOf(id: String): Int = state.order[id] ?: Int.MAX_VALUE

    /** Junta o que está salvo com o que existe de fato na pasta (aproveita imagens da versão antiga). */
    @Synchronized
    private fun reload() {
        val saved = ArrayList<Tpl>()
        try {
            val json = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                saved.add(
                    Tpl(
                        o.getString("id"),
                        if (o.has("color")) o.getInt("color") else null,
                        o.optBoolean("drag", true)
                    )
                )
            }
        } catch (e: Exception) {
            // Sem dados salvos ou arquivo corrompido: começa do que existe na pasta.
        }

        val existing = dir().listFiles()?.filter { it.isFile }?.map { it.name }?.sorted()
            ?: emptyList()
        val result = ArrayList<Tpl>()
        for (t in saved) {
            if (t.id in existing && result.none { it.id == t.id }) result.add(t)
        }
        for (name in existing) {
            if (result.none { it.id == name }) result.add(Tpl(name, null, true))
        }
        commit(result)
    }

    private fun commit(list: List<Tpl>) {
        state = State(list)
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("id", t.id)
            val c = t.color
            if (c != null) o.put("color", c)
            o.put("drag", t.drag)
            arr.put(o)
        }
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Copia uma imagem escolhida pelo usuário para a pasta interna do app. */
    @Synchronized
    fun addFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return false
            val name = "tpl_${System.currentTimeMillis()}_${(0..9999).random()}.png"
            FileOutputStream(File(dir(), name)).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
            commit(state.list + Tpl(name, null, true))
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun setColor(id: String, color: Int?) = update(id) { it.copy(color = color) }

    @Synchronized
    fun setDrag(id: String, drag: Boolean) = update(id) { it.copy(drag = drag) }

    private fun update(id: String, change: (Tpl) -> Tpl) {
        commit(state.list.map { if (it.id == id) change(it) else it })
    }

    /** Move a imagem na lista: delta = -1 sobe, +1 desce. */
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
        file(id).delete()
        commit(state.list.filter { it.id != id })
    }

    @Synchronized
    fun clear() {
        state.list.forEach { file(it.id).delete() }
        commit(emptyList())
    }
}
