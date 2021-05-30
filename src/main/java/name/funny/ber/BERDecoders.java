package name.funny.ber;

import java.io.EOFException;
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

    public static StructureAndContent fromInputStream(InputStream in, byte[] bytes) throws BERDecodingException {
        InputStreamParser parser = new InputStreamParser(in, bytes);
        BERValue result = parser.decodeOne();
        return new StructureAndContent(result, parser.bytes);
    }

    public static BERValue fromByteArray(byte[] bytes, int offset, int length) throws BERDecodingException {
        return new ByteArrayParser(bytes, offset, offset + length).decodeOne();
    }

    private static class InputStreamParser extends AbstractTreeBuilder {
        private final InputStream in;
        private byte[] bytes;

        public InputStreamParser(InputStream in, byte[] bytes) {
            this.in = in;
            this.bytes = bytes;
        }

        @Override
        protected void skip(int currentPosition, int skipLength) throws IOException {
            if (skipLength != 0) {
                long skipped;
                if (bytes != null) {
                    int newPosition = currentPosition + skipLength;
                    if (newPosition > bytes.length) {
                        bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, newPosition));
                    }
                    skipped = in.readNBytes(bytes, currentPosition, skipLength);
                } else {
                    skipped = in.skip(skipLength);
                }
                if (skipped != skipLength) {
                    throw new IOException("skip=" + skipLength + " skipped=" + skipped);
                }
            }
        }

        @Override
        protected byte nextByte(int currentPosition) throws IOException {
            int n = in.read();
            if (n == -1) {
                throw new EOFException();
            }
            byte b = (byte) n;
            if (bytes != null) {
                if (currentPosition >= bytes.length) {
                    bytes = Arrays.copyOf(bytes, bytes.length * 2);
                }
                bytes[currentPosition] = b;
            }
            return b;
        }
    }

    private static class ByteArrayParser extends AbstractTreeBuilder {
        private final byte[] bytes;

        public ByteArrayParser(byte[] bytes, int offset, int limit) {
            super(offset, limit);
            this.bytes = bytes;
        }

        @Override
        protected void skip(int currentPosition, int skipLength) {
            // no need to do anything, bytes to be skipped are already known
        }

        @Override
        protected byte nextByte(int currentPosition) {
            return bytes[currentPosition];
        }
    }
}
