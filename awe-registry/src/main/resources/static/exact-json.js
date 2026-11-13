/* JSON 数值只在展示层保留字面值：不更改服务端 INTEGER/DECIMAL 契约，不经过有损 Number 转换。 */
(() => {
  "use strict";
  function parse(text) {
    if (typeof text !== "string") throw new TypeError("JSON input must be text");
    let position = 0;
    const number = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/y;
    const fail = () => {
      throw new SyntaxError("Invalid JSON at position " + position);
    };
    function whitespace() {
      while (position < text.length && /[ \t\r\n]/.test(text[position])) position++;
    }
    function string() {
      const start = position++;
      while (position < text.length) {
        const character = text[position++];
        if (character === '"') return JSON.parse(text.slice(start, position));
        if (character === "\\") position++;
      }
      return fail();
    }
    function value(depth) {
      if (depth > 256) throw new SyntaxError("JSON nesting exceeds display limit");
      whitespace();
      const character = text[position];
      if (character === '"') return string();
      if (character === "{") {
        position++;
        const object = {};
        whitespace();
        if (text[position] === "}") {
          position++;
          return object;
        }
        while (position < text.length) {
          whitespace();
          if (text[position] !== '"') return fail();
          const name = string();
          whitespace();
          if (text[position++] !== ":") return fail();
          const item = value(depth + 1);
          // 与 JSON.parse 一样把 __proto__ 当普通数据字段，不能触发原型 setter。
          Object.defineProperty(object, name, {
            value: item,
            enumerable: true,
            configurable: true,
            writable: true,
          });
          whitespace();
          const separator = text[position++];
          if (separator === "}") return object;
          if (separator !== ",") return fail();
        }
        return fail();
      }
      if (character === "[") {
        position++;
        const array = [];
        whitespace();
        if (text[position] === "]") {
          position++;
          return array;
        }
        while (position < text.length) {
          array.push(value(depth + 1));
          whitespace();
          const separator = text[position++];
          if (separator === "]") return array;
          if (separator !== ",") return fail();
        }
        return fail();
      }
      if (character === "-" || (character >= "0" && character <= "9")) {
        number.lastIndex = position;
        const token = number.exec(text);
        if (!token) return fail();
        position = number.lastIndex;
        const literal = token[0];
        if (/[.eE]/.test(literal) || literal === "-0") return literal;
        const integer = Number(literal);
        return Number.isSafeInteger(integer) ? integer : literal;
      }
      for (const [literal, result] of [
        ["true", true],
        ["false", false],
        ["null", null],
      ]) {
        if (text.startsWith(literal, position)) {
          position += literal.length;
          return result;
        }
      }
      return fail();
    }
    const result = value(0);
    whitespace();
    if (position !== text.length) return fail();
    return result;
  }
  globalThis.AweExactJson = Object.freeze({ parse });
})();
