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
const appPath = "app/build.gradle.kts";
const appSource = fs.readFileSync(appPath, "utf8");
const appUpdated = appSource
  .replace(/versionCode = \d+/, `versionCode = ${versionCode}`)
  .replace(/versionName = "[^"]+"/, `versionName = "${version}"`);
if (appUpdated === appSource) throw new Error("InkDAV Android version declarations were not updated");
fs.writeFileSync(appPath, appUpdated);

const inkVaultPath = "inkvault/build.gradle.kts";
const inkVaultSource = fs.readFileSync(inkVaultPath, "utf8");
const inkVaultUpdated = inkVaultSource
  .replace(/gradleProperty\("inkVaultVersionCode"\)\.getOrElse\("\d+"\)/, `gradleProperty("inkVaultVersionCode").getOrElse("${versionCode}")`)
  .replace(/gradleProperty\("inkVaultVersion"\)\.getOrElse\("[^"]+"\)/, `gradleProperty("inkVaultVersion").getOrElse("${version}")`);
if (inkVaultUpdated === inkVaultSource) throw new Error("InkVault Android version declarations were not updated");
fs.writeFileSync(inkVaultPath, inkVaultUpdated);
