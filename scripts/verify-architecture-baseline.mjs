import assert from "node:assert/strict";
import { access, readFile, readdir } from "node:fs/promises";
import { constants } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

async function assertExists(relativePath, kind) {
  const target = path.join(repositoryRoot, relativePath);
  await access(target, constants.F_OK);
  console.log(`ok ${kind}: ${relativePath}`);
}

async function assertMissing(relativePath, kind) {
  const target = path.join(repositoryRoot, relativePath);
  try {
    await access(target, constants.F_OK);
  } catch (error) {
    if (error?.code === "ENOENT") {
      console.log(`ok retired ${kind} absent: ${relativePath}`);
      return;
    }
    throw error;
  }
  assert.fail(`Retired ${kind} still exists: ${relativePath}`);
}

async function collectManifestFiles(directory) {
  const ignoredDirectories = new Set([
    ".codex-tmp",
    ".git",
    ".next",
    ".pet-runs",
    ".playwright-cli",
    ".venv",
    ".venv310",
    ".venv312",
    "build",
    "node_modules",
    "output",
    "target",
    ".worktrees",
  ]);
  const manifestNames = new Set([
    "Cargo.toml",
    "Pipfile",
    "build.gradle",
    "build.gradle.kts",
    "go.mod",
    "package.json",
    "pom.xml",
    "pyproject.toml",
  ]);
  const files = [];
  const entries = await readdir(directory, { withFileTypes: true });

  for (const entry of entries) {
    if (entry.isDirectory() && !ignoredDirectories.has(entry.name)) {
      files.push(...(await collectManifestFiles(path.join(directory, entry.name))));
      continue;
    }

    if (
      entry.isFile() &&
      (manifestNames.has(entry.name) || /^requirements(?:[-.].*)?\.txt$/i.test(entry.name))
    ) {
      files.push(path.join(directory, entry.name));
    }
  }

  return files;
}

function dshPackageName(name) {
  return /^(?:@[^/]+\/)?dsh(?:[-_.]|$)/i.test(name);
}

function dshDependencyInJson(manifest, manifestPath) {
  const runtimeSections = [
    "bundleDependencies",
    "bundledDependencies",
    "dependencies",
    "optionalDependencies",
    "peerDependencies",
  ];

  for (const section of runtimeSections) {
    const value = manifest[section];
    const names = Array.isArray(value) ? value : value && typeof value === "object" ? Object.keys(value) : [];
    const dependency = names.find(dshPackageName);
    if (dependency) {
      return `${manifestPath} declares dsh runtime dependency ${dependency} in ${section}`;
    }
  }

  return null;
}

function dshDependencyInText(content, manifestPath) {
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.replace(/#.*/, "").trim();
    if (!line || line.startsWith("//") || line.startsWith("<!--") || line.startsWith("*") || line.startsWith("<project")) {
      continue;
    }

    if (/^(?:-e\s+.*#egg=)?dsh(?:[-_.]|\s|[<>=!~]|$)/i.test(line)) {
      return `${manifestPath} declares dsh runtime dependency: ${rawLine.trim()}`;
    }

    if (/<(?:groupId|artifactId)>\s*(?:[^<]*:)?dsh(?:[-_.]|\s|<|$)/i.test(line)) {
      return `${manifestPath} declares dsh runtime dependency: ${rawLine.trim()}`;
    }

    if (/\b(?:implementation|api|runtimeOnly|compile)\s*[("'].*(?:^|[/.:_-])dsh(?:[-_.:/"')]|$)/i.test(line)) {
      return `${manifestPath} declares dsh runtime dependency: ${rawLine.trim()}`;
    }
  }

  return null;
}

async function assertNoDshRuntimeDependency() {
  const manifests = await collectManifestFiles(repositoryRoot);
  const violations = [];

  for (const manifestPath of manifests) {
    const relativePath = path.relative(repositoryRoot, manifestPath);
    const content = await readFile(manifestPath, "utf8");
    if (path.basename(manifestPath) === "package.json") {
      try {
        const violation = dshDependencyInJson(JSON.parse(content), relativePath);
        if (violation) violations.push(violation);
      } catch (error) {
        throw new Error(`Unable to parse ${relativePath}: ${error.message}`);
      }
    } else {
      const violation = dshDependencyInText(content, relativePath);
      if (violation) violations.push(violation);
    }
  }

  assert.deepEqual(violations, [], violations.join("\n"));
  console.log(`ok dsh runtime dependency check: scanned ${manifests.length} manifests`);
}

await assertExists("server/main/manager-api", "manager-api");
await assertExists("server/main/zixuan-server", "zixuan-server");
await assertMissing("server/main/xiaozhi-server", "python runtime");
await assertExists("mqtt-gateway", "mqtt-gateway");
await assertExists("firmware", "firmware");
await assertExists("server/main/companion-console", "companion-console");
await assertExists("server/main/companion-web", "companion-web");
await assertExists("docs/public-conversation-api.yaml", "public conversation OpenAPI");
await assertExists("docs/adr/0011-conversation-runtime-without-dsh-dependency.md", "architecture decision");
await assertNoDshRuntimeDependency();

console.log("Architecture baseline verified.");
