package name.funny.ber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.IntFunction;

public class BERDecoder {
    private BERDecoder() {
    }

    public static Decoder.Mutator decodeOne(Function<BERValue, Decoder.Mutator> next) {
        return Decoder.getPosition(elementStart -> Decoder.read(firstTagByte -> {
            if (firstTagByte == 0) {
                return Decoder.read(zeroLength -> {
                    if (zeroLength != 0) {
                        return Decoder.error(Decoder.Error.BAD_EOC);
                    }
                    return next.apply(null);
                });
            } else {
                BERTagClass tagClass = BERTagClass.values()[(firstTagByte >> 6) & 0x03];
                boolean constructed = (firstTagByte & 0x20) != 0;
                return decodeTag(firstTagByte & 0x1f, tag -> Decoder.read(firstLengthByte -> {
                    if (firstLengthByte == (byte) 0x80) {
                        if (!constructed) {
                            return Decoder.error(Decoder.Error.PRIMITIVE_INDEFINITE);
                        }
                        return Decoder.getPosition(contentStart ->
                                decodeIndefiniteLengthContent(new ArrayList<>(), nested -> Decoder.getPosition(elementEnd -> {
                                    int contentEnd = elementEnd - 2;
                                    BERValue structure = new BERValue(tagClass, tag,
                                            true, nested, contentStart, contentEnd,
                                            elementStart, elementEnd);
                                    return next.apply(structure);
                                })));
                    } else {
                        return decodeLength(firstLengthByte, valueLength -> Decoder.getPosition(contentStart -> {
                            int contentEnd = contentStart + valueLength;
                            if (constructed) {
                                return Decoder.getLimit(outerLimit ->
                                        Decoder.setLimit(contentEnd, decodeAll(nested -> {
                                            BERValue structure =
                                                    new BERValue(tagClass, tag,
                                                            false, nested,
                                                            contentStart, contentEnd,
                                                            elementStart, contentEnd);
                                            return Decoder.setLimit(outerLimit, next.apply(structure));
                                        })));
                            } else {
                                BERValue structure = new BERValue(tagClass, tag,
                                        false, null,
                                        contentStart, contentEnd,
                                        elementStart, contentEnd);
                                return Decoder.skip(valueLength, next.apply(structure));
                            }
                        }));
                    }
                }));
            }
        }));
    }

    public static Decoder.Mutator decodeAll(Function<Collection<BERValue>, Decoder.Mutator> next) {
        return decodeAll0(new ArrayList<>(), next);
    }

    private static Decoder.Mutator decodeTag(int firstTagBits, IntFunction<Decoder.Mutator> next) {
        if (firstTagBits != 0x1f) {
            return next.apply(firstTagBits);
        }
        return decodeLongTag(0, next);
    }

    private static Decoder.Mutator decodeLongTag(int tag, IntFunction<Decoder.Mutator> next) {
        if (tag > Integer.MAX_VALUE >> 7) {
            return Decoder.error(Decoder.Error.LARGE_TAG_VALUE);
        }
        return Decoder.read(tagByte -> {
            int newTag = (tag << 7) | (tagByte & 0x7f);
            if ((tagByte & 0x80) == 0) {
                return next.apply(newTag);
            }
            return decodeLongTag(newTag, next);
        });
    }

    private static Decoder.Mutator decodeLength(byte firstLengthByte, IntFunction<Decoder.Mutator> next) {
        if ((firstLengthByte & 0x80) == 0) {
            return next.apply(firstLengthByte);
        }
        return decodeLongLength(firstLengthByte & 0x7f, 0, next);
    }

    private static Decoder.Mutator decodeLongLength(int lengthSize, int valueLength, IntFunction<Decoder.Mutator> next) {
        if (lengthSize == 0) {
            return next.apply(valueLength);
        }
        if (valueLength > Integer.MAX_VALUE >> 8) {
            return Decoder.error(Decoder.Error.LARGE_TAG_LENGTH);
        }
        return Decoder.read(b -> decodeLongLength(lengthSize - 1, (valueLength << 8) | (b & 0xff), next));
    }

    private static Decoder.Mutator decodeIndefiniteLengthContent(
            Collection<BERValue> nested,
            Function<Collection<BERValue>, Decoder.Mutator> next) {
        return decodeOne(berValue -> {
            if (berValue == null) {
                return next.apply(nested);
            }
            nested.add(berValue);
            return decodeIndefiniteLengthContent(nested, next);
        });
    }

    private static Decoder.Mutator decodeAll0(
            Collection<BERValue> nested,
            Function<Collection<BERValue>, Decoder.Mutator> next) {
        return Decoder.isEOF(eof -> {
            if (eof) {
                return next.apply(nested);
            }
            return decodeOne(berValue -> {
                nested.add(berValue);
                return decodeAll0(nested, next);
            });
        });
    }
}
