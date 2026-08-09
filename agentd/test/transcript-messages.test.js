const test = require("node:test");
const assert = require("node:assert/strict");
const {
  MAX_TEXT_CHARS,
  condense,
  extractMessage,
  toolSummary,
} = require("../dist/transcript-messages.js");

test("conversation condensation preserves useful line breaks and normalizes horizontal space", () => {
  assert.equal(
    condense("  first\t\tline  \r\n second   value\rthird\n \n\n\n fourth  "),
    "first line\nsecond value\nthird\n\nfourth",
  );

  const message = extractMessage({
    type: "assistant",
    timestamp: "2026-08-08T10:00:00.000Z",
    message: {
      role: "assistant",
      content: [
        { type: "text", text: "Paragraph one.\r\nStill one." },
        { type: "text", text: "Paragraph two." },
      ],
    },
  });
  assert.equal(message.text, "Paragraph one.\nStill one.\nParagraph two.");
});

test("conversation condensation caps text at 3500 characters", () => {
  assert.equal(MAX_TEXT_CHARS, 3500);
  assert.equal(condense("x".repeat(MAX_TEXT_CHARS + 200)).length, MAX_TEXT_CHARS);
});

test("tool summaries stay single-line and within 90 characters", () => {
  assert.equal(
    toolSummary("shell", { command: "git   status\r\n&&\tgit diff" }),
    "shell · git status && git diff",
  );
  const long = toolSummary("shell", { command: `run ${"argument ".repeat(30)}` });
  assert.equal(long.includes("\n"), false);
  assert.equal(long.length, 90);
});
