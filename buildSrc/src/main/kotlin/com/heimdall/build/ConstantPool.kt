package com.heimdall.build

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Reads a classfile's string constants: the `ldc` operands, which is where a literal class name
 * passed to `Class.forName` lives.
 *
 * A substring search over the raw bytes cannot answer this question, because the same name also
 * appears in descriptors and class references, where relocation is exactly what should happen.
 * Only the targets of `CONSTANT_String` entries are what a `String` literal in the source became.
 */
object ConstantPool {

    /** The text of every `CONSTANT_String` in [classFile], in pool order. */
    fun stringConstants(classFile: ByteArray): List<String> {
        val input = DataInputStream(ByteArrayInputStream(classFile))
        input.skipBytes(8) // magic, minor, major
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val stringIndexes = mutableListOf<Int>()
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF() // modified UTF-8, as readUTF expects
                8 -> stringIndexes += input.readUnsignedShort()
                7, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    index++ // a long or double takes two slots
                }
                else -> throw IllegalArgumentException("unknown constant pool tag $tag at $index")
            }
            index++
        }
        return stringIndexes.map { utf8.getOrNull(it) ?: "" }
    }
}
