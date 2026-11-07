package com.macro.visionpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Guarda as imagens de referência (os "modelos" que o app procura na tela). */
object TemplateStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "templates").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun count(ctx: Context): Int = list(ctx).size

    /** Copia uma imagem escolhida pelo usuário para a pasta interna do app. */
    fun addFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return false
            val name = "tpl_${System.currentTimeMillis()}_${(0..9999).random()}.png"
            FileOutputStream(File(dir(ctx), name)).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clear(ctx: Context) {
        list(ctx).forEach { it.delete() }
    }
}
