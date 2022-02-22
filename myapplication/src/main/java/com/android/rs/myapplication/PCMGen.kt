package com.android.rs.myapplication

class PCMGen(val sample:Int,val channel: Int) {

    var tincr = (2 * Math.PI * 110.0 / sample).toFloat()
    val tincr2 = tincr / sample
    var t = 0f


    fun fill_pcm(data: ShortArray) {
        var idx = 0
        for (j in 0 until data.size / channel) {
            val v = (Math.sin(t.toDouble()) * 10000).toInt()
            for (i in 0 until channel) {
                data[idx++] = v.toShort()
                t += tincr
                tincr += tincr2
            }
        }
    }
}