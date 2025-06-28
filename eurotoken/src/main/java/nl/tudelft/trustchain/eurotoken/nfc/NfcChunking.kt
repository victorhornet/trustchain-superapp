package nl.tudelft.trustchain.eurotoken.nfc

import java.nio.ByteBuffer

object NfcChunkingProtocol {
    private const val MAX_CHUNK_SIZE = 240
    private const val CHUNK_HEADER_SIZE = 5

    private const val FLAG_FIRST_CHUNK: Byte = 0x01
    private const val FLAG_LAST_CHUNK: Byte = 0x02
    private const val FLAG_SINGLE_CHUNK: Byte = 0x03

    data class Chunk(
        val isFirst: Boolean,
        val isLast: Boolean,
        val totalChunks: Int,
        val chunkIndex: Int,
        val data: ByteArray
    )

    fun createChunks(data: ByteArray): List<ByteArray> {
        val dataChunkSize = MAX_CHUNK_SIZE - CHUNK_HEADER_SIZE
        if (data.size <= dataChunkSize) {
            // Single chunk
            return listOf(createChunkPacket(FLAG_SINGLE_CHUNK, 1, 0, data))
        }

        val totalChunks = (data.size + dataChunkSize - 1) / dataChunkSize
        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until totalChunks) {
            val start = i * dataChunkSize
            val end = minOf(start + dataChunkSize, data.size)
            val chunkData = data.copyOfRange(start, end)

            val flag = when {
                i == 0 -> FLAG_FIRST_CHUNK
                i == totalChunks - 1 -> FLAG_LAST_CHUNK
                else -> 0x00.toByte()
            }

            chunks.add(createChunkPacket(flag, totalChunks, i, chunkData))
        }

        return chunks
    }

    private fun createChunkPacket(flag: Byte, totalChunks: Int, index: Int, data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(CHUNK_HEADER_SIZE + data.size)
        buffer.put(flag)
        buffer.putShort(totalChunks.toShort())
        buffer.putShort(index.toShort())
        buffer.put(data)
        return buffer.array()
    }

    fun parseChunk(chunkData: ByteArray): Chunk {
        val buffer = ByteBuffer.wrap(chunkData)
        val flag = buffer.get()
        val totalChunks = buffer.short.toInt()
        val chunkIndex = buffer.short.toInt()
        val data = ByteArray(chunkData.size - CHUNK_HEADER_SIZE)
        buffer.get(data)

        return Chunk(
            isFirst = (flag.toInt() and FLAG_FIRST_CHUNK.toInt()) != 0,
            isLast = (flag.toInt() and FLAG_LAST_CHUNK.toInt()) != 0,
            totalChunks = totalChunks,
            chunkIndex = chunkIndex,
            data = data
        )
    }

    class ChunkAssembler {
        val chunks = mutableMapOf<Int, ByteArray>()
        private var totalChunks: Int? = null

        fun addChunk(chunk: Chunk): ByteArray? {
            chunks[chunk.chunkIndex] = chunk.data
            totalChunks = chunk.totalChunks

            if (chunks.size == totalChunks) {
                val result = ByteArray(chunks.values.sumOf { it.size })
                var offset = 0
                for (i in 0 until totalChunks!!) {
                    val chunkData = chunks[i] ?: throw IllegalStateException("Missing chunk $i")
                    chunkData.copyInto(result, offset)
                    offset += chunkData.size
                }
                reset()
                return result
            }

            return null
        }

        fun reset() {
            chunks.clear()
            totalChunks = null
        }
    }
}
