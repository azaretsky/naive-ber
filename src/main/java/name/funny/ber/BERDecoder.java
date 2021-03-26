package name.funny.ber;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;

public class BERDecoder {
    private final ByteBuffer buffer;
    private boolean constructed;
    private int contentStart;
    private int contentEnd;
    private int elementEnd;

    public static BERValue decode(byte[] bytes) throws BERDecodingException {
        return new BERDecoder(bytes).decode();
    }

    public static BERValue decode(ByteBuffer buffer) throws BERDecodingException {
        return new BERDecoder(buffer).decode();
    }

    public BERDecoder(byte[] bytes) {
        this(ByteBuffer.wrap(bytes));
    }

    public BERDecoder(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    public BERValue decode() throws BERDecodingException {
        BERValue berValue = decodeOneValue();
        if (buffer.hasRemaining()) {
            throw new BERDecodingException(buffer.toString() + ": expected single value");
        }
        return berValue;
    }

    public BERValue decodeOneValue() throws BERDecodingException {
        try {
            return decodeOneValue0();
        } catch (IllegalArgumentException | BufferUnderflowException e) {
            throw new BERDecodingException(buffer.toString(), e);
        }
    }

    public Collection<BERValue> decodeAll() throws BERDecodingException {
        try {
            return decodeAll0();
        } catch (IllegalArgumentException | BufferUnderflowException e) {
            throw new BERDecodingException(buffer.toString(), e);
        }
    }

    private Collection<BERValue> decodeAll0() {
        Collection<BERValue> elements = new ArrayList<>();
        while (buffer.hasRemaining()) {
            elements.add(decodeOneValue0());
        }
        return elements;
    }

    private BERValue decodeOneValue0() {
        int elementStart = buffer.position();
        byte firstTagByte = buffer.get();
        BERTagClass tagClass = BERTagClass.values()[(firstTagByte >> 6) & 0x03];
        constructed = (firstTagByte & 0x20) != 0;
        int tag = decodeTag(firstTagByte & 0x1f);
        byte firstLengthByte = buffer.get();
        Collection<BERValue> nested;
        if (firstLengthByte == (byte) 0x80) {
            nested = decodeIndefiniteLengthContent();
        } else {
            nested = decodeDefiniteLengthContent(firstLengthByte);
        }
        ByteBuffer elementBuffer = subBuffer(elementStart, elementEnd);
        return new BERValue(tagClass, tag, false, nested, elementBuffer, captureContent());
    }

    private int decodeTag(int firstTagBits) {
        if (firstTagBits != 0x1f) {
            return firstTagBits;
        }
        int tag = 0;
        for (; ; ) {
            if (tag > Integer.MAX_VALUE >> 7) {
                throw new IllegalArgumentException("long tag value is too big");
            }
            byte tagByte = buffer.get();
            tag = (tag << 7) | (tagByte & 0x7f);
            if ((tagByte & 0x80) == 0) {
                return tag;
            }
        }
    }

    private Collection<BERValue> decodeIndefiniteLengthContent() {
        if (!constructed) {
            throw new IllegalArgumentException("indefinite length of a primitive value");
        }
        contentStart = buffer.position();
        Collection<BERValue> nested = new ArrayList<>();
        BERDecoder sub = new BERDecoder(buffer);
        for (; ; ) {
            buffer.mark();
            // test for the end-of-contents marker:
            // current buffer order does not matter,
            // since both bytes should be zero
            if (buffer.getShort() == 0) {
                break;
            }
            buffer.reset();
            nested.add(sub.decodeOneValue0());
        }
        elementEnd = buffer.position();
        contentEnd = elementEnd - 2;
        return nested;
    }

    private Collection<BERValue> decodeDefiniteLengthContent(byte firstLengthByte) {
        int valueLength = decodeLength(firstLengthByte);
        contentStart = buffer.position();
        contentEnd = contentStart + valueLength;
        elementEnd = contentEnd;
        buffer.position(elementEnd);
        if (!constructed) {
            return null;
        }
        return new BERDecoder(captureContent()).decodeAll0();
    }

    private int decodeLength(byte firstLengthByte) {
        if ((firstLengthByte & 0x80) == 0) {
            return firstLengthByte;
        }
        int valueLength = 0;
        int lengthSize = firstLengthByte & 0x7f;
        while (lengthSize-- > 0) {
            if (valueLength > Integer.MAX_VALUE >> 8) {
                throw new IllegalArgumentException("tag length is too big");
            }
            valueLength = (valueLength << 8) | (buffer.get() & 0xff);
        }
        return valueLength;
    }

    private ByteBuffer captureContent() {
        return subBuffer(contentStart, contentEnd);
    }

    private ByteBuffer subBuffer(int start, int end) {
        int originalPosition = buffer.position();
        int originalLimit = buffer.limit();
        buffer.limit(end);
        buffer.position(start);
        ByteBuffer sub = buffer.slice();
        buffer.limit(originalLimit);
        buffer.position(originalPosition);
        return sub;
    }
}
