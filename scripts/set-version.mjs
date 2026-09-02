import fs from "node:fs";

const version = process.argv[2];
const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(version ?? "");
if (!match) throw new Error("Expected a stable semantic version (major.minor.patch)");
const [, majorText, minorText, patchText] = match;
const major = Number(majorText);
const minor = Number(minorText);
const patch = Number(patchText);
if (minor > 999 || patch > 999 || major > 1999) throw new Error("Version is outside InkDAV's Android versionCode range");
const versionCode = major * 1_000_000 + minor * 1_000 + patch;
const path = "app/build.gradle.kts";
const source = fs.readFileSync(path, "utf8");
const updated = source
  .replace(/versionCode = \d+/, `versionCode = ${versionCode}`)
  .replace(/versionName = "[^"]+"/, `versionName = "${version}"`);
if (updated === source) throw new Error("Android version declarations were not updated");
fs.writeFileSync(path, updated);
