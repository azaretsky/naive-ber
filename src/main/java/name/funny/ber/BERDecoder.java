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
        ByteProcessor processor = byteProcessor;
        byteProcessor = null;
        processor.apply(b).advance(this);
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
            simpleAdvance();
            break;
        }
        case EVENT:
            boolean isDCS = event instanceof DefiniteConstructedStart;
            event = null;
            if (isDCS) {
                dcsAdvance(false);
            } else {
                simpleAdvance();
            }
            break;
        default:
            throw new IllegalStateException("cannot continue in " + state + " state");
        }
    }

    private void dcsAdvance(boolean recurse) {
        BooleanProcessor processor = nextAfterDCS;
        nextAfterDCS = null;
        processor.apply(recurse).advance(this);
    }

    private void simpleAdvance() {
        Executor executor = next;
        next = null;
        executor.advance(this);
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
        StringBuilder builder = new StringBuilder()
                .append("{state=").append(state)
                .append(", position=").append(position)
                .append(", limit=").append(limit);
        switch (state) {
        case FAILED:
            builder.append(", error=").append(error);
            break;
        case SKIP:
            builder.append(", skip-length=").append(skipLength);
            break;
        case EVENT:
            builder.append(", event=").append(event);
            break;
        default:
            // no additional information for DONE and NEED_BYTE
        }
        return builder.append('}').toString();
    }

    private void requireState(State requiredState) {
        if (state != requiredState) {
            throw new IllegalStateException("current state is " + state + ", required state is " + requiredState);
        }
    }

    private BERDecoder(Executor executor, int position, int limit) {
        this.position = position;
        this.executeSetLimit(limit, executor);
    }

    private static Executor decodeOne(Executor next) {
        return getPosition(elementStart -> read(firstTagByte -> {
            if (firstTagByte == 0) {
                return error(Error.BAD_TAG);
            }
            return decodeOne0(elementStart, firstTagByte, next);
        }));
    }

    private static Executor decodeOne0(int elementStart, byte firstTagByte, Executor next) {
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

    private static Executor decodeAll(Executor next) {
        return isEOF(eof -> eof ? next : decodeOne(decodeAll(next)));
    }

    @FunctionalInterface
    private interface Executor {
        void advance(BERDecoder decoder);
    }

    private static final Executor decodeOne = BERDecoder.decodeOne(BERDecoder::setDone);

    private static Executor decodeTag(int firstTagBits, IntProcessor next) {
        if (firstTagBits != 0x1f) {
            return next.apply(firstTagBits);
        }
        return decodeLongTag(0, next);
    }

    private static Executor decodeLongTag(int tag, IntProcessor next) {
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

    private static Executor decodeLength(byte firstLengthByte, IntProcessor next) {
        if ((firstLengthByte & 0x80) == 0) {
            return next.apply(firstLengthByte);
        }
        return decodeLongLength(firstLengthByte & 0x7f, 0, next);
    }

    private static Executor decodeLongLength(int lengthSize, int valueLength, IntProcessor next) {
        if (lengthSize == 0) {
            return next.apply(valueLength);
        }
        if (valueLength > Integer.MAX_VALUE >> 8) {
            return error(Error.LARGE_TAG_LENGTH);
        }
        return read(b -> decodeLongLength(lengthSize - 1, (valueLength << 8) | (b & 0xff), next));
    }

    private static Executor decodeIndefiniteLengthContent(Executor next) {
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

    private static Executor error(Error error) {
        return decoder -> decoder.setError(error);
    }

    private static Executor read(ByteProcessor next) {
        return decoder -> decoder.setNeedByte(next);
    }

    private static Executor skip(int length, Executor next) {
        return decoder -> decoder.setSkip(length, next);
    }

    private static Executor emit(DecoderEvent event, Executor next) {
        assert !(event instanceof DefiniteConstructedStart);
        return decoder -> decoder.setEvent(event, next);
    }

    private static Executor emitDCS(DefiniteConstructedStart event, BooleanProcessor next) {
        return decoder -> decoder.setDCS(event, next);
    }

    private static Executor setLimit(int limit, Executor next) {
        return decoder -> decoder.executeSetLimit(limit, next);
    }

    private static Executor getLimit(IntProcessor next) {
        return decoder -> decoder.executeGetLimit(next);
    }

    private static Executor getPosition(IntProcessor next) {
        return decoder -> decoder.executeGetPosition(next);
    }

    private static Executor isEOF(BooleanProcessor next) {
        return decoder -> decoder.executeIsEOF(next);
    }

    private State state;
    private Error error;
    private DecoderEvent event;
    private ByteProcessor byteProcessor;
    private BooleanProcessor nextAfterDCS;
    private int skipLength;
    private Executor next;
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

    private void setSkip(int skipLength, Executor next) {
        if (limit != -1 && position > limit - skipLength) {
            setError(Error.OUT_OF_BOUNDS);
        } else {
            state = State.SKIP;
            this.skipLength = skipLength;
            this.next = next;
        }
    }

    private void setEvent(DecoderEvent event, Executor next) {
        state = State.EVENT;
        this.event = event;
        this.next = next;
    }

    private void setDCS(DefiniteConstructedStart event, BooleanProcessor next) {
        state = State.EVENT;
        this.event = event;
        nextAfterDCS = next;
    }

    private void executeSetLimit(int limit, Executor next) {
        this.limit = limit;
        next.advance(this);
    }

    private void executeGetLimit(IntProcessor next) {
        next.apply(limit).advance(this);
    }

    private void executeGetPosition(IntProcessor next) {
        next.apply(position).advance(this);
    }

    private void executeIsEOF(BooleanProcessor next) {
        next.apply(position == limit).advance(this);
    }

    @FunctionalInterface
    private interface BooleanProcessor {
        Executor apply(boolean value);
    }

    @FunctionalInterface
    private interface ByteProcessor {
        Executor apply(byte value);
    }

    @FunctionalInterface
    private interface IntProcessor {
        Executor apply(int value);
    }
}
