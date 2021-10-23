import sys
import typing

from collections import OrderedDict
from code_writer import CodeWriter


natives = ["int", "long", "boolean", "String", "double", "byte[]", "byte"]

native_to_object = {
    "boolean": "Boolean",
    "byte[]": "Byte",
    "byte": "Byte",
    "int": "Integer",
    "short": "Short",
    "char": "Character",
    "long": "Long",
    "float": "Float",
    "double": "Double",
    "String": "String"
}

comment = ("/**", "* ", "*/")

cmp_natives = natives.copy()
cmp_natives.remove("String")
cmp_natives.remove("byte[]")


def chunker(seq, size):
    return (seq[pos:pos + size] for pos in range(0, len(seq), size))


def split_docs(docs: typing.List[str]) -> typing.List[str]:
    tokens = " ".join(docs).split(" ")
    result = [tokens[0]]

    for token in tokens[1:]:
        if len(" ".join(result[-1]) + token) < 70:
            result[-1] += f" {token}"
        else:
            result.append(token)

    if len(result) > 1 and len(result[-1].split(" ")) < 3:
        result[-2] += f" {result[-1]}"
        del result[-1]

    return result


def extract_doc(lines: typing.List[str], line: int) -> typing.List[str]:
    look_back = 2
    line = next(
        n
        for n in range(line - look_back, 0, -1)
        if lines[n].startswith("/**")
    )

    result = []

    while True:
        line += 1

        if lines[line].startswith("*/"):
            break

        current = lines[line].split(" ", maxsplit=1)[-1]

        if current.strip() != "*":
            result.append(current)

    return result


def hash_object(name: str, typ: str) -> str:
    if not typ.endswith("[]"):
        return f"{name} == null ? 0 : {name}.hashCode()"

    elif typ.endswith("[][]"):
        return f"Arrays.deepHashCode({name})"

    else:
        return f"Arrays.hashCode({name})"


def deserialize_tdapi(output: CodeWriter, arg_name: str, arg_type: str, cont, classes, null_check: bool = True):
    if null_check:
        output.indent()
        output.open_if("input.readBoolean()")
        output.newline()

    output.indent()

    if arg_type in cont:
        output.open_switch("input.readInt()")
        output.newline()

        for class_name, class_meta in classes.items():
            if class_meta[1] == arg_type and class_name != arg_type:
                output.indent()
                output.open_switch_case(f"{class_name}.CONSTRUCTOR")

                output.newline()
                output.indent()
                output.class_assign(arg_name, f"new {class_name}(input)")

                output.newline()
                output.indent()
                output.switch_break()
                output.newline()

        output.indent()
        output.open_switch_default()
        output.newline()

        output.indent()
        output.exception("UnsupportedOperationException")
        output.newline()
        output.indent_depth -= 1

        output.close_block(space=True)

    else:
        output.open_if(f"{arg_type}.CONSTRUCTOR != input.readInt()")

        output.newline()
        output.indent()
        output.exception("UnsupportedOperationException")

        output.newline()
        output.close_block(space=True)

        output.indent()
        output.class_assign(arg_name, f"new {arg_type}(input)")
        output.newline()

    if null_check:
        output.close_block(space=True)


def deserialize_native(output: CodeWriter, arg_name, arg_type, null_check: bool = True):
    if arg_type == "int":
        output.indent()
        output.class_assign(arg_name, "input.readInt()")
        output.newline()

    if arg_type == "byte[]" or arg_type == "byte":
        if null_check:
            output.indent()
            output.open_if("input.readBoolean()")
            output.newline()

        output.indent()
        output.class_assign(arg_name, f"new byte[input.readInt()]")
        output.newline()

        output.indent()
        output.call("input.readFully", f"this.{arg_name}")
        output.newline()

        if null_check:
            output.close_block(space=True)

    if arg_type == "long":
        output.indent()
        output.class_assign(arg_name, "input.readLong()")
        output.newline()

    if arg_type == "double":
        output.indent()
        output.class_assign(arg_name, "input.readDouble()")
        output.newline()

    if arg_type == "boolean":
        output.indent()
        output.class_assign(arg_name, "input.readBoolean()")
        output.newline()

    if arg_type == "String":
        if null_check:
            output.indent()
            output.open_if("input.readBoolean()")
            output.newline()

        tmp_name = arg_name.split("[")[0] + "Tmp"
        output.indent()
        output.local_assign("byte[]", tmp_name, f"new byte[input.readInt()]")
        output.newline()

        output.indent()
        output.call("input.readFully", tmp_name)
        output.newline()

        output.indent()
        output.class_assign(arg_name, f"new String({tmp_name}, StandardCharsets.UTF_8)")
        output.newline()

        if null_check:
            output.close_block(space=True)


def serialize_tdapi(output: CodeWriter, arg_name, null_check: bool = True):
    if null_check:
        output.indent()
        output.open_if(f"this.{arg_name} == null")

        output.newline()
        output.indent()
        output.call("output.writeBoolean", "false")
        output.newline()

        output.open_if_else(space=True)
        output.newline()

        output.indent()
        output.call("output.writeBoolean", "true")
        output.newline()

    output.indent()
    output.call(f"this.{arg_name}.serialize", "output")

    output.newline()

    if null_check:
        output.close_block(space=True)


def serialize_native(output: CodeWriter, arg_type: str, arg_name: str, null_check: bool = True):
    if arg_type == "int":
        output.indent()
        output.call("output.writeInt", f"this.{arg_name}")
        output.newline()

    if arg_type == "byte[]" or arg_type == "byte":
        if null_check:
            output.indent()
            output.open_if(f"this.{arg_name} == null")

            output.newline()
            output.indent()
            output.call("output.writeBoolean", "false")
            output.newline()

            output.open_if_else(space=True)
            output.newline()

            output.indent()
            output.call("output.writeBoolean", "true")
            output.newline()

        output.indent()
        output.call("output.writeInt", f"this.{arg_name}.length")
        output.newline()
        output.indent()
        output.call("output.write", f"this.{arg_name}")
        output.newline()

        if null_check:
            output.close_block(space=True)

    if arg_type == "long":
        output.indent()
        output.call("output.writeLong", f"this.{arg_name}")
        output.newline()

    if arg_type == "double":
        output.indent()
        output.call("output.writeDouble", f"this.{arg_name}")
        output.newline()

    if arg_type == "boolean":
        output.indent()
        output.call("output.writeBoolean", f"this.{arg_name}")
        output.newline()

    if arg_type == "String":
        if null_check:
            output.indent()
            output.open_if(f"this.{arg_name} == null")

            output.newline()
            output.indent()
            output.call("output.writeBoolean", "false")
            output.newline()

            output.open_if_else(space=True)
            output.newline()

            output.indent()
            output.call("output.writeBoolean", "true")
            output.newline()

        output.indent()
        tmp_name = arg_name.split("[")[0] + "Tmp"
        output.local_assign("byte[]", tmp_name, f"this.{arg_name}.getBytes(StandardCharsets.UTF_8)")
        output.newline()
        output.indent()
        output.call("output.writeInt", f"{tmp_name}.length")
        output.newline()
        output.indent()
        output.call("output.write", tmp_name)
        output.newline()

        if null_check:
            output.close_block(space=True)


def main(input_path: str, output_path: str, headers_path: str, java17: str):
    data_input = open(input_path)

    java17 = java17 == "true"
    
    package: typing.Optional[str] = None
    current_constructor: typing.Optional[int] = None
    current_class_name: typing.Optional[str] = None
    inside_abstract_class: bool = False
    inside_object_class: bool = False
    inside_function_class: bool = False
    inside_object_container_class: bool = False
    container_class_name: typing.Optional[str] = None
    current_class_docs: typing.Optional[typing.List[str]] = None
    function_depth: int = 0

    function_classes = OrderedDict()
    # key: {class_name, value: (constructor_id, container_name, [arg_type, arg_name], docs)}

    object_classes = OrderedDict()
    # key: {class_name, value: (constructor_id, container_name, [arg_type, arg_name], docs)}

    container_classes: typing.List[str] = OrderedDict()
    # key: {class_name, value: (docs)}

    current_arguments: typing.Optional[typing.List[typing.Tuple[str, str, typing.List[str]]]] = None
    # [(arg_name, arg_type), ...]

    lines = list(map(str.strip, data_input.readlines()))

    for no, line in enumerate(lines):
        keywords = line.split()

        if not keywords:
            continue

        if (inside_object_class or inside_function_class) and keywords[-1] == "{":
            function_depth += 1
            continue

        if (inside_object_class or inside_function_class) and keywords[-1] == "}" and function_depth > 0:
            function_depth -= 1
            continue

        if function_depth > 0:
            continue

        if inside_object_container_class and keywords[-1] == "}":
            inside_object_container_class = False
            container_classes[current_class_name] = (current_class_docs)
            current_class_name = None
            continue

        if inside_abstract_class and keywords[-1] == "}":
            inside_abstract_class = False
            continue

        if inside_object_class and keywords[-1] == "}":
            inside_object_class = False
            object_classes[current_class_name] = (current_constructor, container_class_name,
                                                  current_arguments, current_class_docs)
            container_class_name = None
            current_arguments = None
            current_class_name = None
            current_constructor = None
            continue

        if inside_function_class and keywords[0] == "}":
            inside_function_class = False
            function_classes[current_class_name] = (current_constructor, container_class_name,
                                                    current_arguments, current_class_docs)
            current_arguments = None
            container_class_name = None
            current_class_name = None
            current_constructor = None
            continue

        if inside_function_class or inside_object_class:
            if len(keywords) == 3 and keywords[-1].endswith(";"):
                current_arguments.append((keywords[1], keywords[2][:-1], extract_doc(lines, no)))
                continue

            if len(keywords) == 7 and keywords[4] == "CONSTRUCTOR":
                current_constructor = int(keywords[6][:-1])
                continue

        if keywords[0] == "package":
            package = line
            continue

        if len(keywords) == 8 and keywords[1] == "abstract":
            inside_object_container_class = True
            current_class_name = keywords[4]
            current_class_docs = extract_doc(lines, no)
            continue

        if len(keywords) == 4 and keywords[2] == "TdApi":
            continue

        if len(keywords) == 6 and keywords[1] == "abstract":
            inside_abstract_class = True
            continue

        if len(keywords) == 7 and keywords[2] == "class" and keywords[5] == "Object":
            current_class_name = keywords[-4]
            current_arguments = []
            inside_object_class = True
            current_class_docs = extract_doc(lines, no)
            container_class_name = keywords[5]
            continue

        if len(keywords) == 7 and keywords[2] == "class" and keywords[5] == "Function":
            current_class_name = keywords[-4]
            current_arguments = []
            inside_function_class = True
            current_class_docs = extract_doc(lines, no)
            container_class_name = keywords[5]
            continue

        if len(keywords) == 7 and keywords[2] == "class" and keywords[5] in container_classes:
            current_class_name = keywords[-4]
            current_arguments = []
            inside_object_class = True
            current_class_docs = extract_doc(lines, no)
            container_class_name = keywords[5]
            continue

    data_input.close()

    data_output = open(output_path, "w")
    data_output.write(package + "\n\n")
    data_output.write(open(headers_path).read())
    del container_classes["Function"]

    output = CodeWriter(data_output, 1)

    output.indent()
    output.open_custom_block(f"public static class Deserializer")
    output.newline()

    output.indent()
    output.open_function("deserialize", [("DataInput", "input")], "static Object", "IOException")
    output.newline()

    output.indent()
    output.open_switch("input.readInt()")
    output.newline()

    for classes in (object_classes, function_classes):
        for class_name in classes.keys():
            output.indent()
            output.open_switch_case(f"{class_name}.CONSTRUCTOR")
            output.newline()

            output.indent()
            output.ret(f"new {class_name}(input)")
            output.newline()

            output.indent_depth -= 1

    output.indent()
    output.open_switch_default()
    output.newline()

    output.indent()
    output.exception("UnsupportedOperationException")
    output.newline()
    output.indent_depth -= 1

    output.close_block(space=True)
    output.close_block(space=True)
    output.close_block(space=True)

    output.newline()

    for container_class_name, container_class_meta in container_classes.items():
        output.indent()
        output.open_docs()

        for docs in [container_class_meta[0]]:
            for doc in split_docs([docs]):
                output.newline()
                output.indent()
                output.write_docs(doc)

        output.newline()
        output.indent()
        output.close_docs()

        output.newline()
        output.indent()

        allowed_classess = []

        for classes in (object_classes, function_classes):
            for class_name, class_meta in classes.items():
                if container_class_name == class_meta[1]:
                    allowed_classess.append(class_name)

        allowed_classess_str = "permits "

        for i, allowed_classess_group in enumerate(chunker(allowed_classess, 3)):
            if i:
                allowed_classess_str += ",\n"
                allowed_classess_str += output.indent_chr * (output.indent_depth + 1)
            allowed_classess_str += ", ".join(allowed_classess_group)

        if allowed_classess and java17:
            output.open_custom_block(f"public abstract static sealed class", container_class_name, "extends Object", allowed_classess_str)
        else:
            output.open_custom_block(f"public abstract static class", container_class_name, "extends Object")

        output.close_block()
        output.newline()

    for classes in (object_classes, function_classes):
        for class_name, class_meta in classes.items():
            output.indent()
            output.open_docs()

            for docs in class_meta[3]:
                for doc in split_docs([docs]):
                    output.newline()
                    output.indent()
                    output.write_docs(doc)

            output.newline()
            output.indent()
            output.close_docs()

            output.newline()
            output.indent()

            class_generics = ""

            if class_meta[1] == "Function":
                class_generics = "<"
                class_generics += docs.split("@link ")[1].split()[0]
                class_generics += ">"

            output.open_custom_block("public static final class", class_name, "extends", class_meta[1] + class_generics)

            for arg_type, arg_name, docs in class_meta[2]:
                output.newline()
                output.indent()
                output.open_docs()
                output.newline()
                output.indent()
                for doc in split_docs(docs):
                    output.write_docs(doc)
                    output.newline()
                    output.indent()
                output.close_docs()
                output.newline()
                output.indent()

                output.declare(arg_name, arg_type, "public")
                output.newline()

            output.newline()
            output.indent()
            output.declare("CONSTRUCTOR", "int", "public static final", value=str(class_meta[0]))
            output.newline()
            output.newline()

            output.indent()
            output.open_docs()

            for docs in class_meta[3]:
                for doc in split_docs([docs]):
                    output.newline()
                    output.indent()
                    output.write_docs(doc)

            output.newline()
            output.indent()
            output.close_docs()

            output.newline()
            output.indent()
            output.open_constructor_function(class_name, [])
            output.close_block()
            output.newline()

            output.indent()
            output.open_docs()

            for docs in class_meta[3]:
                for doc in split_docs([docs]):
                    output.newline()
                    output.indent()
                    output.write_docs(doc)

            for arg_name, arg_type, docs in class_meta[2]:
                output.newline()
                output.indent()
                output.write_docs()
                docs = split_docs(docs)
                output.newline()
                output.indent()
                output.write_docs(f"@param {arg_type} {arg_name} {docs[0]}")

                for doc in docs[1:]:
                    output.newline()
                    output.indent()
                    output.write_docs()
                    output.fd.write(f"        {doc}")

            output.newline()
            output.indent()
            output.close_docs()

            output.newline()

            if class_meta[2]:
                output.indent()
                output.open_constructor_function(class_name, [(x[0], x[1]) for x in class_meta[2]])
                output.newline()

                for arg_type, arg_name, _ in class_meta[2]:
                    output.indent()
                    output.class_assign(arg_name, arg_name)
                    output.newline()

                output.close_block(space=True)
                output.newline()

                output.indent()
                output.open_docs()

                for docs in class_meta[3]:
                    for doc in split_docs([docs]):
                        output.newline()
                        output.indent()
                        output.write_docs(doc)

                output.newline()
                output.indent()
                output.close_docs()
                output.newline()

            output.indent()
            output.open_constructor_function(class_name, [("DataInput", "input")], "IOException")
            output.newline()

            for arg_type, arg_name, _ in class_meta[2]:
                if arg_type in natives:
                    deserialize_native(output, arg_name, arg_type)

                elif not arg_type.endswith("[]"):
                    deserialize_tdapi(output, arg_name, arg_type, container_classes, object_classes)

                elif arg_type == "byte[][]" or not arg_type.endswith("[][]"):
                    output.indent()
                    output.open_if("input.readBoolean()")
                    output.newline()

                    output.indent()
                    if arg_type == "byte[][]":
                        output.class_assign(arg_name, f"new byte[input.readInt()][]")
                    else:
                        output.class_assign(arg_name, f"new {arg_type[:-2]}[input.readInt()]")
                    output.newline()

                    output.indent()
                    output.open_for("int i = 0", f"i < this.{arg_name}.length", "i++")
                    output.newline()

                    if arg_type[:-2] in natives:
                        deserialize_native(output, f"{arg_name}[i]", arg_type[:-2], null_check=False)

                    else:
                        deserialize_tdapi(output, f"{arg_name}[i]", arg_type[:-2],
                                          container_classes, object_classes, null_check=False)

                    output.close_block(space=True)
                    output.close_block(space=True)

                elif arg_type.endswith("[][]"):
                    output.indent()
                    output.open_if("input.readBoolean()")
                    output.newline()

                    output.indent()
                    output.class_assign(arg_name, f"new {arg_type[:-4]}[input.readInt()][]")
                    output.newline()

                    output.indent()
                    output.open_for("int i = 0", f"i < this.{arg_name}.length", "i++")
                    output.newline()

                    output.indent()
                    output.class_assign(f"{arg_name}[i]", f"new {arg_type[:-4]}[input.readInt()]")
                    output.newline()

                    output.indent()
                    output.open_for("int j = 0", f"j < this.{arg_name}[i].length", "j++")
                    output.newline()

                    if arg_type[:-4] in natives:
                        deserialize_native(output, f"{arg_name}[i][j]", arg_type[:-4], null_check=False)

                    else:
                        deserialize_tdapi(output, f"{arg_name}[i][j]", arg_type[:-4],
                                          container_classes, object_classes, null_check=False)

                    output.close_block(space=True)
                    output.close_block(space=True)
                    output.close_block(space=True)

            output.close_block(space=True)

            output.newline()
            output.indent()
            output.open_function("getConstructor", [], "int")
            output.newline()

            output.indent()
            output.ret("CONSTRUCTOR")
            output.newline()

            output.close_block(space=True)

            output.newline()
            output.indent()
            output.open_function("serialize", [("DataOutput", "output")], "void", "IOException")
            output.newline()

            output.indent()
            output.call("output.writeInt", f"CONSTRUCTOR")
            output.newline()

            for arg_type, arg_name, _ in class_meta[2]:
                if arg_type in natives:
                    serialize_native(output, arg_type, arg_name)

                elif not arg_type.endswith("[]"):
                    serialize_tdapi(output, arg_name)

                elif arg_type == "byte[][]" or not arg_type.endswith("[][]"):
                    output.indent()
                    output.open_if(f"this.{arg_name} == null")
                    output.newline()

                    output.indent()
                    output.call("output.writeBoolean", "false")
                    output.newline()

                    output.open_if_else(space=True)
                    output.newline()

                    output.indent()
                    output.call("output.writeBoolean", "true")
                    output.newline()

                    output.indent()
                    output.call("output.writeInt", f"this.{arg_name}.length")
                    output.newline()

                    output.indent()
                    output.open_for("int i = 0", f"i < this.{arg_name}.length", "i++")
                    output.newline()

                    if arg_type[:-2] in natives:
                        serialize_native(output, arg_type[:-2], f"{arg_name}[i]", null_check=False)
                    else:
                        serialize_tdapi(output, f"{arg_name}[i]", null_check=False)

                    output.close_block(space=True)
                    output.close_block(space=True)

                elif arg_type.endswith("[][]"):
                    output.indent()
                    output.open_if(f"this.{arg_name} == null")
                    output.newline()

                    output.indent()
                    output.call("output.writeBoolean", "false")
                    output.newline()

                    output.open_if_else(space=True)
                    output.newline()

                    output.indent()
                    output.call("output.writeBoolean", "true")
                    output.newline()

                    output.indent()
                    output.call("output.writeInt", f"this.{arg_name}.length")
                    output.newline()

                    output.indent()
                    output.open_for("int i = 0", f"i < this.{arg_name}.length", "i++")
                    output.newline()

                    output.indent()
                    output.call("output.writeInt", f"this.{arg_name}[i].length")
                    output.newline()

                    output.indent()
                    output.open_for("int j = 0", f"j < this.{arg_name}[i].length", "j++")
                    output.newline()

                    if arg_type[:-4] in natives:
                        serialize_native(output, arg_type[:-4], f"{arg_name}[i][j]", null_check=False)
                    else:
                        serialize_tdapi(output, f"{arg_name}[i][j]", null_check=False)

                    output.close_block(space=True)
                    output.close_block(space=True)
                    output.close_block(space=True)

            output.close_block(space=True)

            output.newline()
            output.indent()
            output.open_function("equals", [("java.lang.Object", "o")], "boolean")
            output.newline()

            output.indent()
            output.open_if("this == o")
            output.newline()
            output.indent()
            output.ret("true")
            output.newline()
            output.close_block(space=True)

            output.indent()
            output.open_if("o == null || getClass() != o.getClass()")
            output.newline()
            output.indent()
            output.ret("false")
            output.newline()
            output.close_block(space=True)

            if class_meta[2]:
                output.indent()
                other_class = class_name[0].lower() + class_name[1:]
                output.local_assign(class_name, other_class, f"({class_name}) o")

                output.newline()

                for arg_type, arg_name, _ in class_meta[2]:
                    output.indent()

                    if arg_type in cmp_natives:
                        output.open_if(f"this.{arg_name} != {other_class}.{arg_name}")

                    elif not arg_type.endswith("[]"):
                        output.open_if(f"!Objects.equals(this.{arg_name}, {other_class}.{arg_name})")

                    elif arg_type.endswith("[][]"):
                        output.open_if(f"!Arrays.deepEquals(this.{arg_name}, {other_class}.{arg_name})")

                    else:
                        output.open_if(f"!Arrays.equals(this.{arg_name}, {other_class}.{arg_name})")

                    output.newline()
                    output.indent()
                    output.ret("false")
                    output.newline()
                    output.close_block(space=True)

            output.indent()
            output.ret("true")
            output.newline()

            output.close_block(space=True)

            output.newline()
            output.indent()
            output.open_function("hashCode", [], "int")
            output.newline()

            output.indent()

            if class_meta[2]:
                primitives = [(t, n) for t, n, _ in class_meta[2] if t in cmp_natives]

                if primitives and len(class_meta[2]) == 1:
                    output.ret(f"{native_to_object[primitives[0][0]]}.hashCode(this.{primitives[0][1]})")

                elif primitives:
                    output.local_assign("int", "result",
                                        f"{native_to_object[primitives[0][0]]}.hashCode(this.{primitives[0][1]})")
                    output.newline()
                    output.indent()

                    for arg_type, arg_name in primitives[1:]:
                        output.assign("result", f"result * 31 + "
                                                f"{native_to_object[arg_type]}.hashCode(this.{arg_name})")
                        output.newline()
                        output.indent()

                tdapi = [(t, n) for t, n, _ in class_meta[2] if n not in [p[1] for p in primitives]]

                if tdapi and len(class_meta[2]) == 1:
                    output.ret(hash_object(f"this.{tdapi[0][1]}", tdapi[0][0]))
                    start = 1

                else:
                    if not primitives:
                        output.local_assign("int", "result", hash_object(f'this.{tdapi[0][1]}', tdapi[0][0]))
                        output.newline()
                        output.indent()
                        start = 1
                    else:
                        start = 0

                for arg_type, arg_name in tdapi[start:]:
                    output.assign("result", f"result * 31 + ({hash_object(f'this.{arg_name}', arg_type)})")
                    output.newline()
                    output.indent()

                if len(class_meta[2]) > 1:
                    output.ret("result")

            else:
                output.ret("CONSTRUCTOR")

            output.newline()

            output.close_block(space=True)
            output.close_block(space=True)
            output.newline()

    data_output.seek(data_output.tell() - 1)
    data_output.write("}")


if __name__ == '__main__':
    main(sys.argv[-4], sys.argv[-3], sys.argv[-2], sys.argv[-1])
