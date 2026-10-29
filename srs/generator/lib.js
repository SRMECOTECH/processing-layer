// Shared building blocks for the three Word documents: styles, numbered headings, tables, captions, title page,
// document control and the header/footer. Every document is A4 with 2.54 cm margins (content width 9026 DXA).
const fs = require("fs");
const path = require("path");
const {
  AlignmentType, BorderStyle, Footer, Header, HeadingLevel, ImageRun, LevelFormat, PageBreak, PageNumber,
  PageOrientation, Paragraph, ShadingType, Table, TableCell, TableOfContents, TableRow, TabStopType, TextRun,
  VerticalAlign, WidthType,
} = require("docx");

const FONT = "Calibri";
const MONO = "Consolas";
const NAVY = "1F3864";
const BLUE = "2E75B6";
const HEADER_FILL = "1F3864";
const ZEBRA = "F2F6FC";
const BORDER = "A6A6A6";
const A4 = { width: 11906, height: 16838 };
const MARGIN = 1440;
const CONTENT = A4.width - 2 * MARGIN; // 9026
const CONTENT_LANDSCAPE = A4.height - 2 * MARGIN; // 13958

// "Table 7" / "Figure 2" by label, filled in by the previous build pass (documents are built twice).
const REFS = {};
const ref = (label) => REFS[label] || "Table ?";

const styles = {
  default: { document: { run: { font: FONT, size: 21 }, paragraph: { spacing: { after: 100, line: 264 } } } },
  paragraphStyles: [
    { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
      run: { size: 32, bold: true, font: FONT, color: NAVY },
      paragraph: { spacing: { before: 360, after: 160 }, outlineLevel: 0, keepNext: true } },
    { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
      run: { size: 26, bold: true, font: FONT, color: NAVY },
      paragraph: { spacing: { before: 260, after: 120 }, outlineLevel: 1, keepNext: true } },
    { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
      run: { size: 22, bold: true, font: FONT, color: BLUE },
      paragraph: { spacing: { before: 200, after: 80 }, outlineLevel: 2, keepNext: true } },
    { id: "Caption", name: "Caption", basedOn: "Normal", next: "Normal", quickFormat: true,
      run: { size: 18, italics: true, color: "404040" },
      paragraph: { spacing: { before: 60, after: 200 }, alignment: AlignmentType.CENTER } },
    { id: "Code", name: "Code", basedOn: "Normal", next: "Code",
      run: { font: MONO, size: 16 },
      paragraph: { spacing: { after: 0, line: 228 } } },
  ],
};

const numbering = {
  config: [
    { reference: "bullets", levels: [
      { level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 540, hanging: 270 } } } },
      { level: 1, format: LevelFormat.BULLET, text: "–", alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 1080, hanging: 270 } } } },
    ] },
  ],
};

/** A document under construction: numbered headings, table and figure counters, and its body. */
class DocBuilder {
  constructor(meta) {
    this.meta = meta;
    this.sections = [{ landscape: false, children: [] }];
    this.h = [0, 0, 0];
    this.tables = 0;
    this.figures = 0;
    this.listRefs = 0;
  }

  /** The paragraphs of the section being written. */
  get body() {
    return this.sections[this.sections.length - 1].children;
  }

  /** Continues on landscape pages (wide figures and tables) until portrait() is called. */
  landscape() {
    this.sections.push({ landscape: true, children: [] });
  }

  portrait() {
    this.sections.push({ landscape: false, children: [] });
  }

  get width() {
    return this.sections[this.sections.length - 1].landscape ? CONTENT_LANDSCAPE : CONTENT;
  }

  // ---- text ----
  /** Drops the spacer after a table when a new page follows: a spacer that spills over would leave a blank page. */
  dropTrailingSpacer() {
    if (this.body.length && this.body[this.body.length - 1] === this.lastSpacer) {
      this.body.pop();
    }
  }

  h1(text, { pageBreak = true } = {}) {
    this.h = [this.h[0] + 1, 0, 0];
    if (pageBreak) this.dropTrailingSpacer();
    this.body.push(new Paragraph({ heading: HeadingLevel.HEADING_1, pageBreakBefore: pageBreak,
      children: [new TextRun(`${this.h[0]}  ${text}`)] }));
  }

  h2(text) {
    this.h = [this.h[0], this.h[1] + 1, 0];
    this.body.push(new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(`${this.h[0]}.${this.h[1]}  ${text}`)] }));
  }

  h3(text) {
    this.h[2] += 1;
    this.body.push(new Paragraph({ heading: HeadingLevel.HEADING_3,
      children: [new TextRun(`${this.h[0]}.${this.h[1]}.${this.h[2]}  ${text}`)] }));
  }

  /** Unnumbered heading (front matter, appendices title). */
  plainHeading(text, level = HeadingLevel.HEADING_1, pageBreak = true) {
    if (pageBreak) this.dropTrailingSpacer();
    this.body.push(new Paragraph({ heading: level, pageBreakBefore: pageBreak, children: [new TextRun(text)] }));
  }

  p(text, opts = {}) {
    this.body.push(new Paragraph({ children: runs(text), alignment: opts.align, spacing: opts.spacing,
      keepNext: opts.keepNext }));
  }

  note(text) {
    this.body.push(new Paragraph({
      children: [new TextRun({ text: "Note: ", bold: true, color: BLUE }), ...runs(text)],
      shading: { type: ShadingType.CLEAR, fill: "EEF3FA", color: "auto" },
      border: { left: { style: BorderStyle.SINGLE, size: 18, color: BLUE, space: 6 } },
      indent: { left: 160 }, spacing: { before: 80, after: 160 },
    }));
  }

  bullets(items, level = 0) {
    for (const item of items) {
      if (Array.isArray(item)) {
        this.bullets(item, level + 1);
        continue;
      }
      this.body.push(new Paragraph({ numbering: { reference: "bullets", level }, children: runs(item),
        spacing: { after: 60 } }));
    }
  }

  /** Numbered list that restarts at 1 every time. */
  numbered(items) {
    const ref = `num-${++this.listRefs}`;
    this.extraNumbering = this.extraNumbering || [];
    this.extraNumbering.push({ reference: ref, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
      alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 360 } } } }] });
    for (const item of items) {
      this.body.push(new Paragraph({ numbering: { reference: ref, level: 0 }, children: runs(item),
        spacing: { after: 60 } }));
    }
  }

  code(lines) {
    const text = Array.isArray(lines) ? lines : lines.split("\n");
    text.forEach((line, i) => this.body.push(new Paragraph({
      style: "Code",
      children: [new TextRun({ text: line.length ? line : " ", font: MONO, size: 16 })],
      shading: { type: ShadingType.CLEAR, fill: "F5F5F5", color: "auto" },
      border: {
        left: { style: BorderStyle.SINGLE, size: 4, color: "BFBFBF", space: 4 },
        right: { style: BorderStyle.SINGLE, size: 4, color: "BFBFBF", space: 4 },
        top: i === 0 ? { style: BorderStyle.SINGLE, size: 4, color: "BFBFBF", space: 2 } : undefined,
        bottom: i === text.length - 1 ? { style: BorderStyle.SINGLE, size: 4, color: "BFBFBF", space: 2 } : undefined,
      },
      spacing: { after: i === text.length - 1 ? 160 : 0, line: 228 },
    })));
  }

  pageBreak() {
    this.body.push(new Paragraph({ children: [new PageBreak()] }));
  }

  // ---- tables ----
  /**
   * A table with a dark header row. columns: [{ header, width, align? }]; rows: arrays of strings (or {text, bold}).
   * The caption goes ABOVE the table, as is usual for tables.
   */
  table(caption, columns, rows, { size = 17, zebra = true, label } = {}) {
    const total = columns.reduce((s, c) => s + c.width, 0);
    const width = this.width;
    if (total !== width) {
      throw new Error(`Table "${caption}": column widths add up to ${total}, expected ${width}`);
    }
    if (caption) {
      this.tables += 1;
      if (label) REFS[label] = `Table ${this.tables}`;
      this.body.push(new Paragraph({ style: "Caption", keepNext: true, spacing: { before: 160, after: 80 },
        children: [new TextRun({ text: `Table ${this.tables}: `, bold: true, italics: true }), new TextRun({ text: caption, italics: true })] }));
    }
    const border = { style: BorderStyle.SINGLE, size: 4, color: BORDER };
    const borders = { top: border, bottom: border, left: border, right: border };
    const cell = (value, col, rowIndex, header) => {
      const v = typeof value === "object" && value !== null ? value : { text: String(value ?? "") };
      const lines = String(v.text).split("\n");
      return new TableCell({
        borders,
        width: { size: col.width, type: WidthType.DXA },
        shading: header ? { fill: HEADER_FILL, type: ShadingType.CLEAR, color: "auto" }
          : (zebra && rowIndex % 2 === 1 ? { fill: ZEBRA, type: ShadingType.CLEAR, color: "auto" } : undefined),
        margins: { top: 50, bottom: 50, left: 90, right: 90 },
        verticalAlign: VerticalAlign.TOP,
        children: lines.map((line) => new Paragraph({
          alignment: col.align === "center" ? AlignmentType.CENTER : AlignmentType.LEFT,
          spacing: { after: 0, line: 240 },
          children: header ? [new TextRun({ text: line, bold: true, color: "FFFFFF", size })]
            : runs(line, { size, bold: v.bold, font: col.mono ? MONO : undefined }),
        })),
      });
    };
    const headerRow = new TableRow({ tableHeader: true, cantSplit: true,
      children: columns.map((c) => cell(c.header, c, 0, true)) });
    const bodyRows = rows.map((r, i) => new TableRow({ cantSplit: true, children: columns.map((c, j) => cell(r[j], c, i, false)) }));
    this.body.push(new Table({ width: { size: width, type: WidthType.DXA }, columnWidths: columns.map((c) => c.width),
      rows: [headerRow, ...bodyRows] }));
    this.lastSpacer = new Paragraph({ spacing: { after: 120 }, children: [] });
    this.body.push(this.lastSpacer);
  }

  /** Requirement table: ID | Requirement | Priority | Verification | Status. */
  requirements(caption, rows, trace = {}) {
    this.table(caption, [
      { header: "ID", width: 1350, mono: false },
      { header: "Requirement", width: 5076 },
      { header: "Priority", width: 850, align: "center" },
      { header: "Verify", width: 750, align: "center" },
      { header: "Status", width: 1000, align: "center" },
    ], rows.map((r) => [{ text: r[0], bold: true }, r[1], r[2], r[3], r[4]]), { size: 17 });
    for (const r of rows) {
      if (this.meta.register) {
        this.meta.register.push({ id: r[0], priority: r[2], method: r[3], status: r[4],
          objective: trace.objective || "", component: trace.component || "", tests: trace.tests || "" });
      }
    }
  }

  /** Key/value table (two columns). */
  keyValue(caption, pairs, keyWidth = 2600) {
    this.table(caption, [{ header: "Item", width: keyWidth }, { header: "Value", width: this.width - keyWidth }],
      pairs.map(([k, v]) => [{ text: k, bold: true }, v]), { zebra: false });
  }

  // ---- figures ----
  figure(file, caption, widthInches = 6.27, label) {
    const data = fs.readFileSync(file);
    const { w, h } = pngSize(data);
    const width = Math.round(widthInches * 96);
    const height = Math.round(width * h / w);
    this.figures += 1;
    if (label) REFS[label] = `Figure ${this.figures}`;
    this.body.push(new Paragraph({ alignment: AlignmentType.CENTER, keepNext: true, spacing: { before: 120, after: 40 },
      children: [new ImageRun({ type: "png", data, transformation: { width, height },
        altText: { title: caption, description: caption, name: path.basename(file) } })] }));
    this.body.push(new Paragraph({ style: "Caption",
      children: [new TextRun({ text: `Figure ${this.figures}: `, bold: true, italics: true }), new TextRun({ text: caption, italics: true })] }));
  }

  // ---- front matter ----
  titlePage() {
    const m = this.meta;
    const line = new Paragraph({ border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: NAVY, space: 1 } },
      spacing: { after: 240 }, children: [] });
    this.body.push(
      new Paragraph({ spacing: { before: 1400, after: 120 }, children: [new TextRun({ text: m.organisation.toUpperCase(), bold: true, size: 22, color: BLUE, characterSpacing: 40 })] }),
      line,
      new Paragraph({ spacing: { after: 160 }, children: [new TextRun({ text: m.title, bold: true, size: 52, color: NAVY })] }),
      new Paragraph({ spacing: { after: 600 }, children: [new TextRun({ text: m.subtitle, size: 30, color: "404040" })] }),
    );
    const rows = [
      ["Document identifier", m.docId],
      ["Version", m.version],
      ["Status", m.status],
      ["Date", m.date],
      ["Prepared by", m.author],
      ["Classification", m.classification],
      ["Governing standard", m.standard],
    ];
    const border = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
    this.body.push(new Table({
      width: { size: 6800, type: WidthType.DXA }, columnWidths: [2400, 4400],
      rows: rows.map(([k, v]) => new TableRow({ children: [
        new TableCell({ width: { size: 2400, type: WidthType.DXA }, borders: { top: border, bottom: border, left: border, right: border },
          margins: { top: 40, bottom: 40, left: 0, right: 120 },
          children: [new Paragraph({ spacing: { after: 0 }, children: [new TextRun({ text: k, bold: true, color: NAVY, size: 21 })] })] }),
        new TableCell({ width: { size: 4400, type: WidthType.DXA }, borders: { top: border, bottom: border, left: border, right: border },
          margins: { top: 40, bottom: 40, left: 0, right: 0 },
          children: [new Paragraph({ spacing: { after: 0 }, children: [new TextRun({ text: v, size: 21 })] })] }),
      ] })),
    }));
    this.body.push(new Paragraph({ spacing: { before: 1600 }, children: [new TextRun({ text: m.confidentialNote, size: 17, color: "595959", italics: true })] }));
  }

  documentControl(revisions, approvals, distribution) {
    this.plainHeading("Document Control");
    this.p("This document is placed under configuration control. Changes are made only through the change-control "
      + "procedure in the companion document “SRS Rules and Regulations” and are recorded in the revision history below. "
      + "A version number ending in “.0” denotes a baselined (approved) issue; any other number denotes a working draft.");
    this.table("Revision history", [
      { header: "Version", width: 1000, align: "center" },
      { header: "Date", width: 1300, align: "center" },
      { header: "Author", width: 2000 },
      { header: "Description of change", width: 4726 },
    ], revisions, { zebra: false });
    this.table("Review and approval", [
      { header: "Role", width: 2600 },
      { header: "Name", width: 2300 },
      { header: "Signature", width: 2326 },
      { header: "Date", width: 1800 },
    ], approvals, { zebra: false });
    if (distribution) {
      this.table("Distribution list", [
        { header: "Recipient", width: 3600 },
        { header: "Purpose", width: 5426 },
      ], distribution, { zebra: false });
    }
  }

  toc() {
    // Styled like Heading 1 but not a heading, so the contents page does not list itself.
    this.dropTrailingSpacer();
    this.body.push(new Paragraph({ pageBreakBefore: true, spacing: { before: 360, after: 160 },
      children: [new TextRun({ text: "Table of Contents", bold: true, size: 32, color: NAVY })] }));
    this.body.push(new TableOfContents("Table of Contents", { hyperlink: true, headingStyleRange: "1-3" }));
  }

  // ---- output ----
  build() {
    const m = this.meta;
    const { Document } = require("docx");
    const header = (width) => new Header({ children: [new Paragraph({
      border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: BLUE, space: 4 } },
      tabStops: [{ type: TabStopType.RIGHT, position: width }],
      children: [new TextRun({ text: m.shortTitle, size: 16, color: "595959" }),
        new TextRun({ text: `	${m.docId}  |  v${m.version}`, size: 16, color: "595959" })],
    })] });
    const footer = (width) => new Footer({ children: [new Paragraph({
      border: { top: { style: BorderStyle.SINGLE, size: 6, color: BLUE, space: 4 } },
      tabStops: [{ type: TabStopType.RIGHT, position: width }],
      children: [new TextRun({ text: `${m.organisation}  |  ${m.classification}`, size: 16, color: "595959" }),
        new TextRun({ children: ["	Page ", PageNumber.CURRENT, " of ", PageNumber.TOTAL_PAGES], size: 16, color: "595959" })],
    })] });
    const margin = { top: MARGIN, right: MARGIN, bottom: MARGIN, left: MARGIN };
    const sections = this.sections.filter((sec) => sec.children.length).map((sec, i) => {
      const width = sec.landscape ? CONTENT_LANDSCAPE : CONTENT;
      return {
        properties: {
          titlePage: i === 0,
          page: { size: { width: A4.width, height: A4.height, orientation: sec.landscape ? PageOrientation.LANDSCAPE : PageOrientation.PORTRAIT }, margin },
        },
        headers: { default: header(width) },
        footers: { default: footer(width) },
        children: sec.children,
      };
    });
    return new Document({
      creator: m.author, title: m.title, description: m.subtitle, subject: m.docId,
      styles,
      numbering: { config: [...numbering.config, ...(this.extraNumbering || [])] },
      sections,
    });
  }
}

/**
 * Plain text with light inline markup: **bold**, `code`. Returns TextRuns.
 */
function runs(text, base = {}) {
  const out = [];
  const parts = String(text).split(/(\*\*[^*]+\*\*|`[^`]+`)/g).filter((s) => s.length);
  for (const part of parts) {
    if (part.startsWith("**") && part.endsWith("**")) {
      out.push(new TextRun({ text: part.slice(2, -2), bold: true, size: base.size, font: base.font }));
    } else if (part.startsWith("`") && part.endsWith("`")) {
      out.push(new TextRun({ text: part.slice(1, -1), font: MONO, size: base.size ? base.size - 1 : 19, color: "1F3864" }));
    } else {
      out.push(new TextRun({ text: part, bold: base.bold, size: base.size, font: base.font }));
    }
  }
  return out;
}

function pngSize(buffer) {
  return { w: buffer.readUInt32BE(16), h: buffer.readUInt32BE(20) };
}

module.exports = { DocBuilder, CONTENT, CONTENT_LANDSCAPE, runs, ref };
