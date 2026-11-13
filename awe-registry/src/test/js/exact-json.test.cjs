const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const scope = vm.createContext({});
vm.runInContext(
  fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/exact-json.js"), "utf8"),
  scope,
);
const parse = scope.AweExactJson.parse;

test("preserves exact int8, numeric, exponent and negative zero lexemes for display", () => {
  const response = parse(
    `{"result":{"rows":[{"id":9007199254740993,"negative":-9007199254740993,"amount":1234567890.123456789012345678901,"exponent":1.234567890123456789e+30,"zero":-0}]}}`,
  );
  const row = response.result.rows[0];
  assert.equal(row.id, "9007199254740993");
  assert.equal(row.negative, "-9007199254740993");
  assert.equal(row.amount, "1234567890.123456789012345678901");
  assert.equal(row.exponent, "1.234567890123456789e+30");
  assert.equal(row.zero, "-0");
});

test("keeps safe integer metadata numeric and never rewrites number-like string contents", () => {
  const response = parse(
    `{"maxRows":100,"maxBytes":65536,"createdAt":1794473441554,"fraction":0.1000,"literal":"9007199254740993 and 1.00 \\"quoted\\"","escaped":"\\u0031\\u0032","flag":true,"missing":null}`,
  );
  assert.equal(response.maxRows, 100);
  assert.equal(response.maxBytes, 65536);
  assert.equal(response.createdAt, 1794473441554);
  assert.equal(response.fraction, "0.1000");
  assert.equal(response.literal, '9007199254740993 and 1.00 "quoted"');
  assert.equal(response.escaped, "12");
  assert.equal(response.flag, true);
  assert.equal(response.missing, null);
});

test("rejects malformed JSON rather than accepting numeric object keys after rewriting", () => {
  for (const source of [
    "{1.5:2}",
    "[01]",
    "[1,]",
    '{"x":1,}',
    "1e+",
    '{"x":"unterminated}',
    "truefalse",
    "[1 2]",
    "",
    "+1",
    "[.5]",
  ]) {
    assert.throws(() => parse(source), { name: "SyntaxError" });
  }
});

test("treats __proto__ as an ordinary JSON data property and handles duplicate keys", () => {
  const response = parse('{"__proto__":{"polluted":true},"same":1,"same":2}');
  assert.equal(Object.hasOwn(response, "__proto__"), true);
  assert.equal(Object.getPrototypeOf(response).polluted, undefined);
  assert.equal(Object.prototype.polluted, undefined);
  assert.equal(response.same, 2);
});
