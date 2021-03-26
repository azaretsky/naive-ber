package name.funny.ber;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

public class BERValue implements Iterable<BERValue> {
    private final BERTagClass tagClass;
    private final int tag;
    private final boolean sorted;
    private final Collection<BERValue> nested;
    private final ByteBuffer contentBuffer;
    private final ByteBuffer elementBuffer;

    public static BERValue primitive(BERTagClass tagClass, int tag) {
        return new BERValue(tagClass, tag, null);
    }

    public static BERValue primitive(BERTagClass tagClass, int tag, byte[] content) {
        return new BERValue(tagClass, tag, ByteBuffer.wrap(content));
    }

    public static BERValue constructed(BERTagClass tagClass, int tag, boolean sorted, BERValue... nested) {
        return new BERValue(tagClass, tag, sorted, Arrays.asList(nested));
    }

    public static BERValue constructed(BERTagClass tagClass, int tag, boolean sorted, Collection<BERValue> nested) {
        return new BERValue(tagClass, tag, sorted, nested);
    }

    private BERValue(BERTagClass tagClass, int tag, ByteBuffer contentBuffer) {
        this(tagClass, tag, false, null, null, contentBuffer);
    }

    private BERValue(BERTagClass tagClass, int tag, boolean sorted, Collection<BERValue> nested) {
        this(tagClass, tag, sorted, Objects.requireNonNull(nested, "nested"), null, null);
    }

    BERValue(BERTagClass tagClass, int tag,
             boolean sorted, Collection<BERValue> nested,
             ByteBuffer elementBuffer,
             ByteBuffer contentBuffer) {
        Objects.requireNonNull(tagClass, "tagClass");
        if (tag < 0 || tagClass == BERTagClass.Universal && tag == 0) {
            throw new IllegalArgumentException("Bad tag " + tagClass + " " + tag);
        }
        this.tagClass = tagClass;
        this.tag = tag;
        this.sorted = sorted;
        this.nested = nested;
        this.elementBuffer = elementBuffer;
        this.contentBuffer = contentBuffer;
    }

    public BERTagClass getTagClass() {
        return tagClass;
    }

    public int getTag() {
        return tag;
    }

    public boolean isSorted() {
        return sorted;
    }

    public boolean isConstructed() {
        return nested != null;
    }

    public ByteBuffer getElementBuffer() {
        return elementBuffer;
    }

    public ByteBuffer getContentBuffer() {
        return contentBuffer;
    }

    public boolean isEmpty() {
        return isConstructed() ? !nested.iterator().hasNext() : contentBuffer == null || contentBuffer.limit() == 0;
    }

    public BERValue implicit(int tag) {
        return new BERValue(BERTagClass.Context, tag, sorted, nested, null, contentBuffer);
    }

    public BERValue explicit(int tag) {
        return BERValue.constructed(BERTagClass.Context, tag, false, this);
    }

    @Override
    public Iterator<BERValue> iterator() {
        if (isConstructed()) {
            return nested.iterator();
        }
        throw new IllegalStateException("primitive");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof BERValue)) {
            return false;
        }
        BERValue that = (BERValue) obj;
        if (this.isConstructed() != that.isConstructed()
                || this.getTagClass() != that.getTagClass()
                || this.getTag() != that.getTag()) {
            return false;
        }
        if (isConstructed()) {
            return this.nested.equals(that.nested);
        } else {
            ByteBuffer thisValue = this.getContentBuffer();
            thisValue.rewind();
            ByteBuffer thatValue = that.getContentBuffer();
            thatValue.rewind();
            return thisValue.equals(thatValue);
        }
    }

    @Override
    public int hashCode() {
        if (!isConstructed()) {
            contentBuffer.rewind();
        }
        return Objects.hash(tagClass, tag, nested, isConstructed() ? null : contentBuffer);
    }

    @Override
    public String toString() {
        return BERDumper.toString(this);
    }
}
