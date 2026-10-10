#!/usr/bin/env node
/*
 * Parse every changed .java file, and say so before a runner does.
 *
 * Why: the machine these patches are written on has no JDK, so nothing local can compile them, and two
 * separate commits went out with Java that javac rejected on the first try - an unbalanced `when` and a
 * string literal whose inner quotes lost their escaping through a patch script ("...none ("companions":
 * [...])"). The second one is invisible to any per-line heuristic: the quotes are even in number, so the
 * line looks closed, and only a parser sees that `companions` is where a `)` belongs. Counting quotes was
 * tried first, measured against the tree and against the bug, caught ten lines that compile and missed
 * the one that did not; this replaces it.
 *
 * It is not wired into CI. A CI job has a real compiler, which is strictly stronger; this exists for the
 * half-minute before a push, which is where the mistake is still cheap. It needs one dependency:
 *   npm install java-parser        # pure JS, from the npm registry, no JDK
 *
 * Usage:
 *   node tools/test/check_java_syntax.js                 # every .java changed since 61cf66e1..HEAD
 *   node tools/test/check_java_syntax.js 61cf66e1..HEAD  # ...or since whatever range you name
 *   node tools/test/check_java_syntax.js --paths a.java  # ...or exactly these files
 *   node tools/test/check_java_syntax.js --paths f1.java f2.java
 */
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");

let parse;
try {
  ({ parse } = require("java-parser"));
} catch (e) {
  console.error("java-parser is not installed (npm install java-parser). This check is a convenience:");
  console.error("the compiler in CI is the authority. Skipping is not failing.");
  process.exit(0);
}

const args = process.argv.slice(2);
let files;
if (args[0] === "--paths") {
  files = args.slice(1);
} else {
  const ref = args[0] || "61cf66e1";
  const range = ref.includes("..") ? ref : `${ref}..HEAD`;   // a base, or a full range, either is fine
  let listing;
  try {
    listing = execFileSync("git", ["diff", "--name-only", range], { encoding: "utf8" });
  } catch (e) {
    console.error(`git diff ${range} failed - is that a real revision here?`);
    process.exit(2);
  }
  files = listing.split("\n").filter((f) => f.endsWith(".java") && fs.existsSync(f));
}
if (!files.length) {
  console.log("no .java files in range - nothing to parse");
  process.exit(0);
}

let bad = 0;
for (const f of files) {
  const text = fs.readFileSync(f, "utf8");
  try {
    parse(text);
  } catch (e) {
    bad++;
    // java-parser's message is a wall of stack-ish lines; the first three say everything useful.
    const lines = String(e.message).split("\n").filter((l) => l.trim()).slice(0, 4);
    console.log(`${f}: ${lines.shift()}`);
    for (const l of lines) console.log(`    ${l.trim()}`);
  }
}
if (bad) {
  console.error(`\n${bad} of ${files.length} .java file(s) do not parse - javac would stop here too.`);
  process.exit(1);
}
console.log(`parsed ${files.length} .java file(s): no syntax errors`);
