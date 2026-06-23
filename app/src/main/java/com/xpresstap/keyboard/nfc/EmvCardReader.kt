package com.xpresstap.keyboard.nfc

import android.nfc.tech.IsoDep

object EmvCardReader {

    data class CardData(
        val pan: String,
        val expiry: String,
        val last4: String,
        val cardKey: String
    )

    private val PPSE_AID = byteArrayOf(
        0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53,
        0x2E, 0x44, 0x44, 0x46, 0x30, 0x31
    )

    fun read(isoDep: IsoDep): CardData? {
        return try {
            isoDep.connect()
            isoDep.timeout = 5000

            val ppseResponse = isoDep.transceive(buildSelectApdu(PPSE_AID))
            if (!isSuccess(ppseResponse)) return null

            val aid = parseAidFromPpse(ppseResponse) ?: return null
            val appResponse = isoDep.transceive(buildSelectApdu(aid))
            if (!isSuccess(appResponse)) return null

            isoDep.transceive(buildGpo())

            var pan: String? = null
            var expiry: String? = null

            outer@ for (sfi in 1..3) {
                for (record in 1..8) {
                    val resp = isoDep.transceive(buildReadRecord(sfi, record))
                    if (!isSuccess(resp)) continue
                    val extracted = extractPanAndExpiry(resp)
                    if (extracted.first != null) pan = extracted.first
                    if (extracted.second != null) expiry = extracted.second
                    if (pan != null && expiry != null) break@outer
                }
            }

            if (pan == null || expiry == null) return null

            val last4 = pan.takeLast(4)
            CardData(pan = pan, expiry = expiry, last4 = last4, cardKey = "${last4}_${expiry}")
        } catch (e: Exception) {
            null
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun buildSelectApdu(aid: ByteArray): ByteArray {
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
    }

    private fun buildGpo(): ByteArray {
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00)
    }

    private fun buildReadRecord(sfi: Int, record: Int): ByteArray {
        val p2 = ((sfi shl 3) or 4).toByte()
        return byteArrayOf(0x00, 0xB2.toByte(), record.toByte(), p2, 0x00)
    }

    private fun isSuccess(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return sw1 == 0x90 && sw2 == 0x00
    }

    private fun parseAidFromPpse(response: ByteArray): ByteArray? {
        return findTlvTag(response, 0x4F)
    }

    private fun extractPanAndExpiry(response: ByteArray): Pair<String?, String?> {
        val panBytes = findTlvTag(response, 0x5A)
        val expiryBytes = findTlvTag2(response, 0x5F, 0x24)

        val pan = panBytes?.let { bytes ->
            bytes.joinToString("") { "%02X".format(it) }
                .trimEnd('F', 'f')
                .filter { it.isDigit() }
        }

        val expiry = expiryBytes?.let { bytes ->
            if (bytes.size >= 2) {
                val yy = "%02X".format(bytes[0])
                val mm = "%02X".format(bytes[1])
                "${mm}${yy}"
            } else null
        }

        return Pair(pan, expiry)
    }

    private fun findTlvTag(data: ByteArray, tag: Int): ByteArray? {
        var i = 0
        while (i < data.size - 1) {
            val currentTag = data[i].toInt() and 0xFF
            i++
            if (i >= data.size) break
            val length = data[i].toInt() and 0xFF
            i++
            if (i + length > data.size) break
            if (currentTag == tag) return data.copyOfRange(i, i + length)
            i += length
        }
        return null
    }

    private fun findTlvTag2(data: ByteArray, tag1: Int, tag2: Int): ByteArray? {
        var i = 0
        while (i < data.size - 2) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = data[i + 1].toInt() and 0xFF
            if (b1 == tag1 && b2 == tag2) {
                i += 2
                if (i >= data.size) break
                val length = data[i].toInt() and 0xFF
                i++
                if (i + length > data.size) break
                return data.copyOfRange(i, i + length)
            }
            i++
        }
        return null
    }
}
