package name.funny.ber;

import name.funny.ber.DecoderEvent.DefiniteConstructedStart;
import name.funny.ber.DecoderEvent.IndefiniteConstructedEnd;
import name.funny.ber.DecoderEvent.IndefiniteConstructedStart;
import name.funny.ber.DecoderEvent.Primitive;

import static name.funny.ber.DecoderEvent.definiteConstructedEnd;

public final class BERDecoder {
    public enum State {
        DONE,
        FAILED,
        NEED_BYTE,
        SKIP,
        EVENT
    }

    public enum Error {
        BAD_TAG,
        BAD_EOC,
        PRIMITIVE_INDEFINITE,
        LARGE_TAG_LENGTH,
        LARGE_TAG_VALUE,
        OUT_OF_BOUNDS
    }

    public static BERDecoder singleElementDecoder() {
        return new BERDecoder(decodeOne, 0, -1);
    }

    public static BERDecoder singleElementDecoder(int position, int limit) {
        return new BERDecoder(decodeOne, position, limit);
    }

    public State getState() {
        return state;
    }

    public Error getError() {
        requireState(State.FAILED);
        return error;
    }

    public int getSkipLength() {
        requireState(State.SKIP);
        return skipLength;
    }

    public DecoderEvent getEvent() {
        requireState(State.EVENT);
        return event;
    }

    public void processByte(byte b) {
        requireState(State.NEED_BYTE);
        ++position;
        ByteProcessor bp = byteProcessor;
        byteProcessor = null;
        bp.apply(b).mutate(this);
    }

    public void recurse() {
        if (!(state == State.EVENT && event instanceof DefiniteConstructedStart)) {
            throw new IllegalStateException("cannot recurse in state " + state + " after event " + event);
        }
        event = null;
        dcsAdvance(true);
    }

    public void next() {
        switch (state) {
        case SKIP: {
            position += skipLength;
            simpleSdvance();
            break;
        }
        case EVENT:
            boolean isDCS = event instanceof DefiniteConstructedStart;
            event = null;
            if (isDCS) {
                dcsAdvance(false);
            } else {
                simpleSdvance();
            }
            break;
        default:
            throw new IllegalStateException("cannot continue in " + state + " state");
        }
    }

    private void dcsAdvance(boolean recurse) {
        BooleanProcessor bp = nextAfterDefiniteConstructedStart;
        nextAfterDefiniteConstructedStart = null;
        bp.apply(recurse).mutate(this);
    }

    private void simpleSdvance() {
        Mutator m = next;
        next = null;
        m.mutate(this);
    }

    public int getPosition() {
        return position;
    }

    public boolean hasLimit() {
        return limit != -1;
    }

    public boolean isEOF() {
        return position == limit;
    }

    @Override
    public String toString() {
        return "{state=" + state + ", position=" + position + " limit=" + limit + "}";
    }

    private void requireState(State requiredState) {
        if (state != requiredState) {
            throw new IllegalStateException("current state is " + state + ", required state is " + requiredState);
        }
    }

    private BERDecoder(Mutator mutator, int position, int limit) {
        this.position = position;
        this.limit = limit;
        mutator.mutate(this);
    }

    private static Mutator decodeOne(Mutator next) {
        return getPosition(elementStart -> read(firstTagByte -> {
            if (firstTagByte == 0) {
                return error(Error.BAD_TAG);
            }
            return decodeOne0(elementStart, firstTagByte, next);
        }));
    }

    private static Mutator decodeOne0(int elementStart, byte firstTagByte, Mutator next) {
        BERTagClass tagClass = BERTagClass.values()[(firstTagByte >> 6) & 0x03];
        boolean constructed = (firstTagByte & 0x20) != 0;
        return decodeTag(firstTagByte & 0x1f, tag -> read(firstLengthByte -> {
            if (firstLengthByte == (byte) 0x80) {
                if (!constructed) {
                    return error(Error.PRIMITIVE_INDEFINITE);
                }
                return getPosition(contentStart ->
                        emit(new IndefiniteConstructedStart(elementStart, tagClass, tag, contentStart),
                                decodeIndefiniteLengthContent(next)));
            } else {
                return decodeLength(firstLengthByte, valueLength -> getPosition(contentStart -> {
                    int contentEnd = contentStart + valueLength;
                    if (constructed) {
                        var event = new DefiniteConstructedStart(elementStart, tagClass, tag, contentStart, valueLength);
                        return emitDCS(event,
                                recurse -> {
                                    if (recurse) {
                                        return getLimit(outerLimit ->
                                                setLimit(contentEnd,
                                                        decodeAll(emit(definiteConstructedEnd,
                                                                setLimit(outerLimit, next)))));
                                    } else {
                                        return skip(valueLength, next);
                                    }
                                });
                    } else {
                        return emit(new Primitive(elementStart, tagClass, tag, contentStart, valueLength),
                                skip(valueLength, next));
                    }
                }));
            }
        }));
    }

    private static Mutator decodeAll(Mutator next) {
        return isEOF(eof -> eof ? next : decodeOne(decodeAll(next)));
    }

    @FunctionalInterface
    private interface Mutator {
        void mutate(BERDecoder decoder);
    }

    private static final Mutator decodeOne = BERDecoder.decodeOne(BERDecoder::setDone);

    private static Mutator decodeTag(int firstTagBits, IntProcessor next) {
        if (firstTagBits != 0x1f) {
            return next.apply(firstTagBits);
        }
        return decodeLongTag(0, next);
    }

    private static Mutator decodeLongTag(int tag, IntProcessor next) {
        if (tag > Integer.MAX_VALUE >> 7) {
            return error(Error.LARGE_TAG_VALUE);
        }
        return read(tagByte -> {
            int newTag = (tag << 7) | (tagByte & 0x7f);
            if ((tagByte & 0x80) == 0) {
                return next.apply(newTag);
            }
            return decodeLongTag(newTag, next);
        });
    }

    private static Mutator decodeLength(byte firstLengthByte, IntProcessor next) {
        if ((firstLengthByte & 0x80) == 0) {
            return next.apply(firstLengthByte);
        }
        return decodeLongLength(firstLengthByte & 0x7f, 0, next);
    }

    private static Mutator decodeLongLength(int lengthSize, int valueLength, IntProcessor next) {
        if (lengthSize == 0) {
            return next.apply(valueLength);
        }
        if (valueLength > Integer.MAX_VALUE >> 8) {
            return error(Error.LARGE_TAG_LENGTH);
        }
        return read(b -> decodeLongLength(lengthSize - 1, (valueLength << 8) | (b & 0xff), next));
    }

    private static Mutator decodeIndefiniteLengthContent(Mutator next) {
        return getPosition(elementStart -> read(firstTagByte -> {
            if (firstTagByte == 0) {
                return read(zeroLength -> {
                    if (zeroLength != 0) {
                        return error(Error.BAD_EOC);
                    }
                    var event = new IndefiniteConstructedEnd(elementStart, elementStart + 2);
                    return emit(event, next);
                });
            }
            return decodeOne0(elementStart, firstTagByte, decodeIndefiniteLengthContent(next));
        }));
    }

    private static Mutator error(Error error) {
        return decoder -> decoder.setError(error);
    }

    private static Mutator read(ByteProcessor next) {
        return decoder -> decoder.setNeedByte(next);
    }

    private static Mutator skip(int length, Mutator next) {
        return decoder -> decoder.setSkip(length, next);
    }

    private static Mutator emit(DecoderEvent event, Mutator next) {
        assert !(event instanceof DefiniteConstructedStart);
        return decoder -> decoder.setEvent(event, next);
    }

    private static Mutator emitDCS(DefiniteConstructedStart event, BooleanProcessor next) {
        return decoder -> decoder.setDCS(event, next);
    }

    private static Mutator setLimit(int limit, Mutator next) {
        return decoder -> {
            decoder.limit = limit;
            next.mutate(decoder);
        };
    }

    private static Mutator getLimit(IntProcessor next) {
        return decoder -> next.apply(decoder.limit).mutate(decoder);
    }

    private static Mutator getPosition(IntProcessor next) {
        return decoder -> next.apply(decoder.getPosition()).mutate(decoder);
    }

    private static Mutator isEOF(BooleanProcessor next) {
        return decoder -> next.apply(decoder.isEOF()).mutate(decoder);
    }

    private State state;
    private Error error;
    private DecoderEvent event;
    private ByteProcessor byteProcessor;
    private BooleanProcessor nextAfterDefiniteConstructedStart;
    private int skipLength;
    private Mutator next;
    private int position;
    private int limit;

    private void setDone() {
        state = State.DONE;
    }

    private void setError(Error error) {
        state = State.FAILED;
        this.error = error;
    }

    private void setNeedByte(ByteProcessor byteProcessor) {
        if (limit != -1 && position >= limit) {
            setError(Error.OUT_OF_BOUNDS);
        } else {
            state = State.NEED_BYTE;
            this.byteProcessor = byteProcessor;
        }
    }

    private void setSkip(int skipLength, Mutator next) {
        if (limit != -1 && position > limit - skipLength) {
            setError(Error.OUT_OF_BOUNDS);
        } else {
            state = State.SKIP;
            this.skipLength = skipLength;
            this.next = next;
        }
    }

    private void setEvent(DecoderEvent event, Mutator next) {
        state = State.EVENT;
        this.event = event;
        this.next = next;
    }

    private void setDCS(DefiniteConstructedStart event, BooleanProcessor next) {
        state = State.EVENT;
        this.event = event;
        nextAfterDefiniteConstructedStart = next;
    }

    @FunctionalInterface
    private interface BooleanProcessor {
        Mutator apply(boolean value);
    }

    @FunctionalInterface
    private interface ByteProcessor {
        Mutator apply(byte value);
    }

    @FunctionalInterface
    private interface IntProcessor {
        Mutator apply(int value);
    }
}
