package name.funny.ber;

import java.io.IOException;

public abstract class AbstractPullParser {
    protected final BERDecoder decoder;

    protected AbstractPullParser() {
        decoder = BERDecoder.singleElementDecoder();
    }
    protected AbstractPullParser(int position, int limit) {
        decoder = BERDecoder.singleElementDecoder(position, limit);
    }

    protected abstract byte nextByte(int currentPosition) throws IOException;

    protected void checkDecoderState() throws BERDecodingException {
        if (decoder.getState() == BERDecoder.State.FAILED) {
            throw new BERDecodingException(decoder.toString());
        }
    }

    protected DecoderEvent nextEvent() throws BERDecodingException, IOException {
        for (; ; ) {
            switch (decoder.getState()) {
            case FAILED:
                throw new BERDecodingException(decoder.toString());
            case NEED_BYTE:
                decoder.processByte(nextByte(decoder.getPosition()));
                break;
            case EVENT:
                return decoder.getEvent();
            default:
                throw new AssertionError("unexpected state " + decoder);
            }
        }
    }
}
