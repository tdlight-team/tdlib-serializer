import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class transform {

    static List<String> NATIVES = List.of("int", "long", "boolean", "String", "double", "byte[]", "byte");
    static List<String> PRIMITIVES = List.of("int", "long", "boolean", "double", "byte");
    static Map<String, String> NATIVE_TO_OBJ = Map.of("boolean", "Boolean",
            "byte[]", "Byte",
            "byte", "Byte",
            "int", "Integer",
            "short", "Short",
            "char", "Character",
            "long", "Long",
            "float", "Float",
            "double", "Double",
            "String", "String");

    public static void main(String[] args) throws IOException, URISyntaxException {
        var headersPath = Path.of(transform.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve("headers.txt");

        // Parse arguments
        Path sourcePath = null;
        Path outputPath = null;
        boolean tempJava17 = false;
        boolean tempOverwrite = false;
        for (int i = 0; i < args.length; i++) {
            if (i + 1 >= args.length) throw getHelp();
            switch (args[i]) {
                case "--source": {
                    sourcePath = Path.of(args[++i]);
                    break;
                }
                case "--output": {
                    outputPath = Path.of(args[++i]);
                    break;
                }
                case "--java17": {
                    tempJava17 = Boolean.parseBoolean(args[++i]);
                    break;
                }
                case "--overwrite": {
                    tempOverwrite = Boolean.parseBoolean(args[++i]);
                    break;
                }
                case "--headers": {
                    headersPath = Path.of(args[++i]);
                    if (!Files.isRegularFile(headersPath)) {
                        System.err.println("The headers file is not a regular file: " + headersPath);
                        System.exit(1);
                    }
                    if (!Files.isReadable(headersPath)) {
                        System.err.println("The headers file is not a readable file: " + headersPath);
                        System.exit(1);
                    }
                    break;
                }
                default: throw getHelp();
            }
        }
        // Check required arguments
        if (sourcePath == null || outputPath == null) throw getHelp();
        boolean java17 = tempJava17;
        boolean overwrite = tempOverwrite;
        var headers = Files.readString(headersPath, StandardCharsets.UTF_8);

        String package_ = null;
        Map<String, TdType> functionClasses = new LinkedHashMap<>();
        Map<String, TdType> objectClasses = new LinkedHashMap<>();
        Map<String, List<String>> containerClassesDocs = new LinkedHashMap<>();
        List<CurrentArgument> currentArguments = new ArrayList<>();

        // Parse input document
        {
            int currentConstructor = -1;
            String currentClassName = null;
            boolean insideAbstractClass = false;
            boolean insideObjectClass = false;
            boolean insideFunctionClass = false;
            boolean insideObjectContainerClass = false;
            String containerClassName = null;
            List<String> currentClassDocs = new ArrayList<>();
            int functionDepth = 0;

            // Read the input document
            List<String> lines;
            try (var l = Files.lines(sourcePath)) {
                lines = l.map(String::strip).map(x -> !x.strip().startsWith("*") ? x.replaceAll("<[a-zA-Z_]*>", "") : x).collect(Collectors.toList());
            }

            int no = 0;
            for (String line : lines) {
                var keywords = Arrays.stream(line.split("[ \t]")).filter(l -> !l.isBlank()).collect(Collectors.toList());

                lineProcess: {
                    if (keywords.isEmpty()) {
                        break lineProcess;
                    }

                    var firstKeyword = keywords.get(0);
                    var lastKeyword = keywords.get(keywords.size() - 1);

                    boolean startsWithOpenParenthesis = lastKeyword.equals("{");
                    boolean startsWithClosedParenthesis = lastKeyword.equals("}");
                    boolean endsWithOpenParenthesis = lastKeyword.equals("{");
                    boolean endsWithClosedParenthesis = lastKeyword.equals("}");

                    if ((insideObjectClass || insideFunctionClass) && endsWithOpenParenthesis) {
                        functionDepth++;
                        break lineProcess;
                    }

                    if ((insideObjectClass || insideFunctionClass) && endsWithClosedParenthesis && functionDepth > 0) {
                        functionDepth--;
                        break lineProcess;
                    }

                    if (functionDepth > 0) {
                        break lineProcess;
                    }

                    if (insideObjectContainerClass && endsWithClosedParenthesis) {
                        insideObjectContainerClass = false;
                        containerClassesDocs.put(currentClassName, copyNullableList(currentClassDocs));
                        currentClassName = null;
                        break lineProcess;
                    }

                    if (insideAbstractClass && endsWithClosedParenthesis) {
                        insideAbstractClass = false;
                        break lineProcess;
                    }

                    if (insideObjectClass && endsWithClosedParenthesis) {
                        insideObjectClass = false;
                        var type = new TdType();
                        type.constructorId = currentConstructor;
                        type.containerName = containerClassName;
                        type.arguments = new ArrayList<>(currentArguments);
                        type.docs = copyNullableList(currentClassDocs);
                        objectClasses.put(currentClassName, type);
                        currentClassName = null;
                        break lineProcess;
                    }

                    if (insideAbstractClass && endsWithClosedParenthesis) {
                        insideAbstractClass = false;
                        break lineProcess;
                    }

                    if (insideObjectClass && endsWithClosedParenthesis) {
                        insideObjectClass = false;
                        var type = new TdType();
                        type.constructorId = currentConstructor;
                        type.containerName = containerClassName;
                        type.arguments = new ArrayList<>(currentArguments);
                        type.docs = copyNullableList(currentClassDocs);
                        objectClasses.put(currentClassName, type);
                        containerClassName = null;
                        currentArguments = new ArrayList<>();
                        currentClassName = null;
                        currentConstructor = -1;
                    }

                    if (insideFunctionClass && startsWithClosedParenthesis) {
                        insideFunctionClass = false;
                        var type = new TdType();
                        type.constructorId = currentConstructor;
                        type.containerName = containerClassName;
                        type.arguments = new ArrayList<>(currentArguments);
                        type.docs = copyNullableList(currentClassDocs);
                        functionClasses.put(currentClassName, type);
                        currentArguments = new ArrayList<>();
                        containerClassName = null;
                        currentClassName = null;
                        currentConstructor = -1;
                    }

                    if (insideFunctionClass || insideObjectClass) {
                        if (keywords.size() == 3 && lastKeyword.endsWith(";")) {
                            var arg = new CurrentArgument();
                            arg.type = keywords.get(1);
                            arg.name = removeLastChars(keywords.get(2), 1);
                            arg.docs = extractDoc(lines, no);
                            currentArguments.add(arg);
                            break lineProcess;
                        }
                        if (keywords.size() == 7 && keywords.get(4).equals("CONSTRUCTOR")) {
                            currentConstructor = Integer.parseInt(removeLastChars(keywords.get(6), 1));
                            break lineProcess;
                        }
                    }

                    if (firstKeyword.equals("package")) {
                        package_ = line;
                        break lineProcess;
                    }

                    if (keywords.size() == 8 && keywords.get(1).equals("abstract")) {
                        insideObjectContainerClass = true;
                        currentClassName = keywords.get(4);
                        currentClassDocs = extractDoc(lines, no);
                        break lineProcess;
                    }

                    if (keywords.size() == 4 && keywords.get(2).equals("TdApi")) {
                        break lineProcess;
                    }

                    if (keywords.size() == 6 && keywords.get(1).equals("abstract")) {
                        insideAbstractClass = true;
                        break lineProcess;
                    }

                    if (keywords.size() == 7 && keywords.get(2).equals("class") && keywords.get(5).equals("Object")) {
                        currentClassName = keywords.get(keywords.size() - 4);
                        currentArguments = new ArrayList<>();
                        insideObjectClass = true;
                        currentClassDocs = extractDoc(lines, no);
                        containerClassName = keywords.get(5);
                        break lineProcess;
                    }

                    if (keywords.size() == 7 && keywords.get(2).equals("class") && keywords.get(5).equals("Function")) {
                        currentClassName = keywords.get(keywords.size() - 4);
                        currentArguments = new ArrayList<>();
                        insideFunctionClass = true;
                        currentClassDocs = extractDoc(lines, no);
                        containerClassName = keywords.get(5);
                        break lineProcess;
                    }

                    if (keywords.size() == 7 && keywords.get(2).equals("class") && containerClassesDocs.containsKey(keywords.get(5))) {
                        currentClassName = keywords.get(keywords.size() - 4);
                        currentArguments = new ArrayList<>();
                        insideObjectClass = true;
                        currentClassDocs = extractDoc(lines, no);
                        containerClassName = keywords.get(5);
                        break lineProcess;
                    }
                }

                no++;
            }

            containerClassesDocs.remove("Function");
        }
        Map<String, TdType> allClasses = new LinkedHashMap<>();
        allClasses.putAll(objectClasses);
        allClasses.putAll(functionClasses);

        // Write output document
        {
            List<OpenOption> openFlags = new ArrayList<>();
            openFlags.add(StandardOpenOption.WRITE);
            if (overwrite) {
                openFlags.add(StandardOpenOption.CREATE);
                openFlags.add(StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                openFlags.add(StandardOpenOption.CREATE_NEW);
            }
            try (var w = new JavaWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8, openFlags.toArray(OpenOption[]::new)), 1, java17)) {
                // Write package
                w.write(package_).writeNewLine().writeNewLine();
                // Write header
                w.write(headers);

                w.writeIndent().writeOpenDocs().writeNewLine();
                w.writeIndent().writeDocs("This class deserializes TDLib classes").writeNewLine();
                w.writeIndent().writeCloseDocs().writeNewLine();

                w.writeIndent().writeOpenCustomBlock("public static class Deserializer").writeNewLine().writeNewLine();

                w.writeIndent().writeOpenDocs().writeNewLine();
                w.writeIndent().writeDocs("The default constructor").writeNewLine();
                w.writeIndent().writeCloseDocs().writeNewLine();
                w.writeIndent().writeOpenConstructorFunction("Deserializer", List.of(), null).writeNewLine();
                w.writeCloseBlock(true).writeNewLine();
                w.writeNewLine();


                w.writeIndent().writeOpenDocs().writeNewLine();
                w.writeIndent().writeDocs("Deserialize the TDLib class").writeNewLine();
                w.writeIndent().writeDocs("@param input stream that contain the serialized TDLib class to deserialize").writeNewLine();
                w.writeIndent().writeDocs("@return the deserialized TDLib class").writeNewLine();
                w.writeIndent().writeDocs("@throws IOException the deserialization failed").writeNewLine();
                w.writeIndent().writeCloseDocs().writeNewLine();

                w.writeIndent().writeOpenFunction("deserialize", List.of(Map.entry("DataInput", "input")), "static Object", "IOException").writeNewLine();

                w.writeIndent().writeOpenSwitchExpression(null, null, false, "input.readInt()").writeNewLine();

                allClasses.forEach((className, x) -> {
                   w.writeIndent().writeSwitchCaseAndYield(className + ".CONSTRUCTOR", "new " + className + "(input)").writeNewLine();
                });

                w.writeIndent().writeOpenSwitchDefault().writeNewLine();

                w.writeIndent().writeYieldException("UnsupportedOperationException").writeNewLine();
                w.writeIndent().writeCloseCase().writeNewLine();
                w.writeCloseSwitchExpression().writeNewLine();

                w.writeCloseBlock(true).writeNewLine();
                w.writeCloseBlock(true).writeNewLine();

                w.writeNewLine();

                containerClassesDocs.forEach((containerClassName, containerClassMeta) -> {
                    w.writeIndent().writeOpenDocs();
                    var docs = containerClassMeta;
                    splitDocs(docs).forEach(docLine -> w.writeNewLine().writeIndent().writeDocs(docLine));
                    w.writeNewLine().writeIndent().writeCloseDocs();
                    w.writeNewLine().writeIndent();

                    var allowedClasses = new LinkedHashSet<String>();

                    allClasses.forEach((className, classMeta) -> {
                        if (containerClassName.equals(classMeta.containerName)) {
                            allowedClasses.add(className);
                        }
                    });

                    if (java17) {
                        w.write("public abstract static sealed class " + containerClassName + " extends Object permits ");
                        w.incrementIndentation();
                        var lines = streamGrouped(allowedClasses.stream(), 3)
                                .map(permitGroup -> String.join(", ", permitGroup))
                                .collect(Collectors.toList());
                        for (int i = 0; i < lines.size(); i++) {
                            if (i > 0) {
                                w.write(",").writeNewLine().writeIndent();
                            }
                            w.write(lines.get(i));
                        }
                        w.decrementIndentation();
                        w.writeOpenCustomBlock("").writeNewLine();
                    } else {
                        w.writeOpenCustomBlock("public abstract static class " + containerClassName + " extends Object").writeNewLine();
                    }

                    w.writeNewLine();

                    w.writeIndent().writeOpenDocs().writeNewLine();
                    w.writeIndent().writeDocs("Default class constructor.").writeNewLine();
                    w.writeIndent().writeCloseDocs().writeNewLine();
                    w.writeIndent().writeOpenConstructorFunction(containerClassName, List.of(), null).writeCloseBlock().writeNewLine().writeNewLine();

                    w.writeCloseBlock(true).writeNewLine().writeNewLine();
                });

                allClasses.forEach((className, classMeta) -> {
                    w.writeIndent().writeOpenDocs();
                    splitDocs(classMeta.docs).forEach(doc -> w.writeNewLine().writeIndent().writeDocs(doc));
                    w.writeNewLine().writeIndent().writeCloseDocs();
                    w.writeNewLine().writeIndent();
                    String classGenerics;
                    if (classMeta.containerName.equals("Function")) {
                        classGenerics = "<" + extractGeneric(classMeta.docs) + ">";
                    } else {
                        classGenerics = "";
                    }
                    w.writeOpenCustomBlock("public static final class", className, "extends", classMeta.containerName + classGenerics).writeNewLine();
                    w.writeNewLine();

                    classMeta.arguments.forEach(argument -> {
                        w.writeNewLine().writeIndent().writeOpenDocs().writeNewLine().writeIndent();
                        argument.docs.forEach(doc -> w.writeDocs(doc).writeNewLine().writeIndent());
                        w.writeCloseDocs().writeNewLine().writeIndent();
                        w.writeDeclare(argument.name, argument.type, "public", null);
                        w.writeNewLine();
                    });

                    w.writeNewLine();
                    w.writeIndent().writeOpenDocs().writeNewLine().writeIndent().writeDocs("Identifier uniquely determining type of the object.").writeNewLine().writeIndent().writeCloseDocs().writeNewLine();
                    w.writeIndent().writeDeclare("CONSTRUCTOR", "int", "public static final", String.valueOf(classMeta.constructorId));
                    w.writeNewLine().writeNewLine().writeIndent().writeOpenDocs();
                    classMeta.docs.forEach(doc -> w.writeNewLine().writeIndent().writeDocs(doc));
                    w.writeNewLine().writeIndent().writeCloseDocs();

                    w.writeNewLine().writeIndent();
                    w.writeOpenConstructorFunction(className, List.of(), null);
                    w.writeCloseBlock().writeNewLine();
                    w.writeNewLine();

                    w.writeIndent().writeOpenDocs();

                    classMeta.docs.forEach(doc -> w.writeNewLine().writeIndent().writeDocs(doc));

                    w.writeNewLine().writeIndent().writeDocs("");
                    classMeta.arguments.forEach(arg -> {
                        var docs = arg.docs;
                        w.writeNewLine().writeIndent();
                        w.writeDocs("@param " + arg.name + " " + docs.get(0));
                    });
                    classMeta.docs.subList(1, classMeta.docs.size()).forEach(doc -> w.writeNewLine().writeIndent().writeDocs(doc));
                    w.writeNewLine().writeIndent().writeCloseDocs();
                    w.writeNewLine();

                    if (!classMeta.arguments.isEmpty()) {
                        w.writeIndent().writeOpenConstructorFunction(className, classMeta.arguments.stream().map(a -> Map.entry(a.type, a.name)).collect(Collectors.toList()), null);
                        w.writeNewLine();
                        classMeta.arguments.forEach(arg -> w.writeIndent().writeClassAssign(arg.name, arg.name).writeNewLine());
                        w.writeCloseBlock(true).writeNewLine();

                    }
                    w.writeNewLine().writeIndent().writeOpenDocs().writeNewLine();
                    classMeta.docs.forEach(doc -> w.writeIndent().writeDocs(doc).writeNewLine());
                    w.writeIndent().writeDocs("").writeNewLine();
                    w.writeIndent().writeDocs("@param input Serialized input").writeNewLine();
                    w.writeIndent().writeDocs("@throws IOException the deserialization failed").writeNewLine();
                    w.writeIndent().writeCloseDocs().writeNewLine();
                    w.writeIndent().writeOpenConstructorFunction(className, List.of(Map.entry("DataInput", "input")), "IOException").writeNewLine();

                    classMeta.arguments.forEach(arg -> {
                        if (NATIVES.contains(arg.type)) {
                            deserializeNative(w, arg.name, arg.type);
                        } else if (!arg.type.endsWith("[]")) {
                            deserializeTdApi(w, arg.name, arg.type, containerClassesDocs, objectClasses);
                        } else if (arg.type.equals("byte[][]") || !arg.type.endsWith("[][]")) {
                            var baseArgType = arg.type.substring(0, arg.type.length() - 2);
                            w.writeIndent().writeOpenIf("input.readBoolean()").writeNewLine();
                            w.writeIndent();
                            if (arg.type.equals("byte[][]")) {
                                w.writeClassAssign(arg.name, "new byte[input.readInt()][]");
                            } else {
                                w.writeClassAssign(arg.name, "new " + baseArgType + "[input.readInt()]");
                            }
                            w.writeNewLine();
                            w.writeIndent().writeOpenFor("int i = 0", "i < this." + arg.name + ".length", "i++").writeNewLine();

                            if (NATIVES.contains(baseArgType)) {
                                deserializeNative(w, arg.name + "[i]", baseArgType, false);
                            } else {
                                deserializeTdApi(w, arg.name + "[i]", baseArgType, containerClassesDocs, objectClasses, false);
                            }
                            w.writeCloseBlock(true).writeNewLine();
                            w.writeCloseBlock(true).writeNewLine();
                        } else if (arg.type.endsWith("[][]")) {
                            var baseArgType = arg.type.substring(0, arg.type.length() - 4);
                            w.writeIndent().writeOpenIf("input.readBoolean()").writeNewLine();
                            w.writeIndent().writeClassAssign(arg.name, "new " + baseArgType + "[input.readInt()][]").writeNewLine();
                            w.writeIndent().writeOpenFor("int i = 0", "i < this." + arg.name + ".length", "i++").writeNewLine();
                            w.writeIndent().writeClassAssign(arg.name + "[i]", "new " + baseArgType + "[input.readInt()]");
                            w.writeIndent().writeOpenFor("int j = 0", "j < this."+arg.name+"[i].length", "j++").writeNewLine();
                            if (NATIVES.contains(baseArgType)) {
                                deserializeNative(w, arg.name + "[i][j]", baseArgType, false);
                            } else {
                                deserializeTdApi(w, arg.name + "[i][j]", baseArgType, containerClassesDocs, objectClasses, false);
                            }
                            w.writeCloseBlock(true).writeNewLine();
                            w.writeCloseBlock(true).writeNewLine();
                            w.writeCloseBlock(true).writeNewLine();
                        }
                    });
                    w.writeCloseBlock(true).writeNewLine();
                    w.writeNewLine().writeIndent().writeOpenDocs().writeNewLine();
                    w.writeIndent().writeDocs("@return this.CONSTRUCTOR").writeNewLine();
                    w.writeIndent().writeCloseDocs().writeNewLine();
                    w.writeIndent().writeOpenFunction("getConstructor", List.of(), "int", null).writeNewLine();
                    w.writeIndent().writeReturn(className + ".CONSTRUCTOR").writeNewLine();
                    w.writeCloseBlock(true).writeNewLine();
                    w.writeNewLine();

                    w.writeIndent().writeOpenDocs().writeNewLine();
                    w.writeIndent().writeDocs("Serialize the TDLib class").writeNewLine();
                    w.writeIndent().writeDocs("@param output output data stream").writeNewLine();
                    w.writeIndent().writeDocs("@throws IOException the serialization failed").writeNewLine();
                    w.writeIndent().writeCloseDocs().writeNewLine();

                    w.writeIndent().writeOpenFunction("serialize", List.of(Map.entry("DataOutput", "output")), "void", "IOException").writeNewLine();
                    w.writeIndent().writeCall("output.writeInt", className + ".CONSTRUCTOR").writeNewLine();
                    classMeta.arguments.forEach(arg -> {
                        if (NATIVES.contains(arg.type)) {
                            serializeNative(w, arg.name, arg.type);
                        } else if (!arg.type.endsWith("[]")) {
                            serializeTdApi(w, arg.name);
                        } else if (arg.type.equals("byte[][]") || !arg.type.endsWith("[][]")) {
                            var baseArgType = arg.type.substring(0, arg.type.length() - 2);
                            w.writeIndent().writeOpenIf("this." + arg.name  + " == null").writeNewLine();
                            w.writeIndent().writeCall("output.writeBoolean", "false").writeNewLine();
                            w.writeOpenIfElse(true).writeNewLine();
                            w.writeIndent().writeCall("output.writeBoolean", "true").writeNewLine();
                            w.writeIndent().writeCall("output.writeInt", "this." + arg.name + ".length").writeNewLine();
                            w.writeIndent().writeOpenFor("int i = 0", "i < this." + arg.name + ".length", "i++").writeNewLine();
                            if (NATIVES.contains(baseArgType)) {
                                serializeNative(w, arg.name + "[i]", baseArgType, false);
                            } else {
                                serializeTdApi(w, arg.name + "[i]", false);
                            }
                            w.writeCloseBlock(true).writeNewLine();
                            w.writeCloseBlock(true).writeNewLine();
                        } else if (arg.type.endsWith("[][]")) {
                            var baseArgType = arg.type.substring(0, arg.type.length() - 4);
                            w.writeIndent().writeOpenIf("this." + arg.name + " == null").writeNewLine();
                            w.writeIndent().writeCall("output.writeBoolean", "false").writeNewLine();
                            w.writeOpenIfElse(true).writeNewLine();
                            w.writeIndent().writeCall("output.writeBoolean", "true").writeNewLine();
                            w.writeIndent().writeCall("output.writeInt", "this." + arg.name + ".length").writeNewLine();
                            w.writeIndent().writeOpenFor("int i = 0", "i < this." + arg.name + ".length", "i++").writeNewLine();
                            w.writeIndent().writeCall("output.writeInt", "this." + arg.name + "[i].length").writeNewLine();
                            w.writeIndent().writeOpenFor("int j = 0", "j < this." + arg.name + "[i].length", "j++").writeNewLine();
                            if (NATIVES.contains(baseArgType)) {
                                serializeNative(w, arg.name + "[i][j]", baseArgType, false);
                            } else {
                                serializeTdApi(w, arg.name + "[i][j]", false);
                            }
                            w.writeCloseBlock(true).writeNewLine();
                            w.writeCloseBlock(true).writeNewLine();
                            w.writeCloseBlock(true).writeNewLine();
                        }
                    });
                    w.writeCloseBlock(true).writeNewLine();

                    w.writeNewLine();
                    w.writeNewLine().writeIndent().writeOpenFunction("equals", List.of(Map.entry("java.lang.Object", "o")), "boolean", null).writeNewLine();
                    w.writeIndent().writeOpenIf("this == o").writeNewLine();
                    w.writeIndent().writeReturn("true").writeNewLine().writeCloseBlock(true).writeNewLine();
                    w.writeIndent().writeOpenIf("o == null || getClass() != o.getClass()").writeNewLine();
                    w.writeIndent().writeReturn("false").writeNewLine();
                    w.writeCloseBlock(true).writeNewLine();
                    if (!classMeta.arguments.isEmpty()) {
                        w.writeIndent();
                        var otherClass = className.substring(0, 1).toLowerCase(Locale.US) +  className.substring(1);
                        w.writeLocalAssign(className, otherClass, "(" + className + ") o");
                        w.writeNewLine();
                        classMeta.arguments.forEach(arg -> {
                            w.writeIndent();
                            if (NATIVE_TO_OBJ.containsKey(arg.type)) {
                                w.writeOpenIf("this." + arg.name + " != " + otherClass + "."  + arg.name);
                            } else if (!arg.type.endsWith("[]")) {
                                w.writeOpenIf("!Objects.equals(this." + arg.name + ", " + otherClass + "." + arg.name + ")");
                            } else if (arg.type.endsWith("[][]")) {
                                w.writeOpenIf("!Arrays.deepEquals(this." + arg.name + ", " + otherClass + "." + arg.name + ")");
                            } else {
                                w.writeOpenIf("!Arrays.equals(this." + arg.name + ", " + otherClass + "." + arg.name + ")");
                            }
                            w.writeNewLine().writeIndent().writeReturn("false").writeNewLine().writeCloseBlock(true).writeNewLine();
                        });
                    }
                    w.writeIndent().writeReturn("true").writeNewLine();
                    w.writeCloseBlock(true).writeNewLine();
                    w.writeNewLine();
                    w.writeIndent().writeOpenFunction("hashCode", List.of(), "int", null).writeNewLine();
                    w.writeIndent();
                    if (!classMeta.arguments.isEmpty()) {
                        var primitives = classMeta.arguments.stream().filter(n -> PRIMITIVES.contains(n.type)).collect(Collectors.toList());
                        if (!primitives.isEmpty() && classMeta.arguments.size() == 1) {
                            w.writeReturn(NATIVE_TO_OBJ.get(primitives.get(0).type) + ".hashCode(this." + primitives.get(0).name + ")");
                        } else if (!primitives.isEmpty()) {
                            w.writeLocalAssign("int", "result", NATIVE_TO_OBJ.get(primitives.get(0).type) + ".hashCode(this." + primitives.get(0).name + ")").writeNewLine();
                            w.writeIndent();
                        }
                        var tdapi = classMeta.arguments.stream().filter(n -> !PRIMITIVES.contains(n.type)).collect(Collectors.toList());
                        int start;
                        if (!tdapi.isEmpty() && classMeta.arguments.size() == 1) {
                            w.writeReturn(hashObject("this." + tdapi.get(0).name, tdapi.get(0).type));
                            start = 1;
                        } else {
                            if (primitives.isEmpty()) {
                                w.writeLocalAssign("int", "result", hashObject("this." + tdapi.get(0).name, tdapi.get(0).type)).writeNewLine();
                                w.writeIndent();
                                start = 1;
                            } else {
                                start = 0;
                            }
                        }

                        tdapi.stream().skip(start).forEach(arg -> {
                            w.writeAssign("result", "result * 31 + (" + hashObject("this." + arg.name, arg.type) + ")").writeNewLine();
                            w.writeIndent();
                        });

                        if (classMeta.arguments.size() > 1) {
                            w.writeReturn("result");
                        }
                    } else {
                        w.writeReturn(className + ".CONSTRUCTOR");
                    }
                    w.writeNewLine();

                    w.writeCloseBlock(true).writeNewLine();
                    w.writeCloseBlock(true).writeNewLine();
                    w.writeNewLine();
                });
                w.write("}");
            }
        }
    }

    private static String hashObject(String name, String type) {
        if (!type.endsWith("[]")) {
            return name + " == null ? 0 : " + name + ".hashCode()";
        } else if (type.endsWith("[][]")) {
            return "Arrays.deepHashCode(" + name + ")";
        } else {
            return "Arrays.hashCode(" + name + ")";
        }
    }

    private static void serializeTdApi(JavaWriter w, String argName) {
        serializeTdApi(w, argName, true);
    }

    private static void serializeTdApi(JavaWriter w, String argName, boolean nullCheck) {
        if (nullCheck) {
            w.writeIndent().writeOpenIf("this." + argName + " == null").writeNewLine();
            w.writeIndent().writeCall("output.writeBoolean", "false").writeNewLine();
            w.writeOpenIfElse(true).writeNewLine();
            w.writeIndent().writeCall("output.writeBoolean", "true").writeNewLine();
        }
        w.writeIndent().writeCall("this." + argName + ".serialize", "output").writeNewLine();
        if (nullCheck) {
            w.writeCloseBlock(true).writeNewLine();
        }
    }

    private static void serializeNative(JavaWriter w,  String argName, String argType) {
        serializeNative(w, argName, argType, true);
    }

    private static void serializeNative(JavaWriter w, String argName, String argType, boolean nullCheck) {
        switch (argType) {
            case "int":
                w.writeIndent().writeCall("output.writeInt", "this." + argName).writeNewLine();
                break;
            case "byte[]":
            case "byte":
                if (nullCheck) {
                    w.writeIndent().writeOpenIf("this." + argName + " == null").writeNewLine();
                    w.writeIndent().writeCall("output.writeBoolean", "false").writeNewLine();
                    w.writeOpenIfElse(true).writeNewLine();
                    w.writeIndent().writeCall("output.writeBoolean", "true").writeNewLine();
                }
                w.writeIndent().writeCall("output.writeInt", "this." + argName + ".length").writeNewLine();
                w.writeIndent().writeCall("output.write", "this." + argName).writeNewLine();
                if (nullCheck) {
                    w.writeCloseBlock(true).writeNewLine();
                }
                break;
            case "long":
                w.writeIndent().writeCall("output.writeLong", "this." + argName).writeNewLine();
                break;
            case "double":
                w.writeIndent().writeCall("output.writeDouble", "this." + argName).writeNewLine();
                break;
            case "boolean":
                w.writeIndent().writeCall("output.writeBoolean", "this." + argName).writeNewLine();
                break;
            case "String":
                if (nullCheck) {
                    w.writeIndent().writeOpenIf("this." + argName + " == null").writeNewLine();
                    w.writeIndent().writeCall("output.writeBoolean", "false").writeNewLine();
                    w.writeIndent().writeOpenIfElse(true).writeNewLine();
                    w.writeIndent().writeCall("output.writeBoolean", "true").writeNewLine();
                }
                var tmpName = argName.split("\\[", 2)[0] + "Tmp";
                w.writeIndent().writeLocalAssign("byte[]", tmpName, "this." + argName + ".getBytes(StandardCharsets.UTF_8)").writeNewLine();
                w.writeIndent().writeCall("output.writeInt", tmpName + ".length").writeNewLine();
                w.writeIndent().writeCall("output.write", tmpName).writeNewLine();
                if (nullCheck) {
                    w.writeCloseBlock(true).writeNewLine();
                }
                break;
            default: throw new UnsupportedOperationException(argName + ":" + argType);
        }
    }

    private static void deserializeTdApi(JavaWriter w, String name, String type, Map<String, List<String>> cont, Map<String, TdType> classes) {
        deserializeTdApi(w, name, type, cont, classes, true);
    }

    private static void deserializeTdApi(JavaWriter w, String argName, String argType, Map<String, List<String>> cont, Map<String, TdType> classes, boolean nullCheck) {
        if (nullCheck) {
            w.writeIndent().writeOpenIf("input.readBoolean()").writeNewLine();
        }
        w.writeIndent();
        if (cont.containsKey(argType)) {
            w.writeOpenSwitchExpression(null, "this." + argName, true, "input.readInt()").writeNewLine();

            classes.forEach((className, classMeta) -> {
                if (classMeta.containerName.equals(argType) && !className.equals(argType)) {
                    w.writeIndent().writeSwitchCaseAndYield(className + ".CONSTRUCTOR", "new " + className + "(input)").writeNewLine();
                }
            });

            w.writeIndent().writeOpenSwitchDefault().writeNewLine();
            w.writeIndent().writeYieldException("UnsupportedOperationException").writeNewLine();
            w.writeIndent().writeCloseCase().writeNewLine();
            w.writeCloseSwitchExpression().writeNewLine();
        } else {
            w.writeOpenIf(argType + ".CONSTRUCTOR != input.readInt()");
            w.writeNewLine().writeIndent().writeException("UnsupportedOperationException");
            w.writeNewLine().writeCloseBlock(true).writeNewLine();
            w.writeIndent().writeClassAssign(argName, "new " + argType + "(input)").writeNewLine();
        }
        if (nullCheck) {
            w.writeCloseBlock(true).writeNewLine();
        }
    }

    private static void deserializeNative(JavaWriter w, String name, String type) {
        deserializeNative(w, name, type, true);
    }

    private static void deserializeNative(JavaWriter w, String name, String type, boolean nullCheck) {
        switch (type) {
            case "int": {
                w.writeIndent().writeClassAssign(name, "input.readInt()").writeNewLine();
                break;
            }
            case "byte":
            case "byte[]": {
                if (nullCheck) {
                    w.writeIndent().writeOpenIf("input.readBoolean()").writeNewLine();
                }
                w.writeIndent().writeClassAssign(name, "new byte[input.readInt()]").writeNewLine();

                w.writeIndent().writeCall("input.readFully", "this." + name).writeNewLine();

                if (nullCheck) {
                    w.writeCloseBlock(true).writeNewLine();
                }
                break;
            }
            case "long": {
                w.writeIndent().writeClassAssign(name, "input.readLong()").writeNewLine();
                break;
            }
            case "double": {
                w.writeIndent().writeClassAssign(name, "input.readDouble()").writeNewLine();
                break;
            }
            case "boolean": {
                w.writeIndent().writeClassAssign(name, "input.readBoolean()").writeNewLine();
                break;
            }
            case "String": {
                if (nullCheck) {
                    w.writeIndent().writeOpenIf("input.readBoolean()").writeNewLine();
                }
                var tmpName = name.split("\\[", 2)[0] + "Tmp";
                w.writeIndent().writeLocalAssign("byte[]", tmpName, "new byte[input.readInt()]").writeNewLine();
                w.writeIndent().writeCall("input.readFully", tmpName).writeNewLine();
                w.writeIndent().writeClassAssign(name, "new String(" + tmpName + ", StandardCharsets.UTF_8)").writeNewLine();

                if (nullCheck) {
                    w.writeCloseBlock(true).writeNewLine();
                }
                break;
            }
        }
    }

    private static String extractGeneric(List<String> docs) {
        return docs.stream()
                .filter(line -> line.strip().startsWith("<p> Returns ") && line.contains("@link"))
                .findFirst().map(line -> line.substring(line.indexOf("{@link ") + 7).split(" ")[0])
                .orElseThrow();
    }

    private static List<String> copyNullableList(List<String> list) {
        return list != null ? new ArrayList<>(list) : null;
    }

    private static <X> Stream<List<X>> streamGrouped(Stream<X> in, int pageSize) {
        var list = in.collect(Collectors.toList());
        return IntStream.range(0, (list.size() + pageSize - 1) / pageSize)
                .mapToObj(i -> list.subList(i * pageSize, Math.min(pageSize * (i + 1), list.size())));
    }

    private static List<String> splitDocs(List<String> docs) {
        if (docs == null) return List.of();
        return docs.stream()
                .flatMap(line -> wrapLine(line, 70).stream())
                .collect(Collectors.toList());
    }


    private static List<String> wrapLine(String line, int lineLength) {
        if (line.isBlank()) return List.of();
        if (line.length() <= lineLength) return List.of(line);
        String[] words = line.split(" ");
        List<String> allLines = new ArrayList<>();
        StringBuilder trimmedLine = new StringBuilder();
        for (String word : words) {
            if (trimmedLine.length() + 1 + word.length() <= lineLength) {
                trimmedLine.append(word).append(" ");
            } else {
                allLines.add(trimmedLine.toString());
                trimmedLine = new StringBuilder();
                trimmedLine.append(word).append(" ");
            }
        }
        if (trimmedLine.length() > 0) {
            allLines.add(trimmedLine.toString());
        }
        return allLines;
    }

    private static String removeLastChars(String s, int i) {
        return s.substring(0, s.length() - i);
    }

    private static List<String> extractDoc(List<String> lines, int line) {
        int lookBack = 2;
        var cursor = lines.listIterator(line - lookBack);
        boolean foundDoc = false;
        while (cursor.hasPrevious()) {
            var prev = cursor.previous();
            if (prev.strip().startsWith("/**")) {
                cursor.next();
                foundDoc = true;
                break;
            }
        }
        if (!foundDoc) {
            return List.of();
        }

        var docs = new ArrayList<String>();

        while (cursor.hasNext()) {
            var current = cursor.next();
            if (current.startsWith("*/")) break;
            var currentSplit = List.of(current.split(" ", 2));
            current = currentSplit.get(currentSplit.size() - 1);
            if (!current.strip().equals("*")) {
                docs.add(current);
            } else {
                docs.add("");
            }
        }
        return docs;
    }

    private static RuntimeException getHelp() {
        System.err.println("Invalid syntax!\nArguments: --source PATH --output PATH [--java17 BOOLEAN] [--overwrite BOOLEAN] [--headers PATH]");
        System.exit(1);
        return new IllegalStateException();
    }

    static class CurrentArgument {
        public String name;
        public String type;
        public List<String> docs = new ArrayList<>();
    }

    static class TdType {
        public int constructorId;
        public String containerName;
        public List<CurrentArgument> arguments = new ArrayList<>();
        public List<String> docs;
    }

    @SuppressWarnings({"UnusedReturnValue", "unused", "resource"})
    public static class JavaWriter implements Closeable {
        private final Writer os;
        private final boolean java17;
        private int depth;
        private boolean insideSwitchExpression;
        private String switchExpressionVarName;

        public JavaWriter(Writer os, int depth, boolean java17) {
            this.os = os;
            this.depth = depth;
            this.java17 = java17;
        }

        public JavaWriter write(String data) {
            try {
                os.write(data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return this;
        }

        public JavaWriter writeIndent() {
            this.write("\t".repeat(depth));
            return this;
        }

        public JavaWriter incrementIndentation() {
            depth++;
            return this;
        }

        public JavaWriter decrementIndentation() {
            depth--;
            return this;
        }

        public JavaWriter writeOpenCustomBlock(String... strings) {
            write(String.join(" ", strings) + " {");
            incrementIndentation();
            return this;
        }

        public JavaWriter writeOpenDocs() {
            write("/**");
            return this;
        }

        public JavaWriter writeDocs(String text) {
            if (text.lines().count() > 1) {
                throw new UnsupportedOperationException("Docs text must be on a single line: " + text);
            }
            write(" " + ("* " + text).strip());
            return this;
        }

        public JavaWriter writeCloseDocs() {
            write("**/");
            return this;
        }

        public JavaWriter writeNewLine() {
            write(System.lineSeparator());
            return this;
        }

        public JavaWriter writeClassAssign(String classValue, String localValue) {
            write("this." + classValue + " = " + localValue + ";");
            return this;
        }

        public JavaWriter writeCloseBlock() {
            writeCloseBlock(false);
            return this;
        }

        public JavaWriter writeCloseBlock(boolean space) {
            decrementIndentation();
            if (space) {
                writeIndent();
            }
            write("}");
            return this;
        }

        public JavaWriter writeOpenSwitch(String data) {
            incrementIndentation();
            write("switch(" + data + ") {");
            return this;
        }

        public JavaWriter writeOpenSwitchExpression(String varType, String varName, boolean alreadyExistingVar, String data) {
            if (insideSwitchExpression) throw new UnsupportedOperationException();
            insideSwitchExpression = true;
            this.switchExpressionVarName = varName;
            if (java17) {
                if (switchExpressionVarName == null) {
                    write("return switch (" + data + ") {");
                } else if (alreadyExistingVar) {
                    write(varName + " = switch (" + data + ") {");
                } else {
                    write((varType == null ? "var" : varType) + " " + varName + " = switch (" + data + ") {");
                }
            } else {
                if (!alreadyExistingVar && varName != null) {
                    write((varType == null ? "Object" : varType) + " " + varName + ";");
                    writeNewLine();
                    writeIndent();
                }
                write("switch(" + data + ") {");
            }
            incrementIndentation();
            return this;
        }

        public JavaWriter writeSwitchBreak() {
            if (insideSwitchExpression) throw new UnsupportedOperationException();
            decrementIndentation();
            write("break;");
            return this;
        }

        public JavaWriter writeYield(String expression) {
            if (!insideSwitchExpression) throw new UnsupportedOperationException();
            if (java17) {
                write("yield " + expression + ";");
            } else if (this.switchExpressionVarName == null) {
                write("return " + expression + ";");
            } else {
                write(this.switchExpressionVarName + " = " + expression + "; break;");
            }
            decrementIndentation();
            return this;
        }

        public JavaWriter writeYieldException(String expression) {
            if (!insideSwitchExpression) throw new UnsupportedOperationException();
            write("throw new " + expression + "();");
            decrementIndentation();
            return this;
        }

        public JavaWriter writeCloseCase() {
            write("}");
            return this;
        }

        public JavaWriter writeCloseSwitchExpression() {
            decrementIndentation();
            writeIndent();
            if (!insideSwitchExpression) throw new UnsupportedOperationException();
            insideSwitchExpression = false;
            if (java17) {
                write("};");
            } else {
                write("}");
            }
            return this;
        }

        public JavaWriter writeOpenSwitchCase(String data) {
            if (java17 && insideSwitchExpression) {
                write("case " + data + " -> {");
            } else {
                write("case " + data + ":");
            }
            incrementIndentation();
            return this;
        }

        public JavaWriter writeSwitchCaseAndYield(String caseExpression, String yieldExpression) {
            if (java17 && insideSwitchExpression) {
                write("case " + caseExpression + " -> ");
            } else {
                write("case " + caseExpression + ": ");
            }
            if (!insideSwitchExpression) throw new UnsupportedOperationException();
            if (java17) {
                write(yieldExpression + ";");
            } else if (this.switchExpressionVarName == null) {
                write("return " + yieldExpression + ";");
            } else {
                write(this.switchExpressionVarName + " = " + yieldExpression + "; break;");
            }
            return this;
        }

        public JavaWriter writeOpenSwitchDefault() {
            if (java17 && insideSwitchExpression) {
                write("default -> {");
            } else {
                write("default: {");
            }
            incrementIndentation();
            return this;
        }

        public JavaWriter writeException(String excClass) {
            write("throw new " + excClass + "();");
            return this;
        }

        public JavaWriter writeOpenIfElse() {
            return writeOpenIfElse(false);
        }

        public JavaWriter writeOpenIfElse(boolean space) {
            decrementIndentation();
            if (space) {
                writeIndent();
            }
            write("} else {");
            incrementIndentation();
            return this;
        }

        public JavaWriter writeLocalAssign(String objectType, String name, String value) {
            write(objectType + " " + name + " = " + value + ";");
            return this;
        }

        public JavaWriter writeAssign(String name, String value) {
            write(name + " = " + value + ";");
            return this;
        }

        public JavaWriter writeOpenFor(String start, String cond, String stmt) {
            incrementIndentation();
            write("for (" + start + "; " + cond + "; " + stmt + ") {");
            return this;
        }

        public JavaWriter writeOpenIf(String cond) {
            incrementIndentation();
            write("if (" + cond + ") {");
            return this;
        }

        public JavaWriter writeCall(String method, String... args) {
            writeCall(method, Stream.of(args));
            return this;
        }

        public JavaWriter writeCall(String method, Stream<String> args) {
            write(args.collect(Collectors.joining(", ", method + "(", ");")));
            return this;
        }

        public JavaWriter writeReturn(String val) {
            write("return " + val + ";");
            return this;
        }

        public JavaWriter writeDeclare(String name, String type, String flag, String value) {
            write(flag + " " + type + " " + name);
            if (value != null) {
                write(" = " + value);
            }
            write(";");
            return this;
        }

        public JavaWriter writeOpenFunction(String name, List<Map.Entry<String, String>> args, String type, String e) {
            incrementIndentation();
            write(args.stream().map(arg -> arg.getKey() + " " + arg.getValue()).collect(Collectors.joining(", ",
                    "public " + type + " " + name + "(",
                    ")")));
            if (e != null) {
                write(" throws " + e);
            }
            write(" {");
            return this;
        }

        public JavaWriter writeOpenConstructorFunction(String name, List<Map.Entry<String, String>> args, String e) {
            incrementIndentation();
            write("public " + name + "(");
            var resultArgs = args.stream().map(arg -> arg.getKey() + " " + arg.getValue()).collect(Collectors.joining(", "));
            if (resultArgs.length() > 60) {
                var resultArgsSplitted = new ArrayDeque<>(List.of(resultArgs.split(", ")));
                incrementIndentation();
                while (!resultArgsSplitted.isEmpty()) {
                    write(resultArgsSplitted.removeFirst());
                    if (!resultArgsSplitted.isEmpty()) {
                        write(",");
                        writeNewLine();
                        writeIndent();
                    }
                }
                decrementIndentation();
            } else {
                write(resultArgs);
            }
            write(")");
            if (e != null) {
                write(" throws " + e);
            }
            write(" {");
            return this;
        }

        @Override
        public void close() throws IOException {
            os.close();
        }
    }
}
