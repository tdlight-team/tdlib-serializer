import typing


indent = "\t"


class CodeWriter:
    fd: typing.TextIO
    indent_depth: int

    def __init__(self, file: typing.TextIO, indent_depth: int = 0):
        self.fd = file
        self.indent_depth = indent_depth

    def indent(self):
        self.fd.write(indent * self.indent_depth)

    def open_custom_block(self, *blocks: str):
        self.fd.write(" ".join(blocks) + " {")
        self.indent_depth += 1

    def class_assign(self, class_value, local_value):
        self.fd.write("this." + class_value + " = " + local_value + ";")

    def close_block(self, space: bool = False, newline: bool = True):
        self.indent_depth -= 1
        if space:
            self.indent()
        self.fd.write("}\n" if newline else "}")

    def newline(self):
        self.fd.write("\n")

    def open_switch(self, data: str):
        self.indent_depth += 1
        self.fd.write("switch(" + data + ") {")

    def switch_break(self):
        self.indent_depth -= 1
        self.fd.write("break;")

    def open_switch_case(self, case: str):
        self.indent_depth += 1
        self.fd.write("case " + case + ":")

    def open_switch_default(self):
        self.indent_depth += 1
        self.fd.write("default:")

    def exception(self, exception_class: str):
        self.fd.write("throw new " + exception_class + "();")

    def open_if_else(self, space: bool = False):
        self.indent_depth -= 1
        if space:
            self.indent()
        self.fd.write("} else {")
        self.indent_depth += 1

    def local_assign(self, object_type: str, name: str, value: str):
        self.fd.write(object_type + " " + name + " = " + value + ";")

    def assign(self, name: str, value: str):
        self.fd.write(name + " = " + value + ";")

    def open_for(self, start: str, cond: str, stmt: str):
        self.indent_depth += 1
        self.fd.write("for (" + start + "; " + cond + "; " + stmt + ") {")

    def open_if(self, cond: str):
        self.indent_depth += 1
        self.fd.write("if (" + cond + ") {")

    def call(self, method: str, *args: str):
        self.fd.write(method + "(" + ", ".join(args) + ");")

    def ret(self, var: str):
        self.fd.write("return " + var + ";")

    def open_function(self, name: str, args: typing.List[typing.Tuple[str, str]], t: str, e: str = None):
        self.indent_depth += 1
        result = "public " + t + " " + name + "(" + ", ".join((t + " " + n for t, n in args)) + ")"
        if e:
            result += " throws " + e
        result += " {"
        self.fd.write(result)

    def open_constructor_function(self, name: str, args: typing.List[typing.Tuple[str, str]], e: str = None):
        self.indent_depth += 1
        result = "public " + name + "(" + ", ".join((t + " " + n for t, n in args)) + ")"
        if e:
            result += " throws " + e
        result += " {"
        self.fd.write(result)

    def declare(self, name: str, typ: str, flags: str, value: str = None):
        result = flags + " " + typ + " " + name
        if value:
            result += " = " + value
        result += ";"
        self.fd.write(result)
