package com.xpresstap.keyboard.nfc

import android.nfc.tech.IsoDep

object EmvCardReader {

    data class CardData(
        val pan: String,
        val expiry: String,
        val last4: String,
        val cardKey: String
    )

    fun read(isoDep: IsoDep): CardData? {
        return try {
            isoDep.connect()
            isoDep.timeout = 5000
            readCard(isoDep)
        } catch (_: Exception) {
            null
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun readCard(isoDep: IsoDep): CardData? {
        // Try PPSE first (contactless), fall back to PSE (contact)
        val aids = selectPaymentEnvironment(isoDep, ppse = true)
            ?: selectPaymentEnvironment(isoDep, ppse = false)
            ?: return null

        for (aid in aids) {
            val result = readWithAid(isoDep, aid)
            if (result != null) return result
        }
        return null
    }

    private fun selectPaymentEnvironment(isoDep: IsoDep, ppse: Boolean): List<ByteArray>? {
        val name = if (ppse) "2PAY.SYS.DDF01" else "1PAY.SYS.DDF01"
        val resp = isoDep.transceive(buildSelect(name.toByteArray(Charsets.US_ASCII)))
        if (!isSuccess(resp)) return null
        return parseAidsFromFci(resp).takeIf { it.isNotEmpty() }
    }

    private fun parseAidsFromFci(fci: ByteArray): List<ByteArray> {
        val aids = mutableListOf<ByteArray>()
        // Walk TLV tree looking for 0x61 Application Templates containing 0x4F AIDs
        forEachTlv(fci) { tag, value ->
            if (tag == 0x61) {
                forEachTlv(value) { t, v ->
                    if (t == 0x4F && v.size in 5..16) aids.add(v)
                }
            } else if (tag == 0xBF0C) {
                forEachTlv(value) { t, v ->
                    if (t == 0x61) forEachTlv(v) { t2, v2 ->
                        if (t2 == 0x4F && v2.size in 5..16) aids.add(v2)
                    }
                }
            }
        }
        return aids
    }

    private fun readWithAid(isoDep: IsoDep, aid: ByteArray): CardData? {
        val selectResp = isoDep.transceive(buildSelect(aid))
        if (!isSuccess(selectResp)) return null

        // Build GPO with PDOL data (zeros satisfy most cards)
        val pdol = findTag(selectResp, 0x9F38)
        val gpoResp = isoDep.transceive(buildGpo(pdol))

        var pan: String? = null
        var expiry: String? = null

        if (isSuccess(gpoResp)) {
            // Parse AFL from GPO response and read exactly those records
            val afl = parseAfl(gpoResp)
            if (afl != null) {
                outer@ for (entry in parseAflEntries(afl)) {
                    for (rec in entry.first..entry.second) {
                        val resp = isoDep.transceive(buildReadRecord(entry.third, rec))
                        if (!isSuccess(resp)) continue
                        extractCardData(resp).let { (p, e) ->
                            if (pan == null) pan = p
                            if (expiry == null) expiry = e
                        }
                        if (pan != null && expiry != null) break@outer
                    }
                }
            }
        }

        // Fallback: brute-force SFI 1-4 records 1-10
        if (pan == null || expiry == null) {
            outer@ for (sfi in 1..4) {
                for (rec in 1..10) {
                    val resp = isoDep.transceive(buildReadRecord(sfi, rec))
                    if (!isSuccess(resp)) continue
                    extractCardData(resp).let { (p, e) ->
                        if (pan == null) pan = p
                        if (expiry == null) expiry = e
                    }
                    if (pan != null && expiry != null) break@outer
                }
            }
        }

        if (pan == null || expiry == null) return null
        val last4 = pan!!.takeLast(4)
        return CardData(pan!!, expiry!!, last4, "${last4}_${expiry}")
    }

    private fun parseAfl(gpoResp: ByteArray): ByteArray? {
        // Tag 94 may be nested inside 77 or directly in 80 format
        findTag(gpoResp, 0x94)?.let { return it }
        // Format 1: 80 len [2-byte AIP] [AFL bytes...]
        var i = 0
        while (i < gpoResp.size - 1) {
            if (gpoResp[i].toInt() and 0xFF == 0x80) {
                i++
                val len = gpoResp[i].toInt() and 0xFF
                i++
                if (len > 2 && i + len <= gpoResp.size) {
                    return gpoResp.copyOfRange(i + 2, i + len)
                }
                break
            }
            i++
        }
        return null
    }

    // Returns list of (firstRecord, lastRecord, sfi) triples
    private fun parseAflEntries(afl: ByteArray): List<Triple<Int, Int, Int>> {
        val entries = mutableListOf<Triple<Int, Int, Int>>()
        var i = 0
        while (i + 3 < afl.size) {
            val sfi = (afl[i].toInt() and 0xFF) shr 3
            val first = afl[i + 1].toInt() and 0xFF
            val last = afl[i + 2].toInt() and 0xFF
            i += 4
            if (sfi > 0 && first <= last) entries.add(Triple(first, last, sfi))
        }
        return entries
    }

    private fun extractCardData(data: ByteArray): Pair<String?, String?> {
        var pan: String? = null
        var expiry: String? = null

        // Tag 5A — PAN
        findTag(data, 0x5A)?.let { bytes ->
            val raw = bytes.joinToString("") { "%02X".format(it) }.trimEnd('F', 'f').filter { it.isDigit() }
            if (raw.length in 13..19) pan = raw
        }

        // Tag 5F24 — Expiry Date (YYMMDD bcd, we use YYMM)
        findTag(data, 0x5F24)?.let { bytes ->
            if (bytes.size >= 2) {
                val yy = "%02X".format(bytes[0])
                val mm = "%02X".format(bytes[1])
                expiry = "$mm/$yy"
            }
        }

        // Tag 57 — Track 2 Equivalent Data (fallback for both PAN and expiry)
        if (pan == null || expiry == null) {
            findTag(data, 0x57)?.let { bytes ->
                val hex = bytes.joinToString("") { "%02X".format(it) }
                val m = Regex("([0-9]{13,19})D([0-9]{2})([0-9]{2})").find(hex)
                if (m != null) {
                    if (pan == null) pan = m.groupValues[1]
                    if (expiry == null) expiry = "${m.groupValues[3]}/${m.groupValues[2]}"
                }
            }
        }

        return Pair(pan, expiry)
    }

    // ── APDU builders ──────────────────────────────────────────────────────────

    private fun buildSelect(name: ByteArray) =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, name.size.toByte()) + name + byteArrayOf(0x00)

    private fun buildGpo(pdol: ByteArray?): ByteArray {
        val pdolLen = pdol?.let { parsePdolLength(it) } ?: 0
        val inner = byteArrayOf(0x83.toByte(), pdolLen.toByte()) + ByteArray(pdolLen)
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, inner.size.toByte()) + inner + byteArrayOf(0x00)
    }

    private fun parsePdolLength(pdol: ByteArray): Int {
        var total = 0; var i = 0
        while (i < pdol.size) {
            val b = pdol[i].toInt() and 0xFF; i++
            if (b and 0x1F == 0x1F) while (i < pdol.size && pdol[i].toInt() and 0x80 != 0) i++
            if (i < pdol.size) i++ // last byte of multi-byte tag
            if (i < pdol.size) { total += pdol[i].toInt() and 0xFF; i++ }
        }
        return total
    }

    private fun buildReadRecord(sfi: Int, record: Int) =
        byteArrayOf(0x00, 0xB2.toByte(), record.toByte(), ((sfi shl 3) or 4).toByte(), 0x00)

    // ── TLV parser ─────────────────────────────────────────────────────────────

    private fun isSuccess(r: ByteArray) =
        r.size >= 2 && r[r.size - 2].toInt() and 0xFF == 0x90 && r[r.size - 1].toInt() and 0xFF == 0x00

    private fun forEachTlv(data: ByteArray, action: (Int, ByteArray) -> Unit) {
        var i = 0
        while (i < data.size) {
            var firstByte = data[i].toInt() and 0xFF; i++
            if (firstByte == 0x00 || firstByte == 0xFF) continue
            var tag = firstByte
            if (firstByte and 0x1F == 0x1F) {
                while (i < data.size) {
                    val b = data[i].toInt() and 0xFF; i++
                    tag = (tag shl 8) or b
                    if (b and 0x80 == 0) break
                }
            }
            if (i >= data.size) break
            var len = data[i].toInt() and 0xFF; i++
            when (len) {
                0x81 -> { if (i >= data.size) break; len = data[i].toInt() and 0xFF; i++ }
                0x82 -> { if (i + 1 >= data.size) break; len = ((data[i].toInt() and 0xFF) shl 8) or (data[i+1].toInt() and 0xFF); i += 2 }
            }
            if (i + len > data.size) break
            action(tag, data.copyOfRange(i, i + len))
            i += len
        }
    }

    /** Recursive search — descends into constructed TLV containers */
    private fun findTag(data: ByteArray, target: Int): ByteArray? {
        var result: ByteArray? = null
        forEachTlv(data) outer@{ tag, value ->
            if (result != null) return@outer
            if (tag == target) { result = value; return@outer }
            val firstByte = if (tag > 0xFF) (tag shr 8) and 0xFF else tag and 0xFF
            if (firstByte and 0x20 != 0) result = findTag(value, target) // constructed → recurse
        }
        return result
    }
}
