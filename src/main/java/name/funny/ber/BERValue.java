package name.funny.ber;

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

public class BERValue implements Iterable<BERValue> {
    private final BERTagClass tagClass;
    private final int tag;
    private final boolean indefiniteLength;
    private final Collection<BERValue> nested;
    private final int contentStart;
    private final int contentLength;
    private final int elementStart;
    private final int elementLength;

    BERValue(BERTagClass tagClass, int tag, boolean indefiniteLength,
             Collection<BERValue> nested,
             int contentStart, int contentEnd,
             int elementStart, int elementEnd) {
        Objects.requireNonNull(tagClass, "tagClass");
        if (tag < 0 || tagClass == BERTagClass.Universal && tag == 0) {
            throw new IllegalArgumentException("Bad tag " + tagClass + " " + tag);
        }
        this.tagClass = tagClass;
        this.tag = tag;
        this.indefiniteLength = indefiniteLength;
        this.nested = nested;
        this.contentStart = contentStart;
        this.contentLength = contentEnd - contentStart;
        this.elementStart = elementStart;
        this.elementLength = elementEnd - elementStart;
    }

    public BERTagClass getTagClass() {
        return tagClass;
    }

    public int getTag() {
        return tag;
    }

    public boolean isIndefiniteLength() {
        return indefiniteLength;
    }

    public boolean isConstructed() {
        return nested != null;
    }

    public boolean isEmpty() {
        return isConstructed() ? !nested.iterator().hasNext() : contentLength == 0;
    }

    public int getElementStart() {
        return elementStart;
    }

    public int getElementLength() {
        return elementLength;
    }

    public int getContentStart() {
        return contentStart;
    }

    public int getContentLength() {
        return contentLength;
    }

    @Override
    public Iterator<BERValue> iterator() {
        if (isConstructed()) {
            return nested.iterator();
        }
        throw new IllegalStateException("primitive");
    }
}
