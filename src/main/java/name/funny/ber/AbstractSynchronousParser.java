package name.funny.ber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

abstract class AbstractSynchronousParser {
    private final BERDecoder decoder;

    protected AbstractSynchronousParser() {
        decoder = BERDecoder.singleElementDecoder();
    }

    protected AbstractSynchronousParser(int position, int limit) {
        decoder = BERDecoder.singleElementDecoder(position, limit);
    }

    public BERValue decodeOne() throws BERDecodingException {
        BERValue berValue;
        try {
            berValue = decodeOne0(nextEvent());
        } catch (IOException e) {
            throw new BERDecodingException(decoder + " " + e.getMessage(), e);
        }
        if (decoder.getState() != BERDecoder.State.DONE) {
            throw new AssertionError("unexpected state " + decoder);
        }
        if (decoder.hasLimit() && !decoder.isEOF()) {
            throw new BERDecodingException("trailing data " + decoder);
        }
        return berValue;
    }

    protected abstract void doSkip(int position, int skipLength) throws IOException;
    protected abstract byte doReadByte(int position) throws IOException;

    private BERValue decodeOne0(DecoderEvent event) throws BERDecodingException, IOException {
        BERValue structure;
        if (event instanceof DecoderEvent.Primitive) {
            decoder.next();
            checkDecoderState();
            var primitive = (DecoderEvent.Primitive) event;
            structure = new BERValue(primitive.tagClass, primitive.tag,
                    false, null,
                    primitive.contentStart, primitive.contentEnd(),
                    primitive.elementStart, primitive.elementEnd());
            doSkip(decoder.getPosition(), decoder.getSkipLength());
        } else if (event instanceof DecoderEvent.DefiniteConstructedStart) {
            decoder.recurse();
            checkDecoderState();
            var start = (DecoderEvent.DefiniteConstructedStart) event;
            Collection<BERValue> nested = new ArrayList<>();
            decodeNested(nested);
            structure = new BERValue(start.tagClass, start.tag,
                    false, nested,
                    start.contentStart, start.contentEnd(),
                    start.elementStart, start.elementEnd());
        } else if (event instanceof DecoderEvent.IndefiniteConstructedStart) {
            decoder.next();
            checkDecoderState();
            var start = (DecoderEvent.IndefiniteConstructedStart) event;
            Collection<BERValue> nested = new ArrayList<>();
            var end = (DecoderEvent.IndefiniteConstructedEnd) decodeNested(nested);
            structure = new BERValue(start.tagClass, start.tag,
                    false, nested,
                    start.contentStart, end.contentEnd,
                    start.elementStart, end.elementEnd);
        } else {
            throw new AssertionError("unexpected event " + decoder);
        }
        decoder.next();
        return structure;
    }

    private void checkDecoderState() throws BERDecodingException {
        if (decoder.getState() == BERDecoder.State.FAILED) {
            throw new BERDecodingException(decoder.getError() + " " + decoder);
        }
    }

    private DecoderEvent.ConstructedEnd decodeNested(Collection<BERValue> nested)
            throws BERDecodingException, IOException {
        for (; ; ) {
            DecoderEvent event = nextEvent();
            if (event instanceof DecoderEvent.ConstructedEnd) {
                return (DecoderEvent.ConstructedEnd) event;
            }
            nested.add(decodeOne0(event));
        }
    }

    private DecoderEvent nextEvent() throws BERDecodingException, IOException {
        for (; ; ) {
            switch (decoder.getState()) {
            case FAILED:
                throw new BERDecodingException(decoder.getError() + " " + decoder);
            case NEED_BYTE:
                decoder.processByte(doReadByte(decoder.getPosition()));
                break;
            case EVENT:
                return decoder.getEvent();
            default:
                throw new AssertionError("unexpected state " + decoder);
            }
        }
    }
}
