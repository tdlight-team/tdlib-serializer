public final class transform_test {

    public static void main(String[] args) {
        assertExpression("int", "count", "other", "this.count != other.count");
        assertExpression("double", "latitude", "other", "Double.compare(this.latitude, other.latitude) != 0");
        assertExpression("String", "text", "other", "!Objects.equals(this.text, other.text)");
        assertExpression("byte[]", "data", "other", "!Arrays.equals(this.data, other.data)");
        assertExpression("String[]", "names", "other", "!Arrays.equals(this.names, other.names)");
        assertExpression("byte[][]", "chunks", "other", "!Arrays.deepEquals(this.chunks, other.chunks)");

        assertHashExpression("int", "count", "Integer.hashCode(this.count)");
        assertHashExpression("double", "latitude", "Double.hashCode(this.latitude)");
        assertHashExpression("String", "text", "this.text == null ? 0 : this.text.hashCode()");
        assertHashExpression("byte[]", "data", "Arrays.hashCode(this.data)");
        assertHashExpression("String[]", "names", "Arrays.hashCode(this.names)");
        assertHashExpression("byte[][]", "chunks", "Arrays.deepHashCode(this.chunks)");

        var arguments = java.util.List.of(
                argument("int", "count"),
                argument("long", "identifier"),
                argument("boolean", "enabled"),
                argument("double", "latitude"),
                argument("String", "text"),
                argument("byte[]", "data"),
                argument("String[]", "names"),
                argument("byte[][]", "chunks")
        );
        var expectedHashes = java.util.List.of(
                "Integer.hashCode(this.count)",
                "Long.hashCode(this.identifier)",
                "Boolean.hashCode(this.enabled)",
                "Double.hashCode(this.latitude)",
                "this.text == null ? 0 : this.text.hashCode()",
                "Arrays.hashCode(this.data)",
                "Arrays.hashCode(this.names)",
                "Arrays.deepHashCode(this.chunks)"
        );
        var actualHashes = transform.hashExpressions(arguments);
        if (!expectedHashes.equals(actualHashes)) {
            throw new AssertionError("Every field must contribute in declaration order. Expected "
                    + expectedHashes + ", got " + actualHashes);
        }

        if (Double.compare(+0.0d, -0.0d) == 0) {
            throw new AssertionError("Signed zero must remain distinct to match Double.hashCode");
        }
        if (Double.compare(Double.NaN, Double.longBitsToDouble(0x7ff8000000000001L)) != 0) {
            throw new AssertionError("All NaN values must compare equal to match Double.hashCode");
        }
        if (Double.hashCode(+0.0d) == Double.hashCode(-0.0d)) {
            throw new AssertionError("Signed zero hashes must remain distinct");
        }
        if (Double.hashCode(Double.NaN)
                != Double.hashCode(Double.longBitsToDouble(0x7ff8000000000001L))) {
            throw new AssertionError("All NaN values must have the same hash code");
        }
    }

    private static void assertExpression(String type, String fieldName, String otherClass, String expected) {
        var actual = transform.inequalityExpression(type, fieldName, otherClass);
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected '" + expected + "', got '" + actual + "'");
        }
    }

    private static void assertHashExpression(String type, String fieldName, String expected) {
        var actual = transform.hashExpression(type, fieldName);
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected '" + expected + "', got '" + actual + "'");
        }
    }

    private static transform.CurrentArgument argument(String type, String name) {
        var argument = new transform.CurrentArgument();
        argument.type = type;
        argument.name = name;
        return argument;
    }
}
