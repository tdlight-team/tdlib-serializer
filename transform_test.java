public final class transform_test {

    public static void main(String[] args) throws Exception {
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

        assertNullableObjectVectors();

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

    private static void assertNullableObjectVectors() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("tdapi-vector-test-");
        try {
            var input = directory.resolve("Input.java");
            java.nio.file.Files.writeString(input, """
                    package fixture;
                    public class TdApi {
                        /**
                         * A message.
                         */
                        public static class Message extends Object {
                            /**
                             * Message identifier.
                             */
                            public long id;
                            public static final int CONSTRUCTOR = 1;
                        }
                        /**
                         * A message sender.
                         */
                        public abstract static class MessageSender extends Object {
                        }
                        /**
                         * A user sender.
                         */
                        public static class MessageSenderUser extends MessageSender {
                            /**
                             * User identifier.
                             */
                            public long userId;
                            public static final int CONSTRUCTOR = 2;
                        }
                        /**
                         * A chat sender.
                         */
                        public static class MessageSenderChat extends MessageSender {
                            /**
                             * Chat identifier.
                             */
                            public long chatId;
                            public static final int CONSTRUCTOR = 3;
                        }
                        /**
                         * A message collection.
                         */
                        public static class Messages extends Object {
                            /**
                             * Messages including unavailable positions.
                             */
                            public Message[] messages;
                            /**
                             * Polymorphic senders.
                             */
                            public MessageSender[] senders;
                            /**
                             * Rows of polymorphic senders.
                             */
                            public MessageSender[][] rows;
                            public static final int CONSTRUCTOR = 4;
                        }
                    }
                    """);
            var verification = directory.resolve("Verify.java");
            java.nio.file.Files.writeString(verification, """
                    package fixture;
                    import java.io.*;
                    import java.util.Arrays;
                    public class Verify {
                        public static void main(String[] args) throws Exception {
                            TdApi.Message first = new TdApi.Message(10);
                            TdApi.Message last = new TdApi.Message(20);
                            TdApi.MessageSender user = new TdApi.MessageSenderUser(30);
                            TdApi.MessageSender chat = new TdApi.MessageSenderChat(40);
                            roundTrip(new TdApi.Messages(null, null, null));
                            roundTrip(new TdApi.Messages(new TdApi.Message[0],
                                    new TdApi.MessageSender[0], new TdApi.MessageSender[0][]));
                            roundTrip(new TdApi.Messages(new TdApi.Message[] {null},
                                    new TdApi.MessageSender[] {null}, new TdApi.MessageSender[][] {{null}}));
                            roundTrip(new TdApi.Messages(new TdApi.Message[] {null, first, null, last, null},
                                    new TdApi.MessageSender[] {null, user, null, chat, null},
                                    new TdApi.MessageSender[][] {{null, user, null, chat, null}, {}}));
                            roundTrip(new TdApi.Messages(new TdApi.Message[] {first, last},
                                    new TdApi.MessageSender[] {user, chat},
                                    new TdApi.MessageSender[][] {{user, chat}, {user}}));
                        }
                        private static void roundTrip(TdApi.Messages original) throws Exception {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            DataOutputStream output = new DataOutputStream(bytes);
                            original.serialize(output);
                            new TdApi.Message(99).serialize(output);
                            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
                            TdApi.Messages copy = (TdApi.Messages) TdApi.Deserializer.deserialize(input);
                            if (!Arrays.equals(original.messages, copy.messages)
                                    || !Arrays.equals(original.senders, copy.senders)
                                    || !Arrays.deepEquals(original.rows, copy.rows)) {
                                throw new AssertionError("Object vector positions or values changed");
                            }
                            TdApi.Message trailing = (TdApi.Message) TdApi.Deserializer.deserialize(input);
                            if (trailing.id != 99 || input.available() != 0) {
                                throw new AssertionError("Object vector consumed the next frame or left bytes unread");
                            }
                        }
                    }
                    """);
            var headers = java.nio.file.Path.of("tdlib-serializer", "headers.txt");
            if (!java.nio.file.Files.isRegularFile(headers)) {
                headers = java.nio.file.Path.of("headers.txt");
            }
            for (boolean sealed : new boolean[] {false, true}) {
                var output = java.nio.file.Files.createDirectory(directory.resolve(sealed ? "sealed" : "legacy"));
                var generated = output.resolve("TdApi.java");
                transform.main(new String[] {"--source", input.toString(), "--output", generated.toString(),
                        "--java17", Boolean.toString(sealed), "--headers", headers.toString()});
                int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                        "--release", sealed ? "17" : "8", "-d", output.toString(),
                        generated.toString(), verification.toString());
                if (result != 0) {
                    throw new AssertionError("Generated " + (sealed ? "sealed" : "legacy") + " API failed to compile");
                }
                try (var loader = new java.net.URLClassLoader(new java.net.URL[] {output.toUri().toURL()})) {
                    loader.loadClass("fixture.Verify").getMethod("main", String[].class)
                            .invoke(null, (java.lang.Object) new String[0]);
                }
            }
        } finally {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                    java.nio.file.Files.delete(path);
                }
            }
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
