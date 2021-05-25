package name.funny.ber;

import java.util.function.IntFunction;

import static name.funny.ber.Decoder.State.DONE;
import static name.funny.ber.Decoder.State.FAILED;
import static name.funny.ber.Decoder.State.NEED_BYTE;
import static name.funny.ber.Decoder.State.SKIP;

public final class Decoder {
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

    @FunctionalInterface
    public interface Mutator {
        void mutate(Decoder decoder);
    }

    public static final Mutator done = decoder -> decoder.state = DONE;

    public static Mutator error(Error error) {
        return decoder -> decoder.setError(error);
    }

    public static Mutator read(ByteProcessor next) {
        return decoder -> decoder.setNeedByte(next);
    }

    public static Mutator skip(int length, Mutator next) {
        return decoder -> decoder.setSkip(length, next);
    }

    public static Mutator setLimit(int limit, Mutator next) {
        return decoder -> {
            decoder.limit = limit;
            next.mutate(decoder);
        };
    }

    public static Mutator getLimit(IntFunction<Mutator> next) {
        return decoder -> next.apply(decoder.limit).mutate(decoder);
    }

    public static Mutator getPosition(IntFunction<Mutator> next) {
        return decoder -> next.apply(decoder.getPosition()).mutate(decoder);
    }

    public static Mutator isEOF(BooleanProcessor next) {
        return decoder -> next.apply(decoder.isEOF()).mutate(decoder);
    }

    public Decoder(Mutator mutator) {
        this(mutator, 0, -1);
    }

    public Decoder(Mutator mutator, int position, int limit) {
        this.position = position;
        this.limit = limit;
        mutator.mutate(this);
    }

    public State getState() {
        return state;
    }

    public Error getError() {
        assert state == FAILED;
        return error;
    }

    public int getSkipLength() {
        assert state == SKIP;
        return skipLength;
    }

    public void processByte(byte b) {
        assert state == NEED_BYTE;
        ByteProcessor next = byteProcessor;
        byteProcessor = null;
        ++position;
        next.apply(b).mutate(this);
    }

    public void skip() {
        assert state == SKIP;
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

    private State state;
    private Error error;
    private ByteProcessor byteProcessor;
    private int skipLength;
    private Mutator skipContinuation;
    private int position;
    private int limit;

    private void setError(Error error) {
        state = FAILED;
        this.error = error;
    }

    private void setNeedByte(ByteProcessor byteProcessor) {
        if (limit != -1 && position >= limit) {
            setError(Error.OUT_OF_BOUNDS);
        } else {
            state = NEED_BYTE;
            this.byteProcessor = byteProcessor;
        }
    }

    private void setSkip(int skipLength, Mutator skipContinuation) {
        if (limit != -1 && position > limit - skipLength) {
            setError(Error.OUT_OF_BOUNDS);
        } else {
            state = SKIP;
            this.skipLength = skipLength;
            this.skipContinuation = skipContinuation;
        }
    }

    @FunctionalInterface
    interface BooleanProcessor {
        Mutator apply(boolean value);
    }

    @FunctionalInterface
    interface ByteProcessor {
        Mutator apply(byte value);
    }
}
