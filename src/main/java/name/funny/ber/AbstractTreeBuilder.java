package name.funny.ber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

abstract class AbstractTreeBuilder extends AbstractPullParser {
    protected AbstractTreeBuilder() {
    }

    protected AbstractTreeBuilder(int position, int limit) {
        super(position, limit);
    }

    public BERValue decodeOne() throws BERDecodingException {
        BERValue berValue;
        try {
            berValue = decodeOne0(nextEvent());
            checkDecoderState();
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

    protected abstract void skip(int currentPosition, int skipLength) throws IOException;

    private BERValue decodeOne0(DecoderEvent event) throws BERDecodingException, IOException {
        if (event instanceof DecoderEvent.Primitive) {
            var primitive = (DecoderEvent.Primitive) event;
            skip(decoder.getPosition(), primitive.valueLength);
            decoder.next();
            return new BERValue(primitive.tagClass, primitive.tag,
                    false, null,
                    primitive.contentStart, primitive.contentEnd(),
                    primitive.elementStart, primitive.elementEnd());
        } else if (event instanceof DecoderEvent.DefiniteConstructedStart) {
            var start = (DecoderEvent.DefiniteConstructedStart) event;
            decoder.recurse();
            Collection<BERValue> nested = new ArrayList<>();
            decodeNested(nested);
            return new BERValue(start.tagClass, start.tag,
                    false, nested,
                    start.contentStart, start.contentEnd(),
                    start.elementStart, start.elementEnd());
        } else if (event instanceof DecoderEvent.IndefiniteConstructedStart) {
            var start = (DecoderEvent.IndefiniteConstructedStart) event;
            decoder.next();
            Collection<BERValue> nested = new ArrayList<>();
            var end = (DecoderEvent.IndefiniteConstructedEnd) decodeNested(nested);
            return new BERValue(start.tagClass, start.tag,
                    false, nested,
                    start.contentStart, end.contentEnd,
                    start.elementStart, end.elementEnd);
        } else {
            throw new AssertionError("unexpected event " + decoder);
        }
    }

    private DecoderEvent.ConstructedEnd decodeNested(Collection<BERValue> nested)
            throws BERDecodingException, IOException {
        for (; ; ) {
            DecoderEvent event = nextEvent();
            if (event instanceof DecoderEvent.ConstructedEnd) {
                decoder.next();
                return (DecoderEvent.ConstructedEnd) event;
            }
            nested.add(decodeOne0(event));
        }
    }
}
