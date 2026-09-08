package de.tobisk.inkvault.audio

import de.sciss.jump3r.mp3.*
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib

/** Java LAME core; deliberately avoids the Java Sound desktop wrapper. 44.1 kHz, mono, 96 kb/s. */
class Mp3Encoder : AutoCloseable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    init {
        val gain = GainAnalysis()
        val bits = BitStream()
        val presets = Presets()
        val quantizePrivate = QuantizePVT()
        val quantize = Quantize()
        val vbr = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val mpg = MPGLib()
        val decoder = Interface()
        val common = Common()
        lame.setModules(gain, bits, presets, quantizePrivate, quantize, vbr, version, id3, mpg)
        bits.setModules(gain, mpg, version, vbr)
        id3.setModules(bits, version)
        presets.setModules(lame)
        quantize.setModules(bits, reservoir, quantizePrivate, takehiro)
        quantizePrivate.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bits)
        takehiro.setModules(quantizePrivate)
        vbr.setModules(lame, bits, version)
        mpg.setModules(decoder, common)
        decoder.setModules(vbr, common)
        flags = lame.lame_init().apply {
            num_channels = 1
            in_samplerate = 44100
            out_samplerate = 44100
            brate = 96
            quality = 5
            mode = MPEGMode.MONO
            write_id3tag_automatic = false
            bWriteVbrTag = false
        }
        check(lame.lame_init_params(flags) == 0) { "MP3 encoder initialization failed" }
    }
    fun encode(pcm: ShortArray, count: Int, output: ByteArray): Int {
        val samples = IntArray(count) { pcm[it].toInt() shl 16 }
        return lame.lame_encode_buffer_int(flags, samples, samples, count, output, 0, output.size).also { check(it >= 0) { "MP3 encode failed: $it" } }
    }
    fun finish(output: ByteArray) = lame.lame_encode_flush(flags, output, 0, output.size).also { check(it >= 0) }
    override fun close() {
        lame.lame_close(flags)
    }
}
