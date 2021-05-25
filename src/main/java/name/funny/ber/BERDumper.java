package name.funny.ber;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Formatter;
import java.util.Properties;

public class BERDumper {
    private static final Properties knownOids;
    private static final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder();
    private static final CharsetDecoder asciiDecoder = StandardCharsets.US_ASCII.newDecoder();

    static {
        Properties oids = new Properties();
        try (InputStream inputStream = BERValue.class.getResourceAsStream("known-oids.properties");
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            oids.load(reader);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        knownOids = oids;
    }

    private final Appendable out;
    private final boolean showExtents;

    public BERDumper(StringBuilder out) {
        this(out, false);
    }

    public BERDumper(Appendable out, boolean showExtents) {
        this.out = out;
        this.showExtents = showExtents;
    }

    private boolean dumpUniversallyTaggedElement(int tag, ByteBuffer contentBuffer) throws IOException {
        switch (tag) {
        case 1:
            if (contentBuffer.limit() == 1) {
                int value = contentBuffer.get(0) & 0xff;
                out.append(value == 0 ? " false" : " true");
                if (value != 0xff) {
                    out.append(" non-DER ");
                    out.append(String.valueOf(value));
                }
                break;
            } else {
                return false;
            }
        case 2:
            out.append(' ');
            hexdump(out, contentBuffer);
            break;
        case 6:
            out.append(' ');
            String oid = decodeOid(contentBuffer);
            String oidName = knownOids.getProperty(oid);
            if (oidName != null) {
                out.append(oidName);
                out.append(" (");
                out.append(oid);
                out.append(')');
            } else {
                out.append(oid);
            }
            break;
        case 12:
        case 19:
        case 22:
        case 23:
        case 24:
        case 30:
            if (contentBuffer.limit() > 0) {
                Charset charset;
                switch (tag) {
                case 12:
                    charset = StandardCharsets.UTF_8;
                    break;
                case 30:
                    charset = StandardCharsets.UTF_16BE;
                    break;
                default:
                    charset = StandardCharsets.US_ASCII;
                    break;
                }
                out.append(' ');
                out.append(charset.decode(contentBuffer));
            }
            break;
        default:
            return false;
        }
        return true;
    }

    private static boolean isAsciiControl(int c) {
        return c < ' ' && c != '\t' && c != '\n' && c != '\r';
    }

    private static String tryDecodeASCII(ByteBuffer bytes) {
        bytes.rewind();
        try {
            CharBuffer characters = asciiDecoder.decode(bytes);
            if (characters.chars().anyMatch(c -> isAsciiControl(c) || c > '~')) {
                return null;
            }
            return characters.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String tryDecodeUTF8(ByteBuffer bytes) {
        bytes.rewind();
        try {
            CharBuffer characters = utf8Decoder.decode(bytes);
            if (characters.chars().anyMatch(BERDumper::isAsciiControl)) {
                return null;
            }
            return characters.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private void dump(BERDecoders.StructureAndContent structureAndContent, IndentSequence indent) throws IOException {
        BERValue berValue = structureAndContent.getStructure();
        byte[] content = structureAndContent.getContent();
        out.append(indent);
        if (showExtents) {
            out.append('<');
            out.append(String.valueOf(berValue.getElementStart()));
            out.append(' ');
            out.append(String.valueOf(berValue.getElementLength()));
            out.append("> ");
        }
        String knownName = knownTagName(berValue);
        if (knownName != null) {
            out.append(knownName);
        } else {
            out.append('[');
            switch (berValue.getTagClass()) {
            case Universal:
            case Application:
            case Private:
                out.append(berValue.getTagClass().toString().toLowerCase());
                out.append(' ');
                break;
            case Context:
                break;
            }
            out.append(String.valueOf(berValue.getTag()));
            out.append(']');
        }
        if (berValue.isEmpty()) {
            return;
        }
        if (berValue.isConstructed()) {
            out.append(" {\n");
            IndentSequence subIndent = indent.extend(4);
            for (BERValue child : berValue) {
                dump(new BERDecoders.StructureAndContent(child, content), subIndent);
                out.append('\n');
            }
            out.append(indent);
            out.append("}");
            return;
        }
        if (content == null) {
            out.append(" <length: ");
            out.append(String.valueOf(berValue.getContentLength()));
            out.append(">");
            return;
        }
        ByteBuffer contentBuffer = structureAndContent.byteBuffer();
        if (berValue.getTagClass() == BERTagClass.Universal &&
                dumpUniversallyTaggedElement(berValue.getTag(), contentBuffer)) {
            return;
        }
        BERValue innerBER = tryExtractBerValue(structureAndContent);
        if (innerBER != null) {
            out.append(" ber-encoded ");
            hexdump(out, contentBuffer);
            out.append(":\n");
            dump(new BERDecoders.StructureAndContent(innerBER, content), indent.extend(2));
        } else {
            String stringValue;
            if ((stringValue = tryDecodeASCII(contentBuffer)) != null) {
                out.append(" ia5-string ");
                out.append(stringValue);
            } else if ((stringValue = tryDecodeUTF8(contentBuffer)) != null) {
                out.append(" utf8-string ");
                out.append(stringValue);
            } else {
                out.append(' ');
                hexdump(out, contentBuffer);
            }
        }
    }

    @SuppressWarnings("java:S2095")
    private static void hexdump(Appendable out, ByteBuffer valueBuffer) {
        Formatter fmt = new Formatter(out);
        for (int i = 0; i < valueBuffer.limit(); i++) {
            fmt.format("%02x", valueBuffer.get(i));
        }
    }

    private static BERValue tryExtractBerValue(BERDecoders.StructureAndContent structureAndContent) {
        BERValue berValue = structureAndContent.getStructure();
        byte[] content = structureAndContent.getContent();
        // let's see if it's a BER-encoded element contained inside a sequence of bytes
        try {
            return BERDecoders.fromByteArray(content, berValue.getContentStart(), berValue.getContentLength());
        } catch (BERDecodingException e) {
            try {
                // sometimes BER-encoded values are put into bit-strings -
                // consider only bit-strings which contain whole number of bytes,
                // i.e. with the first byte 0
                if (berValue.getContentLength() > 1 && content[berValue.getContentStart()] == 0) {
                    return BERDecoders.fromByteArray(content, berValue.getContentStart() + 1, berValue.getContentLength() - 1);
                }
            } catch (BERDecodingException deeperE) {
                // we tried our best
            }
        }
        return null;
    }

    private static String decodeOid(ByteBuffer bytes) {
        StringBuilder out = new StringBuilder();
        int n = -1;
        boolean first = true;
        while (bytes.hasRemaining()) {
            byte b = bytes.get();
            if (n == -1) {
                n = b & 0x7f;
            } else {
                if (n > (Integer.MAX_VALUE >> 7)) {
                    throw new IllegalArgumentException("OID segment does not fit into int");
                }
                n = (n << 7) | (b & 0x7f);
            }
            if ((b & 0x80) == 0) {
                if (first) {
                    if (n >= 80) {
                        out.append("2.");
                        out.append(n - 80);
                    } else {
                        out.append(n / 40);
                        out.append('.');
                        out.append(n % 40);
                    }
                    first = false;
                } else {
                    out.append('.');
                    out.append(n);
                }
                n = -1;
            }
        }
        return out.toString();
    }

    private static String knownTagName(BERValue berValue) {
        if (berValue.getTagClass() == BERTagClass.Universal) {
            switch (berValue.getTag()) {
            case 1:
                return "boolean";
            case 2:
                return "integer";
            case 3:
                return "bit-string";
            case 4:
                return "octet-string";
            case 5:
                return "null";
            case 6:
                return "oid";
            case 10:
                return "enumerated";
            case 12:
                return "utf8-string";
            case 16:
                return "sequence";
            case 17:
                return "set";
            case 19:
                return "printable-string";
            case 22:
                return "ia5-string";
            case 23:
                return "utc-time";
            case 24:
                return "generalized-time";
            case 30:
                return "bmp-string";
            }
        }
        return null;
    }

    public void dump(BERDecoders.StructureAndContent structureAndContent) throws IOException {
        dump(structureAndContent, new IndentSequence(0, ' '));
    }

    public static String toString(BERDecoders.StructureAndContent structureAndContent) {
        StringBuilder out = new StringBuilder();
        try {
            new BERDumper(out).dump(structureAndContent);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toString();
    }

    @SuppressWarnings("java:S106")
    public static void main(String[] args) throws IOException, BERDecodingException {
        InputStream in = args.length == 0 || "-".equals(args[0]) ? System.in : Files.newInputStream(Paths.get(args[0]));
        BERDecoders.StructureAndContent structureAndContent = BERDecoders.fromInputStream(in);
        new BERDumper(System.out, args.length > 1)
                .dump(structureAndContent);
        System.out.println();
    }

    private static class IndentSequence implements CharSequence {
        private final int count;
        private final char ch;

        public IndentSequence(int count, char ch) {
            this.count = count;
            this.ch = ch;
        }

        public IndentSequence extend(int add) {
            return new IndentSequence(count + add, ch);
        }

        @Override
        public int length() {
            return count;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException(index);
            }
            return ch;
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < 0 || end > count || start > end) {
                throw new IndexOutOfBoundsException("start " + start + ", end " + end + ", count " + count);
            }
            return new IndentSequence(end - start, ch);
        }

        @Override
        public String toString() {
            return String.valueOf(ch).repeat(count);
        }
    }
}
