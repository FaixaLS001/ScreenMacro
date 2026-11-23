package com.macro.visionpaint

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Guarda e lê um [ViewBank] (sem Android), para não refazer as vistas de um modelo pesado toda vez
 * que o app inicia. [key] descreve com o que o banco foi feito (eixo "para cima", ponto de mira,
 * raio...): se a chave do arquivo for outra, o banco é refeito.
 */
object BankIO {
    private const val MAGIC = 0x56504231 // "VPB1"

    fun write(bank: ViewBank, key: String, out: OutputStream) {
        val d = DataOutputStream(out.buffered(1 shl 16))
        d.writeInt(MAGIC)
        d.writeUTF(key)
        d.writeInt(bank.yawCount)
        d.writeInt(bank.pitches.size)
        for (p in bank.pitches) d.writeFloat(p)
        d.writeFloat(bank.aimRadius)
        d.writeInt(bank.views.size)
        for (v in bank.views) {
            d.writeFloat(v.yaw)
            d.writeFloat(v.pitch)
            d.writeFloat(v.aimU)
            d.writeFloat(v.aimV)
            d.writeFloat(v.minU)
            d.writeFloat(v.maxU)
            d.writeFloat(v.minV)
            d.writeFloat(v.maxV)
            d.writeInt(v.pts.size)
            for (f in v.pts) d.writeFloat(f)
        }
        d.flush()
    }

    /** Devolve o banco, ou null se o arquivo é de outra versão, tem outra [key] ou está corrompido. */
    fun read(key: String, input: InputStream): ViewBank? {
        return try {
            val d = DataInputStream(input.buffered(1 shl 16))
            if (d.readInt() != MAGIC) return null
            if (d.readUTF() != key) return null
            val yawCount = d.readInt()
            val np = d.readInt()
            if (yawCount < 4 || yawCount > 360 || np < 1 || np > 16) return null
            val pitches = FloatArray(np) { d.readFloat() }
            val aimR = d.readFloat()
            val nv = d.readInt()
            if (nv != yawCount * np) return null
            val views = ArrayList<BankView>(nv)
            for (j in 0 until nv) {
                val yaw = d.readFloat()
                val pitch = d.readFloat()
                val au = d.readFloat()
                val av = d.readFloat()
                val minU = d.readFloat()
                val maxU = d.readFloat()
                val minV = d.readFloat()
                val maxV = d.readFloat()
                val n = d.readInt()
                if (n < 0 || n % 5 != 0 || n > 5 * 400_000) return null
                val pts = FloatArray(n)
                for (i in 0 until n) pts[i] = d.readFloat()
                views.add(BankView(yaw, pitch, pts, au, av, minU, maxU, minV, maxV))
            }
            ViewBank(yawCount, pitches, views.toTypedArray(), aimR)
        } catch (e: Exception) {
            null
        }
    }
}
