package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import kotlin.test.Test

class W3261ExploreTest {
    private val policy = LocalPolicy(defaultReservation = 1L)
    private val max = Long.MAX_VALUE

    private fun run(golden: String, name: String, from: String, to: String) {
        check(from in golden) { "$name: '$from' not in golden" }
        val text = golden.replaceFirst(from, to)
        val out = try { LocalHeddle.decode(text, Json) } catch (t: Throwable) { println("W3261 $name DECODE-THROWS ${t::class.simpleName}: ${t.message}"); return }
        if (out !is RestoreResult.Restored) { println("W3261 $name ${out::class.simpleName}: ${(out as? RestoreResult.Corrupt)?.reason?.take(140)}"); return }
        val s = out.snapshot
        val groups = s.ledger.allEdges().mapNotNull { s.ledger.record(it)?.child }.toSet()
        for (arm in listOf("acquireFirst", "completeFirst")) {
            val step = StringBuilder()
            try {
                step.append("restore;")
                val h = LocalHeddle.restore(s, policy, epoch = s.epoch + 1)
                if (arm == "acquireFirst") { step.append("acquire;"); step.append(h.acquire(groups, groups, startedAt = 0L)::class.simpleName).append(';') }
                for (lease in h.snapshot().leases) {
                    step.append("complete ${lease.id};")
                    step.append(h.complete(lease.id, actualCost = if (arm == "acquireFirst") lease.reserved else lease.reserved + 1, waiting = groups)::class.simpleName).append(';')
                }
                if (arm == "completeFirst") { step.append("acquire;"); step.append(h.acquire(groups, groups, startedAt = 0L)::class.simpleName).append(';') }
                println("W3261 $name RESTORED $arm OK $step")
            } catch (t: Throwable) {
                println("W3261 $name RESTORED $arm THROWS at $step ${t::class.simpleName}: ${t.message?.take(160)}")
            }
        }
    }

    @Test
    fun explore() {
        val g = GOLDEN_FORMAT_1
        val r = GOLDEN_FORMAT_1_RICH
        run(g, "control", "\"totalSlots\":2", "\"totalSlots\":2")
        run(r, "rich-control", "\"totalSlots\":4", "\"totalSlots\":4")
        run(g, "epoch", "{\"epoch\":0,\"totalSlots\"", "{\"epoch\":${max - 1},\"totalSlots\"")
        run(g, "totalSlots", "\"totalSlots\":2", "\"totalSlots\":$max")
        run(g, "weightNum", "\"weight\":{\"numerator\":1,\"denominator\":1}}],\"root/b", "\"weight\":{\"numerator\":$max,\"denominator\":1}}],\"root/b")
        run(g, "weightDen", "\"weight\":{\"numerator\":1,\"denominator\":1}}],\"root/b", "\"weight\":{\"numerator\":1,\"denominator\":$max}}],\"root/b")
        run(g, "minted", "\"amount\":56", "\"amount\":$max")
        run(g, "issuedA", "\"root/a@1:1#0\":{\"counts\":{\"local\":51}}", "\"root/a@1:1#0\":{\"counts\":{\"local\":$max}}")
        run(g, "issuedB", "\"root/b@1:1#0\":{\"counts\":{\"local\":1}}", "\"root/b@1:1#0\":{\"counts\":{\"local\":$max}}")
        run(g, "issuedNeg", "\"root/a@1:1#0\":{\"counts\":{\"local\":51}}", "\"root/a@1:1#0\":{\"counts\":{\"local\":-1}}")
        run(g, "issuedTwoReplicas", "\"root/b@1:1#0\":{\"counts\":{\"local\":1}}", "\"root/b@1:1#0\":{\"counts\":{\"local\":1,\"other\":$max}}")
        run(g, "leafSpent", "\"leafSpent\":{\"root/a@1:1#0\":{\"counts\":{\"local\":50}}}", "\"leafSpent\":{\"root/a@1:1#0\":{\"counts\":{\"local\":$max}}}")
        run(g, "returnedA", "\"returned\":{}", "\"returned\":{\"root/a@1:1#0\":{\"counts\":{\"local\":$max}}}")
        run(g, "rollupSpent", "\"rollupSpent\":{}", "\"rollupSpent\":{\"root/a@1:1#0\":{\"counts\":{\"local\":$max}}}")
        run(g, "gaugeTicksLow", "{\"folded\":51,\"ticksLow\":816}", "{\"folded\":51,\"ticksLow\":$max}")
        run(g, "gaugeTicksHigh", "{\"folded\":51,\"ticksLow\":816}", "{\"folded\":51,\"ticksHigh\":$max,\"ticksLow\":816}")
        run(g, "gaugeTicksNeg", "{\"folded\":51,\"ticksLow\":816}", "{\"folded\":51,\"ticksHigh\":-1,\"ticksLow\":816}")
        run(g, "gaugeFoldedNeg", "{\"folded\":51,\"ticksLow\":816}", "{\"folded\":-1,\"ticksLow\":816}")
        run(g, "wakeLow", "{\"low\":800}", "{\"low\":$max}")
        run(g, "wakeHigh", "{\"low\":800}", "{\"high\":$max}")
        run(g, "clockLow", "{\"low\":582341760}", "{\"low\":$max}")
        run(g, "clockHigh", "{\"low\":582341760}", "{\"high\":$max}")
        run(g, "clockMin", "{\"low\":582341760}", "{\"high\":${Long.MIN_VALUE}}")
        run(g, "servedA", "\"root/a@1:1#0\":51,\"root/b@1:1#0\":0", "\"root/a@1:1#0\":$max,\"root/b@1:1#0\":0")
        run(g, "servedB", "\"root/a@1:1#0\":51,\"root/b@1:1#0\":0", "\"root/a@1:1#0\":51,\"root/b@1:1#0\":$max")
        run(g, "servedNeg", "\"root/a@1:1#0\":51,\"root/b@1:1#0\":0", "\"root/a@1:1#0\":51,\"root/b@1:1#0\":${Long.MIN_VALUE}")
        run(g, "seq", "\"seq\":51,", "\"seq\":$max,")
        run(g, "reservedMax", "\"seq\":50,\"reserved\":1,", "\"seq\":50,\"reserved\":$max,")
        run(g, "reserved2", "\"seq\":50,\"reserved\":1,", "\"seq\":50,\"reserved\":2,")
        run(g, "reservedB2", "\"seq\":51,\"reserved\":1,", "\"seq\":51,\"reserved\":2,")
        run(g, "reserved0", "\"seq\":50,\"reserved\":1,", "\"seq\":50,\"reserved\":0,")
        run(g, "reservedPastMax", "\"seq\":50,\"reserved\":1,", "\"seq\":50,\"reserved\":${LocalHeddle.MAX_RESERVATION + 1},")
        run(g, "startedAtMax", "\"startedAt\":100", "\"startedAt\":$max")
        run(g, "startedAtMin", "\"startedAt\":100", "\"startedAt\":${Long.MIN_VALUE}")
        run(r, "forgottenOutflow", "\"outflow\":{\"root/tenants\":{\"local\":3}}", "\"outflow\":{\"root/tenants\":{\"local\":$max}}")
        run(r, "forgottenLeafSpend", "\"leafSpend\":{\"local\":3}", "\"leafSpend\":{\"local\":$max}")
        run(r, "forgottenFloor", "\"generationFloor\":4", "\"generationFloor\":$max")
        run(r, "departure", "\"root/acme/interactive@1:1#0\":{\"low\":23063040}", "\"root/acme/interactive@1:1#0\":{\"high\":$max}")
        run(r, "departureMin", "\"root/acme/interactive@1:1#0\":{\"low\":23063040}", "\"root/acme/interactive@1:1#0\":{\"high\":${Long.MIN_VALUE}}")
        run(r, "shiftNum", "{\"numerator\":{\"low\":144144},\"denominator\":1}", "{\"numerator\":{\"low\":$max},\"denominator\":1}")
        run(r, "shiftDen", "{\"numerator\":{\"low\":144144},\"denominator\":1}", "{\"numerator\":{\"low\":144144},\"denominator\":$max}")
        run(r, "maxConcurrent", "\"maxConcurrent\":3", "\"maxConcurrent\":${Int.MAX_VALUE}")
        run(r, "richReturned", "\"root/acme/interactive@1:1#0\":{\"counts\":{\"local\":2}},\"root/acme@3:2#0\"", "\"root/acme/interactive@1:1#0\":{\"counts\":{\"local\":$max}},\"root/acme@3:2#0\"")
        run(r, "richRollup", "\"rollupSpent\":{\"root/tenants@1:1#0\":{\"counts\":{\"local\":3}}}", "\"rollupSpent\":{\"root/tenants@1:1#0\":{\"counts\":{\"local\":$max}}}")
        run(r, "richReservedMax", "\"seq\":1,\"reserved\":3,", "\"seq\":1,\"reserved\":$max,")
        run(r, "richGeneration", "\"generationFloor\":4", "\"generationFloor\":0")
    }
}
