package name.funny.ber;

public interface DecoderEvent {
    interface Constructed extends DecoderEvent {
    }

    abstract class ConstructedStart implements Constructed {
        public final int elementStart;
        public final BERTagClass tagClass;
        public final int tag;
        public final int contentStart;

        protected ConstructedStart(int elementStart, BERTagClass tagClass, int tag, int contentStart) {
            this.elementStart = elementStart;
            this.tagClass = tagClass;
            this.tag = tag;
            this.contentStart = contentStart;
        }
    }

    final class IndefiniteConstructedStart extends ConstructedStart {
        IndefiniteConstructedStart(int elementStart, BERTagClass tagClass, int tag, int contentStart) {
            super(elementStart, tagClass, tag, contentStart);
        }
    }

    final class DefiniteConstructedStart extends ConstructedStart {
        public final int valueLength;

        public int contentEnd() {
            return contentStart + valueLength;
        }

        public int elementEnd() {
            return contentEnd();
        }

        DefiniteConstructedStart(int elementStart, BERTagClass tagClass, int tag, int contentStart, int valueLength) {
            super(elementStart, tagClass, tag, contentStart);
            this.valueLength = valueLength;
        }
    }

    interface ConstructedEnd extends Constructed {
    }

    final class IndefiniteConstructedEnd implements ConstructedEnd {
        public final int contentEnd;
        public final int elementEnd;

        IndefiniteConstructedEnd(int contentEnd, int elementEnd) {
            this.contentEnd = contentEnd;
            this.elementEnd = elementEnd;
        }
    }

    DefiniteConstructedEnd definiteConstructedEnd = new DefiniteConstructedEnd();

    final class DefiniteConstructedEnd implements ConstructedEnd {
        private DefiniteConstructedEnd() {
        }
    }

    final class Primitive implements DecoderEvent {
        public final int elementStart;
        public final BERTagClass tagClass;
        public final int tag;
        public final int contentStart;
        public final int valueLength;

        public int contentEnd() {
            return contentStart + valueLength;
        }

        public int elementEnd() {
            return contentEnd();
        }

        Primitive(int elementStart, BERTagClass tagClass, int tag, int contentStart, int valueLength) {
            this.elementStart = elementStart;
            this.tagClass = tagClass;
            this.tag = tag;
            this.contentStart = contentStart;
            this.valueLength = valueLength;
        }
    }
}
