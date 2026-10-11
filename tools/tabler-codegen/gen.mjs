// Generates app/src/commonMain/kotlin/ui/icons/TablerIcons.kt from the upstream
// Tabler SVG set. Paths are embedded verbatim and parsed at runtime by
// androidx.compose.ui.graphics.vector.PathParser, which is what ui/icons/OrbitIcons.kt
// already does -- so no SVG-path -> Compose-DSL transpiler lives in this repo.
//
// Usage:
//   node tools/tabler-codegen/gen.mjs <path-to-tarball> [--tabler 3.49.0]
//
// <path-to-tarball> is an extracted @tabler/icons package containing icons/outline and
// icons/filled. The set is pinned by TABLER_VERSION below and written into the header of
// the generated file so drift is visible in review.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const TABLER_VERSION = "3.49.0";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "..", "..");
const outFile = path.join(repoRoot, "app/src/commonMain/kotlin/ui/icons/TablerIcons.kt");
const manifestFile = path.join(here, "manifest.txt");

const pkgDir = process.argv[2];
if (!pkgDir) {
  console.error("usage: node gen.mjs <path-to-extracted-@tabler/icons-package>");
  process.exit(1);
}

const outlineDir = path.join(pkgDir, "icons", "outline");
const filledDir = path.join(pkgDir, "icons", "filled");
for (const d of [outlineDir, filledDir]) {
  if (!fs.existsSync(d)) throw new Error(`missing icon directory: ${d}`);
}

// PascalCase -> kebab-case, e.g. Volume2 -> volume-2, InfoCircle -> info-circle.
function kebab(symbol) {
  return symbol
    .replace(/([a-z0-9])([A-Z])/g, "$1-$2")
    .replace(/([A-Za-z])(\d)/g, "$1-$2")
    .replace(/(\d)([A-Za-z])/g, "$1-$2")
    .toLowerCase();
}

// Collect the <path d="..."> of an icon, in document order. Tabler normalises every
// icon to <path> only, but stay defensive about a stray element appearing upstream.
function readPaths(file) {
  const svg = fs.readFileSync(file, "utf8");
  const out = [];
  const re = /<path\b[^>]*\bd="([^"]*)"/g;
  let m;
  while ((m = re.exec(svg)) !== null) {
    const d = m[1].trim();
    if (!d || d === "M0 0h24v24H0z") continue; // Tabler's no-op clipping path
    out.push(d.replace(/\s+/g, " "));
  }
  return out;
}

const symbols = fs
  .readFileSync(manifestFile, "utf8")
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith("#"));

const resolved = [];
const missing = [];
for (const symbol of symbols) {
  const file = kebab(symbol);
  const outlinePath = path.join(outlineDir, `${file}.svg`);
  const filledPath = path.join(filledDir, `${file}.svg`);
  const hasOutline = fs.existsSync(outlinePath);
  const hasFilled = fs.existsSync(filledPath);
  if (!hasOutline && !hasFilled) {
    missing.push(`${symbol} -> ${file}.svg`);
    continue;
  }
  resolved.push({
    symbol,
    file,
    hasOutline,
    hasFilled,
    outline: hasOutline ? readPaths(outlinePath) : null,
    filled: hasFilled ? readPaths(filledPath) : null,
  });
}

if (missing.length) {
  console.error("unresolved symbols:\n  " + missing.join("\n  "));
  process.exit(1);
}


// Every glyph is emitted into one lookup table first; the extension properties below just
// memoise out of it. Keeping the table as the single source of truth means the path strings
// exist exactly once, and lets tests parse them without going through a built ImageVector
// (ImageVector.paths is not public API).
const glyphs = [];
for (const r of resolved) {
  if (r.hasFilled) glyphs.push({ key: r.symbol, stroke: false, paths: r.filled });
  glyphs.push({ key: r.symbol + (r.hasFilled ? "Outline" : ""), stroke: true, paths: r.outline });
}

const filledCount = resolved.filter((r) => r.hasFilled).length;

const tableEntries = glyphs
  .map((g) => {
    const ds = g.paths.map((d) => `        """${d}"""`).join(",\n");
    return `    "${g.key}" to TablerGlyph(stroke = ${g.stroke}, paths = listOf(\n${ds}\n    ))`;
  })
  .join(",\n");

const props = resolved
  .map((r) => {
    const lines = [`    private var _${r.symbol}: ImageVector? = null`];
    lines.push(
      "",
      `    val TablerIcons.${r.symbol}: ImageVector`,
      `        get() = _${r.symbol} ?: tablerGlyph("${r.symbol}").also { _${r.symbol} = it }`
    );
    if (r.hasFilled) {
      lines.push(
        "",
        `    private var _${r.symbol}Outline: ImageVector? = null`,
        "",
        `    val TablerIcons.${r.symbol}Outline: ImageVector`,
        `        get() = _${r.symbol}Outline ?: tablerGlyph("${r.symbol}Outline").also { _${r.symbol}Outline = it }`
      );
    }
    return lines.join("\n");
  })
  .join("\n\n");

const kt = `// GENERATED FILE -- DO NOT EDIT BY HAND.
// Regenerate with: node tools/tabler-codegen/gen.mjs <extracted-@tabler/icons-package>
//
// Source: Tabler Icons v${TABLER_VERSION} (MIT), https://tabler.io/icons
//   ${resolved.length} symbols, ${glyphs.length} glyphs -- ${filledCount} symbols have an
//   upstream filled variant, the rest fall back to their outline.
//
// Convention: \`TablerIcons.Foo\` resolves to the FILLED variant of "foo" when Tabler ships
// one, otherwise to the outline variant. \`TablerIcons.FooOutline\` always resolves to the
// outline variant and exists only for symbols that have a filled variant.
//
// Path data is embedded verbatim from the upstream SVGs and handed to Compose's PathParser
// on first access, exactly as ui/icons/OrbitIcons.kt does. No SVG-path -> Compose-DSL
// transpiler lives in this repo, so nothing can reinterpret Tabler's geometry in transit.

package ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object TablerIcons

${props}

internal data class TablerGlyph(val stroke: Boolean, val paths: List<String>)

internal val TablerGlyphs: Map<String, TablerGlyph> = mapOf(
${tableEntries}
)

private fun tablerGlyph(name: String): ImageVector {
    val glyph = TablerGlyphs.getValue(name)
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        glyph.paths.forEach { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                pathFillType = PathFillType.NonZero,
                fill = if (glyph.stroke) null else SolidColor(Color.Black),
                stroke = if (glyph.stroke) SolidColor(Color.Black) else null,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()
}
`;

fs.writeFileSync(outFile, kt);

console.log(`wrote ${path.relative(repoRoot, outFile)}`);
console.log(`  ${resolved.length} symbols -> ${glyphs.length} glyphs (${filledCount} filled)`);
console.log(`  ${kt.split("\n").length} lines`);
