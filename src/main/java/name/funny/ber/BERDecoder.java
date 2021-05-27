package name.funny.ber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.IntFunction;

public final class BERDecoder {
    public enum State {
        DONE,
        FAILED,
        NEED_BYTE,
        SKIP
    }

    public enum Error {
        BAD_EOC,
        PRIMITIVE_INDEFINITE,
        LARGE_TAG_LENGTH,
        LARGE_TAG_VALUE,
        OUT_OF_BOUNDS
    }

    public State getState() {
        return state;
    }

    public Error getError() {
        assert state == State.FAILED;
        return error;
    }

    public int getSkipLength() {
        assert state == State.SKIP;
        return skipLength;
    }

    public void processByte(byte b) {
        assert state == State.NEED_BYTE;
        ByteProcessor next = byteProcessor;
        byteProcessor = null;
        ++position;
        next.apply(b).mutate(this);
    }

    public void skip() {
        assert state == State.SKIP;
        Mutator next = skipContinuation;
        skipContinuation = null;
        position += skipLength;
        next.mutate(this);
    }

    public int getPosition() {
        return position;
    }

    public boolean isEOF() {
        return position == limit;
    }

    @Override
    public String toString() {
        return "{state=" + state + ", position=" + position + " limit=" + limit + "}";
    }

    BERDecoder(Mutator mutator) {
        this(mutator, 0, -1);
    }

    BERDecoder(Mutator mutator, int position, int limit) {
        this.position = position;
        this.limit = limit;
        mutator.mutate(this);
    }

    static Mutator decodeOne(Function<BERValue, Mutator> next) {
        return getPosition(elementStart -> read(firstTagByte -> {
            if (firstTagByte == 0) {
                return read(zeroLength -> {
                    if (zeroLength != 0) {
                        return error(Error.BAD_EOC);
                    }
                    return next.apply(null);
                });
            } else {
                BERTagClass tagClass = BERTagClass.values()[(firstTagByte >> 6) & 0x03];
                boolean constructed = (firstTagByte & 0x20) != 0;
                return decodeTag(firstTagByte & 0x1f, tag -> read(firstLengthByte -> {
                    if (firstLengthByte == (byte) 0x80) {
                        if (!constructed) {
                            return error(Error.PRIMITIVE_INDEFINITE);
                        }
                        return getPosition(contentStart ->
                                decodeIndefiniteLengthContent(new ArrayList<>(), nested -> getPosition(elementEnd -> {
                                    int contentEnd = elementEnd - 2;
                                    BERValue structure = new BERValue(tagClass, tag,
                                            true, nested, contentStart, contentEnd,
                                            elementStart, elementEnd);
                                    return next.apply(structure);
                                })));
                    } else {
                        return decodeLength(firstLengthByte, valueLength -> getPosition(contentStart -> {
                            int contentEnd = contentStart + valueLength;
                            if (constructed) {
                                return getLimit(outerLimit ->
                                        setLimit(contentEnd, decodeAll(nested -> {
                                            BERValue structure =
                                                    new BERValue(tagClass, tag,
                                                            false, nested,
                                                            contentStart, contentEnd,
                                                            elementStart, contentEnd);
                                            return setLimit(outerLimit, next.apply(structure));
                                        })));
                            } else {
                                BERValue structure = new BERValue(tagClass, tag,
                                        false, null,
                                        contentStart, contentEnd,
                                        elementStart, contentEnd);
                                return skip(valueLength, next.apply(structure));
                            }
                        }));
                    }
                }));
            }
        }));
    }

    static Mutator decodeAll(Function<Collection<BERValue>, Mutator> next) {
        return decodeAll0(new ArrayList<>(), next);
    }

    @FunctionalInterface
    interface Mutator {
        void mutate(BERDecoder decoder);
    }

    static final Mutator done = decoder -> decoder.state = State.DONE;

    private static Mutator decodeTag(int firstTagBits, IntFunction<Mutator> next) {
        if (firstTagBits != 0x1f) {
            return next.apply(firstTagBits);
        }
        return decodeLongTag(0, next);
    }

    private static Mutator decodeLongTag(int tag, IntFunction<Mutator> next) {
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

    private static Mutator decodeLength(byte firstLengthByte, IntFunction<Mutator> next) {
        if ((firstLengthByte & 0x80) == 0) {
            return next.apply(firstLengthByte);
        }
        return decodeLongLength(firstLengthByte & 0x7f, 0, next);
    }

    private static Mutator decodeLongLength(int lengthSize, int valueLength, IntFunction<Mutator> next) {
        if (lengthSize == 0) {
            return next.apply(valueLength);
        }
        if (valueLength > Integer.MAX_VALUE >> 8) {
            return error(Error.LARGE_TAG_LENGTH);
        }
        return read(b -> decodeLongLength(lengthSize - 1, (valueLength << 8) | (b & 0xff), next));
    }

    private static Mutator decodeIndefiniteLengthContent(
            Collection<BERValue> nested,
            Function<Collection<BERValue>, Mutator> next) {
        return decodeOne(berValue -> {
            if (berValue == null) {
                return next.apply(nested);
            }
            nested.add(berValue);
            return decodeIndefiniteLengthContent(nested, next);
        });
    }

    private static Mutator decodeAll0(
            Collection<BERValue> nested,
            Function<Collection<BERValue>, Mutator> next) {
        return isEOF(eof -> {
            if (eof) {
                return next.apply(nested);
            }
            return decodeOne(berValue -> {
                nested.add(berValue);
                return decodeAll0(nested, next);
            });
        });
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

    private static Mutator setLimit(int limit, Mutator next) {
        return decoder -> {
            decoder.limit = limit;
            next.mutate(decoder);
        };
    }

    private static Mutator getLimit(IntFunction<Mutator> next) {
        return decoder -> next.apply(decoder.limit).mutate(decoder);
    }

    private static Mutator getPosition(IntFunction<Mutator> next) {
        return decoder -> next.apply(decoder.getPosition()).mutate(decoder);
    }

    private static Mutator isEOF(BooleanProcessor next) {
        return decoder -> next.apply(decoder.isEOF()).mutate(decoder);
    }

    private State state;
    private Error error;
    private ByteProcessor byteProcessor;
    private int skipLength;
    private Mutator skipContinuation;
    private int position;
    private int limit;

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

    private void setSkip(int skipLength, Mutator skipContinuation) {
        if (limit != -1 && position > limit - skipLength) {
            setError(Error.OUT_OF_BOUNDS);
        } else {
            state = State.SKIP;
            this.skipLength = skipLength;
            this.skipContinuation = skipContinuation;
        }
    }

    @FunctionalInterface
    private interface BooleanProcessor {
        Mutator apply(boolean value);
    }

    @FunctionalInterface
    private interface ByteProcessor {
        Mutator apply(byte value);
    }
}
