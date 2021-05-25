package name.funny.ber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BEREncoder {
/*
    private static final Comparator<byte[]> unsignedByteArrayComparator = (byte[] a, byte[] b) -> {
        if (a == b) {
            return 0;
        }
        if (a == null || b == null) {
            return a == null ? -1 : 1;
        }
        int commonPrefix = Math.min(a.length, b.length);
        int i = -1;
        for (int k = 0; k < commonPrefix; ++k) {
            if (a[k] != b[k]) {
                i = k;
                break;
            }
        }
        if (i >= 0) {
            return Integer.compare(a[i] & 0xff, b[i] & 0xff);
        }
        return a.length - b.length;
    };

    private final OutputStream out;

    public static byte[] toBytes(BERValue berValue) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            new BEREncoder(bytes).encode(berValue);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return bytes.toByteArray();
    }

    public BEREncoder(OutputStream out) {
        this.out = out;
    }

    public void encode(BERValue berValue) throws IOException {
        int firstTagByte = berValue.getTagClass().ordinal() << 6;
        if (berValue.isConstructed()) {
            firstTagByte |= 0x20;
        }
        int tag = berValue.getTag();
        firstTagByte |= Math.min(tag, 0x1f);
        out.write(firstTagByte);
        if (tag >= 0x1f) {
            encodeLongTag(tag);
        }
        if (berValue.isEmpty()) {
            encodeLength(0);
        } else if (!berValue.isConstructed()) {
            encodePrimitive(berValue.getContentBuffer());
        } else if (berValue.isSorted()) {
            encodeSet(berValue);
        } else {
            encodeSequence(berValue);
        }
    }

    private void encodeLongTag(int tag) throws IOException {
        for (int tagByteCount = bitGroups(7, tag); tagByteCount > 0; --tagByteCount) {
            int tagByte = (tag >> ((tagByteCount - 1) * 7)) & 0x7f;
            if (tagByteCount > 1) {
                tagByte |= 0x80;
            }
            out.write(tagByte);
        }
    }

    private void encodeLength(int length) throws IOException {
        if (length <= 127) {
            out.write(length);
            return;
        }
        int lengthByteCount = bitGroups(8, length);
        out.write(0x80 | lengthByteCount);
        do {
            out.write((length >> (--lengthByteCount * 8)) & 0xff);
        } while (lengthByteCount > 0);
    }

    private void encodePrimitive(ByteBuffer contentBuffer) throws IOException {
        int length = contentBuffer.limit();
        encodeLength(length);
        if (contentBuffer.hasArray()) {
            out.write(contentBuffer.array(), contentBuffer.arrayOffset(), length);
        } else {
            byte[] bytes = new byte[length];
            contentBuffer.rewind();
            contentBuffer.get(bytes);
            out.write(bytes);
        }
    }

    private void encodeSet(Iterable<BERValue> values) throws IOException {
        List<byte[]> valueEncodings = new ArrayList<>();
        for (BERValue v : values) {
            valueEncodings.add(toBytes(v));
        }
        valueEncodings.sort(unsignedByteArrayComparator);
        encodeLength(valueEncodings.stream().mapToInt(e -> e.length).sum());
        for (byte[] valueEncoding : valueEncodings) {
            out.write(valueEncoding);
        }
    }

    private void encodeSequence(Iterable<BERValue> values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BEREncoder nested = new BEREncoder(bytes);
        for (BERValue v : values) {
            nested.encode(v);
        }
        encodeLength(bytes.size());
        bytes.writeTo(out);
    }

    private static int bitGroups(int groupSize, int bits) {
        int count = 1;
        for (bits >>>= groupSize; bits != 0; bits >>>= groupSize) {
            ++count;
        }
        return count;
    }
*/
}
