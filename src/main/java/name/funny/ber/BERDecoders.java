package name.funny.ber;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class BERDecoders {
    private BERDecoders() {
    }

    public static class StructureAndContent {
        private final BERValue structure;
        private final byte[] content;

        public StructureAndContent(BERValue structure, byte[] content) {
            this.structure = structure;
            this.content = content;
        }

        public BERValue getStructure() {
            return structure;
        }

        public byte[] getContent() {
            return content;
        }

        public ByteBuffer byteBuffer() {
            return ByteBuffer.wrap(content, structure.getContentStart(), structure.getContentLength()).slice();
        }
    }

    public static StructureAndContent fromInputStream(InputStream in) throws BERDecodingException {
        var ref = new Object() {
            BERValue result;
        };
        Decoder decoder = new Decoder(BERDecoder.decodeOne(berValue -> {
            ref.result = berValue;
            return Decoder.done;
        }));
        byte[] bytes = fromInputStream0(decoder, new byte[4096], new BufferedInputStream(in));
        return new StructureAndContent(ref.result, bytes);
    }

    private static byte[] fromInputStream0(Decoder decoder, byte[] bytes, InputStream in) throws BERDecodingException {
        for (; ; ) {
            switch (decoder.getState()) {
            case FAILED:
                throw new BERDecodingException(decoder.getError() + " " + decoder);
            case DONE:
                return bytes;
            case NEED_BYTE:
                bytes = readByte(decoder, bytes, in);
                break;
            case SKIP:
                bytes = readBytes(decoder, bytes, in);
                break;
            }
        }
    }

    private static byte[] readByte(Decoder decoder, byte[] bytes, InputStream in) throws BERDecodingException {
        int b;
        try {
            b = in.read();
        } catch (IOException e) {
            throw new BERDecodingException(decoder.toString(), e);
        }
        if (b == -1) {
            throw new BERDecodingException("EOF " + decoder);
        }
        if (decoder.getPosition() >= bytes.length) {
            bytes = Arrays.copyOf(bytes, bytes.length * 2);
        }
        bytes[decoder.getPosition()] = (byte) b;
        decoder.processByte((byte) b);
        return bytes;
    }

    private static byte[] readBytes(Decoder decoder, byte[] bytes, InputStream in) throws BERDecodingException {
        int skipLength = decoder.getSkipLength();
        if (skipLength != 0) {
            int newPosition = decoder.getPosition() + skipLength;
            if (newPosition > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, newPosition));
            }
            int read;
            try {
                read = in.readNBytes(bytes, decoder.getPosition(), skipLength);
            } catch (IOException e) {
                throw new BERDecodingException(decoder.toString(), e);
            }
            if (read != skipLength) {
                throw new BERDecodingException("skip=" + skipLength + " read=" + read + " " + decoder);
            }
        }
        decoder.skip();
        return bytes;
    }

    public static BERValue fromByteArray(byte[] bytes, int offset, int length) throws BERDecodingException {
        var ref = new Object() {
            BERValue result;
        };
        Decoder decoder = new Decoder(
                BERDecoder.decodeOne(berValue -> {
                    ref.result = berValue;
                    return Decoder.done;
                }),
                offset, offset + length);
        for (; ; ) {
            switch (decoder.getState()) {
            case FAILED:
                throw new BERDecodingException(decoder.getError() + " " + decoder);
            case DONE:
                if (!decoder.isEOF()) {
                    throw new BERDecodingException("trailing data " + decoder);
                }
                return ref.result;
            case NEED_BYTE:
                decoder.processByte(bytes[decoder.getPosition()]);
                break;
            case SKIP:
                decoder.skip();
                break;
            }
        }
    }
}
